package com.saatiril.operator.camera

import android.content.Context
import android.util.Log
import android.view.TextureView
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Dual Camera Manager — USB (UVCCamera) + Built-in (CameraX)
 *
 * PRIORITY: USB camera > Built-in back camera
 * - When USB camera connects → automatically switch to USB
 * - When USB camera disconnects → fall back to built-in
 * - User can manually switch via camera picker
 *
 * This is the ONLY approach that works:
 * - UVCCamera library talks directly to USB hardware (bypasses broken Camera2 HAL)
 * - CameraX works fine for built-in cameras
 * - WebView/getUserMedia CANNOT see USB cameras on Android
 */
class DualCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "DualCamera"
    }

    // ─── Engines ───────────────────────────────────────────────────
    val usbEngine = USBCameraEngine(context)
    val builtinEngine = BuiltInCameraEngine(context)

    // ─── Combined State ────────────────────────────────────────────
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _cameraType = MutableStateFlow("none")
    val cameraType: StateFlow<String> = _cameraType.asStateFlow()

    private val _currentCameraId = MutableStateFlow("")
    val currentCameraIdFlow: StateFlow<String> = _currentCameraId.asStateFlow()

    private val _availableCameras = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val availableCameras: StateFlow<List<Pair<String, String>>> = _availableCameras.asStateFlow()

    // ─── Active engine tracking ────────────────────────────────────
    private var usingUSB = false

    // ─── Lifecycle owner for CameraX ───────────────────────────────
    private var lifecycleOwner: LifecycleOwner? = null
    private var previewView: PreviewView? = null
    private var textureView: TextureView? = null

    /**
     * Initialize USB engine immediately (detects USB devices).
     * Built-in engine init requires LifecycleOwner + PreviewView.
     */
    fun init() {
        Log.i(TAG, "═══════════════════════════════════════════════════")
        Log.i(TAG, "init: Dual Camera Manager (v10 — UVCCamera + CameraX)")
        Log.i(TAG, "═══════════════════════════════════════════════════")

        // Initialize USB engine — this detects USB devices right away
        usbEngine.init()
    }

    /**
     * Initialize built-in camera engine (requires LifecycleOwner + PreviewView).
     * ALWAYS initializes the built-in engine even if USB is connected,
     * so the user can switch between cameras later.
     */
    fun initBuiltinCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        Log.i(TAG, "initBuiltinCamera: starting CameraX (USB connected: ${usbEngine.isConnected.value})")
        this.lifecycleOwner = lifecycleOwner
        this.previewView = previewView

        // ALWAYS init built-in camera — user may switch to it later
        builtinEngine.init(lifecycleOwner, previewView)

        if (!usbEngine.isConnected.value) {
            // USB not connected → use built-in as active
            _cameraType.value = builtinEngine.cameraType.value
            _isConnected.value = builtinEngine.isConnected.value
            _currentCameraId.value = builtinEngine.currentCameraIdFlow.value
        }

        updateCombinedCameraList()
    }

    /**
     * Set TextureView for USB camera preview.
     * Called when OperatorScreen creates the TextureView.
     */
    fun setTextureView(tv: TextureView) {
        Log.i(TAG, "setTextureView for USB camera")
        textureView = tv
        usbEngine.setTextureView(tv)
    }

    /**
     * Called when USB camera connects — switch to USB.
     */
    fun onUSBCameraConnected() {
        Log.i(TAG, "═══════════════════════════════════════════════════")
        Log.i(TAG, "USB CAMERA CONNECTED — switching to USB!")
        Log.i(TAG, "═══════════════════════════════════════════════════")
        usingUSB = true
        _isConnected.value = true
        _cameraType.value = "external"
        _currentCameraId.value = usbEngine.currentCameraIdFlow.value

        // Pause built-in camera (free camera hardware resources for USB)
        builtinEngine.pausePreview()

        // Start USB preview on TextureView
        textureView?.let { usbEngine.setTextureView(it) }

        // Update combined camera list
        updateCombinedCameraList()
    }

    /**
     * Called when USB camera disconnects — fall back to built-in.
     */
    fun onUSBCameraDisconnected() {
        Log.i(TAG, "USB CAMERA DISCONNECTED — falling back to built-in")
        usingUSB = false

        // Resume built-in camera
        val owner = lifecycleOwner
        val pv = previewView
        if (owner != null && pv != null) {
            builtinEngine.resumePreview(owner, pv)
        }

        _cameraType.value = builtinEngine.cameraType.value
        _isConnected.value = builtinEngine.isConnected.value
        _currentCameraId.value = builtinEngine.currentCameraIdFlow.value

        updateCombinedCameraList()
    }

    // ─── Combined camera list ──────────────────────────────────────
    private fun updateCombinedCameraList() {
        val cameras = mutableListOf<Pair<String, String>>()

        // USB cameras first
        cameras.addAll(usbEngine.availableCameras.value)
        // Then built-in cameras
        cameras.addAll(builtinEngine.availableCameras.value)

        _availableCameras.value = cameras
        Log.i(TAG, "Combined camera list: ${cameras.map { it.second }}")
    }

    // ─── Photo Capture ─────────────────────────────────────────────
    fun capturePhoto(onResult: (String?) -> Unit) {
        if (usingUSB) {
            usbEngine.capturePhoto(onResult)
        } else {
            builtinEngine.capturePhoto(onResult)
        }
    }

    // ─── Camera switching ──────────────────────────────────────────
    fun switchCamera(deviceId: String) {
        Log.i(TAG, "switchCamera: deviceId=$deviceId, usbConnected=${usbEngine.isConnected.value}, builtinConnected=${builtinEngine.isConnected.value}")

        if (deviceId.contains("usb", ignoreCase = true) || deviceId.contains("capture", ignoreCase = true)) {
            // Switch to USB
            if (usbEngine.isConnected.value) {
                Log.i(TAG, "Switching to USB camera")
                usingUSB = true
                _isConnected.value = true
                _cameraType.value = "external"
                _currentCameraId.value = usbEngine.currentCameraIdFlow.value

                // Pause built-in preview (unbind use cases but keep provider alive)
                builtinEngine.pausePreview()

                // Start USB preview on TextureView
                textureView?.let { usbEngine.setTextureView(it) }
            } else {
                Log.w(TAG, "USB camera not connected, cannot switch to it")
            }
        } else {
            // Switch to built-in (front or back)
            Log.i(TAG, "Switching to built-in camera: $deviceId")
            usingUSB = false

            // Stop USB preview
            usbEngine.stopPreview()

            // Tell built-in engine to switch camera (and resume preview)
            builtinEngine.switchCamera(deviceId)

            // Update combined state from built-in engine
            _isConnected.value = builtinEngine.isConnected.value
            _cameraType.value = builtinEngine.cameraType.value
            _currentCameraId.value = builtinEngine.currentCameraIdFlow.value

            // Re-initialize built-in camera if needed (e.g. after pause)
            val owner = lifecycleOwner
            val pv = previewView
            if (owner != null && pv != null && !builtinEngine.isConnected.value) {
                builtinEngine.resumePreview(owner, pv)
                _isConnected.value = builtinEngine.isConnected.value
                _cameraType.value = builtinEngine.cameraType.value
                _currentCameraId.value = builtinEngine.currentCameraIdFlow.value
            }
        }

        updateCombinedCameraList()
    }

    fun forceRescan() {
        usbEngine.forceRescan()
        builtinEngine.refreshCameraList()
        updateCombinedCameraList()
    }

    fun refreshCameraList() {
        usbEngine.refreshCameraList()
        builtinEngine.refreshCameraList()
        updateCombinedCameraList()
    }

    fun switchToCamera(cameraId: String) = switchCamera(cameraId)
    fun forceSwitchToUSB() = forceRescan()
    fun hasCameraPermission(): Boolean = true

    fun updateConfig(aspectRatio: Double, filterPreset: String) {
        usbEngine.updateConfig(aspectRatio, filterPreset)
        builtinEngine.updateConfig(aspectRatio, filterPreset)
    }

    fun setFrameOverlay(base64Data: String?) {
        usbEngine.setFrameOverlay(base64Data)
        builtinEngine.setFrameOverlay(base64Data)
    }

    // ─── Cleanup ───────────────────────────────────────────────────
    fun destroy() {
        Log.i(TAG, "destroy: cleaning up both engines")
        usbEngine.destroy()
        builtinEngine.destroy()
        _isConnected.value = false
        _cameraType.value = "none"
    }
}
