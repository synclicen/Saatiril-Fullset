package com.saatiril.full.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import android.view.Surface
import android.view.TextureView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ═════════════════════════════════════════════════════════════════════════
 * Camera2 Manager — v14 Direct Camera Access (USB Capture Card Support)
 * ═════════════════════════════════════════════════════════════════════════
 *
 * CRITICAL: Uses Camera2 API directly instead of WebView+getUserMedia.
 * WebView/getUserMedia CANNOT access USB capture cards on Android —
 * the Chromium engine's getUserMedia only enumerates Camera2-visible cameras
 * and often fails to open external/USB cameras properly.
 *
 * Camera2 API with LENS_FACING_EXTERNAL is the correct way to access
 * USB capture cards on Android. This manager:
 *
 * 1. Enumerates ALL camera IDs via CameraManager
 * 2. Detects external/USB cameras via LENS_FACING_EXTERNAL (API 28+)
 *    or camera ID >= "2" as fallback
 * 3. Auto-selects USB/external camera when detected
 * 4. Uses TextureView for preview (NOT WebView)
 * 5. Uses ImageReader for photo capture
 * 6. Reports camera list, current camera type, and status to ViewModel
 *
 * Layout structure (same as v13 but with TextureView instead of WebView):
 * ┌──────────────────────────────┐
 * │  FrameLayout (root)          │
 * │  ┌────────────────────────┐  │
 * │  │ TextureView (preview)  │  │  ← Bottom layer, camera preview
 * │  └────────────────────────┘  │
 * │  ┌────────────────────────┐  │
 * │  │ ComposeView (UI)       │  │  ← Top layer, transparent background
 * │  └────────────────────────┘  │
 * └──────────────────────────────┘
 */
class Camera2Manager(private val context: Context) {

    companion object {
        private const val TAG = "Camera2Manager"
        private const val CAPTURE_WIDTH = 1920
        private const val CAPTURE_HEIGHT = 1080
    }

    // ─── Data Classes ────────────────────────────────────────────

    data class CameraInfo(
        val id: String,
        val label: String,
        val isExternal: Boolean,
        val facing: Int
    )

    // ─── State Flows for UI ──────────────────────────────────────

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _cameraType = MutableStateFlow("none")
    val cameraType: StateFlow<String> = _cameraType.asStateFlow()

    private val _isUSBCamera = MutableStateFlow(false)
    val isUSBCamera: StateFlow<Boolean> = _isUSBCamera.asStateFlow()

    private val _availableCamerasInfo = MutableStateFlow<List<CameraInfo>>(emptyList())
    val availableCamerasInfo: StateFlow<List<CameraInfo>> = _availableCamerasInfo.asStateFlow()

    /** Compatibility: exposes cameras as List<Pair<deviceId, label>> matching WebViewCameraManager */
    private val _availableCameras = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val availableCameras: StateFlow<List<Pair<String, String>>> = _availableCameras.asStateFlow()

    private val _currentCameraId = MutableStateFlow("")
    val currentCameraIdFlow: StateFlow<String> = _currentCameraId.asStateFlow()

    // ─── Camera2 Objects ─────────────────────────────────────────

    private var cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var textureView: TextureView? = null

    /** Background handler for camera operations */
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    /** Pending capture callback */
    private var captureCallback: ((String?) -> Unit)? = null

    /** Whether we're in the process of opening a camera (prevents double-open) */
    @Volatile
    private var isOpening = false

    /** Preview size that was negotiated for the current camera */
    private var previewWidth = CAPTURE_WIDTH
    private var previewHeight = CAPTURE_HEIGHT

    // ─── TextureView Setup ───────────────────────────────────────

