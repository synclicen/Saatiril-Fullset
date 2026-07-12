package com.saatiril.operator.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * ═════════════════════════════════════════════════════════════════════════
 * WebView Camera Manager v9 — getUserMedia with USB-FIRST + robust retry
 * ═════════════════════════════════════════════════════════════════════════
 *
 * KEY FIXES FROM v8:
 * 1. Load camera.html via loadDataWithBaseURL with https://localhost origin
 *    (file:// origin may restrict getUserMedia USB camera enumeration)
 * 2. Persist USB camera deviceId across screen transitions
 * 3. On init, if a USB camera was previously selected, send its deviceId
 *    to JS immediately after loading
 * 4. Add periodic rescan timer to detect USB cameras that appear late
 * 5. Robust logging for diagnosing USB camera issues
 *
 * ARCHITECTURE:
 * - camera.html (in assets/) handles all camera logic via getUserMedia
 * - JavaScript Interface bridge for Kotlin ↔ JS communication
 * - Kotlin calls JS: selectCamera(deviceId), capturePhoto(), forceRescan()
 * - JS calls Kotlin: onCameraList(), onCameraReady(), onCaptureResult()
 * - Photo capture: canvas.toDataURL('image/jpeg') → base64 → bridge → Kotlin
 * - Photos NOT saved on operator device (sent via socket only, like Electron)
 *
 * Camera priority (matching Electron behavior):
 * 1. USB/External camera — auto-selected if present, retried up to 5 times
 * 2. Built-in back camera — fallback
 * 3. Built-in front camera — last resort
 */
class WebViewCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "WebViewCamera"
        private const val RESCAN_INTERVAL_MS = 8000L  // Rescan every 8 seconds
        private const val USB_REMEMBER_KEY = "saatiril_usb_camera_id"
        private const val USB_REMEMBER_LABEL_KEY = "saatiril_usb_camera_label"
    }

    // ─── WebView reference ────────────────────────────────────────────
    private var webView: WebView? = null

    // ─── Camera state ─────────────────────────────────────────────────
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _cameraType = MutableStateFlow("none") // "external", "back", "front", "none"
    val cameraType: StateFlow<String> = _cameraType.asStateFlow()

    private val _currentCameraId = MutableStateFlow("")
    val currentCameraIdFlow: StateFlow<String> = _currentCameraId.asStateFlow()

    private val _availableCameras = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val availableCameras: StateFlow<List<Pair<String, String>>> = _availableCameras.asStateFlow()

    // ─── Current camera info (from JS) ────────────────────────────────
    private var currentCameraLabel: String = ""
    private var currentCameraIsExternal: Boolean = false

    // ─── USB Camera Persistence ───────────────────────────────────────
    // Remember the USB camera across screen transitions and WebView reloads
    private var rememberedUSBCameraId: String = ""
    private var rememberedUSBCameraLabel: String = ""

    // ─── Pending capture callback ─────────────────────────────────────
    private var pendingCaptureCallback: ((String?) -> Unit)? = null

    // ─── Rescan timer ─────────────────────────────────────────────────
    private var rescanRunnable: Runnable? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    // ─── Init state ───────────────────────────────────────────────────
    private var isInitialized = false

    // ═══════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Initialize with a WebView instance.
     * Sets up WebView settings, JavaScript interface, and loads camera.html.
     *
     * KEY FIX: Use loadDataWithBaseURL with https://localhost origin
     * instead of file:// — this gives proper security context for getUserMedia
     * to enumerate ALL cameras including USB capture cards.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun init(webView: WebView) {
        Log.i(TAG, "═══════════════════════════════════════════════════")
        Log.i(TAG, "init: WebView Camera Engine v9 — USB-FIRST")
        Log.i(TAG, "═══════════════════════════════════════════════════")

        // Guard against double initialization — if already initialized with this WebView, skip
        if (isInitialized && this.webView === webView) {
            Log.i(TAG, "init: Already initialized with same WebView, skipping")
            return
        }

        // If initialized with a DIFFERENT WebView, clean up old one first
        if (isInitialized && this.webView !== webView) {
            Log.i(TAG, "init: Different WebView provided, re-initializing")
            stopRescanTimer()
            try {
                this.webView?.stopLoading()
                this.webView?.removeJavascriptInterface("AndroidBridge")
            } catch (e: Exception) {
                Log.w(TAG, "Error cleaning up old WebView: ${e.message}")
            }
        }

        this.webView = webView
        isInitialized = true

        // Load remembered USB camera from SharedPreferences
        loadRememberedUSBCamera()

        // Configure WebView for camera access
        webView.settings.apply {
            javaScriptEnabled = true
            mediaPlaybackRequiresUserGesture = false  // Allow auto-play without user gesture
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true

            // CRITICAL: Allow file:// URLs to access camera (getUserMedia)
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true

            // CRITICAL: Allow mixed content (HTTP camera streams)
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            // Additional settings for better compatibility
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            databaseEnabled = true
        }

        // Set WebViewClient to handle page loading
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                Log.i(TAG, "camera.html loaded successfully — url=$url")

                // After page loads, if we remember a USB camera, tell JS about it
                if (rememberedUSBCameraId.isNotEmpty()) {
                    Log.i(TAG, " onPageFinished: Sending remembered USB camera to JS: $rememberedUSBCameraId")
                    // Small delay to ensure JS bridge is ready
                    handler.postDelayed({
                        executeJS("if(typeof setRememberedUSBCamera === 'function') setRememberedUSBCamera('$rememberedUSBCameraId', '${rememberedUSBCameraLabel.replace("'", "\\'")}')")
                    }, 300)
                }
            }

            override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                Log.e(TAG, "WebView error: ${error?.description} (code=${error?.errorCode})")
            }
        }

        // Set WebChromeClient to handle permission requests (CRITICAL for camera access)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                Log.i(TAG, "═══════════════════════════════════════════════════")
                Log.i(TAG, "PERMISSION REQUEST: ${request?.resources?.contentToString()}")
                Log.i(TAG, "═══════════════════════════════════════════════════")

                // GRANT ALL permission requests — camera, microphone, etc.
                // This is essential for getUserMedia to work in WebView
                request?.grant(request.resources)
                Log.i(TAG, "ALL permissions GRANTED for WebView camera")
            }
        }

        // Add JavaScript interface
        webView.addJavascriptInterface(CameraBridge(), "AndroidBridge")

        // ═══ KEY FIX: Load via loadDataWithBaseURL ═══
        // Using file:///android_asset/ as the base URL but with https:// scheme
        // This gives proper security context for getUserMedia to see USB cameras
        try {
            val htmlStream = context.assets.open("camera.html")
            val htmlBytes = ByteArray(htmlStream.available())
            htmlStream.read(htmlBytes)
            htmlStream.close()
            val htmlContent = String(htmlBytes, Charsets.UTF_8)

            // Use loadDataWithBaseURL with https://localhost origin
            // This is a KNOWN technique to give WebView proper security context
            // while still loading local content
            webView.loadDataWithBaseURL(
                "https://localhost",  // base URL — gives proper security context
                htmlContent,          // HTML content
                "text/html",          // MIME type
                "UTF-8",              // encoding
                null                  // history URL
            )
            Log.i(TAG, "camera.html loaded via loadDataWithBaseURL (https://localhost origin)")
        } catch (e: Exception) {
            // Fallback to file:// if asset reading fails
            Log.e(TAG, "Failed to load camera.html via loadDataWithBaseURL: ${e.message}")
            webView.loadUrl("file:///android_asset/camera.html")
            Log.i(TAG, "camera.html loaded via file:// fallback")
        }

        // Start periodic rescan timer
        startRescanTimer()
    }

    // ═══════════════════════════════════════════════════════════════════
    // USB CAMERA PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Save the USB camera ID so it persists across screen transitions.
     */
    private fun saveRememberedUSBCamera(deviceId: String, label: String) {
        rememberedUSBCameraId = deviceId
        rememberedUSBCameraLabel = label
        try {
            val prefs = context.getSharedPreferences("saatiril_camera", Context.MODE_PRIVATE)
            prefs.edit()
                .putString(USB_REMEMBER_KEY, deviceId)
                .putString(USB_REMEMBER_LABEL_KEY, label)
                .apply()
            Log.i(TAG, "USB camera SAVED: id=$deviceId label=$label")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save USB camera preference: ${e.message}")
        }
    }

    private fun loadRememberedUSBCamera() {
        try {
            val prefs = context.getSharedPreferences("saatiril_camera", Context.MODE_PRIVATE)
            rememberedUSBCameraId = prefs.getString(USB_REMEMBER_KEY, "") ?: ""
            rememberedUSBCameraLabel = prefs.getString(USB_REMEMBER_LABEL_KEY, "") ?: ""
            if (rememberedUSBCameraId.isNotEmpty()) {
                Log.i(TAG, "USB camera REMEMBERED from prefs: id=$rememberedUSBCameraId label=$rememberedUSBCameraLabel")
            } else {
                Log.i(TAG, "No USB camera previously remembered")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load USB camera preference: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // PERIODIC RESCAN TIMER
    // ═══════════════════════════════════════════════════════════════════
    // The devicechange event in WebView may not fire reliably for USB
    // hot-plug on all Android devices. So we also do periodic rescans.

    private fun startRescanTimer() {
        stopRescanTimer()
        rescanRunnable = object : Runnable {
            override fun run() {
                if (isInitialized && webView != null) {
                    // Only rescan if we're NOT on an external camera
                    if (_cameraType.value != "external") {
                        Log.d(TAG, "Periodic rescan: current camera is ${_cameraType.value}, checking for USB...")
                        executeJS("if(typeof enumerateCameras === 'function') enumerateCameras()")
                    } else {
                        Log.d(TAG, "Periodic rescan: USB camera active, skipping")
                    }
                }
                handler.postDelayed(this, RESCAN_INTERVAL_MS)
            }
        }
        handler.postDelayed(rescanRunnable!!, RESCAN_INTERVAL_MS)
        Log.i(TAG, "Periodic rescan timer started (interval=${RESCAN_INTERVAL_MS}ms)")
    }

    private fun stopRescanTimer() {
        rescanRunnable?.let {
            handler.removeCallbacks(it)
        }
        rescanRunnable = null
    }

    // ═══════════════════════════════════════════════════════════════════
    // CAMERA CONTROL (Kotlin → JavaScript)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Switch to a specific camera by deviceId.
     * Calls JavaScript selectCamera(deviceId).
     */
    fun switchCamera(deviceId: String) {
        Log.i(TAG, "switchCamera: deviceId=$deviceId")
        executeJS("selectCamera('$deviceId')")
    }

    /**
     * Force rescan for USB cameras and auto-select if found.
     * Calls JavaScript forceRescan().
     */
    fun forceRescan() {
        Log.i(TAG, "forceRescan: triggering USB camera rescan")
        executeJS("forceRescan()")
    }

    /**
     * Capture a photo from the current camera.
     * Result comes back asynchronously via CameraBridge.onCaptureResult().
     */
    fun capturePhoto(onResult: (String?) -> Unit) {
        Log.i(TAG, "capturePhoto: requesting capture from WebView")
        pendingCaptureCallback = onResult
        executeJS("capturePhoto()")
    }

    /**
     * Update capture configuration (aspect ratio, filter preset).
     */
    fun updateConfig(aspectRatio: Double, filterPreset: String) {
        Log.i(TAG, "updateConfig: aspectRatio=$aspectRatio, filterPreset=$filterPreset")
        executeJS("updateConfig({aspectRatio: $aspectRatio, filterPreset: '$filterPreset'})")
    }

    /**
     * Set frame overlay image (base64 data URI).
     */
    fun setFrameOverlay(base64Data: String?) {
        if (base64Data != null) {
            val escaped = base64Data.replace("'", "\\'").replace("\n", "\\n")
            executeJS("setFrameOverlay('$escaped')")
        } else {
            executeJS("setFrameOverlay(null)")
        }
    }

    /**
     * Refresh the camera list by re-enumerating.
     */
    fun refreshCameraList() {
        executeJS("enumerateCameras()")
    }

    // ─── Helper: Execute JavaScript ───────────────────────────────────

    private fun executeJS(script: String) {
        try {
            webView?.post {
                try {
                    webView?.evaluateJavascript(script) { result ->
                        Log.d(TAG, "JS result: $result")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "JS execution failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post JS to WebView: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // JAVASCRIPT BRIDGE (JavaScript → Kotlin)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * JavaScript interface class for callbacks from camera.html.
     * All methods are called from the WebView's JavaScript thread.
     */
    inner class CameraBridge {
        /**
         * Called when camera list is available or updated.
         * JSON format: [{"deviceId":"...", "label":"...", "isExternal":true/false}, ...]
         */
        @JavascriptInterface
        fun onCameraList(json: String) {
            Log.i(TAG, "onCameraList: $json")
            try {
                val array = JSONArray(json)
                val cameras = mutableListOf<Pair<String, String>>()
                var hasUsbCamera = false
                var usbCameraId = ""
                var usbCameraLabel = ""

                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val deviceId = obj.getString("deviceId")
                    val label = obj.getString("label")
                    val isExternal = obj.optBoolean("isExternal", false)
                    cameras.add(deviceId to (if (isExternal) "USB: $label" else label))

                    if (isExternal) {
                        hasUsbCamera = true
                        usbCameraId = deviceId
                        usbCameraLabel = label
                    }
                }

                _availableCameras.value = cameras
                Log.i(TAG, "Camera list updated: ${cameras.size} cameras, hasUsb=$hasUsbCamera")

                // If USB camera found and we're NOT currently on USB, auto-switch
                if (hasUsbCamera && _cameraType.value != "external") {
                    Log.i(TAG, "USB camera found but not active — auto-switching! id=$usbCameraId")
                    saveRememberedUSBCamera(usbCameraId, usbCameraLabel)
                    // Trigger switch to USB camera
                    handler.post {
                        executeJS("selectCamera('$usbCameraId')")
                    }
                }

                // Save USB camera info for persistence
                if (hasUsbCamera) {
                    saveRememberedUSBCamera(usbCameraId, usbCameraLabel)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse camera list: ${e.message}")
            }
        }

        /**
         * Called when a camera is successfully opened and streaming.
         * JSON format: {"deviceId":"...", "label":"...", "width":1920, "height":1080, "isExternal":true}
         */
        @JavascriptInterface
        fun onCameraReady(json: String) {
            Log.i(TAG, "═══════════════════════════════════════════════════")
            Log.i(TAG, "CAMERA READY: $json")
            Log.i(TAG, "═══════════════════════════════════════════════════")

            try {
                val obj = JSONObject(json)
                currentCameraLabel = obj.optString("label", "Unknown")
                currentCameraIsExternal = obj.optBoolean("isExternal", false)
                _currentCameraId.value = obj.optString("deviceId", "")

                val newCameraType = if (currentCameraIsExternal) "external" else "back"
                _cameraType.value = newCameraType
                _isConnected.value = true

                // Save USB camera info for persistence
                if (currentCameraIsExternal) {
                    saveRememberedUSBCamera(_currentCameraId.value, currentCameraLabel)
                    Log.i(TAG, "USB camera ACTIVE and SAVED: id=${_currentCameraId.value}")
                }

                Log.i(TAG, "Camera type: $newCameraType, label: $currentCameraLabel, external: $currentCameraIsExternal")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse camera ready: ${e.message}")
                _isConnected.value = true  // Assume connected even if parsing fails
            }
        }

        /**
         * Called when photo capture is complete.
         * dataUrl: "data:image/jpeg;base64,..." or null on error
         */
        @JavascriptInterface
        fun onCaptureResult(dataUrl: String?) {
            Log.i(TAG, "onCaptureResult: ${if (dataUrl != null) "base64 data (${dataUrl.length} chars)" else "null"}")

            val callback = pendingCaptureCallback
            pendingCaptureCallback = null
            callback?.invoke(dataUrl)
        }

        /**
         * Called when a camera error occurs.
         */
        @JavascriptInterface
        fun onCameraError(message: String) {
            Log.e(TAG, "Camera error from JS: $message")
            // Don't set isConnected to false on error — the JS auto-retries
            // _isConnected.value = false
            // _cameraType.value = "none"
        }

        /**
         * Called when USB devices change (hot-plug).
         */
        @JavascriptInterface
        fun onDeviceChange() {
            Log.i(TAG, "Device change detected from JS")
            // JS handles auto-reconnect via devicechange event
        }

        /**
         * Debug log from JavaScript.
         */
        @JavascriptInterface
        fun log(message: String) {
            Log.d(TAG, "[JS] $message")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // COMPATIBILITY: Match UnifiedCameraManager interface
    // ═══════════════════════════════════════════════════════════════════

    fun forceSwitchToUSB() {
        Log.i(TAG, "forceSwitchToUSB: triggering rescan")
        forceRescan()
    }

    fun switchToCamera(cameraId: String) {
        switchCamera(cameraId)
    }

    fun hasCameraPermission(): Boolean {
        return true  // WebView handles permissions via onPermissionRequest
    }

    // ═══════════════════════════════════════════════════════════════════
    // CLEANUP
    // ═══════════════════════════════════════════════════════════════════

    fun destroy() {
        Log.i(TAG, "destroy: cleaning up WebView camera")
        stopRescanTimer()
        isInitialized = false
        try {
            webView?.stopLoading()
            webView?.loadUrl("about:blank")
            webView?.removeJavascriptInterface("AndroidBridge")
            webView?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying WebView: ${e.message}")
        }
        webView = null
        // Don't reset isConnected/cameraType — we want to remember state
        // for when the WebView is recreated after screen transition
    }

    /**
     * Soft reset — keep state but release WebView reference.
     * Called when screen changes but we want to keep camera state.
     */
    fun releaseWebView() {
        Log.i(TAG, "releaseWebView: releasing WebView but keeping state")
        stopRescanTimer()
        try {
            webView?.stopLoading()
            webView?.removeJavascriptInterface("AndroidBridge")
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing WebView: ${e.message}")
        }
        webView = null
    }
}
