package com.saatiril.operator.camera

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager as AndroidCameraManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.TextureView
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ═════════════════════════════════════════════════════════════════════════
 * Unified Camera Manager — DUAL ENGINE architecture (v4)
 * ═════════════════════════════════════════════════════════════════════════
 *
 * ROOT CAUSE OF ALL PREVIOUS FAILURES:
 * CameraX 1.3.x CANNOT reliably use USB HDMI capture cards because:
 * 1. ProcessCameraProvider.availableCameraInfos is a FROZEN SNAPSHOT
 * 2. ProcessCameraProvider is a SINGLETON — can't get a fresh instance
 * 3. addCameraFilter + bindToLifecycle fails with IllegalArgumentException
 *    when the camera ID isn't in CameraX's internal registry
 * 4. LENS_FACING_EXTERNAL + hasCamera() returns false on most devices
 * 5. Even force-reinitializing the provider returns the SAME stale instance
 *
 * THE PROVEN SOLUTION (v4 — DUAL ENGINE):
 * ┌─────────────────────────────────────────────────────────┐
 * │ Built-in cameras (BACK/FRONT) → CameraX engine          │
 * │   - CameraX handles lifecycle, preview, capture          │
 * │   - Works perfectly for phone's built-in cameras         │
 * │                                                          │
 * │ USB cameras (EXTERNAL) → Camera2 engine                  │
 * │   - Camera2 API opens USB camera directly                │
 * │   - CameraManager.getCameraIdList() ALWAYS sees USB      │
 * │   - CameraDevice + CameraCaptureSession for preview      │
 * │   - ImageReader for JPEG still capture                   │
 * │   - TextureView for preview rendering                    │
 * └─────────────────────────────────────────────────────────┘
 *
 * This manager auto-detects USB cameras on init and switches
 * between engines seamlessly. The rest of the app (ViewModel,
 * OperatorScreen) only interacts with this class.
 *
 * KEY DECISION: When a USB camera is detected at init time,
 * it is AUTOMATICALLY selected (highest priority). The user
 * can switch back to built-in via the camera picker dropdown.
 */
