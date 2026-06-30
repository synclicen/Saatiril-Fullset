package com.saatiril.operator.camera

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import android.view.SurfaceView
import android.view.TextureView
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer

/**
 * Manages UVC (USB Video Class) camera devices — HDMI capture cards.
 * Uses the saki7/UVCCamera library for direct USB access.
 */
class UVCCameraManager(private val context: Context) {
    
    companion object {
        private const val TAG = "UVCCameraManager"
        private const val PREVIEW_WIDTH = 1920
        private const val PREVIEW_HEIGHT = 1080
        private const val PREVIEW_MODE = UVCCamera.DEFAULT_PREVIEW_MODE

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
    
    private var usbMonitor: USBMonitor? = null
    private var uvcCamera: UVCCamera? = null
    private var previewSurface: Any? = null // SurfaceView or TextureView
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    private val _connectedDevice = MutableStateFlow<UsbDevice?>(null)
    val connectedDevice: StateFlow<UsbDevice?> = _connectedDevice.asStateFlow()
    
    private val _availableDevices = MutableStateFlow<List<UsbDevice>>(emptyList())
    val availableDevices: StateFlow<List<UsbDevice>> = _availableDevices.asStateFlow()
    
    var onFrameCaptured: ((ByteBuffer) -> Unit)? = null
    
    // ─── Lifecycle ──────────────────────────────────────────────
    
    fun init() {
        usbMonitor = USBMonitor(context, object : USBMonitor.OnDeviceConnectListener {
            override fun onAttach(device: UsbDevice) {
                Log.i(TAG, "USB device attached: ${device.deviceName}")
                val devices = usbMonitor?.deviceList ?: emptyList()
                _availableDevices.value = devices
                // Auto-connect to first UVC device
                if (!_isConnected.value) {
                    usbMonitor?.requestPermission(device)
                }
            }
            
            override fun onDetach(device: UsbDevice) {
                Log.i(TAG, "USB device detached: ${device.deviceName}")
                _availableDevices.value = usbMonitor?.deviceList ?: emptyList()
                if (_connectedDevice.value?.deviceName == device.deviceName) {
                    releaseCamera()
                }
            }
            
            override fun onConnect(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock, createNew: Boolean) {
                Log.i(TAG, "USB device connected: ${device.deviceName}")
                _connectedDevice.value = device
                openCamera(ctrlBlock)
            }
            
            override fun onDisconnect(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {
                Log.i(TAG, "USB device disconnected: ${device.deviceName}")
                releaseCamera()
            }
            
            override fun onCancel(device: UsbDevice) {
                Log.w(TAG, "USB permission cancelled for: ${device.deviceName}")
            }
        })
        usbMonitor?.register()
        
        // List currently connected devices
        _availableDevices.value = usbMonitor?.deviceList ?: emptyList()
    }
    
    fun destroy() {
        releaseCamera()
        usbMonitor?.unregister()
        usbMonitor = null
    }
    
    // ─── Camera Operations ──────────────────────────────────────
    
    private fun openCamera(ctrlBlock: USBMonitor.UsbControlBlock) {
        try {
            releaseCamera()
            
            uvcCamera = UVCCamera().apply {
                open(ctrlBlock)
                
                // Try to set preview size (capture card may have fixed resolution)
                try {
                    setPreviewSize(
                        PREVIEW_WIDTH, PREVIEW_HEIGHT,
                        PREVIEW_MODE,
                        1,       // min fps
                        30,      // max fps
                        UVCCamera.DEFAULT_BANDWIDTH
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Could not set preview size, using default: ${e.message}")
                    setPreviewSize(
                        UVCCamera.DEFAULT_PREVIEW_WIDTH,
                        UVCCamera.DEFAULT_PREVIEW_HEIGHT,
                        PREVIEW_MODE,
                        1, 30,
                        UVCCamera.DEFAULT_BANDWIDTH
                    )
                }
                
                // Set preview surface
                when (previewSurface) {
                    is SurfaceView -> setPreviewDisplay(previewSurface as SurfaceView)
                    is TextureView -> setPreviewTexture(previewSurface as TextureView)
                }
                
                // Set frame callback for capture
                setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_YUV420SP)
                
                startPreview()
                _isConnected.value = true
                Log.i(TAG, "UVC camera opened and preview started")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open UVC camera: ${e.message}")
            _isConnected.value = false
        }
    }
    
    fun requestPermission(device: UsbDevice) {
        usbMonitor?.requestPermission(device)
    }
    
    private fun releaseCamera() {
        try {
            uvcCamera?.stopPreview()
            uvcCamera?.close()
            uvcCamera?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing camera: ${e.message}")
        }
        uvcCamera = null
        _isConnected.value = false
        _connectedDevice.value = null
    }
    
    fun setPreviewSurface(surface: Any) {
        previewSurface = surface
        try {
            when (surface) {
                is SurfaceView -> uvcCamera?.setPreviewDisplay(surface)
                is TextureView -> uvcCamera?.setPreviewTexture(surface)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set preview surface: ${e.message}")
        }
    }
    
    // ─── Frame Callback ─────────────────────────────────────────
    
    private val frameCallback = IFrameCallback { frame ->
        onFrameCaptured?.invoke(frame)
    }
    
    // ─── Capture ────────────────────────────────────────────────
    
    fun captureFrame(): ByteBuffer? {
        // The UVCCamera library provides frame data via callback
        // For synchronous capture, we use the last frame
        // In practice, capture is handled by the preview surface capture
        return null // Capture is done via Surface/TexureView screenshot
    }
    
}
