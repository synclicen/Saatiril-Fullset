package com.saatiril.operator.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * ═══════════════════════════════════════════════════════════════════
 * USB Camera Manager using Camera2 API DIRECTLY.
 * ═══════════════════════════════════════════════════════════════════
 *
 * WHY THIS EXISTS:
 * CameraX 1.3.x CANNOT reliably bind to USB HDMI capture cards because:
 * 1. ProcessCameraProvider.availableCameraInfos is a SNAPSHOT that doesn't
 *    update when USB cameras are hot-plugged
 * 2. ProcessCameraProvider is a SINGLETON — destroying and recreating it
 *    returns the SAME stale instance
 * 3. Even using Camera2CameraInfo + addCameraFilter fails because CameraX's
 *    internal camera registry doesn't contain the USB camera at all
 * 4. LENS_FACING_EXTERNAL selector returns hasCamera() = false on most devices
 *
 * THE SOLUTION:
 * Use Android's Camera2 API directly for USB cameras. Camera2 ALWAYS sees
 * USB cameras via CameraManager.getCameraIdList() and can open them with
 * CameraManager.openCamera(). This bypasses CameraX entirely for USB cameras.
 *
 * ARCHITECTURE:
 * - Preview: CameraDevice → CameraCaptureSession → SurfaceTexture (TextureView)
 * - Capture: CameraDevice → CameraCaptureSession → ImageReader → Bitmap
 * - Built-in cameras still use CameraX (via BuiltInCameraManager)
 * - This manager is ONLY used when a USB capture card is detected
 */
class ExternalCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "ExternalCameraManager"
        private const val CAMERA_OPEN_TIMEOUT_MS = 3000L
        private const val PREVIEW_WIDTH = 1920
        private const val PREVIEW_HEIGHT = 1080
        private const val CAPTURE_WIDTH = 1920
        private const val CAPTURE_HEIGHT = 1080
    }

    private val cameraManager: CameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Semaphore to prevent multiple simultaneous camera open operations
    private val cameraOpenCloseLock = Semaphore(1)

    // Camera ID for the USB camera
    private var usbCameraId: String? = null

    // State flows
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _cameraType = MutableStateFlow("none")
    val cameraType: StateFlow<String> = _cameraType.asStateFlow()

    private val _currentCameraId = MutableStateFlow("")
    val currentCameraId: StateFlow<String> = _currentCameraId.asStateFlow()

    // TextureView for preview — set by UI
    private var textureView: TextureView? = null

    // Pending capture callback
    private var pendingCaptureCallback: ((Bitmap?) -> Unit)? = null

    // ─── Discovery ────────────────────────────────────────────

    /**
     * Find USB camera ID using Camera2 CameraManager.
     * Returns the camera ID if found, null otherwise.
     */
    fun findUSBCameraId(): String? {
        try {
            for (id in cameraManager.cameraIdList) {
                try {
                    val characteristics = cameraManager.getCameraCharacteristics(id)
                    val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    if (lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                        Log.i(TAG, "Found USB external camera: id=$id")
                        return id
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Cannot check camera $id: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enumerating cameras: ${e.message}")
        }
        Log.i(TAG, "No USB external camera found")
        return null
    }

    /**
     * Check if a USB camera is currently available.
     */
    fun isUSBCameraAvailable(): Boolean {
        return findUSBCameraId() != null
    }

    // ─── Lifecycle ────────────────────────────────────────────

    /**
     * Start the background thread for camera operations.
     */
    private fun startBackgroundThread() {
        if (backgroundThread != null && backgroundThread!!.isAlive) return
        backgroundThread = HandlerThread("USBCameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    /**
     * Stop the background thread.
     */
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

    /**
     * Initialize and open the USB camera.
     * Must be called after USB camera is detected AND permission is granted.
     *
     * @param textureView The TextureView to render the preview on
     * @return true if camera was opened successfully, false otherwise
     */
    fun init(textureView: TextureView): Boolean {
        Log.i(TAG, "═══════════════════════════════════════════════════")
        Log.i(TAG, "init: Starting USB camera via Camera2 API")
        Log.i(TAG, "═══════════════════════════════════════════════════")

        val cameraId = findUSBCameraId()
        if (cameraId == null) {
            Log.e(TAG, "init: No USB camera found by Camera2")
            _isConnected.value = false
            _cameraType.value = "none"
            return false
        }

        this.usbCameraId = cameraId
        this.textureView = textureView
        this._currentCameraId.value = cameraId

        startBackgroundThread()

        // If TextureView's SurfaceTexture is ready, open camera immediately
        if (textureView.isAvailable) {
            Log.i(TAG, "TextureView is available, opening camera immediately")
            openCamera(cameraId, textureView.surfaceTexture!!)
        } else {
            // Wait for SurfaceTexture to be ready
            Log.i(TAG, "TextureView not yet available, setting listener")
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    Log.i(TAG, "SurfaceTexture available (${width}x${height}), opening camera")
                    openCamera(cameraId, surface)
                }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                    Log.d(TAG, "SurfaceTexture size changed: ${width}x${height}")
                }
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    Log.i(TAG, "SurfaceTexture destroyed")
                    closeCamera()
                    return true
                }
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        }

        return true
    }

    /**
     * Open the camera device.
     */
    private fun openCamera(cameraId: String, surfaceTexture: SurfaceTexture) {
        try {
            if (!cameraOpenCloseLock.tryAcquire(CAMERA_OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.e(TAG, "Time out waiting to lock camera opening")
                return
            }

            // Set up the preview surface
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

            Log.i(TAG, "Opening camera $cameraId...")
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    cameraDevice = camera
                    Log.i(TAG, "═══════════════════════════════════════════════════")
                    Log.i(TAG, "✓ USB CAMERA OPENED SUCCESSFULLY: id=$cameraId")
                    Log.i(TAG, "═══════════════════════════════════════════════════")
                    startPreview()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraOpenCloseLock.release()
                    Log.w(TAG, "USB camera disconnected")
                    cameraDevice = null
                    _isConnected.value = false
                    _cameraType.value = "none"
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraOpenCloseLock.release()
                    Log.e(TAG, "═══════════════════════════════════════════════════")
                    Log.e(TAG, "✗ USB CAMERA OPEN FAILED: error=$error")
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
            Log.e(TAG, "Failed to open camera: ${e.message}")
            _isConnected.value = false
            _cameraType.value = "none"
        }
    }

    /**
     * Start the camera preview session.
     */
    private fun startPreview() {
        val device = cameraDevice ?: return
        val surface = previewSurface ?: return
        val reader = imageReader ?: return

        try {
            val captureRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
            }

            device.createCaptureSession(
                listOf(surface, reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                            // For USB cameras, auto-exposure might not be supported
                            // so we set it but don't fail if it's not available
                            try {
                                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                                    CaptureRequest.CONTROL_AE_MODE_ON)
                            } catch (e: Exception) {
                                Log.d(TAG, "Auto-exposure not supported: ${e.message}")
                            }

                            session.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler)
                            _isConnected.value = true
                            _cameraType.value = "external"

                            Log.i(TAG, "═══════════════════════════════════════════════════")
                            Log.i(TAG, "✓ USB CAMERA PREVIEW STARTED SUCCESSFULLY")
                            Log.i(TAG, "═══════════════════════════════════════════════════")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to start repeating request: ${e.message}")
                            _isConnected.value = false
                            _cameraType.value = "none"
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "═══════════════════════════════════════════════════")
                        Log.e(TAG, "✗ USB CAMERA CAPTURE SESSION CONFIGURE FAILED")
                        Log.e(TAG, "═══════════════════════════════════════════════════")
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

    /**
     * Capture a still photo from the USB camera.
     */
    fun capturePhoto(onResult: (Bitmap?) -> Unit) {
        val device = cameraDevice ?: run {
            Log.e(TAG, "Cannot capture: camera device is null")
            onResult(null)
            return
        }
        val session = captureSession ?: run {
            Log.e(TAG, "Cannot capture: capture session is null")
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
            val captureRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                try {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                } catch (e: Exception) {
                    Log.d(TAG, "AE mode not supported for capture: ${e.message}")
                }
            }

            // Stop the continuous preview first, then capture, then restart preview
            session.stopRepeating()
            session.capture(captureRequestBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    Log.d(TAG, "Still capture completed")
                    // Restart preview after capture
                    try {
                        val previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(previewSurface!!)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        }
                        session.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to restart preview after capture: ${e.message}")
                    }
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    Log.e(TAG, "Still capture failed: reason=${failure.reason}")
                    pendingCaptureCallback = null
                    onResult(null)
                    // Restart preview
                    try {
                        val previewRequestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(previewSurface!!)
                        }
                        session.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to restart preview after failed capture: ${e.message}")
                    }
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initiate capture: ${e.message}")
            pendingCaptureCallback = null
            onResult(null)
        }
    }

    /**
     * Process a captured image from ImageReader.
     */
    private fun processCaptureImage(reader: ImageReader) {
        var image: Image? = null
        try {
            image = reader.acquireLatestImage() ?: return

            if (image.format != ImageFormat.JPEG) {
                Log.w(TAG, "Unexpected image format: ${image.format}, expected JPEG")
                image.close()
                return
            }

            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.capacity())
            buffer.get(bytes)

            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                // Handle rotation if needed
                val rotation = getCameraRotation(usbCameraId)
                val rotated = if (rotation != 0) {
                    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                } else {
                    bitmap
                }
                Log.i(TAG, "Photo captured: ${rotated.width}x${rotated.height}")
                pendingCaptureCallback?.invoke(rotated)
            } else {
                Log.e(TAG, "Failed to decode captured JPEG")
                pendingCaptureCallback?.invoke(null)
            }
            pendingCaptureCallback = null
        } catch (e: Exception) {
            Log.e(TAG, "Error processing capture image: ${e.message}")
            pendingCaptureCallback?.invoke(null)
            pendingCaptureCallback = null
        } finally {
            image?.close()
        }
    }

    /**
     * Get the rotation for the camera sensor.
     * For USB cameras, this is typically 0 (no rotation needed).
     */
    private fun getCameraRotation(cameraId: String?): Int {
        if (cameraId == null) return 0
        return try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Close the camera and release all resources.
     */
    fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while waiting for camera lock: ${e.message}")
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

            Log.i(TAG, "USB camera closed and resources released")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing camera: ${e.message}")
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    /**
     * Destroy this manager — close camera and stop background thread.
     */
    fun destroy() {
        closeCamera()
        stopBackgroundThread()
        textureView?.surfaceTextureListener = null
        textureView = null
        usbCameraId = null
        _currentCameraId.value = ""
        pendingCaptureCallback = null
    }

    /**
     * Switch to a different USB camera by ID.
     */
    fun switchToCamera(cameraId: String) {
        if (cameraId == usbCameraId && cameraDevice != null) {
            Log.d(TAG, "Already using camera $cameraId")
            return
        }

        val tv = textureView ?: return
        closeCamera()
        usbCameraId = cameraId
        _currentCameraId.value = cameraId
        init(tv)
    }
}
