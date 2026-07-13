package com.saatiril.operator.camera

import android.content.Context
import android.graphics.*
import android.hardware.usb.UsbDevice
import android.util.Base64
import android.util.Log
import android.view.TextureView
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.UVCCamera
import com.serenegiant.usb.USBMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * ═════════════════════════════════════════════════════════════════════════
 * NATIVE USB Camera Engine — UVCCamera library (com.herohan:UVCAndroid)
 * ═════════════════════════════════════════════════════════════════════════
 *
 * This BYPASSES Android's broken Camera2/CameraX HAL entirely.
 * UVCCamera communicates directly with USB video devices via USB Host API.
 * This is the ONLY approach proven to work with USB HDMI capture cards on
 * Xiaomi/Redmi devices where Camera2 silently fails and WebView can't see USB cameras.
 *
 * API Reference (UVCAndroid 1.0.13):
 * - USBMonitor.OnDeviceConnectListener: onAttach, onDeviceOpen, onDeviceClose, onDetach, onCancel
 * - UVCCamera.open(UsbControlBlock) → int (0=success)
 * - setPreviewSize(width, height, frameType, fps) — frameType: FRAME_FORMAT_MJPEG=1
 * - setPreviewTexture(SurfaceTexture) or setPreviewDisplay(Surface)
 * - setFrameCallback(IFrameCallback, pixelFormat) — pixelFormat: PIXEL_FORMAT_NV21=3
 * - IFrameCallback.onFrame(ByteBuffer) — NOT ByteArray
 *
 * Camera priority: USB capture card > built-in
 * Photos NOT saved on operator device — sent via socket to admin
 */
class USBCameraEngine(private val context: Context) {

    companion object {
        private const val TAG = "USBCameraEngine"
        const val PREVIEW_WIDTH = 1920
        const val PREVIEW_HEIGHT = 1080
        const val PREVIEW_FPS = 30
    }

    // ─── State ─────────────────────────────────────────────────────
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _cameraType = MutableStateFlow("none") // "external" or "none"
    val cameraType: StateFlow<String> = _cameraType.asStateFlow()

    private val _currentCameraId = MutableStateFlow("")
    val currentCameraIdFlow: StateFlow<String> = _currentCameraId.asStateFlow()

    private val _availableCameras = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val availableCameras: StateFlow<List<Pair<String, String>>> = _availableCameras.asStateFlow()

    // ─── UVCCamera internals ───────────────────────────────────────
    private var usbMonitor: USBMonitor? = null
    private var uvcCamera: UVCCamera? = null
    private var textureView: TextureView? = null
    private var currentUsbDevice: UsbDevice? = null

    // ─── Capture ───────────────────────────────────────────────────
    private var pendingCaptureCallback: ((String?) -> Unit)? = null
    private var latestFrameBytes: ByteArray? = null
    private var frameWidth = PREVIEW_WIDTH
    private var frameHeight = PREVIEW_HEIGHT

    // ═══════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════

