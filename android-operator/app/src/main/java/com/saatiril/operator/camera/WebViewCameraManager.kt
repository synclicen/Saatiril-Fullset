package com.saatiril.operator.camera

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ═════════════════════════════════════════════════════════════════════════
 * PERMANENT WebView Camera Manager — v13 Architecture
 * ═════════════════════════════════════════════════════════════════════════
 *
 * CRITICAL DESIGN: The WebView is created ONCE at the Activity level and
 * added directly to the Activity's root FrameLayout. It is NEVER removed,
 * even during screen transitions (login → operator). This guarantees that
 * the getUserMedia camera stream is NEVER interrupted.
 *
 * Why previous approaches failed:
 * - v8/v9: WebView was inside Compose's AndroidView → gets detached when
 *   screen changes → getUserMedia stream dies → camera reverts to built-in
 * - v10-v12: UVCCamera native approach — library detected USB but couldn't
 *   reliably stream from capture cards on Xiaomi/Redmi devices
 *
 * This approach works because:
 * 1. WebView with Chromium engine has BUILT-IN UVC driver support
 * 2. getUserMedia can access USB cameras that Camera2/CameraX can't
 * 3. WebView is never detached → stream never dies
 * 4. Compose UI renders ON TOP of the WebView (transparent background)
 *
 * The camera.html file in assets/ handles all camera logic via JS.
 * Communication between Kotlin and JS happens via:
 * - JS → Kotlin: AndroidBridge @JavascriptInterface callbacks
 * - Kotlin → JS: webView.evaluateJavascript() calls
 */
class WebViewCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "WebViewCamera"
    }

    // ─── State ─────────────────────────────────────────────────────
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _cameraType = MutableStateFlow("none")
    val cameraType: StateFlow<String> = _cameraType.asStateFlow()

    private val _currentCameraId = MutableStateFlow("")
    val currentCameraIdFlow: StateFlow<String> = _currentCameraId.asStateFlow()

    private val _availableCameras = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val availableCameras: StateFlow<List<Pair<String, String>>> = _availableCameras.asStateFlow()

    private val _isUSBCamera = MutableStateFlow(false)
    val isUSBCamera: StateFlow<Boolean> = _isUSBCamera.asStateFlow()

    // ─── WebView (PERMANENT — never detached) ──────────────────────
    private var webView: WebView? = null
    private var isWebViewReady = false
    private var pendingInit = false

    // ─── Capture ───────────────────────────────────────────────────
    private var captureCallback: ((String?) -> Unit)? = null

    /**
     * Create the WebView and add it to the Activity's root layout.
     * This MUST be called from the Activity, NOT from Compose.
     * The WebView is added as the BOTTOM layer — Compose renders on top.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(rootLayout: FrameLayout) {
        if (webView != null) {
            Log.w(TAG, "WebView already created, skipping")
            return
        }

        Log.i(TAG, "═══════════════════════════════════════════════════")
        Log.i(TAG, "Creating PERMANENT WebView at Activity level")
        Log.i(TAG, "═══════════════════════════════════════════════════")

        webView = WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true
                domStorageEnabled = true
                // CRITICAL for USB camera access
                @Suppress("DEPRECATION")
                allowContentAccess = true
            }

            // CRITICAL: Auto-grant ALL permission requests for camera/mic
            // This is what allows getUserMedia to access USB cameras
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) {
                    Log.i(TAG, "WebView permission request: ${request?.resources?.toList()}")
                    request?.grant(request.resources)
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.i(TAG, "camera.html loaded — WebView ready!")
                    isWebViewReady = true
                    if (pendingInit) {
                        pendingInit = false
                        initCameraJS()
                    }
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    Log.e(TAG, "WebView error: ${error?.description}")
                }
            }

            // Add JavaScript bridge for JS → Kotlin communication
            addJavascriptInterface(CameraBridge(), "AndroidBridge")

            // Layout: fill the entire root, positioned at the bottom layer
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )

            // Transparent background so we can see through when needed
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        // Add WebView as the FIRST child (bottom layer) of the root layout
        rootLayout.addView(webView, 0)

        // Load camera.html
        webView!!.loadUrl("file:///android_asset/camera.html")
        Log.i(TAG, "Loading camera.html...")
    }

    /**
     * Initialize camera via JavaScript.
     * Called when camera permission is granted.
     */
    fun initCamera() {
        Log.i(TAG, "initCamera called, webViewReady=$isWebViewReady")
        if (isWebViewReady) {
            initCameraJS()
        } else {
            pendingInit = true
        }
    }

    private fun initCameraJS() {
        Log.i(TAG, "Calling autoStart() in JavaScript")
        webView?.evaluateJavascript("autoStart()", null)
    }

    /**
     * Show the WebView camera preview.
     * Called when transitioning to OperatorScreen.
     */
    fun showPreview() {
        Log.i(TAG, "showPreview: making WebView visible")
        webView?.visibility = android.view.View.VISIBLE
    }

    /**
     * Hide the WebView camera preview.
     * Called when on ConnectionScreen (camera still runs in background).
     */
    fun hidePreview() {
        Log.i(TAG, "hidePreview: hiding WebView (camera keeps running)")
        // DON'T set INVISIBLE or GONE — that might stop the stream!
        // Instead, make the WebView size 0 but keep it active
        // Actually, we keep it VISIBLE but behind the Compose UI
        // Compose UI has opaque background on ConnectionScreen
    }

    /**
     * Switch camera by deviceId.
     */
    fun switchCamera(deviceId: String) {
        Log.i(TAG, "switchCamera: $deviceId")
        webView?.evaluateJavascript("switchCamera('$deviceId')", null)
    }

    /**
     * Force rescan for USB cameras.
     */
    fun forceRescan() {
        Log.i(TAG, "forceRescan")
        webView?.evaluateJavascript("forceRescan()", null)
    }

    /**
     * Capture photo via JavaScript canvas.
     */
    fun capturePhoto(onResult: (String?) -> Unit) {
        captureCallback = onResult
        webView?.evaluateJavascript("capturePhoto()", null)
    }

    /**
     * Set filter preset.
     */
    fun setFilter(preset: String) {
        webView?.evaluateJavascript("setFilter('$preset')", null)
    }

    /**
     * Set frame overlay.
     */
    fun setFrameOverlay(base64Data: String?) {
        if (base64Data != null && base64Data.isNotEmpty()) {
            // Escape for JS string
            val escaped = base64Data.replace("'", "\\'")
            webView?.evaluateJavascript("setFrameOverlay('$escaped')", null)
        } else {
            webView?.evaluateJavascript("setFrameOverlay(null)", null)
        }
    }

    fun updateConfig(aspectRatio: Double, filterPreset: String) {
        setFilter(filterPreset)
    }

    // Compatibility methods
    fun switchToCamera(cameraId: String) = switchCamera(cameraId)
    fun hasCameraPermission(): Boolean = true
    fun refreshCameraList() = forceRescan()
    fun forceSwitchToUSB() = forceRescan()

    /**
     * Destroy and clean up.
     */
    fun destroy() {
        Log.i(TAG, "destroy: cleaning up WebView")
        try {
            webView?.evaluateJavascript(
                "if(currentStream){currentStream.getTracks().forEach(t=>t.stop());currentStream=null;}",
                null
            )
        } catch (_: Exception) {}
        try {
            (webView?.parent as? ViewGroup)?.removeView(webView)
        } catch (_: Exception) {}
        try {
            webView?.destroy()
        } catch (_: Exception) {}
        webView = null
        isWebViewReady = false
        _isConnected.value = false
        _cameraType.value = "none"
    }

    /**
     * Get the WebView reference for direct access if needed.
     */
    fun getWebView(): WebView? = webView

    // ═══════════════════════════════════════════════════════════════
    // JavaScript Bridge — JS → Kotlin callbacks
    // ═══════════════════════════════════════════════════════════════
    inner class CameraBridge {
        @JavascriptInterface
        fun onCameraList(json: String) {
            Log.i(TAG, "onCameraList: $json")
            try {
                val cameras = mutableListOf<Pair<String, String>>()
                // Simple JSON parsing without Gson dependency
                // Format: [{"deviceId":"...","label":"...","isUSB":true},...]
                val entries = json.removeSurrounding("[", "]").split("},{")
                for (entry in entries) {
                    val clean = entry.trim().removeSurrounding("{", "}").removeSurrounding("\"", "\"")
                    val deviceIdMatch = Regex("""deviceId"\s*:\s*"([^"]*)"""").find(clean)
                    val labelMatch = Regex("""label"\s*:\s*"([^"]*)"""").find(clean)
                    val isUSBMatch = Regex("""isUSB"\s*:\s*(true|false)""").find(clean)

                    val deviceId = deviceIdMatch?.groupValues?.get(1) ?: continue
                    val label = labelMatch?.groupValues?.get(1) ?: "Unknown"
                    val isUSB = isUSBMatch?.groupValues?.get(1)?.toBoolean() ?: false

                    val displayLabel = if (isUSB) "USB: $label" else label
                    cameras.add(deviceId to displayLabel)

                    if (isUSB) {
                        Log.i(TAG, "USB camera found: $label (deviceId: ${deviceId.take(20)}...)")
                    }
                }
                _availableCameras.value = cameras
                Log.i(TAG, "Camera list updated: ${cameras.size} cameras")
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing camera list: ${e.message}")
            }
        }

        @JavascriptInterface
        fun onCameraStarted(deviceId: String, label: String) {
            Log.i(TAG, "onCameraStarted: deviceId=${deviceId.take(20)}, label=$label")
            _isConnected.value = true
            _currentCameraId.value = deviceId

            val isUSB = label.lowercase().contains("usb") ||
                    label.lowercase().contains("capture") ||
                    label.lowercase().contains("external") ||
                    label.lowercase().contains("uvc")

            _isUSBCamera.value = isUSB
            _cameraType.value = if (isUSB) "external" else "back"
        }

        @JavascriptInterface
        fun onUSBCameraActive(label: String) {
            Log.i(TAG, "═══════════════════════════════════════════════════")
            Log.i(TAG, "USB CAMERA ACTIVE: $label")
            Log.i(TAG, "═══════════════════════════════════════════════════")
            _isUSBCamera.value = true
            _cameraType.value = "external"
        }

        @JavascriptInterface
        fun onCameraError(error: String) {
            Log.e(TAG, "onCameraError: $error")
            _isConnected.value = false
        }

        @JavascriptInterface
        fun onCaptureResult(dataUrl: String) {
            Log.i(TAG, "onCaptureResult: ${dataUrl.length} chars")
            captureCallback?.let { callback ->
                captureCallback = null
                if (dataUrl.isEmpty()) {
                    callback(null)
                } else {
                    callback(dataUrl)
                }
            }
        }

        @JavascriptInterface
        fun onLog(msg: String) {
            Log.d(TAG, "[JS] $msg")
        }
    }
}
