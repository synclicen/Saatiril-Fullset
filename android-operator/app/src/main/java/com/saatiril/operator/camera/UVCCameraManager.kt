package com.saatiril.operator.camera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
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
 * UVCCamera Manager — v17 MacroSilicon Black Screen Fix
 * ═════════════════════════════════════════════════════════════════════════
 *
 * CRITICAL FIXES for MacroSilicon (VID:345F) HDMI capture card:
 *
 * FIX #1: FORCE MJPEG FORMAT + LOCK 720p
 *   MacroSilicon dongles FREEZE (black screen) if YUYV/YUY2 format
 *   is requested or if resolution > 720p at > 30fps.
 *   → setPreviewSize(1280, 720, UVCCamera.FRAME_FORMAT_MJPEG) ALWAYS
 *   → NEVER use FRAME_FORMAT_YUYV or default format
 *
 * FIX #2: BANDWIDTH FACTOR
 *   MacroSilicon often fails USB bandwidth negotiation with Android kernel.
 *   Camera appears "open" (green checkmark) but receives 0-byte packets.
 *   → camera.setBandwidthFactor(1.0f) immediately after open
 *   → If still black, try 0.5f or 0.8f
 *
 * FIX #3: PROPER PREVIEW SURFACE (TextureView + startPreview)
 *   The preview Surface MUST be set before startPreview().
 *   Must wait for TextureView.SurfaceTextureAvailable before setting surface.
 *   → camera.setPreviewTexture(surfaceTexture) THEN camera.startPreview()
 *
 * Using org.uvccamera:lib (alexey-pelykh fork on Maven Central).
 * Same com.serenegiant.usb.* package — drop-in replacement for saki4510t.
 */
class UVCCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "UVCCameraManager"

        // ═══════════════════════════════════════════════════════════
        // FIX #1: Resolution and Format for MacroSilicon
        // MUST use MJPEG, MUST start at 720p. NO YUYV.
        // ═══════════════════════════════════════════════════════════
        private const val PREVIEW_WIDTH = 1280
        private const val PREVIEW_HEIGHT = 720
        private const val PREVIEW_FORMAT = UVCCamera.FRAME_FORMAT_MJPEG  // NEVER YUYV!

        // ═══════════════════════════════════════════════════════════
        // FIX #2: Bandwidth Factor for MacroSilicon
        // 1.0f = full bandwidth. If still black, try 0.5f.
        // ═══════════════════════════════════════════════════════════
        private const val BANDWIDTH_FACTOR = 1.0f

        // Known UVC Vendor IDs
        private const val VENDOR_MACROSILICON = 0x345F  // MacroSilicon (JASOZ capture card)
        private const val VENDOR_FUSHICAI = 0x0489
        private const val VENDOR_MAGEWELL = 0x0416

        const val ACTION_USB_PERMISSION = "com.saatiril.operator.USB_PERMISSION"
    }

    // ─── State Flows ────────────────────────────────────────────
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _cameraType = MutableStateFlow("none")
    val cameraType: StateFlow<String> = _cameraType.asStateFlow()

    private val _currentCameraId = MutableStateFlow("")
    val currentCameraIdFlow: StateFlow<String> = _currentCameraId.asStateFlow()

    private val _availableCameras = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val availableCameras: StateFlow<List<Pair<String, String>>> = _availableCameras.asStateFlow()

    private val _isUSBCamera = MutableStateFlow(false)
    val isUSBCamera: StateFlow<Boolean> = _isUSBCamera.asStateFlow()

    // ─── UVC Internals ──────────────────────────────────────────
    private var usbMonitor: USBMonitor? = null
    private var uvcCamera: UVCCamera? = null
    private var isRegistered = false
    private var currentUsbDevice: UsbDevice? = null
    private var currentCtrlBlock: UsbControlBlock? = null

    // ─── Preview Surface ────────────────────────────────────────
    private var textureView: TextureView? = null
    private var previewSurface: Surface? = null

    // ─── Background Handler ─────────────────────────────────────
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // ─── Capture ────────────────────────────────────────────────
    private var captureCallback: ((String?) -> Unit)? = null

    // ─── USB Permission ─────────────────────────────────────────
    private var usbPermissionReceiver: BroadcastReceiver? = null

    // ─── Discovered Devices ─────────────────────────────────────
    private val discoveredDevices = mutableMapOf<String, UsbDevice>()

    // ─── Pending surface setup ──────────────────────────────────
    private var pendingSurfaceSetup = false

    /**
     * Initialize the UVC camera system.
     * Call this once when the Activity is created.
     */
    fun initCamera() {
        Log.i(TAG, "═══════════════════════════════════════════════════")
        Log.i(TAG, "initCamera: v17 UVCCamera Direct (MacroSilicon Fix)")
        Log.i(TAG, "═══════════════════════════════════════════════════")

        startBackgroundThread()
        registerUsbMonitor()
    }

    // ═══════════════════════════════════════════════════════════════
    // Background Thread (UVC operations MUST be on background thread)
    // ═══════════════════════════════════════════════════════════════

    private fun startBackgroundThread() {
        if (backgroundThread != null) return
        backgroundThread = HandlerThread("UVCameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
        Log.i(TAG, "Background thread started")
    }

    private fun stopBackgroundThread() {
        try {
            backgroundThread?.quitSafely()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping background thread: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // USB Monitor — Detect UVC device attach/detach
    // ═══════════════════════════════════════════════════════════════

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
                        Log.i(TAG, "USB ATTACHED: ${device.deviceName}, vid=0x${Integer.toHexString(device.vendorId)}, pid=0x${Integer.toHexString(device.productId)}")
                        Log.i(TAG, "═══════════════════════════════════════════════════")

                        if (isUVCDevice(device)) {
                            Log.i(TAG, "★ UVC VIDEO DEVICE detected! Requesting permission...")
                            discoveredDevices[device.deviceName] = device
                            updateAvailableCameras()
                            requestUsbPermission(device)
                        } else {
                            Log.i(TAG, "Not a UVC device, ignoring")
                        }
                    }

                    override fun onDisconnect(device: UsbDevice, ctrlBlock: UsbControlBlock) {
                        Log.i(TAG, "USB DISCONNECTED: ${device.deviceName}")
                        handleDeviceDisconnect(device)
                    }

                    override fun onConnect(device: UsbDevice, ctrlBlock: UsbControlBlock, createNew: Boolean) {
                        Log.i(TAG, "═══════════════════════════════════════════════════")
                        Log.i(TAG, "USB CONNECTED (permission granted): ${device.deviceName}")
                        Log.i(TAG, "═══════════════════════════════════════════════════")

                        currentUsbDevice = device
                        currentCtrlBlock = ctrlBlock

                        // Open UVC camera on background thread
                        backgroundHandler?.post {
                            openUVCCamera(device, ctrlBlock)
                        }
                    }

                    override fun onDettach(device: UsbDevice) {
                        Log.i(TAG, "USB DETACHED: ${device.deviceName}")
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

            // Also register BroadcastReceiver for USB permission results
            registerUsbPermissionReceiver()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to register USBMonitor: ${e.message}", e)
        }
    }

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

    // ═══════════════════════════════════════════════════════════════
    // USB Permission — Required for UVC device access
    // ═══════════════════════════════════════════════════════════════

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
                            Log.i(TAG, "USB permission GRANTED for ${device.deviceName}")
                            // USBMonitor handles the connection via onConnect callback
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

    // ═══════════════════════════════════════════════════════════════
    // USB Device Detection — Identify UVC video devices
    // ═══════════════════════════════════════════════════════════════

    private fun isUVCDevice(device: UsbDevice): Boolean {
        // Check by interface class (UVC = Video class)
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            // USB Video Class: Class 14 (0x0E) or Class 239 (0xEF) Subclass 2
            if (iface.interfaceClass == 14 || iface.interfaceClass == 239) {
                Log.i(TAG, "UVC device detected by interface class: ${iface.interfaceClass}")
                return true
            }
        }

        // Check by known Vendor IDs
        val knownVids = intArrayOf(VENDOR_MACROSILICON, VENDOR_FUSHICAI, VENDOR_MAGEWELL)
        if (knownVids.contains(device.vendorId)) {
            Log.i(TAG, "UVC device detected by known VendorID: 0x${Integer.toHexString(device.vendorId)}")
            return true
        }

        // Heuristic: if device has multiple interfaces and isn't a well-known non-UVC type
        if (device.interfaceCount >= 2 && device.vendorId != 0) {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == 239 && iface.interfaceSubclass == 2) {
                    Log.i(TAG, "UVC device detected by class/subclass: 239/2")
                    return true
                }
            }
        }

        return false
    }

    private fun updateAvailableCameras() {
        val cameras = mutableListOf<Pair<String, String>>()

        // Add discovered UVC devices
        for ((name, device) in discoveredDevices) {
            val vid = device.vendorId
            val label = when (vid) {
                VENDOR_MACROSILICON -> "USB: MacroSilicon Capture (0x${Integer.toHexString(vid)})"
                VENDOR_FUSHICAI -> "USB: Fushicai Capture (0x${Integer.toHexString(vid)})"
                VENDOR_MAGEWELL -> "USB: Magewell Capture (0x${Integer.toHexString(vid)})"
                else -> "USB: UVC Device (0x${Integer.toHexString(vid)})"
            }
            cameras.add(name to label)
        }

        _availableCameras.value = cameras
        Log.i(TAG, "Available cameras updated: ${cameras.size} devices")
    }

    // ═══════════════════════════════════════════════════════════════
    // USB Permission Request
    // ═══════════════════════════════════════════════════════════════

    private fun requestUsbPermission(device: UsbDevice) {
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            if (usbManager == null) {
                Log.e(TAG, "UsbManager not available!")
                return
            }

            if (usbManager.hasPermission(device)) {
                Log.i(TAG, "USB permission already granted for ${device.deviceName}")
                // USBMonitor should handle connection automatically
                return
            }

            val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                android.app.PendingIntent.FLAG_MUTABLE
            } else {
                0
            }

            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context, 0,
                Intent(ACTION_USB_PERMISSION),
                flags
            )

            usbManager.requestPermission(device, pendingIntent)
            Log.i(TAG, "USB permission requested for ${device.deviceName}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to request USB permission: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ★★★ OPEN UVC CAMERA — WITH MACROSILICON FIXES ★★★
    // ═══════════════════════════════════════════════════════════════

    private fun openUVCCamera(device: UsbDevice, ctrlBlock: UsbControlBlock) {
        closeUVCCamera()

        Log.i(TAG, "═══════════════════════════════════════════════════")
        Log.i(TAG, "openUVCCamera: ${device.deviceName}")
        Log.i(TAG, "  VID=0x${Integer.toHexString(device.vendorId)} PID=0x${Integer.toHexString(device.productId)}")
        Log.i(TAG, "═══════════════════════════════════════════════════")

        try {
            val camera = UVCCamera()
            uvcCamera = camera

            // ═════════════════════════════════════════════════════
            // STEP 1: Open the camera device
            // ═════════════════════════════════════════════════════
            camera.open(ctrlBlock)
            Log.i(TAG, "★ UVCCamera opened successfully")

            // ═════════════════════════════════════════════════════
            // FIX #2: Set Bandwidth Factor IMMEDIATELY after open
            // MacroSilicon needs bandwidth negotiation fix.
            // Without this, camera opens but receives 0-byte frames.
            // ═════════════════════════════════════════════════════
            camera.setBandwidthFactor(BANDWIDTH_FACTOR)
            Log.i(TAG, "★ Bandwidth factor set to $BANDWIDTH_FACTOR")

            // ═════════════════════════════════════════════════════
            // FIX #1: FORCE MJPEG FORMAT + LOCK 720p
            // MacroSilicon FREEZES on YUYV or > 720p!
            // Try: 1280x720 MJPEG → 640x480 MJPEG → 640x480 YUYV
            // ═════════════════════════════════════════════════════
            try {
                Log.i(TAG, "★ Trying setPreviewSize(${PREVIEW_WIDTH}x${PREVIEW_HEIGHT}, MJPEG)")
                camera.setPreviewSize(PREVIEW_WIDTH, PREVIEW_HEIGHT, PREVIEW_FORMAT)
                Log.i(TAG, "★ Preview size set: ${PREVIEW_WIDTH}x${PREVIEW_HEIGHT} MJPEG")
            } catch (e1: Exception) {
                Log.w(TAG, "1280x720 MJPEG failed: ${e1.message}, trying 640x480 MJPEG...")
                try {
                    camera.setPreviewSize(640, 480, UVCCamera.FRAME_FORMAT_MJPEG)
                    Log.i(TAG, "★ Preview size set: 640x480 MJPEG")
                } catch (e2: Exception) {
                    Log.w(TAG, "640x480 MJPEG failed: ${e2.message}, trying 640x480 YUYV...")
                    try {
                        camera.setPreviewSize(640, 480, UVCCamera.FRAME_FORMAT_YUYV)
                        Log.i(TAG, "★ Preview size set: 640x480 YUYV (last resort)")
                    } catch (e3: Exception) {
                        Log.e(TAG, "ALL preview size attempts failed! ${e3.message}")
                        closeUVCCamera()
                        return
                    }
                }
            }

            // ═════════════════════════════════════════════════════
            // FIX #3: Set the PREVIEW SURFACE before startPreview
            // The TextureView's SurfaceTexture must be available.
            // If not ready yet, we'll set it up when surface is available.
            // ═════════════════════════════════════════════════════
            val tv = textureView
            if (tv != null && tv.isAvailable) {
                setupPreviewSurface(camera, tv)
            } else {
                Log.i(TAG, "TextureView not ready yet — will set up surface when available")
                pendingSurfaceSetup = true
            }

            // Update state
            _isConnected.value = true
            _currentCameraId.value = device.deviceName
            _isUSBCamera.value = true
            _cameraType.value = "external"

            Log.i(TAG, "═══════════════════════════════════════════════════")
            Log.i(TAG, "★ UVC CAMERA READY — streaming should begin")
            Log.i(TAG, "═══════════════════════════════════════════════════")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to open UVCCamera: ${e.message}", e)
            closeUVCCamera()
        }
    }

    /**
     * Set up the preview surface and start streaming.
     * Called when TextureView's surface becomes available.
     */
    private fun setupPreviewSurface(camera: UVCCamera, tv: TextureView) {
        try {
            val surfaceTexture = tv.surfaceTexture
            if (surfaceTexture != null) {
                val surface = Surface(surfaceTexture)
                previewSurface = surface

                // Set the preview surface on the camera
                camera.setPreviewTexture(surfaceTexture)
                Log.i(TAG, "★ Preview texture set on UVCCamera")

                // Also set the preview display surface
                camera.setPreviewDisplay(surface)
                Log.i(TAG, "★ Preview display surface set on UVCCamera")

                // START THE PREVIEW STREAM
                camera.startPreview()
                Log.i(TAG, "★★★ startPreview() called — video stream should now be visible ★★★")
            } else {
                Log.e(TAG, "SurfaceTexture is null even though TextureView is available!")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup preview surface: ${e.message}", e)
        }
    }

    /**
     * Called by the Activity/Compose when the TextureView's surface is ready.
     */
    fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        Log.i(TAG, "onSurfaceTextureAvailable: ${width}x${height}")
        val camera = uvcCamera
        val tv = textureView
        if (camera != null && tv != null) {
            setupPreviewSurface(camera, tv)
        } else {
            pendingSurfaceSetup = true
        }
    }

    /**
     * Called when the TextureView's surface is destroyed.
     */
    fun onSurfaceTextureDestroyed(): Boolean {
        Log.i(TAG, "onSurfaceTextureDestroyed")
        try {
            uvcCamera?.stopPreview()
        } catch (_: Exception) {}
        previewSurface?.release()
        previewSurface = null
        return true
    }

    private fun closeUVCCamera() {
        try {
            uvcCamera?.let { cam ->
                try { cam.stopPreview() } catch (_: Exception) {}
                try { cam.destroy() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        uvcCamera = null
        previewSurface = null
        _isConnected.value = false
        _cameraType.value = "none"
        pendingSurfaceSetup = false
    }

    private fun handleDeviceDisconnect(device: UsbDevice) {
        if (currentUsbDevice?.deviceName == device.deviceName) {
            closeUVCCamera()
            currentUsbDevice = null
            currentCtrlBlock = null
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // TextureView Management
    // ═══════════════════════════════════════════════════════════════

    /**
     * Set the TextureView for camera preview.
     * Called from Compose's AndroidView factory.
     */
    fun setTextureView(tv: TextureView) {
        Log.i(TAG, "setTextureView: new TextureView set")
        this.textureView = tv

        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                Log.i(TAG, "TextureView.SurfaceTexture available: ${width}x${height}")
                onSurfaceTextureAvailable(surface, width, height)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                Log.d(TAG, "TextureView size changed: ${width}x${height}")
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                Log.i(TAG, "TextureView.SurfaceTexture destroyed")
                return onSurfaceTextureDestroyed()
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                // Called on every frame — don't log
            }
        }

        // If camera is already open and surface is available, set it up immediately
        val camera = uvcCamera
        if (camera != null && tv.isAvailable && pendingSurfaceSetup) {
            Log.i(TAG, "Camera already open + TextureView available — setting up preview NOW")
            setupPreviewSurface(camera, tv)
            pendingSurfaceSetup = false
        }
    }

    /**
     * Remove the TextureView reference.
     */
    fun clearTextureView() {
        textureView = null
        previewSurface = null
    }

    // ═══════════════════════════════════════════════════════════════
    // Photo Capture
    // ═══════════════════════════════════════════════════════════════

    /**
     * Capture a photo from the current UVC stream.
     * Strategy:
     * 1. Try TextureView.getBitmap() — simplest, captures rendered frame
     * 2. Fallback: IFrameCallback → NV21 → JPEG → base64
     */
    fun capturePhoto(onResult: (String?) -> Unit) {
        val camera = uvcCamera
        if (camera == null) {
            Log.e(TAG, "capturePhoto: No UVCCamera instance")
            onResult(null)
            return
        }

        // Strategy 1: Capture from TextureView bitmap
        val tv = textureView
        if (tv != null && tv.isAvailable) {
            try {
                val bitmap = tv.bitmap
                if (bitmap != null && !bitmap.isRecycled) {
                    // Check if bitmap is not all black
                    if (!isBitmapBlack(bitmap)) {
                        val outputStream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                        val bytes = outputStream.toByteArray()
                        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        val dataUrl = "data:image/jpeg;base64,$base64"
                        outputStream.close()
                        bitmap.recycle()

                        Log.i(TAG, "Photo captured via TextureView bitmap (${dataUrl.length} chars)")
                        onResult(dataUrl)
                        return
                    } else {
                        Log.w(TAG, "TextureView bitmap is all black, trying IFrameCallback...")
                        bitmap.recycle()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "TextureView bitmap capture failed: ${e.message}")
            }
        }

        // Strategy 2: IFrameCallback
        captureFromFrameCallback(camera, onResult)
    }

    /**
     * Check if a bitmap is entirely black (all pixels = 0).
     */
    private fun isBitmapBlack(bitmap: Bitmap): Boolean {
        try {
            val w = bitmap.width
            val h = bitmap.height
            // Sample pixels instead of checking all
            val stepX = maxOf(1, w / 20)
            val stepY = maxOf(1, h / 20)
            var blackCount = 0
            var totalCount = 0

            for (x in 0 until w step stepX) {
                for (y in 0 until h step stepY) {
                    val pixel = bitmap.getPixel(x, y)
                    totalCount++
                    // Check if pixel is very dark (R+G+B < 30)
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    if (r + g + b < 30) {
                        blackCount++
                    }
                }
            }

            // If > 95% of sampled pixels are black, consider it a black frame
            val blackRatio = blackCount.toFloat() / totalCount.toFloat()
            Log.d(TAG, "Black pixel ratio: $blackRatio (${blackCount}/${totalCount})")
            return blackRatio > 0.95f
        } catch (e: Exception) {
            Log.e(TAG, "isBitmapBlack check failed: ${e.message}")
            return false
        }
    }

    /**
     * Capture via IFrameCallback (raw frame data → NV21 → JPEG → base64).
     */
    private fun captureFromFrameCallback(camera: UVCCamera, callback: (String?) -> Unit) {
        captureCallback = callback

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
                        // Convert ByteBuffer to ByteArray
                        val frameBytes = ByteArray(frame.remaining())
                        frame.get(frameBytes)

                        // Decode as NV21 → JPEG
                        val yuvImage = android.graphics.YuvImage(
                            frameBytes, ImageFormat.NV21,
                            PREVIEW_WIDTH, PREVIEW_HEIGHT, null
                        )
                        val outputStream = ByteArrayOutputStream()
                        yuvImage.compressToJpeg(
                            Rect(0, 0, PREVIEW_WIDTH, PREVIEW_HEIGHT), 95, outputStream
                        )
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

            // Timeout — if no frame received in 3 seconds
            backgroundHandler?.postDelayed({
                if (captureCallback != null) {
                    Log.w(TAG, "IFrameCallback timed out")
                    try {
                        camera.setFrameCallback(null, 0)
                    } catch (_: Exception) {}

                    // Last attempt: TextureView bitmap
                    val tv = textureView
                    if (tv != null && tv.isAvailable) {
                        try {
                            val bitmap = tv.bitmap
                            if (bitmap != null && !bitmap.isRecycled) {
                                val outputStream = ByteArrayOutputStream()
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                                val bytes = outputStream.toByteArray()
                                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                                val dataUrl = "data:image/jpeg;base64,$base64"
                                outputStream.close()
                                bitmap.recycle()

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
            }, 3000)

        } catch (e: Exception) {
            Log.e(TAG, "IFrameCallback setup failed: ${e.message}")
            captureCallback = null
            callback(null)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Camera Switching / Rescan
    // ═══════════════════════════════════════════════════════════════

    fun switchCamera(deviceId: String) {
        val device = discoveredDevices[deviceId]
        if (device != null) {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            if (usbManager?.hasPermission(device) == true) {
                // Force re-connect to this device
                backgroundHandler?.post {
                    closeUVCCamera()
                    // USBMonitor should handle reconnection
                    // Or manually trigger:
                    usbMonitor?.requestPermission(device)
                }
            } else {
                requestUsbPermission(device)
            }
        }
    }

    fun forceRescan() {
        Log.i(TAG, "forceRescan: Re-registering USBMonitor")
        try {
            unregisterUsbMonitor()
            discoveredDevices.clear()
            updateAvailableCameras()
            registerUsbMonitor()

            // Also enumerate currently connected devices
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            usbManager?.deviceList?.values?.forEach { device ->
                if (isUVCDevice(device)) {
                    Log.i(TAG, "forceRescan: Found UVC device: ${device.deviceName}")
                    discoveredDevices[device.deviceName] = device
                    if (!usbManager.hasPermission(device)) {
                        requestUsbPermission(device)
                    }
                }
            }
            updateAvailableCameras()
        } catch (e: Exception) {
            Log.e(TAG, "forceRescan failed: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Compatibility Methods (matching WebViewCameraManager interface)
    // ═══════════════════════════════════════════════════════════════

    fun showPreview() {
        Log.i(TAG, "showPreview — preview is always visible via TextureView")
    }

    fun hidePreview() {
        Log.i(TAG, "hidePreview — TextureView stays active for streaming")
    }

    fun switchToCamera(cameraId: String) = switchCamera(cameraId)
    fun hasCameraPermission(): Boolean = true
    fun refreshCameraList() = forceRescan()
    fun forceSwitchToUSB() = forceRescan()

    fun setFilter(preset: String) {
        Log.d(TAG, "setFilter: $preset (not yet implemented for UVCCamera)")
    }

    fun setFrameOverlay(base64Data: String?) {
        Log.d(TAG, "setFrameOverlay: ${if (base64Data != null) "${base64Data.length} chars" else "null"} (not yet implemented for UVCCamera)")
    }

    fun updateConfig(aspectRatio: Double, filterPreset: String) {
        setFilter(filterPreset)
    }

    /**
     * Get the USBMonitor instance. The Activity needs this to handle
     * USB permission results and device events.
     */
    fun getUSBMonitor(): USBMonitor? = usbMonitor

    /**
     * Called from Activity when USB permission result is received.
     */
    fun onUsbPermissionResult(device: UsbDevice, granted: Boolean) {
        Log.i(TAG, "onUsbPermissionResult: ${device.deviceName}, granted=$granted")
        if (granted) {
            // USBMonitor should handle this via onConnect,
            // but just in case, trigger it manually
            val ctrlBlock = currentCtrlBlock
            if (ctrlBlock != null) {
                backgroundHandler?.post {
                    openUVCCamera(device, ctrlBlock)
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Lifecycle — Destroy
    // ═══════════════════════════════════════════════════════════════

    fun destroy() {
        Log.i(TAG, "destroy: Cleaning up UVCCamera")
        closeUVCCamera()
        unregisterUsbMonitor()
        unregisterUsbPermissionReceiver()
        stopBackgroundThread()
        discoveredDevices.clear()
        _isConnected.value = false
        _cameraType.value = "none"
        _availableCameras.value = emptyList()
        textureView = null
        previewSurface = null
    }
}