@androidx.camera.camera2.interop.ExperimentalCamera2Interop
class BuiltInCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "BuiltInCameraManager"
        private const val USB_CAMERA_REGISTRATION_DELAY_MS = 2000L

        /**
         * Find external camera IDs using Android's Camera2 CameraManager.
         * This queries the OS directly and ALWAYS sees hot-plugged USB cameras.
         */
        fun findExternalCameraIds(context: Context): List<Pair<String, Int>> {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? AndroidCameraManager
                ?: return emptyList()

            val externalCameras = mutableListOf<Pair<String, Int>>()
            try {
                for (id in cameraManager.cameraIdList) {
                    try {
                        val characteristics = cameraManager.getCameraCharacteristics(id)
                        val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                        if (lensFacing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                            Log.i(TAG, "Camera2 discovered external camera: id=$id")
                            externalCameras.add(id to CameraSelector.LENS_FACING_EXTERNAL)
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Cannot check camera $id: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error enumerating Camera2 cameras: ${e.message}")
            }
            return externalCameras
        }

        /**
         * Get ALL camera IDs from Camera2 CameraManager (OS-level, always current).
         */
        fun getAllCameraIdsFromCamera2(context: Context): List<Triple<String, Int, String>> {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? AndroidCameraManager
                ?: return emptyList()

            val cameras = mutableListOf<Triple<String, Int, String>>()
            try {
                for (id in cameraManager.cameraIdList) {
                    try {
                        val characteristics = cameraManager.getCameraCharacteristics(id)
                        val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                        val facing = when (lensFacing) {
                            CameraCharacteristics.LENS_FACING_FRONT -> CameraSelector.LENS_FACING_FRONT
                            CameraCharacteristics.LENS_FACING_BACK -> CameraSelector.LENS_FACING_BACK
                            CameraCharacteristics.LENS_FACING_EXTERNAL -> CameraSelector.LENS_FACING_EXTERNAL
                            else -> -1
                        }
                        val displayName = when (lensFacing) {
                            CameraCharacteristics.LENS_FACING_EXTERNAL -> "USB Capture Card"
                            CameraCharacteristics.LENS_FACING_BACK -> "Kamera Belakang"
                            CameraCharacteristics.LENS_FACING_FRONT -> "Kamera Depan"
                            else -> "Kamera ($id)"
                        }
                        cameras.add(Triple(id, facing, displayName))
                    } catch (e: Exception) {
                        Log.d(TAG, "Cannot get characteristics for camera $id: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting camera ID list: ${e.message}")
            }
            return cameras
        }
    }

    // ═══════════════════════════════════════════════════════════
    // DUAL ENGINE: CameraX (built-in) + Camera2 (USB)
    // ═══════════════════════════════════════════════════════════

    // CameraX engine (for built-in cameras)
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var currentCameraSelector: CameraSelector? = null
    private var currentLensFacing: Int = CameraSelector.LENS_FACING_BACK

    // Camera2 engine (for USB cameras)
    private val externalCameraManager = ExternalCameraManager(context)

    // Common state
    private var isUsingExternalCamera: Boolean = false
    private var currentExternalCameraId: String? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var previewView: PreviewView? = null
    private var textureView: TextureView? = null

    // Track whether camera provider has been initialized
    private var providerInitialized: Boolean = false
    private var initInProgress: Boolean = false
    private var pendingUsbRescan: Boolean = false

    // ═══════════════════════════════════════════════════════════
    // STATE FLOWS (unified — same regardless of engine)
    // ═══════════════════════════════════════════════════════════

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _cameraType = MutableStateFlow("none")
    val cameraType: StateFlow<String> = _cameraType.asStateFlow()

    private val _currentCameraId = MutableStateFlow("")
    val currentCameraId: StateFlow<String> = _currentCameraId.asStateFlow()

    private val _availableCameras = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val availableCameras: StateFlow<List<Pair<String, String>>> = _availableCameras.asStateFlow()

    // Expose whether we're using the Camera2 engine (for UI to know which view to use)
    private val _useCamera2Engine = MutableStateFlow(false)
    val useCamera2Engine: StateFlow<Boolean> = _useCamera2Engine.asStateFlow()

    // ─── Permission Check ──────────────────────────────────────

    fun hasCameraPermission(): Boolean {
        return (ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }

    // ═══════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════

    /**
     * Initialize camera system. This is the MAIN entry point.
     *
     * IMPORTANT: The UI must provide EITHER previewView (for CameraX built-in)
     * OR textureView (for Camera2 USB). Both can be provided.
     *
     * Camera selection priority:
     * 1. USB external camera (Camera2 engine) — if detected
     * 2. Built-in back camera (CameraX engine) — fallback
     * 3. Built-in front camera (CameraX engine) — last resort
     */
    fun init(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        this.lifecycleOwner = lifecycleOwner
        this.previewView = previewView

        if (!hasCameraPermission()) {
            Log.e(TAG, "CAMERA permission not granted — cannot initialize")
            _cameraType.value = "none"
            _isConnected.value = false
            return
        }

        // Refresh camera list from Camera2 (OS-level)
        refreshAvailableCamerasFromCamera2()

        // Step 1: Check for USB camera FIRST
        val externalCameras = findExternalCameraIds(context)
        if (externalCameras.isNotEmpty()) {
            Log.i(TAG, "═══════════════════════════════════════════════════")
            Log.i(TAG, "USB CAMERA DETECTED at init: id=${externalCameras.first().first}")
            Log.i(TAG, "Activating Camera2 engine for USB camera")
            Log.i(TAG, "═══════════════════════════════════════════════════")
            activateUSBCamera(externalCameras.first().first)
            return
        }

        // Step 2: No USB camera — initialize CameraX for built-in cameras
        Log.i(TAG, "No USB camera detected — initializing CameraX for built-in cameras")
        initCameraXProvider(lifecycleOwner, previewView)
    }

    /**
     * Set the TextureView for Camera2 engine (USB camera preview).
     * Must be called BEFORE init() or when the TextureView becomes available.
     */
    fun setTextureView(tv: TextureView) {
        this.textureView = tv
        // If we're already using USB camera but didn't have a TextureView, re-init
        if (isUsingExternalCamera && externalCameraManager.isConnected.value && tv.isAvailable) {
            Log.i(TAG, "TextureView set while USB camera is active — re-initializing")
            val cameraId = currentExternalCameraId ?: return
            externalCameraManager.closeCamera()
            externalCameraManager.init(tv)
        }
    }

    /**
     * Re-initialize camera after permission is granted.
     */
    fun reinit(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        Log.i(TAG, "Re-initializing camera")
        closeAllCameras()
        init(lifecycleOwner, previewView)
    }

    // ═══════════════════════════════════════════════════════════
    // USB CAMERA (Camera2 Engine)
    // ═══════════════════════════════════════════════════════════

    /**
     * Activate USB camera using Camera2 engine.
     * This CLOSES the CameraX engine first, then opens the Camera2 engine.
     */
    private fun activateUSBCamera(cameraId: String) {
        Log.i(TAG, "activateUSBCamera: Switching to Camera2 engine for USB camera id=$cameraId")

        // Close CameraX engine if running
        closeCameraXEngine()

        val tv = textureView
        if (tv == null) {
            Log.w(TAG, "TextureView not set yet — USB camera will be activated when TextureView is provided")
            isUsingExternalCamera = true
            currentExternalCameraId = cameraId
            _useCamera2Engine.value = true
            _currentCameraId.value = cameraId
            _cameraType.value = "external"
            // Will be properly connected when setTextureView() is called
            return
        }

        // Open USB camera via Camera2
        isUsingExternalCamera = true
        currentExternalCameraId = cameraId
        _useCamera2Engine.value = true
        _currentCameraId.value = cameraId

        val success = externalCameraManager.init(tv)
        if (success) {
            // Wait for the camera to actually open (it's async)
            // The ExternalCameraManager sets isConnected = true when it's ready
            // For now, set the type optimistically
            _cameraType.value = "external"
            Log.i(TAG, "USB camera activation initiated via Camera2 engine")
        } else {
            Log.e(TAG, "USB camera activation FAILED — falling back to CameraX built-in")
            isUsingExternalCamera = false
            currentExternalCameraId = null
            _useCamera2Engine.value = false
            // Fall back to CameraX
            val owner = lifecycleOwner ?: return
            val pv = previewView ?: return
            initCameraXProvider(owner, pv)
        }

        refreshAvailableCamerasFromCamera2()
    }

    // ═══════════════════════════════════════════════════════════
    // CameraX Engine (Built-in Cameras)
    // ═══════════════════════════════════════════════════════════

    private fun initCameraXProvider(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        if (providerInitialized && cameraProvider != null) {
            selectBestBuiltInCamera(lifecycleOwner, previewView)
            return
        }

        if (initInProgress) {
            Log.d(TAG, "CameraX init already in progress")
            return
        }

        initInProgress = true
        Log.i(TAG, "Initializing CameraX provider for built-in cameras")

        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                try {
                    cameraProvider = cameraProviderFuture.get()
                    providerInitialized = true
                    initInProgress = false
                    Log.i(TAG, "CameraX provider initialized. Cameras: ${cameraProvider?.availableCameraInfos?.size}")

                    if (pendingUsbRescan) {
                        pendingUsbRescan = false
                        Handler(Looper.getMainLooper()).postDelayed({
                            val extCams = findExternalCameraIds(context)
                            if (extCams.isNotEmpty()) {
                                activateUSBCamera(extCams.first().first)
                            } else {
                                selectBestBuiltInCamera(lifecycleOwner, previewView)
                            }
                        }, USB_CAMERA_REGISTRATION_DELAY_MS)
                    } else {
                        selectBestBuiltInCamera(lifecycleOwner, previewView)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get CameraX provider: ${e.message}")
                    initInProgress = false
                    _cameraType.value = "none"
                    _isConnected.value = false
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get CameraX provider instance: ${e.message}")
            initInProgress = false
            _cameraType.value = "none"
            _isConnected.value = false
        }
    }

    /**
     * Select the best built-in camera (CameraX engine).
     * Only called when no USB camera is available.
     */
    private fun selectBestBuiltInCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val provider = cameraProvider ?: return

        _isConnected.value = false
        _cameraType.value = "none"
        _useCamera2Engine.value = false
        isUsingExternalCamera = false
        currentExternalCameraId = null

        // Try back camera
        try {
            val backSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()
            if (provider.hasCamera(backSelector)) {
                currentLensFacing = CameraSelector.LENS_FACING_BACK
                currentCameraSelector = backSelector
                startCameraX(lifecycleOwner, previewView)
                return
            }
        } catch (e: Exception) {
            Log.d(TAG, "No back camera: ${e.message}")
        }

        // Try front camera
        try {
            val frontSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()
            if (provider.hasCamera(frontSelector)) {
                currentLensFacing = CameraSelector.LENS_FACING_FRONT
                currentCameraSelector = frontSelector
                startCameraX(lifecycleOwner, previewView)
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "No camera at all: ${e.message}")
        }

        Log.e(TAG, "NO CAMERA DETECTED")
        _cameraType.value = "none"
        _isConnected.value = false
    }

    /**
     * Start CameraX camera (built-in).
     */
    private fun startCameraX(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val provider = cameraProvider ?: return
        val selector = currentCameraSelector ?: return

        try {
            provider.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unbind: ${e.message}")
        }

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .build()

        try {
            camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
            _isConnected.value = true
            _cameraType.value = when (currentLensFacing) {
                CameraSelector.LENS_FACING_BACK -> "back"
                CameraSelector.LENS_FACING_FRONT -> "front"
                else -> "unknown"
            }
            _useCamera2Engine.value = false
            _currentCameraId.value = try {
                camera?.cameraInfo?.let { getCameraIdFromCameraInfo(it) } ?: ""
            } catch (e: Exception) { "" }
            refreshAvailableCamerasFromCamera2()
            Log.i(TAG, "CameraX started: type=${_cameraType.value}, id=${_currentCameraId.value}")
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied: ${e.message}")
            _isConnected.value = false
            _cameraType.value = "none"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start CameraX: ${e.message}")
            _isConnected.value = false
            _cameraType.value = "none"
        }
    }

    // ═══════════════════════════════════════════════════════════
    // CAMERA SWITCHING
    // ═══════════════════════════════════════════════════════════

    /**
     * Switch between cameras: USB → Back → Front → USB
     */
    fun switchCamera() {
        if (isUsingExternalCamera) {
            // Switch from USB to built-in back
            switchToBuiltInCamera(CameraSelector.LENS_FACING_BACK)
        } else if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
            // Try USB first, then front
            val extCams = findExternalCameraIds(context)
            if (extCams.isNotEmpty()) {
                activateUSBCamera(extCams.first().first)
            } else {
                switchToBuiltInCamera(CameraSelector.LENS_FACING_FRONT)
            }
        } else {
            // Front → try USB → back
            val extCams = findExternalCameraIds(context)
            if (extCams.isNotEmpty()) {
                activateUSBCamera(extCams.first().first)
            } else {
                switchToBuiltInCamera(CameraSelector.LENS_FACING_BACK)
            }
        }
    }

    /**
     * Switch to a specific camera by ID.
     */
    fun switchToCameraById(cameraId: String) {
        val allCameras = getAllCameraIdsFromCamera2(context)
        val target = allCameras.find { it.first == cameraId } ?: return

        if (target.second == CameraSelector.LENS_FACING_EXTERNAL) {
            activateUSBCamera(cameraId)
        } else {
            switchToBuiltInCamera(target.second)
        }
    }

    /**
     * Switch to a built-in camera (CameraX engine).
     */
    private fun switchToBuiltInCamera(lensFacing: Int) {
        val owner = lifecycleOwner ?: return
        val pv = previewView ?: return

        // Close USB camera engine if running
        closeCamera2Engine()

        // Close CameraX engine and restart with new lens
        isUsingExternalCamera = false
        currentExternalCameraId = null
        _useCamera2Engine.value = false
        currentLensFacing = lensFacing
        currentCameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        startCameraX(owner, pv)
    }

    // ═══════════════════════════════════════════════════════════
    // FORCE REINIT FOR USB (called by ViewModel when USB detected)
    // ═══════════════════════════════════════════════════════════

    /**
     * Force activation of USB camera.
     * Called when:
     * - UVC device is detected (ViewModel's uvcConnectedCollector)
     * - User taps "Pindai Ulang USB" button
     * - Periodic rescan finds UVC but we're still on built-in
     */
    fun forceReinitForUSB() {
        Log.i(TAG, "═══════════════════════════════════════════════════")
        Log.i(TAG, "forceReinitForUSB: USB camera activation requested")
        Log.i(TAG, "═══════════════════════════════════════════════════")

        val extCams = findExternalCameraIds(context)
        if (extCams.isEmpty()) {
            Log.w(TAG, "No USB camera found by Camera2 — scheduling delayed retry")
            Handler(Looper.getMainLooper()).postDelayed({
                val retry = findExternalCameraIds(context)
                if (retry.isNotEmpty()) {
                    Log.i(TAG, "Delayed retry found USB camera: ${retry.first().first}")
                    activateUSBCamera(retry.first().first)
                }
            }, USB_CAMERA_REGISTRATION_DELAY_MS)
            return
        }

        val cameraId = extCams.first().first
        if (isUsingExternalCamera && currentExternalCameraId == cameraId && externalCameraManager.isConnected.value) {
            Log.d(TAG, "USB camera $cameraId is already active")
            return
        }

        activateUSBCamera(cameraId)
    }

    /**
     * Called when USB device is attached/detached.
     */
    fun onUsbDeviceChanged() {
        if (!providerInitialized) {
            pendingUsbRescan = true
        }
    }

    /**
     * Rescan for external cameras.
     */
    fun rescanForExternalCamera() {
        refreshAvailableCamerasFromCamera2()
        val extCams = findExternalCameraIds(context)

        if (extCams.isNotEmpty() && !isUsingExternalCamera) {
            Log.i(TAG, "Rescan found USB camera, activating")
            activateUSBCamera(extCams.first().first)
        } else if (extCams.isEmpty() && isUsingExternalCamera) {
            Log.w(TAG, "USB camera lost, falling back to built-in")
            closeCamera2Engine()
            val owner = lifecycleOwner ?: return
            val pv = previewView ?: return
            isUsingExternalCamera = false
            currentExternalCameraId = null
            _useCamera2Engine.value = false
            selectBestBuiltInCamera(owner, pv)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // PHOTO CAPTURE (delegates to the active engine)
    // ═══════════════════════════════════════════════════════════

    /**
     * Capture a photo. Delegates to Camera2 engine (USB) or CameraX engine (built-in).
     */
    fun capturePhoto(onResult: (Bitmap?) -> Unit) {
        if (isUsingExternalCamera) {
            externalCameraManager.capturePhoto(onResult)
        } else {
            capturePhotoCameraX(onResult)
        }
    }

    private fun capturePhotoCameraX(onResult: (Bitmap?) -> Unit) {
        val capture = imageCapture ?: run {
            Log.e(TAG, "ImageCapture not initialized")
            onResult(null)
            return
        }

        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val bitmap = image.toBitmap()
                        val rotation = image.imageInfo.rotationDegrees
                        val rotated = if (rotation != 0) {
                            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                        } else {
                            bitmap
                        }
                        onResult(rotated)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to process captured image: ${e.message}")
                        onResult(null)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}")
                    onResult(null)
                }
            }
        )
    }

    private fun ImageProxy.toBitmap(): Bitmap {
        if (format == android.graphics.ImageFormat.JPEG ||
            format == android.graphics.ImageFormat.DEPTH_JPEG) {
            val buffer = planes[0].buffer
            val bytes = ByteArray(buffer.capacity())
            buffer.get(bytes)
            return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalStateException("Failed to decode JPEG image")
        }

        return try {
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer
            val yRowStride = planes[0].rowStride
            val uvRowStride = planes[1].rowStride
            val uvPixelStride = planes[1].pixelStride
            val width = width
            val height = height
            val argb = IntArray(width * height)

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val yIndex = y * yRowStride + x
                    val uvIndex = (y / 2) * uvRowStride + (x / 2) * uvPixelStride
                    val yValue = yBuffer.get(yIndex).toInt() and 0xFF
                    val uValue = uBuffer.get(uvIndex).toInt() and 0xFF
                    val vValue = vBuffer.get(uvIndex).toInt() and 0xFF
                    val r = (yValue + 1.370705 * (vValue - 128)).toInt().coerceIn(0, 255)
                    val g = (yValue - 0.337633 * (uValue - 128) - 0.698001 * (vValue - 128)).toInt().coerceIn(0, 255)
                    val b = (yValue + 1.732446 * (uValue - 128)).toInt().coerceIn(0, 255)
                    argb[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            Log.e(TAG, "YUV conversion failed: ${e.message}")
            val buffer = planes[0].buffer
            val bytes = ByteArray(buffer.capacity())
            buffer.get(bytes)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalStateException("Failed to convert image format: $format")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // CAMERA LIST / PICKER
    // ═══════════════════════════════════════════════════════════

    fun getAvailableCameras(): List<Pair<String, String>> {
        refreshAvailableCamerasFromCamera2()
        return _availableCameras.value
    }

    fun refreshAvailableCameras() {
        refreshAvailableCamerasFromCamera2()
    }

    private fun refreshAvailableCamerasFromCamera2() {
        val cameras = getAllCameraIdsFromCamera2(context)
        _availableCameras.value = cameras.map { (id, _, name) -> id to name }
    }

    // ═══════════════════════════════════════════════════════════
    // ENGINE MANAGEMENT
    // ═══════════════════════════════════════════════════════════

    private fun closeCameraXEngine() {
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding CameraX: ${e.message}")
        }
        camera = null
        preview = null
        imageCapture = null
        _isConnected.value = false
    }

    private fun closeCamera2Engine() {
        externalCameraManager.closeCamera()
        _isConnected.value = false
    }

    private fun closeAllCameras() {
        closeCameraXEngine()
        closeCamera2Engine()
        isUsingExternalCamera = false
        currentExternalCameraId = null
        _useCamera2Engine.value = false
        _cameraType.value = "none"
        _isConnected.value = false
    }

    private fun getCameraIdFromCameraInfo(cameraInfo: CameraInfo): String? {
        return try {
            Camera2CameraInfo.from(cameraInfo).cameraId
        } catch (e: Exception) {
            null
        }
    }

    // ═══════════════════════════════════════════════════════════
    // CLEANUP
    // ═══════════════════════════════════════════════════════════

    fun destroy() {
        closeAllCameras()
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding during destroy: ${e.message}")
        }
        externalCameraManager.destroy()
        cameraProvider = null
        preview = null
        imageCapture = null
        camera = null
        lifecycleOwner = null
        previewView = null
        textureView = null
        currentCameraSelector = null
        currentExternalCameraId = null
        isUsingExternalCamera = false
        providerInitialized = false
        initInProgress = false
        pendingUsbRescan = false
        _currentCameraId.value = ""
        _availableCameras.value = emptyList()
    }
}
