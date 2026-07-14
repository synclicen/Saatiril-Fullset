package com.saatiril.operator.camera

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import android.view.Surface
import android.view.TextureView
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.UVCCamera
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.USBMonitor.OnDeviceConnectListener
import com.serenegiant.usb.USBMonitor.UsbControlBlock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * ═════════════════════════════════════════════════════════════════════════
 * UVCCamera Manager — v15 USB Video Class Direct Access
 * ═════════════════════════════════════════════════════════════════════════
 *
 * CRITICAL: Uses UVCCamera library (alexey-pelykh/UVCCamera fork, org.uvccamera:lib on Maven Central)
 * instead of Camera2 API. The original saki4510t/UVCCamera does not have proper JitPack builds.
 * Camera2/CameraX API CANNOT access USB HDMI video capture cards on Android.
 * USB capture cards are UVC (USB Video Class) devices and require a dedicated
 * UVC library to access them directly via USB Host API.
 *
 * Camera2 only sees built-in phone cameras — it never enumerates UVC devices.
 * The UVCCamera library communicates directly with UVC hardware via USB,
 * bypassing the Android camera service entirely.
 *
 * This manager:
 * 1. Uses USBMonitor to detect USB device attach/detach events
 * 2. Requests USB permission via PendingIntent
 * 3. Opens the UVC camera device and starts preview on a TextureView
 * 4. Captures still images (JPEG) via TextureView.bitmap → JPEG → base64
 * 5. Provides the same StateFlow interface as the old Camera2Manager
 *
 * Layout structure (same as v14 but with UVC instead of Camera2):
 * ┌──────────────────────────────┐
 * │  FrameLayout (root)          │
 * │  ┌────────────────────────┐  │
 * │  │ TextureView (preview)  │  │  ← Bottom layer, UVC camera preview
 * │  └────────────────────────┘  │
 * │  ┌────────────────────────┐  │
 * │  │ ComposeView (UI)       │  │  ← Top layer, transparent background
 * │  └────────────────────────┘  │
 * └──────────────────────────────┘
 */
class UVCCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "UVCCameraManager"
        private const val PREVIEW_WIDTH = 1920
        private const val PREVIEW_HEIGHT = 1080
        private const val CAPTURE_WIDTH = 1920
        private const val CAPTURE_HEIGHT = 1080
        const val ACTION_USB_PERMISSION = "com.saatiril.operator.USB_PERMISSION"
    }

    // ─── Data Classes ────────────────────────────────────────────

    data class USBCameraInfo(
        val id: String,
        val label: String,
        val usbDevice: UsbDevice
    )

    // ─── State Flows for UI (same interface as Camera2Manager) ───

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _cameraType = MutableStateFlow("none")
    val cameraType: StateFlow<String> = _cameraType.asStateFlow()

    private val _isUSBCamera = MutableStateFlow(false)
    val isUSBCamera: StateFlow<Boolean> = _isUSBCamera.asStateFlow()

    private val _availableCameras = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val availableCameras: StateFlow<List<Pair<String, String>>> = _availableCameras.asStateFlow()

    private val _currentCameraId = MutableStateFlow("")
    val currentCameraIdFlow: StateFlow<String> = _currentCameraId.asStateFlow()

    // ─── UVC Objects ─────────────────────────────────────────────

    private var usbMonitor: USBMonitor? = null
    private var uvcCamera: UVCCamera? = null
    private var textureView: TextureView? = null
    private var isPreviewing = false
    private var isCameraOpened = false

    /** Currently connected USB device */
    private var currentUsbDevice: UsbDevice? = null
    private var currentCtrlBlock: UsbControlBlock? = null

    /** Discovered UVC devices */
    private val discoveredDevices = mutableMapOf<String, UsbDevice>()

    /** Pending capture callback */
    private var captureCallback: ((String?) -> Unit)? = null

    /** Background handler for UVC operations */
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    /** Whether USB monitoring has been registered */
    private var isRegistered = false

    /** USB permission receiver */
    private var usbPermissionReceiver: BroadcastReceiver? = null

    // ─── TextureView Setup ───────────────────────────────────────

    /**
     * Set the TextureView for camera preview.
     * Called from Activity after creating the TextureView.
     */
    fun setTextureView(tv: TextureView) {
        textureView = tv
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                Log.i(TAG, "SurfaceTexture available: ${width}x${height}")
                // If camera is already opened but preview wasn't started, start it now
                if (isCameraOpened && !isPreviewing) {
                    startPreview()
                }
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                // Restart preview with new size if camera is open
                if (isCameraOpened && isPreviewing) {
                    stopPreview()
                    startPreview()
                }
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                Log.i(TAG, "SurfaceTexture destroyed")
                stopPreview()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                // Called on every frame — no-op
            }
        }
    }

    // ─── Background Thread ───────────────────────────────────────

    private fun startBackgroundThread() {
        if (backgroundThread != null && backgroundThread!!.isAlive) return
        backgroundThread = HandlerThread("UVCBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        try {
            backgroundThread?.quitSafely()
            backgroundThread?.join(1000)
        } catch (_: InterruptedException) {}
        backgroundThread = null
        backgroundHandler = null
    }

    // ─── USB Monitor Setup ───────────────────────────────────────

    /**
     * Initialize USB monitoring. This must be called to start detecting UVC devices.
     */
    fun initCamera() {
        Log.i(TAG, "═══════════════════════════════════════════════════")
        Log.i(TAG, "initCamera: v15 UVCCamera Direct")
        Log.i(TAG, "═══════════════════════════════════════════════════")

        startBackgroundThread()
        registerUsbMonitor()
        enumerateUsbDevices()
    }

    /**
     * Register the USBMonitor to detect UVC device attach/detach events.
     */
    private fun registerUsbMonitor() {
        if (isRegistered) {
            Log.i(TAG, "USBMonitor already registered, skipping")
            return
        }

        try {
            if (usbMonitor == null) {
                usbMonitor = USBMonitor(context, object : OnDeviceConnectListener {
                    override fun onAttach(device: UsbDevice) {
                        Log.i(TAG, "═══════════════════════════════════════════════════")
                        Log.i(TAG, "USB device ATTACHED: ${device.deviceName}, " +
                                "vid=${device.vendorId}, pid=${device.productId}")
                        Log.i(TAG, "═══════════════════════════════════════════════════")

                        // Check if this is a UVC device
                        if (isUVCDevice(device)) {
                            Log.i(TAG, "UVC VIDEO DEVICE detected! Requesting permission...")
                            discoveredDevices[device.deviceName] = device
                            updateAvailableCameras()
                            requestUsbPermission(device)
                        } else {
                            Log.i(TAG, "Not a UVC device, ignoring")
                        }
                    }

                    override fun onDisconnect(device: UsbDevice, ctrlBlock: UsbControlBlock) {
                        Log.i(TAG, "USB device DISCONNECTED: ${device.deviceName}")
                        handleDeviceDisconnect(device)
                    }

                    override fun onConnect(device: UsbDevice, ctrlBlock: UsbControlBlock, createNew: Boolean) {
                        Log.i(TAG, "═══════════════════════════════════════════════════")
                        Log.i(TAG, "USB device CONNECTED (permission granted): ${device.deviceName}")
                        Log.i(TAG, "═══════════════════════════════════════════════════")

                        currentUsbDevice = device
                        currentCtrlBlock = ctrlBlock

                        // Open the UVC camera
                        backgroundHandler?.post {
                            openUVCCamera(device, ctrlBlock)
                        }
                    }

                    override fun onDettach(device: UsbDevice) {
                        Log.i(TAG, "USB device DETACHED: ${device.deviceName}")
                        discoveredDevices.remove(device.deviceName)
                        updateAvailableCameras()
                        handleDeviceDisconnect(device)
                    }

                    override fun onCancel(device: UsbDevice) {
                        Log.i(TAG, "USB permission CANCELLED for: ${device.deviceName}")
                    }
                })
            }

            usbMonitor?.register()
            isRegistered = true
            Log.i(TAG, "USBMonitor registered successfully")

            // Also register a BroadcastReceiver for USB permission results
            // (required for the PendingIntent approach)
            registerUsbPermissionReceiver()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to register USBMonitor: ${e.message}", e)
        }
    }

    /**
     * Unregister the USBMonitor. Called in destroy().
     */
    private fun unregisterUsbMonitor() {
        if (!isRegistered) return
        try {
            usbMonitor?.unregister()
            isRegistered = false
            Log.i(TAG, "USBMonitor unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister USBMonitor: ${e.message}")
        }
    }

    /**
     * Register a BroadcastReceiver to handle USB permission results.
     * This is needed because USBMonitor.requestPermission() uses PendingIntent
     * which requires a broadcast receiver on Android 12+.
     */
    private fun registerUsbPermissionReceiver() {
        if (usbPermissionReceiver != null) return

        usbPermissionReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (ACTION_USB_PERMISSION == intent.action) {
                    synchronized(this) {
                        @Suppress("DEPRECATION")
                        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

                        Log.i(TAG, "USB permission result: device=${device?.deviceName}, granted=$granted")

                        if (granted && device != null) {
                            // USBMonitor handles the connection via onConnect callback
                            // But we may need to manually trigger it if it doesn't
                            Log.i(TAG, "USB permission GRANTED for ${device.deviceName}")
                        } else if (device != null) {
                            Log.w(TAG, "USB permission DENIED for ${device.deviceName}")
                        } else {
                            // No device in intent
                        }
                    }
                }
            }
        }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        context.registerReceiver(usbPermissionReceiver, filter)
        Log.i(TAG, "USB permission BroadcastReceiver registered")
    }

    private fun unregisterUsbPermissionReceiver() {
        try {
            usbPermissionReceiver?.let { context.unregisterReceiver(it) }
        } catch (_: Exception) {}
        usbPermissionReceiver = null
    }

    // ─── USB Device Detection ────────────────────────────────────

    /**
     * Check if a USB device is a UVC (USB Video Class) device.
     *
     * UVC devices have:
     * - Interface class 14 (USB_CLASS_VIDEO)
     * - Or interface class 239 / subclass 2 (Miscellaneous / UVC)
     */
    private fun isUVCDevice(device: UsbDevice): Boolean {
        try {
            val interfaceCount = device.interfaceCount
            for (i in 0 until interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == 14 ||          // USB video class
                    (iface.interfaceClass == 239 && iface.interfaceSubclass == 2)) { // UVC
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking USB device ${device.deviceName}: ${e.message}")
        }
        return false
    }

    /**
     * Enumerate currently connected USB devices and find UVC ones.
     */
    private fun enumerateUsbDevices() {
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            if (usbManager == null) {
                Log.e(TAG, "UsbManager not available")
                return
            }

            val devices = usbManager.deviceList
            discoveredDevices.clear()

            for (device in devices.values) {
                if (isUVCDevice(device)) {
                    Log.i(TAG, "Found UVC device: ${device.deviceName}, vid=${device.vendorId}, pid=${device.productId}")
                    discoveredDevices[device.deviceName] = device
                }
            }

            updateAvailableCameras()

            Log.i(TAG, "═══ Enumerated USB devices: ${discoveredDevices.size} UVC devices found ═══")

            // Auto-request permission for the first available UVC device
            val firstDevice = discoveredDevices.values.firstOrNull()
            if (firstDevice != null && currentUsbDevice == null) {
                Log.i(TAG, "Auto-requesting permission for first UVC device: ${firstDevice.deviceName}")
                requestUsbPermission(firstDevice)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error enumerating USB devices: ${e.message}")
        }
    }

    /**
     * Update the available cameras list exposed via StateFlow.
     */
    private fun updateAvailableCameras() {
        val cameras = discoveredDevices.values.map { device ->
            val label = buildDeviceLabel(device)
            device.deviceName to label
        }
        _availableCameras.value = cameras
        Log.i(TAG, "Available cameras updated: ${cameras.size} devices")
    }

    /**
     * Build a human-readable label for a USB device.
     */
    private fun buildDeviceLabel(device: UsbDevice): String {
        val vendorId = device.vendorId
        val productId = device.productId

        // Known vendor IDs for capture cards
        val label = when (vendorId) {
            0x0489 -> "Fushicai UVC Capture"
            0x0C45 -> "MacroSilicon UVC Capture"
            0x0416 -> "Magewell UVC Capture"
            0x1749 -> "Elgato/Corsair UVC Capture"
            0x11C0 -> "AverMedia UVC Capture"
            0x0424 -> "Genki UVC Capture"
            else -> "USB Capture Card (VID:${String.format("%04X", vendorId)})"
        }

        return label
    }

    // ─── USB Permission ──────────────────────────────────────────

    /**
     * Request USB permission for a specific device.
     * The result will be handled by the USBMonitor's onConnect callback.
     */
    fun requestUsbPermission(device: UsbDevice) {
        try {
            usbMonitor?.requestPermission(device)
            Log.i(TAG, "USB permission requested for ${device.deviceName}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request USB permission: ${e.message}")
        }
    }

    /**
     * Process USB permission result from Activity.
     * Called when the Activity receives the USB_PERMISSION broadcast.
     */
    fun onUsbPermissionResult(device: UsbDevice, granted: Boolean) {
        if (granted) {
            Log.i(TAG, "USB permission GRANTED for ${device.deviceName}")
            // USBMonitor should handle the connection automatically via onConnect
        } else {
            Log.w(TAG, "USB permission DENIED for ${device.deviceName}")
        }
    }

    // ─── UVC Camera Open / Close ─────────────────────────────────

    /**
     * Open a UVC camera device and start preview.
     */
    private fun openUVCCamera(device: UsbDevice, ctrlBlock: UsbControlBlock) {
        // Close existing camera first
        closeUVCCamera()

        Log.i(TAG, "═══════════════════════════════════════════════════")
        Log.i(TAG, "Opening UVC camera: ${device.deviceName}")
        Log.i(TAG, "═══════════════════════════════════════════════════")

        try {
            val camera = UVCCamera()
            camera.open(ctrlBlock)
            uvcCamera = camera
            isCameraOpened = true

            // Set preview size — try 1920x1080 MJPEG first, then fall back
            try {
                camera.setPreviewSize(PREVIEW_WIDTH, PREVIEW_HEIGHT, UVCCamera.FRAME_FORMAT_MJPEG)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set ${PREVIEW_WIDTH}x${PREVIEW_HEIGHT} MJPEG, trying 1280x720: ${e.message}")
                try {
                    camera.setPreviewSize(1280, 720, UVCCamera.FRAME_FORMAT_MJPEG)
                } catch (e2: Exception) {
                    Log.w(TAG, "Failed to set 1280x720 MJPEG, trying default: ${e2.message}")
                    try {
                        camera.setPreviewSize(640, 480, UVCCamera.FRAME_FORMAT_MJPEG)
                    } catch (e3: Exception) {
                        Log.w(TAG, "Failed to set 640x480 MJPEG, trying YUV: ${e3.message}")
                        try {
                            camera.setPreviewSize(640, 480, UVCCamera.FRAME_FORMAT_YUYV)
                        } catch (e4: Exception) {
                            Log.e(TAG, "All preview size attempts failed: ${e4.message}")
                        }
                    }
                }
            }

            // Update state
            _isConnected.value = true
            _currentCameraId.value = device.deviceName
            _isUSBCamera.value = true
            _cameraType.value = "external"

            Log.i(TAG, "UVC camera opened successfully: ${device.deviceName}")

            // Start preview if TextureView is available
            val tv = textureView
            if (tv != null && tv.isAvailable) {
                startPreview()
            } else {
                Log.i(TAG, "TextureView not available yet — preview will start when surface is ready")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to open UVC camera: ${e.message}", e)
            isCameraOpened = false
            _isConnected.value = false
            _cameraType.value = "none"
            _isUSBCamera.value = false

            // Try next device if available
            val otherDevice = discoveredDevices.values.find { it.deviceName != device.deviceName }
            if (otherDevice != null) {
                Log.i(TAG, "Trying next UVC device: ${otherDevice.deviceName}")
                requestUsbPermission(otherDevice)
            }
        }
    }

    /**
     * Close the current UVC camera.
     */
    private fun closeUVCCamera() {
        stopPreview()

        try {
            uvcCamera?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing UVC camera: ${e.message}")
        }

        uvcCamera = null
        isCameraOpened = false
        currentCtrlBlock = null
    }

    /**
     * Handle USB device disconnection.
     */
    private fun handleDeviceDisconnect(device: UsbDevice) {
        if (currentUsbDevice?.deviceName == device.deviceName) {
            Log.i(TAG, "═══════════════════════════════════════════════════")
            Log.i(TAG, "Current UVC camera DISCONNECTED!")
            Log.i(TAG, "═══════════════════════════════════════════════════")

            closeUVCCamera()
            currentUsbDevice = null

            _isConnected.value = false
            _cameraType.value = "none"
            _isUSBCamera.value = false
            _currentCameraId.value = ""

            // Try to connect to another available device
            val nextDevice = discoveredDevices.values.firstOrNull()
            if (nextDevice != null) {
                Log.i(TAG, "Trying to connect to remaining UVC device: ${nextDevice.deviceName}")
                requestUsbPermission(nextDevice)
            }
        }
    }

    // ─── Preview ─────────────────────────────────────────────────

    /**
     * Start camera preview on the TextureView.
     */
    private fun startPreview() {
        val camera = uvcCamera
        val tv = textureView
        if (camera == null) {
            Log.w(TAG, "startPreview: no UVC camera")
            return
        }
        if (tv == null || !tv.isAvailable) {
            Log.w(TAG, "startPreview: TextureView not available")
            return
        }
        if (isPreviewing) {
            Log.w(TAG, "startPreview: already previewing")
            return
        }

        try {
            val surfaceTexture = tv.surfaceTexture
            if (surfaceTexture != null) {
                surfaceTexture.setDefaultBufferSize(PREVIEW_WIDTH, PREVIEW_HEIGHT)
                camera.setPreviewTexture(surfaceTexture)
            } else {
                // Fallback: use Surface
                val surface = Surface(tv.surfaceTexture)
                camera.setPreviewDisplay(surface)
            }

            camera.startPreview()
            isPreviewing = true

            Log.i(TAG, "UVC camera preview started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start UVC preview: ${e.message}", e)
            // Try alternative approach
            try {
                val surface = Surface(tv.surfaceTexture)
                camera.setPreviewDisplay(surface)
                camera.startPreview()
                isPreviewing = true
                Log.i(TAG, "UVC camera preview started via Surface fallback")
            } catch (e2: Exception) {
                Log.e(TAG, "Surface fallback also failed: ${e2.message}", e2)
            }
        }
    }

    /**
     * Stop camera preview.
     */
    private fun stopPreview() {
        if (!isPreviewing) return
        try {
            uvcCamera?.stopPreview()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping UVC preview: ${e.message}")
        }
        isPreviewing = false
    }

    // ─── Photo Capture ───────────────────────────────────────────

    /**
     * Capture a photo. The result is returned as a data URL (base64-encoded JPEG)
     * via the callback, matching the Camera2Manager interface.
     *
     * The org.uvccamera:lib module does NOT have captureStill() method.
     * Instead, we use TextureView.bitmap to grab the current preview frame.
     * Fallback: IFrameCallback to get raw frame data and convert to JPEG.
     */
    fun capturePhoto(callback: (String?) -> Unit) {
        val camera = uvcCamera
        if (camera == null || !isCameraOpened) {
            Log.e(TAG, "capturePhoto: no UVC camera")
            callback(null)
            return
        }

        captureCallback = callback

        // Strategy 1: Grab current TextureView bitmap (fastest, most reliable)
        try {
            captureFromTextureView(callback)
        } catch (e: Exception) {
            Log.e(TAG, "TextureView capture failed, trying frame callback: ${e.message}")
            // Strategy 2: Fallback to IFrameCallback
            try {
                captureFromFrameCallback(camera, callback)
            } catch (e2: Exception) {
                Log.e(TAG, "Frame callback also failed: ${e2.message}")
                captureCallback = null
                callback(null)
            }
        }
    }

    /**
     * Capture from the TextureView's current bitmap.
     * This is the primary capture method — fast and reliable.
     *
     * The org.uvccamera:lib module does NOT have captureStill(),
     * so TextureView.bitmap is the best approach for still capture.
     */
    private fun captureFromTextureView(callback: (String?) -> Unit) {
        val tv = textureView
        if (tv == null || !tv.isAvailable) {
            Log.e(TAG, "captureFromTextureView: TextureView not available")
            throw IllegalStateException("TextureView not available")
        }

        val bitmap = tv.bitmap
        if (bitmap == null) {
            Log.e(TAG, "captureFromTextureView: bitmap is null")
            throw IllegalStateException("TextureView bitmap is null")
        }

        try {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
            val bytes = outputStream.toByteArray()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val dataUrl = "data:image/jpeg;base64,$base64"

            Log.i(TAG, "Photo captured via TextureView.bitmap (${dataUrl.length} chars, ${bytes.size} bytes)")

            bitmap.recycle()
            outputStream.close()

            val cb = captureCallback
            captureCallback = null
            cb?.invoke(dataUrl)
        } catch (e: Exception) {
            bitmap.recycle()
            throw e
        }
    }

    /**
     * Capture from the current preview frame using IFrameCallback.
     * Fallback method when TextureView bitmap capture fails.
     *
     * This captures raw frame data and converts it to JPEG.
     */
    private fun captureFromFrameCallback(camera: UVCCamera, callback: (String?) -> Unit) {
        // First, capture the current TextureView content as a bitmap
        val tv = textureView
        if (tv != null && tv.isAvailable) {
            try {
                val bitmap = tv.bitmap
                if (bitmap != null) {
                    val outputStream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                    val bytes = outputStream.toByteArray()
                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    val dataUrl = "data:image/jpeg;base64,$base64"

                    Log.i(TAG, "Photo captured via TextureView.bitmap (${dataUrl.length} chars)")

                    bitmap.recycle()
                    outputStream.close()

                    val cb = captureCallback
                    captureCallback = null
                    cb?.invoke(dataUrl)
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "TextureView bitmap capture failed: ${e.message}")
            }
        }

        // Last resort: Use IFrameCallback for one frame
        try {
            camera.setFrameCallback(object : IFrameCallback {
                override fun onFrame(frame: ByteBuffer) {
                    if (!frame.hasRemaining()) {
                        Log.e(TAG, "IFrameCallback: empty frame")
                        val cb = captureCallback
                        captureCallback = null
                        cb?.invoke(null)
                        return
                    }

                    try {
                        // Convert ByteBuffer to ByteArray for YuvImage processing
                        val frameBytes = ByteArray(frame.remaining())
                        frame.get(frameBytes)

                        // The frame data from UVCCamera is typically NV21 or YUYV
                        // Try to decode it as NV21 (most common for Android)
                        val yuvImage = YuvImage(frameBytes, ImageFormat.NV21, PREVIEW_WIDTH, PREVIEW_HEIGHT, null)
                        val outputStream = ByteArrayOutputStream()
                        yuvImage.compressToJpeg(Rect(0, 0, PREVIEW_WIDTH, PREVIEW_HEIGHT), 95, outputStream)
                        val bytes = outputStream.toByteArray()
                        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        val dataUrl = "data:image/jpeg;base64,$base64"

                        Log.i(TAG, "Photo captured via IFrameCallback (${dataUrl.length} chars)")

                        outputStream.close()

                        val cb = captureCallback
                        captureCallback = null
                        cb?.invoke(dataUrl)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error converting IFrameCallback frame: ${e.message}")
                        val cb = captureCallback
                        captureCallback = null
                        cb?.invoke(null)
                    }

                    // Remove callback after one frame
                    try {
                        camera.setFrameCallback(null, 0)
                    } catch (_: Exception) {}
                }
            }, UVCCamera.PIXEL_FORMAT_NV21)

            // The callback will fire on the next frame
            // Set a timeout to avoid hanging forever
            backgroundHandler?.postDelayed({
                if (captureCallback != null) {
                    Log.w(TAG, "IFrameCallback timed out, trying TextureView bitmap")
                    try {
                        camera.setFrameCallback(null, 0)
                    } catch (_: Exception) {}

                    // One more try with TextureView bitmap
                    val tv = textureView
                    if (tv != null && tv.isAvailable) {
                        try {
                            val bitmap = tv.bitmap
                            if (bitmap != null) {
                                val outputStream = ByteArrayOutputStream()
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                                val bytes = outputStream.toByteArray()
                                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                                val dataUrl = "data:image/jpeg;base64,$base64"
                                bitmap.recycle()
                                outputStream.close()

                                val cb = captureCallback
                                captureCallback = null
                                cb?.invoke(dataUrl)
                                return@postDelayed
                            }
                        } catch (_: Exception) {}
                    }

                    val cb = captureCallback
                    captureCallback = null
                    cb?.invoke(null)
                }
            }, 3000) // 3 second timeout

        } catch (e: Exception) {
            Log.e(TAG, "IFrameCallback setup failed: ${e.message}")
            val cb = captureCallback
            captureCallback = null
            cb?.invoke(null)
        }
    }

    // ─── Camera Switching ────────────────────────────────────────

    /**
     * Switch to a specific USB device by device name.
     */
    fun switchCamera(deviceId: String) {
        Log.i(TAG, "switchCamera: $deviceId")

        val device = discoveredDevices[deviceId]
        if (device == null) {
            Log.w(TAG, "Device not found: $deviceId")
            return
        }

        if (device.deviceName == currentUsbDevice?.deviceName) {
            Log.i(TAG, "Already connected to $deviceId")
            return
        }

        // Close current camera and open the new one
        closeUVCCamera()
        currentUsbDevice = null
        _isConnected.value = false
        _cameraType.value = "none"
        _isUSBCamera.value = false

        requestUsbPermission(device)
    }

    // ─── USB Camera Rescan ───────────────────────────────────────

    /**
     * Force rescan for USB cameras. Re-enumerates USB devices and
     * auto-switches to USB if found.
     */
    fun forceRescan() {
        Log.i(TAG, "forceRescan: re-enumerating USB devices")
        enumerateUsbDevices()

        // If no camera is connected, try the first available UVC device
        if (!isCameraOpened) {
            val firstDevice = discoveredDevices.values.firstOrNull()
            if (firstDevice != null) {
                Log.i(TAG, "forceRescan: found UVC device ${firstDevice.deviceName}, requesting permission")
                requestUsbPermission(firstDevice)
            } else {
                Log.i(TAG, "forceRescan: no UVC devices found")
            }
        }
    }

    // ─── Camera Close / Destroy ──────────────────────────────────

    /**
     * Full cleanup — close camera, unregister USB monitor, stop background thread.
     */
    fun destroy() {
        Log.i(TAG, "destroy: cleaning up UVC camera resources")
        closeUVCCamera()
        unregisterUsbPermissionReceiver()
        unregisterUsbMonitor()

        try {
            usbMonitor?.destroy()
        } catch (_: Exception) {}
        usbMonitor = null

        stopBackgroundThread()

        _cameraType.value = "none"
        _isUSBCamera.value = false
        _isConnected.value = false
        _currentCameraId.value = ""
        discoveredDevices.clear()
        _availableCameras.value = emptyList()
    }

    // ─── Compatibility Methods (matching Camera2Manager interface) ──

    /**
     * Show the camera preview. For UVC, preview is rendered on TextureView.
     */
    fun showPreview() {
        textureView?.visibility = android.view.View.VISIBLE
        if (isCameraOpened && !isPreviewing) {
            startPreview()
        }
        Log.i(TAG, "showPreview: TextureView visible, previewing=$isPreviewing")
    }

    /**
     * Hide the camera preview. Keeps camera running behind the Compose UI.
     */
    fun hidePreview() {
        // Don't actually hide the TextureView — just let the Compose UI cover it
        // with an opaque background on ConnectionScreen
        Log.i(TAG, "hidePreview: keeping TextureView active (Compose UI will cover)")
    }

    /**
     * Check if camera permission is granted.
     * For UVC cameras, we need USB permission, not CAMERA permission.
     * But we keep CAMERA permission check for compatibility.
     */
    fun hasCameraPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /**
     * Refresh camera list — re-enumerate USB devices.
     */
    fun refreshCameraList(): List<Pair<String, String>> {
        enumerateUsbDevices()
        return _availableCameras.value
    }

    /**
     * Force switch to USB camera.
     */
    fun forceSwitchToUSB() {
        forceRescan()
    }

    /**
     * Switch to specific camera by ID.
     */
    fun switchToCamera(cameraId: String) {
        switchCamera(cameraId)
    }

    /**
     * Set filter preset. TODO: apply filter to captured image in post-processing.
     */
    fun setFilter(preset: String) {
        Log.d(TAG, "setFilter: $preset (not yet implemented for UVCCamera)")
        // TODO: Apply filter to captured image in post-processing
    }

    /**
     * Set frame overlay. TODO: overlay frame on captured image.
     */
    fun setFrameOverlay(base64: String?) {
        Log.d(TAG, "setFrameOverlay: ${if (base64 != null) "${base64.length} chars" else "null"} (not yet implemented for UVCCamera)")
        // TODO: Overlay frame on captured image
    }

    /**
     * Update camera config (aspect ratio + filter).
     */
    fun updateConfig(aspectRatio: Double, filterPreset: String) {
        setFilter(filterPreset)
        // Aspect ratio changes would require restarting the preview with new dimensions
        // For now, we keep the current preview size
    }

    // ─── Public Accessors for Activity ───────────────────────────

    /**
     * Get the USBMonitor instance. The Activity needs this to handle
     * USB permission results and lifecycle events.
     */
    fun getUSBMonitor(): USBMonitor? = usbMonitor

    /**
     * Check if USB monitoring is registered.
     */
    fun isMonitoring(): Boolean = isRegistered
}
