package com.saatiril.operator.camera

import android.content.Context
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
 * Manages the built-in camera using CameraX as a fallback
 * when no UVC capture card is connected.
 */
class BuiltInCameraManager(private val context: Context) {
    
    companion object {
        private const val TAG = "BuiltInCameraManager"
    }
    
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    private val _availableCameras = MutableStateFlow<List<String>>(emptyList())
    val availableCameras: StateFlow<List<String>> = _availableCameras.asStateFlow()
    
    // ─── Setup ──────────────────────────────────────────────────
    
    fun init(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                detectCameras()
                startCamera(lifecycleOwner, previewView)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }
    
    private fun detectCameras() {
        val cameras = mutableListOf<String>()
        cameraProvider?.availableCameraInfos?.forEach { info ->
            cameras.add(info.cameraSelector.toString())
        }
        _availableCameras.value = cameras
    }
    
    private fun startCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val provider = cameraProvider ?: return
        
        // Unbind all use cases first
        provider.unbindAll()
        
        // Preview
        preview = Preview.Builder()
            .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_16_9)
            .build()
            .also {
                it.surfaceProvider = previewView.surfaceProvider
            }
        
        // Image capture
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_16_9)
            .build()
        
        // Select camera
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()
        
        try {
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )
            _isConnected.value = true
            Log.i(TAG, "Built-in camera started (facing: $lensFacing)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start camera: ${e.message}")
            _isConnected.value = false
            
            // Try the other camera direction
            if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                lensFacing = CameraSelector.LENS_FACING_FRONT
                startCamera(lifecycleOwner, previewView)
            }
        }
    }
    
    fun switchCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        startCamera(lifecycleOwner, previewView)
    }
    
    fun destroy() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        _isConnected.value = false
    }
}
