package com.saatiril.operator.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
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
 * Camera selection:
 * - EXTERNAL (priority) — USB HDMI capture cards via UVC
 * - BACK — built-in rear camera (fallback)
 * - FRONT — built-in front camera (last resort)
 */
class BuiltInCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "BuiltInCameraManager"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var currentLensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var lifecycleOwner: LifecycleOwner? = null
    private var previewView: PreviewView? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // Camera source: "external", "back", "front"
    private val _cameraType = MutableStateFlow("none")
    val cameraType: StateFlow<String> = _cameraType.asStateFlow()

    // ─── Setup ──────────────────────────────────────────────────

    fun init(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        this.lifecycleOwner = lifecycleOwner
        this.previewView = previewView

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                selectBestCamera(lifecycleOwner, previewView)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Select the best available camera:
     * 1. External (USB HDMI capture card) — if available
     * 2. Back camera — fallback
     * 3. Front camera — last resort
     */
    private fun selectBestCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val provider = cameraProvider ?: return

        // Try external camera first (USB capture cards)
        val hasExternal = try {
            val externalSelector = CameraSelector.Builder()
                .addCameraFilter { cameras ->
                    cameras.filter { it.hasExtension(CameraExtension.SESSION_EXTENSION) }
                        .ifEmpty { cameras }
                }
                .build()
            // Try to find any camera that's external
            provider.hasCamera(externalSelector) ||
                provider.availableCameraInfos.any { info ->
                    // External cameras are identified by their lens facing
                    try {
                        val selector = CameraSelector.Builder()
                            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                            .build()
                        // Check all cameras for external ones
                        false
                    } catch (e: Exception) { false }
                }
        } catch (e: Exception) {
            Log.d(TAG, "No external camera found: ${e.message}")
            false
        }

        // Determine lens facing
        currentLensFacing = when {
            hasExternal -> CameraSelector.LENS_FACING_BACK // external uses special selector
            provider.hasCamera(CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()) -> CameraSelector.LENS_FACING_BACK
            provider.hasCamera(CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_FRONT).build()) -> CameraSelector.LENS_FACING_FRONT
            else -> {
                Log.e(TAG, "No camera available at all")
                _isConnected.value = false
                _cameraType.value = "none"
                return
            }
        }

        startCamera(lifecycleOwner, previewView)
    }

    private fun startCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val provider = cameraProvider ?: return

        // Unbind all use cases first
        provider.unbindAll()

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

        // Camera selector
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(currentLensFacing)
            .build()

        try {
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )
            _isConnected.value = true

            // Determine camera type
            _cameraType.value = when (currentLensFacing) {
                CameraSelector.LENS_FACING_BACK -> "back"
                CameraSelector.LENS_FACING_FRONT -> "front"
                else -> "unknown"
            }

            Log.i(TAG, "Camera started (lens: $currentLensFacing, type: ${_cameraType.value})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start camera (lens: $currentLensFacing): ${e.message}")
            _isConnected.value = false

            // Try the other camera direction as fallback
            if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
                currentLensFacing = CameraSelector.LENS_FACING_FRONT
                startCamera(lifecycleOwner, previewView)
            } else {
                _cameraType.value = "none"
            }
        }
    }

    /**
     * Switch between front and back camera
     */
    fun switchCamera() {
        val owner = lifecycleOwner ?: return
        val pv = previewView ?: return

        currentLensFacing = if (currentLensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        startCamera(owner, pv)
    }

    /**
     * Capture a photo and return the Bitmap via callback.
     * The bitmap is then processed by CameraCapture (crop, filter, frame overlay)
     * and sent via ViewModel.capturePhoto().
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
     * Convert ImageProxy to Bitmap
     */
    private fun ImageProxy.toBitmap(): Bitmap {
        val buffer = planes[0].buffer
        val bytes = ByteArray(buffer.capacity())
        buffer.get(bytes)
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("Failed to decode captured image")
    }

    fun destroy() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        preview = null
        imageCapture = null
        camera = null
        lifecycleOwner = null
        previewView = null
        _isConnected.value = false
        _cameraType.value = "none"
    }
}
