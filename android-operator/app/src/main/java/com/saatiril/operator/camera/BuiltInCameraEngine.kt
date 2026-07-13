package com.saatiril.operator.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Built-in Camera Engine — CameraX (works fine for built-in cameras)
 * Only used when NO USB camera is connected.
 */
class BuiltInCameraEngine(private val context: Context) {

    companion object {
        private const val TAG = "BuiltInCamera"
    }

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _cameraType = MutableStateFlow("none") // "back", "front", "none"
    val cameraType: StateFlow<String> = _cameraType.asStateFlow()

    private val _currentCameraId = MutableStateFlow("")
    val currentCameraIdFlow: StateFlow<String> = _currentCameraId.asStateFlow()

    private val _availableCameras = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val availableCameras: StateFlow<List<Pair<String, String>>> = _availableCameras.asStateFlow()

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var previewView: PreviewView? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private val executor = Executors.newSingleThreadExecutor()

    // ─── Init ──────────────────────────────────────────────────────
    fun init(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        Log.i(TAG, "init: CameraX built-in camera engine")
        this.lifecycleOwner = lifecycleOwner
        this.previewView = previewView

        try {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    cameraProvider = future.get()
                    enumerateCameras()
                    startCamera()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get camera provider: ${e.message}", e)
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (e: Exception) {
            Log.e(TAG, "CameraX init failed: ${e.message}", e)
        }
    }

    private fun enumerateCameras() {
        val provider = cameraProvider ?: return
        val cameras = mutableListOf<Pair<String, String>>()

        // Check which cameras are available
        try {
            // Try back camera
            try {
                val backCamera = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                if (backCamera) {
                    cameras.add("back" to "Kamera Belakang")
                }
            } catch (_: Exception) {}

            // Try front camera
            try {
                val frontCamera = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                if (frontCamera) {
                    cameras.add("front" to "Kamera Depan")
                }
            } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.e(TAG, "Error enumerating cameras: ${e.message}")
        }

        _availableCameras.value = cameras
        Log.i(TAG, "Found ${cameras.size} built-in cameras: ${cameras.map { it.second }}")
    }

    private fun startCamera() {
        val provider = cameraProvider ?: return
        val owner = lifecycleOwner ?: return
        val pv = previewView ?: return

        try {
            // Unbind all previous use cases
            provider.unbindAll()

            // Preview
            preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(pv.surfaceProvider)
                }

            // Image capture
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            // Bind to lifecycle
            camera = provider.bindToLifecycle(owner, cameraSelector, preview, imageCapture)

            _isConnected.value = true
            _cameraType.value = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) "back" else "front"
            _currentCameraId.value = _cameraType.value

            Log.i(TAG, "Camera started: ${_cameraType.value}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start camera: ${e.message}", e)
            _isConnected.value = false
        }
    }

    // ─── Switch between front/back ─────────────────────────────────
    fun switchToFront() {
        cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
        startCamera()
    }

    fun switchToBack() {
        cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        startCamera()
    }

    /**
     * Pause preview — unbind use cases but keep provider alive.
     * Called when switching to USB camera.
     */
    fun pausePreview() {
        Log.i(TAG, "pausePreview: unbinding use cases")
        cameraProvider?.unbindAll()
        camera = null
        _isConnected.value = false
        // Don't reset _cameraType — we want to remember front/back
    }

    /**
     * Resume preview — re-bind use cases with current cameraSelector.
     * Called when switching back from USB camera.
     */
    fun resumePreview(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        Log.i(TAG, "resumePreview: re-binding use cases for ${if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) "back" else "front"}")
        this.lifecycleOwner = lifecycleOwner
        this.previewView = previewView
        startCamera()
    }

    // ─── Photo Capture ─────────────────────────────────────────────
    fun capturePhoto(onResult: (String?) -> Unit) {
        val capture = imageCapture ?: run {
            Log.e(TAG, "ImageCapture not initialized")
            onResult(null)
            return
        }

        capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val bitmap = image.toBitmap()
                    val baos = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, baos)
                    val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                    val dataUrl = "data:image/jpeg;base64,$base64"
                    Log.i(TAG, "Photo captured: ${bitmap.width}x${bitmap.height}")
                    onResult(dataUrl)
                } catch (e: Exception) {
                    Log.e(TAG, "Bitmap conversion failed: ${e.message}")
                    onResult(null)
                } finally {
                    image.close()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "Capture failed: ${exception.message}")
                onResult(null)
            }
        })
    }

    // ─── Compatibility ─────────────────────────────────────────────
    fun switchCamera(deviceId: String) {
        if (deviceId.contains("front", ignoreCase = true)) switchToFront()
        else switchToBack()
    }

    fun forceRescan() { enumerateCameras() }
    fun refreshCameraList() { enumerateCameras() }
    fun forceSwitchToUSB() {}  // No-op for built-in
    fun switchToCamera(cameraId: String) { switchCamera(cameraId) }
    fun hasCameraPermission(): Boolean = true
    fun updateConfig(aspectRatio: Double, filterPreset: String) {}
    fun setFrameOverlay(base64Data: String?) {}
    fun setTextureView(tv: android.view.TextureView) {}  // No-op — uses PreviewView

    // ─── Cleanup ───────────────────────────────────────────────────
    fun destroy() {
        Log.i(TAG, "destroy: cleaning up")
        cameraProvider?.unbindAll()
        camera = null
        _isConnected.value = false
        _cameraType.value = "none"
    }
}
