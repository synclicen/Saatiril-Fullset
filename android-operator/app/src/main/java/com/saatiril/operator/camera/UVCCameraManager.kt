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
 * On Android, UVC HDMI capture cards are automatically exposed as
 * "external cameras" by the Camera2/CameraX API. This means we
 * DON'T need a native UVC library — CameraX handles the preview
 * and capture automatically when we select the external camera.
 *
 * This class handles:
 * - USB device scanning to detect UVC capture cards
 * - Notifying the ViewModel when a UVC device is attached/detached
 * - The actual camera streaming is done by BuiltInCameraManager
 *   which uses CameraX to select the external camera
 */
class UVCCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "UVCCameraManager"

        /**
         * Check if a USB device is a UVC video device.
         * USB Video Class: Class 14 (Legacy) or Class 239 with Subclass 2
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

        /**
         * Check if any UVC device is currently connected
         */
        fun hasUVCDevice(context: Context): Boolean {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            val deviceList = usbManager?.deviceList ?: return false
            return deviceList.values.any { isUVCDevice(it) }
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
}
