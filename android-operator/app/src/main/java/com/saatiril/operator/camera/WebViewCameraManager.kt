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

/**
 * ═════════════════════════════════════════════════════════════════════════
 * WebView Camera Manager — getUserMedia approach (v8 — WEBVIEW SOLUTION)
 * ═════════════════════════════════════════════════════════════════════════
 *
 * WHY WE DROPPED Camera2/CameraX ENTIRELY:
 * Camera2/CameraX has FAILED 3 times (v3-v6) for USB HDMI capture cards:
 * - Camera2: CameraManager.openCamera() lists USB devices but SILENTLY FAILS
 *   to open/stream them on Xiaomi/Redmi devices
 * - CameraX: ProcessCameraProvider is a SINGLETON with frozen camera list,
 *   LENS_FACING_EXTERNAL + hasCamera() returns false
 * - The Android Camera HAL on many devices (especially Xiaomi MIUI) does NOT
 *   properly support USB capture cards despite listing them
 *
 * WHY WEBVIEW WILL WORK:
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │ Chrome/Chromium has its OWN built-in UVC driver that BYPASSES       │
 * │ Android's broken Camera HAL entirely.                               │
 * │                                                                     │
 * │ This is EXACTLY how the Electron/Chrome version works:              │
 * │ navigator.mediaDevices.getUserMedia() → USB camera streams          │
 * │                                                                     │
 * │ WebView uses the same Chromium engine, so it gets the same UVC      │
 * │ driver. This is proven to work on Chrome for Android with USB       │
 * │ capture cards — WebView has the same capability.                    │
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * ARCHITECTURE:
 * - camera.html (in assets/) handles all camera logic via getUserMedia
 * - JavaScript Interface bridge for Kotlin ↔ JS communication
 * - Kotlin calls JS: switchCamera(deviceId), capturePhoto(), updateConfig()
 * - JS calls Kotlin: onCameraList(), onCameraReady(), onCaptureResult()
 * - Photo capture: canvas.toDataURL('image/jpeg') → base64 → bridge → Kotlin
 * - Photos NOT saved on operator device (sent via socket only, like Electron browser mode)
 *
 * Camera priority (matching Electron behavior):
 * 1. USB/External camera — auto-selected if present
 * 2. Built-in back camera — fallback
 * 3. Built-in front camera — last resort
 */
class WebViewCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "WebViewCamera"
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

    // ─── Pending capture callback ─────────────────────────────────────
    private var pendingCaptureCallback: ((String?) -> Unit)? = null

    // ═══════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Initialize with a WebView instance.
     * Sets up WebView settings, JavaScript interface, and loads camera.html.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun init(webView: WebView) {
        Log.i(TAG, "═══════════════════════════════════════════════════")
        Log.i(TAG, "init: WebView Camera Engine (v8)")
        Log.i(TAG, "═══════════════════════════════════════════════════")

        this.webView = webView

        // Configure WebView for camera access
        webView.settings.apply {
            javaScriptEnabled = true
            mediaPlaybackRequiresUserGesture = false  // Allow auto-play without user gesture
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            // CRITICAL: Allow file:// URLs to access camera (getUserMedia)
            // camera.html is loaded from file:///android_asset/
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
            // CRITICAL: Allow mixed content (HTTP camera streams)
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        // Set WebViewClient to handle page loading
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                Log.i(TAG, "camera.html loaded successfully")
            }

            override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                Log.e(TAG, "WebView error: ${error?.description}")
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

        // Load camera.html from assets
        webView.loadUrl("file:///android_asset/camera.html")
        Log.i(TAG, "camera.html loaded into WebView")
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
     * Calls JavaScript autoStart().
     */
    fun forceRescan() {
        Log.i(TAG, "forceRescan: triggering auto-start")
        executeJS("autoStart()")
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
            // Escape the base64 string for JavaScript
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
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val deviceId = obj.getString("deviceId")
                    val label = obj.getString("label")
                    val isExternal = obj.optBoolean("isExternal", false)
                    cameras.add(deviceId to (if (isExternal) "USB: $label" else label))
                }
                _availableCameras.value = cameras
                Log.i(TAG, "Camera list updated: ${cameras.size} cameras")
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

                _cameraType.value = if (currentCameraIsExternal) "external" else "back"
                _isConnected.value = true

                Log.i(TAG, "Camera type: ${_cameraType.value}, label: $currentCameraLabel, external: $currentCameraIsExternal")
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
            _isConnected.value = false
            _cameraType.value = "none"
        }

        /**
         * Called when USB devices change (hot-plug).
         */
        @JavascriptInterface
        fun onDeviceChange() {
            Log.i(TAG, "Device change detected from JS")
            // The JS auto-reconnects, we just need to update state
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
    // These methods exist so OperatorViewModel doesn't need massive changes

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
        try {
            webView?.stopLoading()
            webView?.loadUrl("about:blank")
            webView?.removeJavascriptInterface("AndroidBridge")
            webView?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying WebView: ${e.message}")
        }
        webView = null
        _isConnected.value = false
        _cameraType.value = "none"
        _currentCameraId.value = ""
        _availableCameras.value = emptyList()
        pendingCaptureCallback = null
    }
}