    /**
     * Set the TextureView for camera preview.
     * Called from Activity after creating the TextureView.
     */
    fun setTextureView(tv: TextureView) {
        textureView = tv
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                Log.i(TAG, "SurfaceTexture available: ${width}x${height}")
                // If camera is already opened but preview wasn't started, start it now
                if (cameraDevice != null && captureSession == null) {
                    startPreview()
                } else if (cameraDevice == null && _isConnected.value) {
                    // Camera was disconnected, reopen
                    openCamera(_currentCameraId.value)
                }
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                // Restart preview with new size if camera is open
                if (cameraDevice != null) {
                    startPreview()
                }
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                Log.i(TAG, "SurfaceTexture destroyed")
                // Stop preview but don't close the camera device
                captureSession?.close()
                captureSession = null
                return true // Allow the SurfaceTexture to be destroyed
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                // Called on every frame — no-op
            }
        }
    }

    // ─── Background Thread ───────────────────────────────────────

    private fun startBackgroundThread() {
        if (backgroundThread != null && backgroundThread!!.isAlive) return
        backgroundThread = HandlerThread("Camera2Background").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        try {
            backgroundThread?.quitSafely()
            backgroundThread?.join(1000)
        } catch (_: InterruptedException) {}
        backgroundThread = null
        backgroundHandler = null
    }

    // ─── Camera Enumeration ──────────────────────────────────────

    /**
     * Enumerate all available cameras and detect USB/external ones.
     *
     * Detection strategy:
     * 1. Primary: Check CameraCharacteristics.LENS_FACING == LENS_FACING_EXTERNAL
     * 2. Fallback: Camera ID >= "2" (0=back, 1=front, 2+=external on most devices)
     * 3. Last resort: Try opening all camera IDs to find one that works
     */
    fun enumerateCameras(): List<CameraInfo> {
        val cameras = mutableListOf<CameraInfo>()
        val cameraIds: Array<String>

        try {
            cameraIds = cameraManager.cameraIdList
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get camera ID list: ${e.message}")
            return cameras
        }

        Log.i(TAG, "═══ Enumerating cameras (found ${cameraIds.size} IDs) ═══")

        for (id in cameraIds) {
            try {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: -1
                val isExternal = isExternalCamera(id, facing)

                val label = when {
                    isExternal -> "USB Capture Card"
                    facing == CameraCharacteristics.LENS_FACING_BACK -> "Kamera Belakang"
                    facing == CameraCharacteristics.LENS_FACING_FRONT -> "Kamera Depan"
                    else -> "Kamera $id"
                }

                Log.i(TAG, "  Camera $id: facing=$facing, isExternal=$isExternal, label=$label")
                cameras.add(CameraInfo(id, label, isExternal, facing))
            } catch (e: Exception) {
                Log.w(TAG, "Cannot get characteristics for camera $id: ${e.message}")
                // Still add it — it might be the USB camera that's not fully ready
                val isExternal = id.toIntOrNull()?.let { it >= 2 } == true
                val label = if (isExternal) "USB Capture Card" else "Kamera $id"
                cameras.add(CameraInfo(id, label, isExternal, -1))
            }
        }

        _availableCamerasInfo.value = cameras
        _availableCameras.value = cameras.map { it.id to it.label }
        return cameras
    }

    /**
     * Determine if a camera is external/USB.
     *
     * Uses multiple detection methods:
     * 1. LENS_FACING_EXTERNAL constant (API 28+)
     * 2. Raw facing value 2 (LENS_FACING_EXTERNAL's value before the constant existed)
     * 3. Camera ID >= "2" as heuristic (most devices: 0=back, 1=front, 2+=external)
     */
    private fun isExternalCamera(cameraId: String, facing: Int): Boolean {
        // Method 1: Use the API 28+ constant if available
        if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
            return true
        }

        // Method 2: Raw value 2 is LENS_FACING_EXTERNAL on API 23+ (before constant in API 28)
        if (facing == 2) {
            return true
        }

        // Method 3: Camera ID heuristic — IDs >= 2 are typically external
        val idNum = cameraId.toIntOrNull()
        if (idNum != null && idNum >= 2) {
            return true
        }

        return false
    }

    // ─── Camera Open / Close ─────────────────────────────────────

    /**
     * Open a camera. If cameraId is null, auto-selects USB camera if available,
     * otherwise falls back to the first available camera.
     */
    fun openCamera(cameraId: String? = null) {
        if (isOpening) {
            Log.w(TAG, "Already opening a camera, ignoring duplicate request")
            return
        }

        // Close current camera first
        closeCamera()

        // Ensure background thread is running
        startBackgroundThread()

        val cameras = enumerateCameras()
        val targetId = cameraId
            ?: cameras.find { it.isExternal }?.id
            ?: cameras.firstOrNull()?.id

        if (targetId == null) {
            Log.e(TAG, "No camera available")
            return
        }

        // Check camera permission
        if (!hasCameraPermission()) {
            Log.e(TAG, "Camera permission not granted — cannot open camera")
            return
        }

        isOpening = true
        Log.i(TAG, "═══════════════════════════════════════════════════")
        Log.i(TAG, "Opening camera: $targetId")
        Log.i(TAG, "═══════════════════════════════════════════════════")

        try {
            cameraManager.openCamera(targetId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    isOpening = false
                    cameraDevice = camera
                    _currentCameraId.value = targetId
                    _isConnected.value = true

                    // Determine if this is an external/USB camera
                    val info = cameras.find { it.id == targetId }
                    val isExternal = info?.isExternal == true
                    _isUSBCamera.value = isExternal
                    _cameraType.value = if (isExternal) "external" else "builtin"

                    Log.i(TAG, "Camera opened: $targetId, isUSB=$isExternal, type=${_cameraType.value}")

                    // Choose appropriate capture size based on camera characteristics
                    chooseCaptureSize(targetId)

                    // Start preview if TextureView surface is available
                    val tv = textureView
                    if (tv != null && tv.isAvailable) {
                        startPreview()
                    } else {
                        Log.i(TAG, "TextureView not available yet — preview will start when surface is ready")
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    isOpening = false
                    Log.w(TAG, "Camera disconnected: $targetId")
                    camera.close()
                    if (cameraDevice == camera) {
                        cameraDevice = null
                        captureSession = null
                        _isConnected.value = false
                        _cameraType.value = "none"
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    isOpening = false
                    Log.e(TAG, "Camera open error: $error for camera $targetId")
                    camera.close()
                    if (cameraDevice == camera) {
                        cameraDevice = null
                        captureSession = null
                        _isConnected.value = false
                        _cameraType.value = "none"

                        // If this camera failed, try the next one
                        // (important for USB cameras that might not be ready)
                        val currentIndex = cameras.indexOfFirst { it.id == targetId }
                        if (currentIndex >= 0 && currentIndex < cameras.size - 1) {
                            val nextCamera = cameras[currentIndex + 1]
                            Log.i(TAG, "Trying next camera: ${nextCamera.id}")
                            viewModelScopeOrHandler?.post {
                                openCamera(nextCamera.id)
                            }
                        }
                    }
                }

                override fun onClosed(camera: CameraDevice) {
                    isOpening = false
                    super.onClosed(camera)
                }
            }, backgroundHandler)
        } catch (e: SecurityException) {
            isOpening = false
            Log.e(TAG, "SecurityException opening camera $targetId: ${e.message}")
        } catch (e: Exception) {
            isOpening = false
            Log.e(TAG, "Failed to open camera $targetId: ${e.message}")
        }
    }

    /**
     * Choose appropriate capture size based on camera characteristics.
     * Tries to match 16:9 aspect ratio at a reasonable resolution.
     */
    private fun chooseCaptureSize(cameraId: String) {
        try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val configMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            if (configMap != null) {
                val jpegSizes = configMap.getOutputSizes(ImageFormat.JPEG)
                if (jpegSizes != null && jpegSizes.isNotEmpty()) {
                    // Prefer 1920x1080 (16:9), then largest available
                    val preferred = jpegSizes.find {
                        it.width == CAPTURE_WIDTH && it.height == CAPTURE_HEIGHT
                    }
                    if (preferred != null) {
                        previewWidth = CAPTURE_WIDTH
                        previewHeight = CAPTURE_HEIGHT
                    } else {
                        // Use the largest available JPEG size
                        val largest = jpegSizes.maxByOrNull { it.width * it.height }
                        if (largest != null) {
                            previewWidth = largest.width
                            previewHeight = largest.height
                        }
                    }
                    Log.i(TAG, "Capture size: ${previewWidth}x${previewHeight}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not determine capture size, using defaults: ${e.message}")
            previewWidth = CAPTURE_WIDTH
            previewHeight = CAPTURE_HEIGHT
        }
    }

    // ─── Preview ─────────────────────────────────────────────────

    /**
     * Start camera preview using the TextureView surface.
     */
    private fun startPreview() {
        val device = cameraDevice ?: run {
            Log.w(TAG, "startPreview: no camera device")
            return
        }
        val tv = textureView ?: run {
            Log.w(TAG, "startPreview: no TextureView")
            return
        }
        val surfaceTexture = tv.surfaceTexture ?: run {
            Log.w(TAG, "startPreview: no SurfaceTexture")
            return
        }

        // Set the SurfaceTexture buffer size to match the preview
        surfaceTexture.setDefaultBufferSize(previewWidth, previewHeight)
        val surface = Surface(surfaceTexture)

        // Close existing session
        captureSession?.close()
        captureSession = null

        // Close existing ImageReader
        imageReader?.close()
        imageReader = null

        // Create ImageReader for photo capture
        try {
            imageReader = ImageReader.newInstance(previewWidth, previewHeight, ImageFormat.JPEG, 2)
            imageReader!!.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null && captureCallback != null) {
                    processCapturedImage(image)
                } else {
                    image?.close()
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create ImageReader: ${e.message}")
            // Try with smaller size
            try {
                imageReader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 2)
                imageReader!!.setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage()
                    if (image != null && captureCallback != null) {
                        processCapturedImage(image)
                    } else {
                        image?.close()
                    }
                }, backgroundHandler)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to create ImageReader at 1280x720 too: ${e2.message}")
            }
        }

        val surfaces = mutableListOf<Surface>()
        surfaces.add(surface)
        imageReader?.let { surfaces.add(it.surface) }

        try {
            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        builder.addTarget(surface)

                        // Auto-focus
                        try {
                            builder.set(CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        } catch (_: Exception) {}

                        // Auto-exposure
                        try {
                            builder.set(CaptureRequest.CONTROL_AE_MODE,
                                CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                        } catch (_: Exception) {}

                        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                        Log.i(TAG, "Camera preview started successfully")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start repeating request: ${e.message}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Camera preview configuration failed")
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create capture session: ${e.message}")
            // Try with just the preview surface (no ImageReader)
            try {
                device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            builder.addTarget(surface)
                            try {
                                builder.set(CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            } catch (_: Exception) {}
                            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                            Log.i(TAG, "Camera preview started (no ImageReader)")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to start repeating request: ${e.message}")
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Camera preview configuration failed (fallback)")
                    }
                }, backgroundHandler)
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback capture session also failed: ${e2.message}")
            }
        }
    }

    // ─── Photo Capture ───────────────────────────────────────────

    /**
     * Get a bitmap from the current camera preview (for hand detection).
     * Returns a downscaled bitmap to save CPU, or null if not available.
     */
    fun getPreviewBitmap(): Bitmap? {
        val tv = textureView ?: return null
        return try {
            if (tv.isAvailable && tv.width > 0 && tv.height > 0) {
                val scale = 320f / tv.width
                val w = 320
                val h = (tv.height * scale).toInt()
                tv.getBitmap(w, h)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Capture a photo. The result is returned as a data URL (base64-encoded JPEG)
     * via the callback, matching the WebViewCameraManager interface.
     */
    fun capturePhoto(callback: (String?) -> Unit) {
        val device = cameraDevice ?: run {
            Log.e(TAG, "capturePhoto: no camera device")
            callback(null)
            return
        }
        val session = captureSession ?: run {
            Log.e(TAG, "capturePhoto: no capture session")
            callback(null)
            return
        }
        val reader = imageReader ?: run {
            Log.e(TAG, "capturePhoto: no ImageReader — cannot capture")
            callback(null)
            return
        }

        captureCallback = callback

        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(reader.surface)

            // Also add the preview surface so the preview doesn't freeze during capture
            val tv = textureView
            if (tv != null && tv.isAvailable) {
                val previewSurface = Surface(tv.surfaceTexture)
                builder.addTarget(previewSurface)
            }

            // Auto-focus
            try {
                builder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            } catch (_: Exception) {}

            // Auto-exposure
            try {
                builder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
            } catch (_: Exception) {}

            // Set JPEG orientation based on camera sensor orientation
            try {
                val characteristics = cameraManager.getCameraCharacteristics(device.id)
                val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                builder.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
            } catch (_: Exception) {}

            session.capture(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: android.hardware.camera2.TotalCaptureResult
                ) {
                    Log.i(TAG, "Photo capture completed")
                    // Image will be processed in ImageReader.OnImageAvailableListener
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: android.hardware.camera2.CaptureFailure
                ) {
                    Log.e(TAG, "Photo capture failed: ${failure.reason}")
                    captureCallback = null
                    callback(null)
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "capturePhoto exception: ${e.message}")
            captureCallback = null
            callback(null)
        }
    }

    /**
     * Process a captured image from ImageReader.
     * Converts JPEG bytes to base64 data URL.
     */
    private fun processCapturedImage(image: Image) {
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            // Convert to base64
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val dataUrl = "data:image/jpeg;base64,$base64"

            Log.i(TAG, "Photo captured and converted to base64 (${dataUrl.length} chars)")

            val cb = captureCallback
            captureCallback = null
            cb?.invoke(dataUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing captured image: ${e.message}")
            val cb = captureCallback
            captureCallback = null
            cb?.invoke(null)
        } finally {
            image.close()
        }
    }

    // ─── Camera Switching ────────────────────────────────────────

    /**
     * Switch to a specific camera by ID.
     */
    fun switchCamera(cameraId: String) {
        Log.i(TAG, "switchCamera: $cameraId")
        openCamera(cameraId)
    }

    // ─── USB Camera Rescan ───────────────────────────────────────

    /**
     * Force rescan for USB cameras. Re-enumerates cameras and
     * auto-switches to USB if found.
     */
    fun forceRescan() {
        Log.i(TAG, "forceRescan: re-enumerating cameras")
        val cameras = enumerateCameras()

        // Try to find and switch to USB camera
        val usbCam = cameras.find { it.isExternal }
        if (usbCam != null && usbCam.id != _currentCameraId.value) {
            Log.i(TAG, "forceRescan: found USB camera ${usbCam.id}, switching")
            openCamera(usbCam.id)
        } else if (usbCam == null) {
            Log.i(TAG, "forceRescan: no USB camera found (total: ${cameras.size})")
        } else {
            Log.i(TAG, "forceRescan: USB camera ${usbCam.id} already active")
        }
    }

    // ─── Camera Close / Destroy ──────────────────────────────────

    /**
     * Close the current camera session and device.
     */
    fun closeCamera() {
        try {
            captureSession?.close()
        } catch (_: Exception) {}
        captureSession = null

        try {
            cameraDevice?.close()
        } catch (_: Exception) {}
        cameraDevice = null

        try {
            imageReader?.close()
        } catch (_: Exception) {}
        imageReader = null

        _isConnected.value = false
    }

    /**
     * Full cleanup — close camera and stop background thread.
     */
    fun destroy() {
        Log.i(TAG, "destroy: cleaning up Camera2 resources")
        closeCamera()
        stopBackgroundThread()
        _cameraType.value = "none"
        _isUSBCamera.value = false
    }

    // ─── Compatibility Methods (matching WebViewCameraManager interface) ──

    /** Show the camera preview. For Camera2, preview is always visible via TextureView. */
    fun showPreview() {
        textureView?.visibility = android.view.View.VISIBLE
        Log.i(TAG, "showPreview: TextureView visible")
    }

    /** Hide the camera preview. Keeps camera running behind the Compose UI. */
    fun hidePreview() {
        // Don't actually hide the TextureView — just let the Compose UI cover it
        // with an opaque background on ConnectionScreen
        Log.i(TAG, "hidePreview: keeping TextureView active (Compose UI will cover)")
    }

    /** Check if camera permission is granted. */
    fun hasCameraPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** Initialize camera — auto-open with USB preference. */
    fun initCamera() {
        Log.i(TAG, "initCamera: opening camera with USB preference")
        openCamera()
    }

    /** Refresh camera list. */
    fun refreshCameraList(): List<Pair<String, String>> {
        enumerateCameras()
        return _availableCameras.value
    }

    /** Force switch to USB camera. */
    fun forceSwitchToUSB() {
        forceRescan()
    }

    /** Switch to specific camera by ID. */
    fun switchToCamera(cameraId: String) {
        switchCamera(cameraId)
    }

    /** Set filter preset. TODO: apply filter to captured image in post-processing. */
    fun setFilter(preset: String) {
        Log.d(TAG, "setFilter: $preset (not yet implemented for Camera2)")
        // TODO: Apply filter to captured image in post-processing
        // For now, filters from WebView camera.html are not available
    }

    /** Set frame overlay. TODO: overlay frame on captured image. */
    fun setFrameOverlay(base64: String?) {
        Log.d(TAG, "setFrameOverlay: ${if (base64 != null) "${base64.length} chars" else "null"} (not yet implemented for Camera2)")
        // TODO: Overlay frame on captured image
        // For now, frame overlays from WebView camera.html are not available
    }

    /** Update camera config (aspect ratio + filter). */
    fun updateConfig(aspectRatio: Double, filterPreset: String) {
        setFilter(filterPreset)
        // Aspect ratio changes would require restarting the preview with new dimensions
        // For now, we keep the current preview size
    }

    // ─── Helper ──────────────────────────────────────────────────

    /**
     * Handler for posting delayed camera operations.
     * Uses the background handler if available, otherwise creates a temporary one.
     */
    private val viewModelScopeOrHandler: Handler?
        get() = backgroundHandler ?: Handler(mainLooper)

    private val mainLooper: android.os.Looper
        get() = context.mainLooper
}