    fun init() {
        Log.i(TAG, "═══════════════════════════════════════════════════")
        Log.i(TAG, "init: Native UVC Camera Engine (v10)")
        Log.i(TAG, "═══════════════════════════════════════════════════")

        if (usbMonitor != null) {
            Log.i(TAG, "Already initialized, skipping")
            return
        }

        usbMonitor = USBMonitor(context, object : USBMonitor.OnDeviceConnectListener {
            override fun onAttach(device: UsbDevice?) {
                Log.i(TAG, "═══ USB DEVICE ATTACHED: ${device?.deviceName} vendor=${device?.vendorId} product=${device?.productId}")
                device?.let {
                    val isUvc = isUVCDevice(it)
                    Log.i(TAG, "Is UVC device: $isUvc, interface count: ${it.interfaceCount}")
                    // Request permission for any USB device with interfaces
                    if (isUvc || it.interfaceCount > 0) {
                        Log.i(TAG, "Requesting USB permission for: ${it.deviceName}")
                        usbMonitor?.requestPermission(it)
                    }
                }
            }

            override fun onDeviceOpen(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?, isNew: Boolean) {
                Log.i(TAG, "═══ USB DEVICE OPENED: ${device?.deviceName} isNew=$isNew")
                if (ctrlBlock == null) {
                    Log.e(TAG, "USB device open but no control block")
                    return
                }

                // Close previous camera if any
                closeCamera()

                // Open UVC camera
                try {
                    uvcCamera = UVCCamera(com.serenegiant.usb.UVCParam())
                    val result = uvcCamera?.open(ctrlBlock) ?: -1
                    if (result != 0) {
                        Log.e(TAG, "UVC Camera open FAILED with result: $result")
                        closeCamera()
                        return
                    }
                    currentUsbDevice = device
                    Log.i(TAG, "UVC Camera opened successfully! result=$result")

                    // Try to set preview size (MJPEG for capture cards)
                    try {
                        uvcCamera?.setPreviewSize(PREVIEW_WIDTH, PREVIEW_HEIGHT, UVCCamera.FRAME_FORMAT_MJPEG, PREVIEW_FPS)
                        frameWidth = PREVIEW_WIDTH
                        frameHeight = PREVIEW_HEIGHT
                        Log.i(TAG, "Preview size set: ${PREVIEW_WIDTH}x${PREVIEW_HEIGHT} MJPEG @${PREVIEW_FPS}fps")
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "Requested size not supported, trying default: ${e.message}")
                        try {
                            uvcCamera?.setPreviewSize(
                                UVCCamera.DEFAULT_PREVIEW_WIDTH,
                                UVCCamera.DEFAULT_PREVIEW_HEIGHT,
                                UVCCamera.DEFAULT_PREVIEW_FRAME_FORMAT,
                                UVCCamera.DEFAULT_PREVIEW_FPS
                            )
                            frameWidth = UVCCamera.DEFAULT_PREVIEW_WIDTH
                            frameHeight = UVCCamera.DEFAULT_PREVIEW_HEIGHT
                            Log.i(TAG, "Preview size set: default ${frameWidth}x${frameHeight}")
                        } catch (e2: Exception) {
                            Log.e(TAG, "Failed to set ANY preview size: ${e2.message}")
                            // Try without format/fps specification
                            try {
                                uvcCamera?.setPreviewSize(640, 480)
                                frameWidth = 640
                                frameHeight = 480
                                Log.i(TAG, "Preview size set: 640x480 fallback")
                            } catch (e3: Exception) {
                                Log.e(TAG, "All preview size attempts failed: ${e3.message}")
                            }
                        }
                    }

                    // Set up frame callback for capture (NV21 format)
                    uvcCamera?.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_NV21)

                    // Start preview on TextureView if available
                    startPreviewOnTextureView()

                    // Update state
                    _isConnected.value = true
                    _cameraType.value = "external"
                    _currentCameraId.value = device?.deviceName ?: "usb"
                    updateCameraList(device)

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open UVC camera: ${e.message}", e)
                    closeCamera()
                }
            }

            override fun onDeviceClose(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                Log.i(TAG, "═══ USB DEVICE CLOSED: ${device?.deviceName}")
                closeCamera()
            }

            override fun onDetach(device: UsbDevice?) {
                Log.i(TAG, "═══ USB DEVICE DETACHED: ${device?.deviceName}")
                closeCamera()
            }

            override fun onCancel(device: UsbDevice?) {
                Log.i(TAG, "USB permission cancelled for: ${device?.deviceName}")
            }
        })

        usbMonitor?.register()
        Log.i(TAG, "USBMonitor registered, scanning for UVC devices...")

        // Also scan for already-connected devices
        scanExistingDevices()
    }

    // ─── Check if USB device is UVC (USB Video Class) ─────────────
    private fun isUVCDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            // Class 14 = Video (legacy), Class 239 = Misc (modern UVC)
            if (iface.interfaceClass == 14 || (iface.interfaceClass == 239 && iface.interfaceSubclass == 2)) {
                return true
            }
        }
        return false
    }

    // ─── Scan for already-connected USB devices ────────────────────
    private fun scanExistingDevices() {
        try {
            val deviceList = usbMonitor?.deviceList
            deviceList?.let { devices ->
                Log.i(TAG, "scanExistingDevices: Found ${devices.size} USB devices")
                for (device in devices) {
                    Log.i(TAG, "  USB device: ${device.deviceName} vendor=${device.vendorId} product=${device.productId} uvc=${isUVCDevice(device)}")
                    if (isUVCDevice(device) || device.interfaceCount > 0) {
                        Log.i(TAG, "  Requesting permission for existing UVC device")
                        usbMonitor?.requestPermission(device)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning existing devices: ${e.message}")
        }
    }

    // ─── Start preview on TextureView ──────────────────────────────
    private fun startPreviewOnTextureView() {
        val tv = textureView
        val cam = uvcCamera
        if (tv == null || cam == null) {
            Log.w(TAG, "Cannot start preview: textureView=${tv != null}, camera=${cam != null}")
            return
        }

        if (tv.isAvailable) {
            try {
                val surfaceTexture = tv.surfaceTexture
                if (surfaceTexture != null) {
                    cam.setPreviewTexture(surfaceTexture)
                    cam.startPreview()
                    Log.i(TAG, "USB camera preview STARTED on TextureView!")
                } else {
                    Log.w(TAG, "SurfaceTexture is null, will retry when available")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start preview: ${e.message}", e)
                // Surface fallback not needed — setPreviewTexture handles it
            }
        } else {
            Log.i(TAG, "TextureView not available yet, will start preview when ready")
        }
    }

    // ─── Set TextureView for preview ───────────────────────────────
    fun setTextureView(tv: TextureView) {
        Log.i(TAG, "setTextureView: surface available=${tv.isAvailable}")
        textureView = tv

        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                Log.i(TAG, "TextureView surface available: ${width}x${height}")
                startPreviewOnTextureView()
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                Log.i(TAG, "TextureView surface destroyed")
                try { uvcCamera?.stopPreview() } catch (_: Exception) {}
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        // If surface already available, start preview immediately
        if (tv.isAvailable) {
            startPreviewOnTextureView()
        }
    }

    // ─── Frame callback for photo capture ──────────────────────────
    // IFrameCallback.onFrame receives ByteBuffer (NOT ByteArray)
    private val frameCallback = IFrameCallback { frame: ByteBuffer? ->
        if (frame != null && frame.hasRemaining()) {
            // Convert ByteBuffer to ByteArray for storage
            val bytes = ByteArray(frame.remaining())
            frame.get(bytes)
            latestFrameBytes = bytes
        }

        // If there's a pending capture, process it
        val callback = pendingCaptureCallback
        if (callback != null && latestFrameBytes != null) {
            pendingCaptureCallback = null
            processAndSendCapture(callback, latestFrameBytes!!)
        }
    }

    // ─── Process frame bytes and send capture result ───────────────
    private fun processAndSendCapture(callback: (String?) -> Unit, frameBytes: ByteArray) {
        try {
            val image = YuvImage(frameBytes, ImageFormat.NV21, frameWidth, frameHeight, null)
            val baos = ByteArrayOutputStream()
            image.compressToJpeg(Rect(0, 0, frameWidth, frameHeight), 95, baos)
            val bitmap = BitmapFactory.decodeByteArray(baos.toByteArray(), 0, baos.size())

            if (bitmap != null) {
                val jpegBaos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, jpegBaos)
                val base64 = Base64.encodeToString(jpegBaos.toByteArray(), Base64.NO_WRAP)
                val dataUrl = "data:image/jpeg;base64,$base64"

                Log.i(TAG, "Photo captured: ${bitmap.width}x${bitmap.height} (${dataUrl.length} chars)")
                callback(dataUrl)
            } else {
                Log.e(TAG, "Failed to decode captured frame to bitmap")
                callback(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Frame capture processing failed: ${e.message}", e)
            callback(null)
        }
    }

    // ─── Photo Capture ─────────────────────────────────────────────
    fun capturePhoto(onResult: (String?) -> Unit) {
        val cam = uvcCamera
        if (cam == null || !_isConnected.value) {
            Log.e(TAG, "Cannot capture: camera not connected")
            onResult(null)
            return
        }

        Log.i(TAG, "capturePhoto: requesting frame capture")

        // Use the latest frame if available (faster), otherwise wait for next frame
        val frame = latestFrameBytes
        if (frame != null) {
            pendingCaptureCallback = null
            processAndSendCapture(onResult, frame)
        } else {
            // No frame available yet, wait for next callback
            pendingCaptureCallback = onResult
        }
    }

    // ─── Force rescan ──────────────────────────────────────────────
    fun forceRescan() {
        Log.i(TAG, "forceRescan: scanning for USB devices")
        scanExistingDevices()
    }

    fun switchCamera(deviceId: String) {
        Log.i(TAG, "switchCamera: deviceId=$deviceId (no-op for USB)")
    }

    fun refreshCameraList() {
        scanExistingDevices()
    }

    // ─── Update camera list ────────────────────────────────────────
    private fun updateCameraList(device: UsbDevice?) {
        device?.let {
            _availableCameras.value = listOf(
                it.deviceName to "USB: Capture Card (${it.vendorId}:${it.productId})"
            )
        }
    }

    // ─── Close camera ──────────────────────────────────────────────
    private fun closeCamera() {
        try { uvcCamera?.stopPreview() } catch (_: Exception) {}
        try { uvcCamera?.close() } catch (_: Exception) {}
        try { uvcCamera?.destroy() } catch (_: Exception) {}
        uvcCamera = null
        currentUsbDevice = null
        _isConnected.value = false
        _cameraType.value = "none"
        latestFrameBytes = null
        pendingCaptureCallback = null
    }

    // ─── Cleanup ───────────────────────────────────────────────────
    fun destroy() {
        Log.i(TAG, "destroy: cleaning up")
        closeCamera()
        try { usbMonitor?.unregister() } catch (_: Exception) {}
        try { usbMonitor?.destroy() } catch (_: Exception) {}
        usbMonitor = null
        textureView = null
    }

    // ─── Compatibility methods ─────────────────────────────────────
    fun forceSwitchToUSB() = forceRescan()
    fun switchToCamera(cameraId: String) = switchCamera(cameraId)
    fun hasCameraPermission(): Boolean = true
    fun updateConfig(aspectRatio: Double, filterPreset: String) {}
    fun setFrameOverlay(base64Data: String?) {}
}
