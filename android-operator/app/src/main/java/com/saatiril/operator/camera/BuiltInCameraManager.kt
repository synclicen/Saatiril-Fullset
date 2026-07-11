package com.saatiril.operator.camera

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
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
 * Manages camera using CameraX — supports BOTH built-in cameras and
 * USB HDMI capture cards (which Android exposes as external cameras).
 *
 * Camera selection strategy:
 * 1. EXTERNAL (USB HDMI capture cards) — highest priority, detected by camera ID containing "external"
 * 2. BACK — built-in rear camera (fallback)
 * 3. FRONT — built-in front camera (last resort)
 *
 * CRITICAL FIXES from camera-not-detected bug:
 * - init() is now IDEMPOTENT: calling it multiple times is safe (cleans up old camera first)
 * - init() checks CAMERA permission BEFORE accessing CameraX
 * - Camera selection failures no longer leave _isConnected/_cameraType in stale state
 * - Added reinit() for re-initializing camera with a new PreviewView or LifecycleOwner
 */
@androidx.camera.camera2.interop.ExperimentalCamera2Interop
class BuiltInCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "BuiltInCameraManager"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var currentCameraSelector: CameraSelector? = null
    private var currentLensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var isUsingExternalCamera: Boolean = false
    private var lifecycleOwner: LifecycleOwner? = null
    private var previewView: PreviewView? = null

    // Track whether camera provider has been initialized
    private var providerInitialized: Boolean = false
    private var initInProgress: Boolean = false

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // Camera source: "external", "back", "front", "none"
    private val _cameraType = MutableStateFlow("none")
    val cameraType: StateFlow<String> = _cameraType.asStateFlow()

    // ─── Permission Check ──────────────────────────────────────

    /**
     * Check if CAMERA permission is granted.
     * This MUST be checked before calling init() or any CameraX API.
     */
    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
    }

    // ─── Setup ──────────────────────────────────────────────────

    /**
     * Initialize camera with the given LifecycleOwner and PreviewView.
     *
     * IDEMPOTENT: Calling this multiple times is safe.
     * - If camera provider is already initialized, it will be reused
     * - If camera is already running with the same lifecycle/preview, it's a no-op
     * - If lifecycle or preview changed, camera will be re-bound
     *
     * CRITICAL: Must only be called after CAMERA permission is granted.
     * Call hasCameraPermission() first.
     */
    fun init(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        // Permission check — MUST have camera permission before accessing CameraX
        if (!hasCameraPermission()) {
            Log.e(TAG, "CAMERA permission not granted — cannot initialize camera")
            _cameraType.value = "none"
            _isConnected.value = false
            return
        }

        val ownerChanged = this.lifecycleOwner != lifecycleOwner
        val previewChanged = this.previewView != previewView
        this.lifecycleOwner = lifecycleOwner
        this.previewView = previewView

        if (providerInitialized && cameraProvider != null) {
            // Provider already initialized — just rebind camera if lifecycle/preview changed
            if (ownerChanged || previewChanged) {
                Log.i(TAG, "Camera provider already initialized, rebinding with new lifecycle/preview")
                selectBestCamera(lifecycleOwner, previewView)
            } else if (_isConnected.value) {
                // Already connected with same lifecycle/preview — nothing to do
                Log.d(TAG, "Camera already initialized and connected — skipping")
                return
            } else {
                // Not connected but provider exists — try to select camera
                Log.i(TAG, "Camera provider exists but not connected — retrying camera selection")
                selectBestCamera(lifecycleOwner, previewView)
            }
            return
        }

        // First-time initialization
        if (initInProgress) {
            Log.d(TAG, "Camera init already in progress — skipping duplicate call")
            return
        }
        Log.i(TAG, "Initializing camera provider for the first time")
        initInProgress = true
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

            cameraProviderFuture.addListener({
                try {
                    cameraProvider = cameraProviderFuture.get()
                    providerInitialized = true
                    initInProgress = false
                    selectBestCamera(lifecycleOwner, previewView)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get camera provider from future: ${e.message}")
                    initInProgress = false
                    _cameraType.value = "none"
                    _isConnected.value = false
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get camera provider instance: ${e.message}")
            initInProgress = false
            _cameraType.value = "none"
            _isConnected.value = false
        }
    }

    /**
     * Re-initialize camera after permission is granted.
     * Safe to call even if camera was already initialized.
     */
    fun reinit(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        Log.i(TAG, "Re-initializing camera (permission may have just been granted)")
        // Reset state
        _isConnected.value = false
        _cameraType.value = "none"

        // Try to clean up existing camera
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding during reinit: ${e.message}")
        }

        // Re-initialize
        init(lifecycleOwner, previewView)
    }

    /**
     * Select the best available camera by enumerating all cameras.
     * Priority: External (USB capture card) → Back → Front
     */
    private fun selectBestCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val provider = cameraProvider ?: return

        // Reset state before selection attempt
        _isConnected.value = false
        _cameraType.value = "none"

        // Enumerate all available cameras
        val availableCameras = provider.availableCameraInfos
        Log.i(TAG, "Available cameras: ${availableCameras.size}")

        // Log each camera for debugging
        for (cameraInfo in availableCameras) {
            val cameraId = getCameraId(cameraInfo)
            val lensFacing = cameraInfo.lensFacing
            Log.d(TAG, "  Camera: id=$cameraId, lensFacing=$lensFacing")
        }

        // Try to find an external camera (USB HDMI capture card)
        val externalCamera = findExternalCamera(provider)
        if (externalCamera != null) {
            Log.i(TAG, "Found external camera, using it")
            isUsingExternalCamera = true
            currentCameraSelector = externalCamera
            startCamera(lifecycleOwner, previewView)
            return
        }

        Log.i(TAG, "No external camera found, trying built-in cameras")
        isUsingExternalCamera = false

        // Fallback to back camera
        try {
            val backSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()
            if (provider.hasCamera(backSelector)) {
                currentLensFacing = CameraSelector.LENS_FACING_BACK
                currentCameraSelector = backSelector
                startCamera(lifecycleOwner, previewView)
                return
            }
        } catch (e: Exception) {
            Log.d(TAG, "No back camera found: ${e.message}")
        }

        // Fallback to front camera
        try {
            val frontSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()
            if (provider.hasCamera(frontSelector)) {
                currentLensFacing = CameraSelector.LENS_FACING_FRONT
                currentCameraSelector = frontSelector
                startCamera(lifecycleOwner, previewView)
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "No camera available at all: ${e.message}")
        }

        // No camera found at all
        Log.e(TAG, "NO CAMERA DETECTED — device may have no camera or permission denied")
        _cameraType.value = "none"
        _isConnected.value = false
    }

    /**
     * Find an external camera (USB HDMI capture card) by enumerating camera IDs.
     * External cameras typically have IDs containing "external" or have
     * LENS_FACING_EXTERNAL on API 30+.
     */
    private fun findExternalCamera(provider: ProcessCameraProvider): CameraSelector? {
        try {
            // Method 1: Try LENS_FACING_EXTERNAL (API 30+)
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                try {
                    val externalSelector = CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_EXTERNAL)
                        .build()
                    if (provider.hasCamera(externalSelector)) {
                        Log.i(TAG, "Found camera via LENS_FACING_EXTERNAL")
                        currentLensFacing = CameraSelector.LENS_FACING_EXTERNAL
                        return externalSelector
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "LENS_FACING_EXTERNAL not supported or no external camera: ${e.message}")
                }
            }

            // Method 2: Enumerate cameras and find one with "external" in the camera ID
            val cameraInfos = provider.availableCameraInfos
            for (cameraInfo in cameraInfos) {
                val cameraId = getCameraId(cameraInfo)
                if (cameraId != null && isExternalCameraId(cameraId)) {
                    Log.i(TAG, "Found external camera by ID: $cameraId — building selector via CameraFilter")
                    val targetCameraId = cameraId
                    val selector = CameraSelector.Builder()
                        .addCameraFilter { cameras ->
                            cameras.filter { cam ->
                                getCameraId(cam) == targetCameraId
                            }
                        }
                        .build()
                    return selector
                }
            }

            // Method 3: Check if there are more cameras than just front+back
            var backCameraCount = 0
            for (cameraInfo in cameraInfos) {
                val lensFacing = cameraInfo.lensFacing
                if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                    backCameraCount++
                }
            }

            if (backCameraCount > 1) {
                Log.i(TAG, "Found $backCameraCount back-facing cameras — likely includes USB capture card")
                currentLensFacing = CameraSelector.LENS_FACING_BACK
                return CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build()
            }

            Log.i(TAG, "No external camera detected among ${cameraInfos.size} cameras")
        } catch (e: Exception) {
            Log.e(TAG, "Error finding external camera: ${e.message}")
        }

        return null
    }

    /**
     * Get the camera ID string from a CameraInfo object.
     * Uses Camera2CameraInfo interop to get the Camera2 camera ID.
     */
    private fun getCameraId(cameraInfo: CameraInfo): String? {
        return try {
            Camera2CameraInfo.from(cameraInfo).cameraId
        } catch (e: NoSuchMethodError) {
            Log.d(TAG, "Camera2 interop not available: ${e.message}")
            null
        } catch (e: NoClassDefFoundError) {
            Log.d(TAG, "Camera2CameraInfo class not found: ${e.message}")
            null
        } catch (e: Exception) {
            Log.d(TAG, "Cannot get Camera2 camera ID: ${e.message}")
            null
        }
    }

    /**
     * Check if a camera ID indicates an external camera (USB HDMI capture card).
     */
    private fun isExternalCameraId(cameraId: String): Boolean {
        val lowerId = cameraId.lowercase()
        return lowerId.contains("external") ||
               lowerId.contains("usb") ||
               lowerId.contains("uvc") ||
               lowerId.matches(Regex(".*\\d+-.*"))
    }

    private fun startCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val provider = cameraProvider ?: return
        val selector = currentCameraSelector ?: return

        // Unbind all use cases first
        try {
            provider.unbindAll()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unbind camera use cases: ${e.message}")
        }

        // Preview
        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        // Image capture — maximize quality
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .build()

        try {
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                selector,
                preview,
                imageCapture
            )
            _isConnected.value = true

            // Determine camera type for display
            _cameraType.value = when {
                isUsingExternalCamera -> "external"
                currentLensFacing == CameraSelector.LENS_FACING_BACK -> "back"
                currentLensFacing == CameraSelector.LENS_FACING_FRONT -> "front"
                else -> "unknown"
            }

            Log.i(TAG, "Camera started successfully (type: ${_cameraType.value}, external: $isUsingExternalCamera)")
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission not granted (SecurityException): ${e.message}")
            _isConnected.value = false
            _cameraType.value = "none"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start camera (type: ${_cameraType.value}): ${e.message}")
            _isConnected.value = false

            // If external camera failed, try back camera as fallback
            if (isUsingExternalCamera) {
                Log.w(TAG, "External camera failed, falling back to back camera")
                isUsingExternalCamera = false
                try {
                    val backSelector = CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build()
                    if (provider.hasCamera(backSelector)) {
                        currentLensFacing = CameraSelector.LENS_FACING_BACK
                        currentCameraSelector = backSelector
                        startCamera(lifecycleOwner, previewView)
                        return
                    }
                } catch (e2: Exception) {
                    Log.e(TAG, "Back camera also failed: ${e2.message}")
                }
            }

            // Try front camera as last resort
            try {
                val frontSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                    .build()
                if (provider.hasCamera(frontSelector)) {
                    currentLensFacing = CameraSelector.LENS_FACING_FRONT
                    currentCameraSelector = frontSelector
                    startCamera(lifecycleOwner, previewView)
                    return
                }
            } catch (e3: Exception) {
                Log.e(TAG, "No camera available: ${e3.message}")
            }

            _cameraType.value = "none"
        }
    }

    /**
     * Switch between front and back camera.
     * If using an external camera, switch between external and back.
     */
    fun switchCamera() {
        val owner = lifecycleOwner ?: return
        val pv = previewView ?: return
        val provider = cameraProvider ?: return

        if (isUsingExternalCamera) {
            isUsingExternalCamera = false
            currentLensFacing = CameraSelector.LENS_FACING_BACK
            currentCameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()
        } else if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
            val external = findExternalCamera(provider)
            if (external != null) {
                isUsingExternalCamera = true
                currentCameraSelector = external
            } else {
                currentLensFacing = CameraSelector.LENS_FACING_FRONT
                currentCameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                    .build()
            }
        } else {
            val external = findExternalCamera(provider)
            if (external != null) {
                isUsingExternalCamera = true
                currentCameraSelector = external
            } else {
                currentLensFacing = CameraSelector.LENS_FACING_BACK
                currentCameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build()
            }
        }
        startCamera(owner, pv)
    }

    /**
     * Re-scan for external cameras (called when USB device is attached/detached).
     * If an external camera becomes available and we're on built-in, switch to it.
     */
    fun rescanForExternalCamera() {
        val owner = lifecycleOwner ?: return
        val pv = previewView ?: return
        val provider = cameraProvider ?: return

        val external = findExternalCamera(provider)
        if (external != null && !isUsingExternalCamera) {
            Log.i(TAG, "External camera detected after rescan, switching to it")
            isUsingExternalCamera = true
            currentCameraSelector = external
            startCamera(owner, pv)
        } else if (external == null && isUsingExternalCamera) {
            Log.w(TAG, "External camera lost after rescan, falling back to built-in")
            isUsingExternalCamera = false
            currentLensFacing = CameraSelector.LENS_FACING_BACK
            currentCameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()
            startCamera(owner, pv)
        }
    }

    /**
     * Capture a photo and return the Bitmap via callback.
     */
    fun capturePhoto(onResult: (Bitmap?) -> Unit) {
        val capture = imageCapture ?: run {
            Log.e(TAG, "ImageCapture not initialized — camera not started?")
            onResult(null)
            return
        }

        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val bitmap = image.toBitmap()

                        // Rotate if needed based on image rotation
                        val rotation = image.imageInfo.rotationDegrees
                        val rotated = if (rotation != 0) {
                            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                            Bitmap.createBitmap(
                                bitmap, 0, 0, bitmap.width, bitmap.height,
                                matrix, true
                            )
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

    /**
     * Convert ImageProxy to Bitmap.
     * Handles JPEG, YUV_420_888, and other image formats.
     */
    private fun ImageProxy.toBitmap(): Bitmap {
        // Try JPEG format first (most common for ImageCapture)
        if (format == android.graphics.ImageFormat.JPEG ||
            format == android.graphics.ImageFormat.DEPTH_JPEG) {
            val buffer = planes[0].buffer
            val bytes = ByteArray(buffer.capacity())
            buffer.get(bytes)
            return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalStateException("Failed to decode JPEG image")
        }

        // For YUV_420_888 and other formats, convert using manual YUV→RGB
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

                    // YUV to RGB conversion (BT.601)
                    val r = (yValue + 1.370705 * (vValue - 128)).toInt().coerceIn(0, 255)
                    val g = (yValue - 0.337633 * (uValue - 128) - 0.698001 * (vValue - 128)).toInt().coerceIn(0, 255)
                    val b = (yValue + 1.732446 * (uValue - 128)).toInt().coerceIn(0, 255)

                    argb[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }

            Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            Log.e(TAG, "YUV conversion failed, trying direct buffer decode: ${e.message}")
            val buffer = planes[0].buffer
            val bytes = ByteArray(buffer.capacity())
            buffer.get(bytes)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalStateException("Failed to convert image format: $format")
        }
    }

    fun destroy() {
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Error unbinding during destroy: ${e.message}")
        }
        cameraProvider = null
        preview = null
        imageCapture = null
        camera = null
        lifecycleOwner = null
        previewView = null
        currentCameraSelector = null
        isUsingExternalCamera = false
        providerInitialized = false
        initInProgress = false
        _isConnected.value = false
        _cameraType.value = "none"
    }
}
