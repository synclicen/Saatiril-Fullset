package com.saatiril.operator.camera

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * ═════════════════════════════════════════════════════════════════════════
 * Unified Camera Manager — Camera2 ONLY (v6 — NUCLEAR OPTION)
 * ═════════════════════════════════════════════════════════════════════════
 *
 * WHY WE DROPPED CameraX ENTIRELY:
 * CameraX 1.3.x is FUNDAMENTALLY BROKEN for USB HDMI capture cards:
 * - ProcessCameraProvider is a SINGLETON with a FROZEN camera list snapshot
 * - LENS_FACING_EXTERNAL + hasCamera() returns false on most devices
 * - addCameraFilter + bindToLifecycle throws IllegalArgumentException
 * - The dual-engine approach (CameraX + Camera2) caused chicken-and-egg
 *   race conditions that are impossible to fix reliably
 *
 * THE v6 SOLUTION — Camera2 for EVERYTHING:
 * ┌─────────────────────────────────────────────────────────┐
 * │ ALL cameras use Camera2 API directly:                    │
 * │ - CameraManager.getCameraIdList() ALWAYS sees USB        │
 * │ - CameraManager.openCamera() works for ALL cameras       │
 * │ - CameraDevice + CameraCaptureSession for preview        │
 * │ - ImageReader for JPEG still capture                     │
 * │ - TextureView for preview rendering                      │
 * │ - NO CameraX dependency at all                           │
 * │ - NO PreviewView needed                                  │
 * │ - ONE view (TextureView) for ALL cameras                 │
 * │ - Switching cameras = close old + open new               │
 * └─────────────────────────────────────────────────────────┘
 *
 * Camera priority:
 * 1. USB external (LENS_FACING_EXTERNAL) — auto-selected if present
 * 2. Built-in back (LENS_FACING_BACK) — fallback
 * 3. Built-in front (LENS_FACING_FRONT) — last resort
 */
class UnifiedCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "UnifiedCamera"
        private const val CAMERA_OPEN_TIMEOUT_MS = 5000L
        private const val PREVIEW_WIDTH = 1920
        private const val PREVIEW_HEIGHT = 1080
        private const val CAPTURE_WIDTH = 1920
        private const val CAPTURE_HEIGHT = 1080

        /**
         * Get ALL camera IDs from Camera2 (OS-level, always current).
         * Returns list of (cameraId, lensFacing, displayName).
         */
        fun getAllCameraIds(context: Context): List<Triple<String, Int, String>> {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return emptyList()

            val cameras = mutableListOf<Triple<String, Int, String>>()
            try {
                for (id in cameraManager.cameraIdList) {
                    try {
                        val chars = cameraManager.getCameraCharacteristics(id)
                        val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: -1
                        val name = when (facing) {
                            CameraCharacteristics.LENS_FACING_EXTERNAL -> "USB Capture Card"
                            CameraCharacteristics.LENS_FACING_BACK -> "Kamera Belakang"
                            CameraCharacteristics.LENS_FACING_FRONT -> "Kamera Depan"
                            else -> "Kamera ($id)"
                        }
                        cameras.add(Triple(id, facing, name))
                        Log.d(TAG, "Camera found: id=$id, facing=$facing, name=$name")
                    } catch (e: Exception) {
                        Log.d(TAG, "Cannot get characteristics for camera $id: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting camera ID list: ${e.message}")
            }
            return cameras
        }

        /**
         * Find best camera ID: USB first, then back, then front.
         */
        fun findBestCameraId(context: Context): String? {
            val cameras = getAllCameraIds(context)
            // Priority: USB → Back → Front
            return cameras.find { it.second == CameraCharacteristics.LENS_FACING_EXTERNAL }?.first
                ?: cameras.find { it.second == CameraCharacteristics.LENS_FACING_BACK }?.first
                ?: cameras.find { it.second == CameraCharacteristics.LENS_FACING_FRONT }?.first
        }

        /**
         * Check if USB capture card is present.
         */
        fun hasUSBCamera(context: Context): Boolean {
            return getAllCameraIds(context).any { it.second == CameraCharacteristics.LENS_FACING_EXTERNAL }
        }
    }

    // ─── Camera2 internals ──────────────────────────────────────

    private val cameraManager: CameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val cameraOpenCloseLock = Semaphore(1)

    // Current camera
    private var currentCameraId: String? = null
    private var currentLensFacing: Int = -1

    // TextureView reference
    private var textureView: TextureView? = null

    // Pending capture callback
    private var pendingCaptureCallback: ((Bitmap?) -> Unit)? = null

    // ─── State Flows ────────────────────────────────────────────

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _cameraType = MutableStateFlow("none")
    val cameraType: StateFlow<String> = _cameraType.asStateFlow()

    private val _currentCameraIdFlow = MutableStateFlow("")
    val currentCameraIdFlow: StateFlow<String> = _currentCameraIdFlow.asStateFlow()

    private val _availableCameras = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val availableCameras: StateFlow<List<Pair<String, String>>> = _availableCameras.asStateFlow()

    // ─── Permission ─────────────────────────────────────────────

    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    // ═══════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════

    /**
     * Initialize with a TextureView. Camera2 uses TextureView for ALL cameras.
     * This is the ONLY view needed — no PreviewView, no CameraX.
     *
     * Automatically selects the best camera (USB → Back → Front).
     */
    fun init(textureView: TextureView) {
        Log.i(TAG, "═══════════════════════════════════════════════════")
        Log.i(TAG, "init: Camera2-ONLY initialization (v6)")
        Log.i(TAG, "═══════════════════════════════════════════════════")

        this.textureView = textureView

        if (!hasCameraPermission()) {
            Log.e(TAG, "Camera permission not granted")
            _cameraType.value = "none"
            _isConnected.value = false
            return
        }

        // Refresh camera list
        refreshCameraList()

        // Find best camera
        val bestId = findBestCameraId(context)
        if (bestId == null) {
            Log.e(TAG, "NO CAMERA FOUND at all!")
            _cameraType.value = "none"
            _isConnected.value = false
            return
        }

        // Open the camera
        openCameraById(bestId, textureView)
    }

    /**
     * Open a specific camera by ID.
     */
    fun openCameraById(cameraId: String, tv: TextureView? = null) {
        val textureViewToUse = tv ?: textureView
        if (textureViewToUse == null) {
            Log.e(TAG, "openCameraById: No TextureView available")
            return
        }

        Log.i(TAG, "═══════════════════════════════════════════════════")
        Log.i(TAG, "Opening camera: $cameraId")
        Log.i(TAG, "═══════════════════════════════════════════════════")

        // Close previous camera if any
        closeCamera()

        this.currentCameraId = cameraId
        this.textureView = textureViewToUse

        // Determine camera type
        try {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            currentLensFacing = chars.get(CameraCharacteristics.LENS_FACING) ?: -1
        } catch (e: Exception) {
            Log.e(TAG, "Cannot get characteristics for $cameraId: ${e.message}")
            currentLensFacing = -1
        }

        _currentCameraIdFlow.value = cameraId

        startBackgroundThread()

        // Open camera when TextureView is ready
        if (textureViewToUse.isAvailable) {
            Log.i(TAG, "TextureView available, opening camera immediately")
            openCameraDevice(cameraId, textureViewToUse.surfaceTexture!!)
        } else {
            Log.i(TAG, "TextureView not available yet, setting SurfaceTexture listener")
            textureViewToUse.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    Log.i(TAG, "SurfaceTexture available (${width}x${height}), opening camera $cameraId")
                    openCameraDevice(cameraId, surface)
                }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    Log.i(TAG, "SurfaceTexture destroyed, closing camera")
                    closeCamera()
                    return true
                }
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        }
    }

    /**
     * Switch to a different camera by ID.
     * Closes current camera and opens the new one.
     */
    fun switchToCamera(cameraId: String) {
        if (cameraId == currentCameraId && cameraDevice != null) {
            Log.d(TAG, "Already using camera $cameraId")
            return
        }
        val tv = textureView ?: return
        Log.i(TAG, "Switching from camera $currentCameraId to $cameraId")
        openCameraById(cameraId, tv)
    }

    /**
     * Force rescan and switch to USB camera if available.
     */
    fun forceSwitchToUSB() {
        Log.i(TAG, "forceSwitchToUSB: Checking for USB camera")
        refreshCameraList()
        val usbCamera = getAllCameraIds(context).find {
            it.second == CameraCharacteristics.LENS_FACING_EXTERNAL
        }
        if (usbCamera != null) {
            Log.i(TAG, "USB camera found: ${usbCamera.first}, switching")
            switchToCamera(usbCamera.first)
        } else {
            Log.w(TAG, "No USB camera found")
        }
    }

    /**
     * Refresh the available camera list from Camera2.
     */
    fun refreshCameraList() {
        val cameras = getAllCameraIds(context)
        _availableCameras.value = cameras.map { (id, _, name) -> id to name }
        Log.i(TAG, "Camera list refreshed: ${cameras.size} cameras found")
        cameras.forEach { (id, facing, name) ->
            Log.i(TAG, "  Camera: id=$id, facing=$facing, name=$name")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Camera2 DEVICE MANAGEMENT
    // ═══════════════════════════════════════════════════════════

    private fun openCameraDevice(cameraId: String, surfaceTexture: SurfaceTexture) {
        try {
            if (!cameraOpenCloseLock.tryAcquire(CAMERA_OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.e(TAG, "Timeout waiting to lock camera opening")
                return
            }

            // Set up preview surface
            surfaceTexture.setDefaultBufferSize(PREVIEW_WIDTH, PREVIEW_HEIGHT)
            previewSurface = Surface(surfaceTexture)

            // Set up ImageReader for still capture
            imageReader = ImageReader.newInstance(
                CAPTURE_WIDTH, CAPTURE_HEIGHT,
                ImageFormat.JPEG, 2
            )
            imageReader?.setOnImageAvailableListener({ reader ->
                processCaptureImage(reader)
            }, backgroundHandler)

            Log.i(TAG, "Opening camera device $cameraId...")
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    cameraDevice = camera
                    Log.i(TAG, "═══════════════════════════════════════════════════")
                    Log.i(TAG, "✓ CAMERA OPENED: id=$cameraId")
                    Log.i(TAG, "═══════════════════════════════════════════════════")
                    startPreview()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    Log.w(TAG, "Camera disconnected: $cameraId")
                    cameraDevice = null
                    _isConnected.value = false
                    _cameraType.value = "none"
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release()
                    Log.e(TAG, "═══════════════════════════════════════════════════")
                    Log.e(TAG, "✗ CAMERA OPEN FAILED: id=$cameraId, error=$error")
                    Log.e(TAG, "═══════════════════════════════════════════════════")
                    cameraDevice = null
                    _isConnected.value = false
                    _cameraType.value = "none"
                }
            }, backgroundHandler)
        } catch (e: SecurityException) {
            cameraOpenCloseLock.release()
            Log.e(TAG, "Camera permission denied: ${e.message}")
            _isConnected.value = false
            _cameraType.value = "none"
        } catch (e: Exception) {
            cameraOpenCloseLock.release()
            Log.e(TAG, "Failed to open camera $cameraId: ${e.message}")
            _isConnected.value = false
            _cameraType.value = "none"
        }
    }

    private fun startPreview() {
        val device = cameraDevice ?: return
        val surface = previewSurface ?: return
        val reader = imageReader ?: return

        try {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
            }

            device.createCaptureSession(
                listOf(surface, reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            // Set AF mode (may not be supported on USB cameras)
                            try {
                                builder.set(CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            } catch (_: Exception) {}

                            // Set AE mode (may not be supported on USB cameras)
                            try {
                                builder.set(CaptureRequest.CONTROL_AE_MODE,
                                    CaptureRequest.CONTROL_AE_MODE_ON)
                            } catch (_: Exception) {}

                            session.setRepeatingRequest(builder.build(), null, backgroundHandler)

                            // Update state
                            _isConnected.value = true
                            _cameraType.value = when (currentLensFacing) {
                                CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                                CameraCharacteristics.LENS_FACING_BACK -> "back"
                                CameraCharacteristics.LENS_FACING_FRONT -> "front"
                                else -> "unknown"
                            }

                            Log.i(TAG, "═══════════════════════════════════════════════════")
                            Log.i(TAG, "✓ CAMERA PREVIEW STARTED: type=${_cameraType.value}, id=$currentCameraId")
                            Log.i(TAG, "═══════════════════════════════════════════════════")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to start repeating request: ${e.message}")
                            _isConnected.value = false
                            _cameraType.value = "none"
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session configure failed")
                        _isConnected.value = false
                        _cameraType.value = "none"
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create capture session: ${e.message}")
            _isConnected.value = false
            _cameraType.value = "none"
        }
    }

    // ═══════════════════════════════════════════════════════════
    // PHOTO CAPTURE
    // ═══════════════════════════════════════════════════════════

    fun capturePhoto(onResult: (Bitmap?) -> Unit) {
        val device = cameraDevice ?: run {
            Log.e(TAG, "Cannot capture: camera device is null")
            onResult(null)
            return
        }
        val session = captureSession ?: run {
            Log.e(TAG, "Cannot capture: session is null")
            onResult(null)
            return
        }
        val reader = imageReader ?: run {
            Log.e(TAG, "Cannot capture: image reader is null")
            onResult(null)
            return
        }

        pendingCaptureCallback = onResult

        try {
            val captureBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                try { set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) } catch (_: Exception) {}
                try { set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON) } catch (_: Exception) {}
            }

            session.stopRepeating()
            session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    Log.d(TAG, "Still capture completed")
                    restartPreview(device, session)
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    Log.e(TAG, "Still capture failed: reason=${failure.reason}")
                    pendingCaptureCallback = null
                    onResult(null)
                    restartPreview(device, session)
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate capture: ${e.message}")
            pendingCaptureCallback = null
            onResult(null)
        }
    }

    private fun restartPreview(device: CameraDevice, session: CameraCaptureSession) {
        try {
            val surface = previewSurface ?: return
            val previewBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                try { set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) } catch (_: Exception) {}
            }
            session.setRepeatingRequest(previewBuilder.build(), null, backgroundHandler)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restart preview: ${e.message}")
        }
    }

    private fun processCaptureImage(reader: ImageReader) {
        var image: Image? = null
        try {
            image = reader.acquireLatestImage() ?: return

            if (image.format != ImageFormat.JPEG) {
                Log.w(TAG, "Unexpected image format: ${image.format}")
                image.close()
                return
            }

            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.capacity())
            buffer.get(bytes)

            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                val rotation = getCameraRotation(currentCameraId)
                val rotated = if (rotation != 0) {
                    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                } else bitmap
                Log.i(TAG, "Photo captured: ${rotated.width}x${rotated.height}")
                pendingCaptureCallback?.invoke(rotated)
            } else {
                Log.e(TAG, "Failed to decode JPEG")
                pendingCaptureCallback?.invoke(null)
            }
            pendingCaptureCallback = null
        } catch (e: Exception) {
            Log.e(TAG, "Error processing capture: ${e.message}")
            pendingCaptureCallback?.invoke(null)
            pendingCaptureCallback = null
        } finally {
            image?.close()
        }
    }

    private fun getCameraRotation(cameraId: String?): Int {
        if (cameraId == null) return 0
        return try {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        } catch (e: Exception) { 0 }
    }

    // ═══════════════════════════════════════════════════════════
    // BACKGROUND THREAD
    // ═══════════════════════════════════════════════════════════

    private fun startBackgroundThread() {
        if (backgroundThread != null && backgroundThread!!.isAlive) return
        backgroundThread = HandlerThread("Camera2Background").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        try {
            backgroundThread?.quitSafely()
            backgroundThread?.join(1000)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping background thread: ${e.message}")
        }
        backgroundThread = null
        backgroundHandler = null
    }

    // ═══════════════════════════════════════════════════════════
    // CLEANUP
    // ═══════════════════════════════════════════════════════════

    fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted waiting for camera lock: ${e.message}")
            return
        }

        try {
            captureSession?.close()
            captureSession = null

            cameraDevice?.close()
            cameraDevice = null

            previewSurface?.release()
            previewSurface = null

            imageReader?.close()
            imageReader = null

            _isConnected.value = false
            _cameraType.value = "none"

            Log.i(TAG, "Camera closed and resources released")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing camera: ${e.message}")
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    fun destroy() {
        closeCamera()
        stopBackgroundThread()
        textureView?.surfaceTextureListener = null
        textureView = null
        currentCameraId = null
        _currentCameraIdFlow.value = ""
        _availableCameras.value = emptyList()
        pendingCaptureCallback = null
    }
}
