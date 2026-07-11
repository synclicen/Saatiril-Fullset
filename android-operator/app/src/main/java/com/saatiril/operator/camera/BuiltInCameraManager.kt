package com.saatiril.operator.camera

import android.content.Context
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
 * External/UVC cameras on Android:
 * - On API 28+, USB HDMI capture cards appear as "external" cameras in Camera2/CameraX
 * - They are identified by CameraCharacteristics containing "external" in the camera ID
 * - OR by LENS_FACING = LENS_FACING_EXTERNAL (API 30+)
 * - We enumerate all available cameras and check for external type
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

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // Camera source: "external", "back", "front", "none"
    private val _cameraType = MutableStateFlow("none")
    val cameraType: StateFlow<String> = _cameraType.asStateFlow()

    // ─── Setup ──────────────────────────────────────────────────

    fun init(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        this.lifecycleOwner = lifecycleOwner
        this.previewView = previewView

        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

            cameraProviderFuture.addListener({
                try {
                    cameraProvider = cameraProviderFuture.get()
                    selectBestCamera(lifecycleOwner, previewView)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize camera: ${e.message}")
                    _cameraType.value = "none"
                    _isConnected.value = false
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get camera provider: ${e.message}")
            _cameraType.value = "none"
            _isConnected.value = false
        }
    }

    /**
     * Select the best available camera by enumerating all cameras.
     * Priority: External (USB capture card) → Back → Front
     */
    private fun selectBestCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val provider = cameraProvider ?: return

        // Enumerate all available cameras
        val availableCameras = provider.availableCameraInfos
        Log.i(TAG, "Available cameras: ${availableCameras.size}")
        
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
            // This is the official way to select external cameras
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
            // On many devices, USB capture cards show up with IDs containing "external"
            val cameraInfos = provider.availableCameraInfos
            for (cameraInfo in cameraInfos) {
                val cameraId = getCameraId(cameraInfo)
                if (cameraId != null && isExternalCameraId(cameraId)) {
                    Log.i(TAG, "Found external camera by ID: $cameraId — building selector via CameraFilter")
                    // Build a CameraSelector that filters for this specific camera
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
            // Some devices expose USB cameras as additional back-facing cameras
            // We detect this by checking if there are multiple back-facing cameras
            var backCameraCount = 0
            for (cameraInfo in cameraInfos) {
                val lensFacing = cameraInfo.lensFacing
                if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                    backCameraCount++
                }
            }
            
            if (backCameraCount > 1) {
                Log.i(TAG, "Found $backCameraCount back-facing cameras — likely includes USB capture card")
                // Multiple back cameras — one might be the USB capture card
                // CameraX typically gives preference to the built-in back camera
                // We'll try BACK and it will usually pick the right one
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
     * CRITICAL: This can throw on some devices where Camera2 interop is not
     * available — must be wrapped in try-catch by callers.
     */
    private fun getCameraId(cameraInfo: CameraInfo): String? {
        return try {
            Camera2CameraInfo.from(cameraInfo).cameraId
        } catch (e: NoSuchMethodError) {
            // Camera2 interop not available on this device
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
               lowerId.matches(Regex(".*\\d+-.*")) // Pattern like "1-USB-camera-device"
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

            Log.i(TAG, "Camera started (type: ${_cameraType.value}, external: $isUsingExternalCamera)")
        } catch (e: SecurityException) {
            Log.e(TAG, "Camera permission not granted: ${e.message}")
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
            // Currently on external — switch to back camera
            isUsingExternalCamera = false
            currentLensFacing = CameraSelector.LENS_FACING_BACK
            currentCameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()
        } else if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
            // Currently on back — try external first, then front
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
            // Currently on front — try external first, then back
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
     * The bitmap is then processed by CameraCapture (crop, filter, frame overlay)
     * and sent via ViewModel.triggerCapture().
     */
    fun capturePhoto(onResult: (Bitmap?) -> Unit) {
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

        // For YUV_420_888 and other formats, convert using PixelCopy or RenderScript
        // The safest approach is to use the ImageProxy->Bitmap conversion
        // that works on all API levels
        return try {
            // YUV_420_888 conversion: combine Y, U, V planes
            val yBuffer = planes[0].buffer // Y
            val uBuffer = planes[1].buffer // U
            val vBuffer = planes[2].buffer // V

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
            // Last resort: try to decode the raw buffer
            val buffer = planes[0].buffer
            val bytes = ByteArray(buffer.capacity())
            buffer.get(bytes)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalStateException("Failed to convert image format: $format")
        }
    }

    fun destroy() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        preview = null
        imageCapture = null
        camera = null
        lifecycleOwner = null
        previewView = null
        currentCameraSelector = null
        isUsingExternalCamera = false
        _isConnected.value = false
        _cameraType.value = "none"
    }
}
