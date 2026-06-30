package com.saatiril.operator.camera

import android.content.Context
import android.hardware.usb.UsbDevice
import android.util.Log
import android.view.SurfaceView
import android.view.TextureView
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.camera.bean.PreviewSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Manages UVC (USB Video Class) camera devices — HDMI capture cards.
 * Uses the jiangdongguo/AndroidUSBCamera library for USB camera access.
 */
class UVCCameraManager(private val context: Context) {
    
    companion object {
        private const val TAG = "UVCCameraManager"
        private const val PREVIEW_WIDTH = 1920
        private const val PREVIEW_HEIGHT = 1080

        /**
         * Check if a USB device is a UVC video device
         */
        fun isUVCDevice(device: UsbDevice): Boolean {
            // Check interface class for Video class (14 or 239)
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                // USB Video Class: Class 14 (Legacy) or 239 with subclass 2
                if (iface.interfaceClass == 14 || 
                    (iface.interfaceClass == 239 && iface.interfaceSubclass == 2)) {
                    return true
                }
            }
            return false
        }
    }
    
    private var multiCameraClient: MultiCameraClient? = null
    private var cameraUVC: CameraUVC? = null
    private var previewSurface: Any? = null // SurfaceView or TextureView
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    private val _connectedDevice = MutableStateFlow<UsbDevice?>(null)
    val connectedDevice: StateFlow<UsbDevice?> = _connectedDevice.asStateFlow()
    
    private val _availableDevices = MutableStateFlow<List<UsbDevice>>(emptyList())
    val availableDevices: StateFlow<List<UsbDevice>> = _availableDevices.asStateFlow()
    
    var onFrameCaptured: ((ByteArray) -> Unit)? = null
    
    // ─── Lifecycle ──────────────────────────────────────────────
    
    fun init() {
        multiCameraClient = MultiCameraClient(context, object : ICameraStateCallBack {
            override fun onCameraState(
                self: MultiCameraClient,
                code: ICameraStateCallBack.State,
                msg: String?,
                camera: com.jiangdg.ausbc.camera.ICamera?
            ) {
                when (code) {
                    ICameraStateCallBack.State.OPENED -> {
                        Log.i(TAG, "USB camera opened: $msg")
                        if (camera is CameraUVC) {
                            cameraUVC = camera
                            _isConnected.value = true
                        }
                    }
                    ICameraStateCallBack.State.CLOSED -> {
                        Log.i(TAG, "USB camera closed: $msg")
                        cameraUVC = null
                        _isConnected.value = false
                        _connectedDevice.value = null
                    }
                    ICameraStateCallBack.State.ERROR -> {
                        Log.e(TAG, "USB camera error: $msg")
                        _isConnected.value = false
                    }
                }
            }
        })
        multiCameraClient?.register()
    }
    
    fun destroy() {
        cameraUVC = null
        multiCameraClient?.unRegister()
        multiCameraClient = null
        _isConnected.value = false
    }
    
    // ─── Camera Operations ──────────────────────────────────────
    
    fun openCamera(usbDevice: UsbDevice, surface: Any? = null) {
        previewSurface = surface ?: previewSurface
        _connectedDevice.value = usbDevice
        
        try {
            val cameraRequest = CameraRequest.Builder()
                .setPreviewWidth(PREVIEW_WIDTH)
                .setPreviewHeight(PREVIEW_HEIGHT)
                .setRenderMode(CameraRequest.RenderMode.OPENGL)
                .setAudioSource(CameraRequest.AudioSource.NONE)
                .create()
            
            multiCameraClient?.openCamera(usbDevice, cameraRequest)
            Log.i(TAG, "Opening UVC camera: ${usbDevice.deviceName}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open UVC camera: ${e.message}")
            _isConnected.value = false
        }
    }
    
    fun requestPermission(device: UsbDevice) {
        multiCameraClient?.requestPermission(device)
    }
    
    private fun releaseCamera() {
        try {
            cameraUVC = null
            multiCameraClient?.closeCamera()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing camera: ${e.message}")
        }
        _isConnected.value = false
        _connectedDevice.value = null
    }
    
    fun setPreviewSurface(surface: Any) {
        previewSurface = surface
    }
    
    // ─── Capture ────────────────────────────────────────────────
    
    fun capturePhoto(callback: ICaptureCallBack) {
        cameraUVC?.captureImage(callback)
            ?: Log.w(TAG, "Cannot capture: camera not opened")
    }
    
    fun capturePhotoToFile(outputFile: File, callback: ICaptureCallBack) {
        cameraUVC?.captureImage(outputFile, callback)
            ?: Log.w(TAG, "Cannot capture: camera not opened")
    }
}
