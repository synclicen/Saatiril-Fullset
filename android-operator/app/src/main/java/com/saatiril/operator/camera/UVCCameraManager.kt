package com.saatiril.operator.camera

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages UVC (USB Video Class) camera device detection.
 * 
 * Detects HDMI capture cards and other UVC devices using Android's
 * built-in USB Host API. For actual camera preview and capture,
 * the app uses CameraX with the built-in camera or relies on
 * the system to route UVC devices through CameraX when supported.
 * 
 * Full UVC direct streaming requires a native library that will be
 * added in a future update once a reliable dependency is available.
 */
class UVCCameraManager(private val context: Context) {
    
    companion object {
        private const val TAG = "UVCCameraManager"

        /**
         * Check if a USB device is a UVC video device
         */
        fun isUVCDevice(device: UsbDevice): Boolean {
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
    
    private val usbManager: UsbManager? = context.getSystemService(Context.USB_SERVICE) as? UsbManager
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    private val _connectedDevice = MutableStateFlow<UsbDevice?>(null)
    val connectedDevice: StateFlow<UsbDevice?> = _connectedDevice.asStateFlow()
    
    private val _availableDevices = MutableStateFlow<List<UsbDevice>>(emptyList())
    val availableDevices: StateFlow<List<UsbDevice>> = _availableDevices.asStateFlow()
    
    // ─── Lifecycle ──────────────────────────────────────────────
    
    fun init() {
        scanForUVCDevices()
        Log.i(TAG, "UVC Camera Manager initialized")
    }
    
    fun destroy() {
        _isConnected.value = false
        _connectedDevice.value = null
        _availableDevices.value = emptyList()
    }
    
    // ─── Device Scanning ────────────────────────────────────────
    
    fun scanForUVCDevices() {
        val deviceList = usbManager?.deviceList ?: return
        val uvcDevices = deviceList.values.filter { isUVCDevice(it) }
        _availableDevices.value = uvcDevices
        
        if (uvcDevices.isNotEmpty()) {
            _connectedDevice.value = uvcDevices.first()
            _isConnected.value = true
            Log.i(TAG, "Found ${uvcDevices.size} UVC device(s): ${uvcDevices.map { it.deviceName }}")
        } else {
            _connectedDevice.value = null
            _isConnected.value = false
            Log.d(TAG, "No UVC devices found")
        }
    }
    
    fun onUsbDeviceAttached(device: UsbDevice) {
        if (isUVCDevice(device)) {
            Log.i(TAG, "UVC device attached: ${device.deviceName}")
            scanForUVCDevices()
        }
    }
    
    fun onUsbDeviceDetached(device: UsbDevice) {
        Log.i(TAG, "USB device detached: ${device.deviceName}")
        if (_connectedDevice.value?.deviceName == device.deviceName) {
            _isConnected.value = false
            _connectedDevice.value = null
        }
        scanForUVCDevices()
    }
    
    fun requestPermission(device: UsbDevice) {
        // Permission is handled by the Activity via USB_DEVICE_ATTACHED intent
        Log.d(TAG, "Permission requested for: ${device.deviceName}")
    }
    
    // ─── Capture ────────────────────────────────────────────────
    
    fun setPreviewSurface(surface: Any) {
        // Preview will be handled by CameraX or a future UVC library
        Log.d(TAG, "Preview surface set")
    }
}
