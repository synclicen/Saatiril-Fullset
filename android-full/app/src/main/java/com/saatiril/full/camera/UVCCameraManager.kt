package com.saatiril.full.camera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
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
 * UVCCamera Manager — v18 Hardened (MacroSilicon Black Screen Fix)
 * ═════════════════════════════════════════════════════════════════════════
 *
 * BUG FIXES from v17 → v18:
 *
 * BUG #1 (CRASH): setBandwidthFactor(1.0f) DOES NOT EXIST in org.uvccamera:lib!
 *   → Use setPreviewSize(w, h, minFps, maxFps, format, bandwidthFactor) instead
 *   → This was causing compilation error or NoSuchMethodError at runtime
 *
 * BUG #2 (DEVICE NOT DETECTED): MacroSilicon VID in device_filter.xml was 3141
 *   → Should be 13407 (0x345F in decimal). 3141 = 0xC45 ≠ MacroSilicon
 *   → Fixed in device_filter.xml
 *
 * BUG #3 (DOUBLE SURFACE): Called both setPreviewTexture() and setPreviewDisplay()
 *   → These conflict. Use setPreviewDisplay(Surface) only for TextureView
 *   → Create Surface from SurfaceTexture, pass to setPreviewDisplay()
 *
 * BUG #4 (THREAD VIOLATION): setupPreviewSurface called from background thread
 *   → Surface/UI operations MUST be on main thread
 *   → Use Handler(Looper.getMainLooper()).post() for surface setup
 *
 * BUG #5 (RECOMPOSITION): AndroidView factory re-creates TextureView
 *   → Added remember + key to prevent unnecessary recreation
 *
 * FIX #1: FORCE MJPEG + LOCK 720p (MacroSilicon freezes on YUYV)
 * FIX #2: Bandwidth factor via setPreviewSize overload (1.0f = full)
 * FIX #3: Proper preview surface — setPreviewDisplay(Surface) then startPreview()
 */
class UVCCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "UVCCameraManager"

        // ═══════════════════════════════════════════════════════════
        // FIX #1: Resolution, Format, FPS for MacroSilicon
        // MUST use MJPEG, MUST start at 720p, MUST cap at 30fps
        // ═══════════════════════════════════════════════════════════
        private const val PREVIEW_WIDTH = 1280
        private const val PREVIEW_HEIGHT = 720
        private const val PREVIEW_MIN_FPS = 1
        private const val PREVIEW_MAX_FPS = 30
        private const val PREVIEW_FORMAT = UVCCamera.FRAME_FORMAT_MJPEG  // NEVER YUYV!

        // ═══════════════════════════════════════════════════════════
        // FIX #2: Bandwidth Factor for MacroSilicon
        // Passed as 6th param to setPreviewSize(w, h, minFps, maxFps, format, bandwidth)
        // 1.0f = full bandwidth. If still black, try 0.5f.
        // ═══════════════════════════════════════════════════════════
        private const val BANDWIDTH_FACTOR = 1.0f

        // Known UVC Vendor IDs (decimal values)
        private const val VENDOR_MACROSILICON = 0x345F  // 13407 decimal — JASOZ capture card
        private const val VENDOR_FUSHICAI = 0x0489       // 1161 decimal
        private const val VENDOR_MAGEWELL = 0x0416       // 1046 decimal

        const val ACTION_USB_PERMISSION = "com.saatiril.full.USB_PERMISSION"
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
    private val mainHandler = Handler(Looper.getMainLooper())

    // ─── Capture ────────────────────────────────────────────────
    private var captureCallback: ((String?) -> Unit)? = null

    // ─── USB Permission ─────────────────────────────────────────
    private var usbPermissionReceiver: BroadcastReceiver? = null

    // ─── Discovered Devices ─────────────────────────────────────
    private val discoveredDevices = mutableMapOf<String, UsbDevice>()

    // ─── State tracking ─────────────────────────────────────────
    private var pendingSurfaceSetup = false
    private var isInitialized = false
    private var isDestroying = false

    // ── Android 14+ USB polling fallback ──
    // On Android 14 (API 34), the USBMonitor library's internal registerReceiver()
    // may fail with SecurityException (missing RECEIVER_EXPORTED flag).
    // This polling mechanism detects USB devices as a fallback.
    private var usbPollingRunnable: Runnable? = null
    private val USB_POLLING_INTERVAL_MS = 2000L // Check every 2 seconds

    /**
     * Initialize the UVC camera system.
     * Called once when the Activity is created.
     * Guard against multiple calls.
     */
    fun initCamera() {
        if (isInitialized) {
            Log.i(TAG, "initCamera: already initialized, skipping")
            return
        }
        isInitialized = true
        isDestroying = false

        Log.i(TAG, "═══════════════════════════════════════════════════")
        Log.i(TAG, "initCamera: v18 UVCCamera Direct (MacroSilicon Fix)")
        Log.i(TAG, "═══════════════════════════════════════════════════")

        startBackgroundThread()
        registerUsbMonitor()
    }

    // ═══════════════════════════════════════════════════════════════
    // Background Thread (UVC open/close MUST be on background thread)
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
            backgroundThread?.join(2000)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping background thread: ${e.message}")
        }
        backgroundThread = null
        backgroundHandler = null
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
                        Log.i(TAG, "  createNew=$createNew")
                        Log.i(TAG, "═══════════════════════════════════════════════════")

                        currentUsbDevice = device
                        currentCtrlBlock = ctrlBlock

                        // Open UVC camera on background thread
                        backgroundHandler?.post {
                            openUVCCamera(device, ctrlBlock)
                        }
                    }

                    // NOTE: onDettach (double-t) is the correct spelling in this library
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

            // Register BroadcastReceiver for USB permission results
            registerUsbPermissionReceiver()

            // Enumerate already-connected USB devices
            enumerateConnectedDevices()

            // ── Android 14+ USB polling fallback ──
            // The USBMonitor library may fail to register its internal
            // BroadcastReceiver on Android 14+ (SecurityException due to
            // missing RECEIVER_EXPORTED flag). This polling mechanism
            // detects USB devices as a fallback by checking UsbManager.deviceList
            // every 2 seconds. It's a no-op if USBMonitor is working correctly
            // (devices already discovered are skipped).
            startUsbPolling()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to register USBMonitor: ${e.message}", e)
        }
    }

    /**
     * Enumerate USB devices that are already connected when app starts.
     */
    private fun enumerateConnectedDevices() {
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            if (usbManager == null) {
                Log.e(TAG, "UsbManager not available for enumeration")
                return
            }

            for (device in usbManager.deviceList.values) {
                Log.i(TAG, "Enumerating: ${device.deviceName} vid=0x${Integer.toHexString(device.vendorId)} pid=0x${Integer.toHexString(device.productId)}")
                if (isUVCDevice(device)) {
                    Log.i(TAG, "★ Already-connected UVC device found: ${device.deviceName}")
                    discoveredDevices[device.deviceName] = device
                    if (!usbManager.hasPermission(device)) {
                        requestUsbPermission(device)
                    } else {
                        // Permission already granted — on Android 14+, USBMonitor's
                        // onConnect may not fire, so open camera directly
                        tryOpenUVCCameraDirect(device)
                    }
                }
            }
            updateAvailableCameras()
        } catch (e: Exception) {
            Log.e(TAG, "Device enumeration failed: ${e.message}")
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
    // Android 14+ USB Polling Fallback
    // ═══════════════════════════════════════════════════════════════
    // On Android 14 (API 34), the USBMonitor library's internal
    // registerReceiver() may throw SecurityException due to missing
    // RECEIVER_EXPORTED/RECEIVER_NOT_EXPORTED flag. This causes
    // USB device detection to silently fail — no onAttach callback,
    // no permission dialog, no camera detected.
    //
    // This polling mechanism checks UsbManager.deviceList every 2
    // seconds as a fallback. It's a no-op if USBMonitor is working
    // correctly (devices already discovered are skipped).
    // ═══════════════════════════════════════════════════════════════

    private fun startUsbPolling() {
        stopUsbPolling() // Clear any existing polling
        usbPollingRunnable = object : Runnable {
            override fun run() {
                if (isDestroying) return
                try {
                    pollUsbDevices()
                } catch (e: Exception) {
                    Log.e(TAG, "USB polling error: ${e.message}")
                }
                // Schedule next poll
                usbPollingRunnable?.let {
                    mainHandler.postDelayed(it, USB_POLLING_INTERVAL_MS)
                }
            }
        }
        // Start polling after a short delay
        usbPollingRunnable?.let { mainHandler.postDelayed(it, USB_POLLING_INTERVAL_MS) }
        Log.i(TAG, "USB polling fallback started (every ${USB_POLLING_INTERVAL_MS}ms)")
    }

    private fun stopUsbPolling() {
        usbPollingRunnable?.let { mainHandler.removeCallbacks(it) }
        usbPollingRunnable = null
    }

    /**
     * Poll UsbManager.deviceList for new UVC devices.
     * This is a fallback for Android 14+ where USBMonitor's BroadcastReceiver
     * may fail to register. It's idempotent — skips already-discovered devices.
     */
    private fun pollUsbDevices() {
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
            var newDeviceFound = false

            for (device in usbManager.deviceList.values) {
                if (discoveredDevices.containsKey(device.deviceName)) continue // Already known

                if (isUVCDevice(device)) {
                    Log.i(TAG, "★ Polling found NEW UVC device: ${device.deviceName} vid=0x${Integer.toHexString(device.vendorId)}")
                    discoveredDevices[device.deviceName] = device
                    newDeviceFound = true

                    // Request permission if not already granted
                    if (!usbManager.hasPermission(device)) {
                        requestUsbPermission(device)
                    } else {
                        // Permission already granted — try to open the camera
                        // The USBMonitor's onConnect won't fire if it's broken,
                        // so we need to manually trigger the camera open
                        tryOpenUVCCameraWithPermission(device)
                    }
                }
            }

            // Check for disconnected devices (plugged out since last poll)
            val connectedNames = usbManager.deviceList.keys
            val removedKeys = discoveredDevices.keys.filter { it !in connectedNames }
            for (key in removedKeys) {
                val device = discoveredDevices.remove(key)
                if (device != null) {
                    Log.i(TAG, "Polling detected USB DISCONNECT: ${device.deviceName}")
                    handleDeviceDisconnect(device)
                    newDeviceFound = true
                }
            }

            if (newDeviceFound) {
                updateAvailableCameras()
            }
        } catch (e: Exception) {
            Log.e(TAG, "USB polling failed: ${e.message}")
        }
    }

    /**
     * Try to open a UVC camera when permission is already granted.
     * This is needed when the USBMonitor's onConnect callback doesn't fire
     * (e.g., on Android 14 where the library's BroadcastReceiver fails).
     */
    private fun tryOpenUVCCameraWithPermission(device: UsbDevice) {
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
            if (!usbManager.hasPermission(device)) return

            // On Android 14+, USBMonitor's onConnect may never fire because
            // the library's internal registerReceiver() throws SecurityException.
            // Try direct open first (bypasses USBMonitor entirely).
            tryOpenUVCCameraDirect(device)
        } catch (e: Exception) {
            Log.e(TAG, "tryOpenUVCCameraWithPermission error: ${e.message}")
        }
    }

    /**
     * Directly open a UVC camera when USB permission is already granted.
     * On Android 14+, USBMonitor's internal BroadcastReceiver may fail due to
     * missing RECEIVER_EXPORTED flag. This method uses USBMonitor.requestPermission()
     * on an already-permitted device, which internally calls processConnect() → onConnect()
     * WITHOUT needing the BroadcastReceiver. This is the key workaround for Android 14.
     */
    private fun tryOpenUVCCameraDirect(device: UsbDevice) {
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
            if (!usbManager.hasPermission(device)) return

            // Don't open if already connected to this device
            if (currentUsbDevice?.deviceName == device.deviceName && _isConnected.value) {
                Log.i(TAG, "tryOpenUVCCameraDirect: already connected to ${device.deviceName}")
                return
            }

            val monitor = usbMonitor
            if (monitor != null) {
                // KEY INSIGHT: USBMonitor.requestPermission() on an already-permitted device
                // calls processConnect() internally, which creates a UsbControlBlock and
                // triggers onConnect(). This works even when the BroadcastReceiver is broken
                // on Android 14 because processConnect() doesn't need the BroadcastReceiver.
                Log.i(TAG, "tryOpenUVCCameraDirect: calling monitor.requestPermission() for already-permitted device")
                backgroundHandler?.post {
                    try {
                        monitor.requestPermission(device)
                    } catch (e: Exception) {
                        Log.e(TAG, "USBMonitor.requestPermission() failed on already-permitted device: ${e.message}")
                        // Last resort: try to force open via UsbManager.openDevice()
                        tryForceOpenViaUsbManager(device)
                    }
                }
            } else {
                Log.e(TAG, "tryOpenUVCCameraDirect: No USBMonitor available")
                tryForceOpenViaUsbManager(device)
            }
        } catch (e: Exception) {
            Log.e(TAG, "tryOpenUVCCameraDirect error: ${e.message}")
        }
    }

    /**
     * Last-resort fallback: try to open camera by registering a fresh USBMonitor
     * and requesting permission. This is for the case where USBMonitor is completely
     * broken on Android 14 MIUI.
     */
    private fun tryForceOpenViaUsbManager(device: UsbDevice) {
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
            if (!usbManager.hasPermission(device)) return

            // Don't open if already connected to this device
            if (currentUsbDevice?.deviceName == device.deviceName && _isConnected.value) {
                return
            }

            Log.i(TAG, "tryForceOpenViaUsbManager: Attempting re-registration of USBMonitor")
            // Try re-registering the USBMonitor — this might work on second attempt
            try {
                unregisterUsbMonitor()
                isRegistered = false
                registerUsbMonitor()
            } catch (e: Exception) {
                Log.e(TAG, "Re-registration failed: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "tryForceOpenViaUsbManager error: ${e.message}")
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
                            // BUG FIX: On Android 14+, USBMonitor's onConnect may never fire
                            // because the library's internal registerReceiver() throws
                            // SecurityException. We MUST open the camera directly here.
                            tryOpenUVCCameraDirect(device)
                        } else if (device != null) {
                            Log.w(TAG, "USB permission DENIED for ${device.deviceName}")
                        } else {
                            // No device in intent — try to find device from polling
                            Log.w(TAG, "USB permission result: no device in intent extras")
                        }
                    }
                }
            }
        }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        // ── Android 14+ (API 34) requires RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED ──
        // Without this flag, registerReceiver() throws SecurityException on Android 14+,
        // which silently prevents USB permission dialogs from working.
        // This receiver is NOT exported (only receives our own PendingIntent broadcasts).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbPermissionReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbPermissionReceiver, filter)
        }
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

        // Heuristic: class 239 subclass 2
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
                // On Android 14+, USBMonitor's onConnect may not fire due to
                // registerReceiver() SecurityException. Open camera directly.
                tryOpenUVCCameraDirect(device)
                return
            }

            // FIX: Add FLAG_UPDATE_CURRENT to ensure PendingIntent contains correct
            // UsbDevice extras. Without it, MIUI may cache a stale PendingIntent
            // without the device, causing the permission dialog to not appear.
            val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            }

            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context, device.deviceName.hashCode(),
                Intent(ACTION_USB_PERMISSION).apply {
                    putExtra(UsbManager.EXTRA_DEVICE, device)
                },
                flags
            )

            usbManager.requestPermission(device, pendingIntent)
            Log.i(TAG, "USB permission requested for ${device.deviceName}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to request USB permission: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ★★★ OPEN UVC CAMERA — WITH ALL MACROSILICON FIXES ★★★
    // ═══════════════════════════════════════════════════════════════

    private fun openUVCCamera(device: UsbDevice, ctrlBlock: UsbControlBlock) {
        if (isDestroying) {
            Log.w(TAG, "openUVCCamera: skipping, manager is destroying")
            return
        }

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
            // FIX #1 + FIX #2 COMBINED:
            // Use setPreviewSize(w, h, minFps, maxFps, format, bandwidthFactor)
            // This is the ONLY way to set bandwidth in org.uvccamera:lib!
            // setBandwidthFactor() DOES NOT EXIST in this library.
            //
            // MacroSilicon: MJPEG forced, 720p, max 30fps, bandwidth 1.0f
            // ═════════════════════════════════════════════════════
            try {
                Log.i(TAG, "★ Trying setPreviewSize(${PREVIEW_WIDTH}x${PREVIEW_HEIGHT}, ${PREVIEW_MIN_FPS}-${PREVIEW_MAX_FPS}fps, MJPEG, bw=${BANDWIDTH_FACTOR})")
                camera.setPreviewSize(
                    PREVIEW_WIDTH, PREVIEW_HEIGHT,
                    PREVIEW_MIN_FPS, PREVIEW_MAX_FPS,
                    PREVIEW_FORMAT, BANDWIDTH_FACTOR
                )
                Log.i(TAG, "★ Preview size set: ${PREVIEW_WIDTH}x${PREVIEW_HEIGHT} MJPEG bw=${BANDWIDTH_FACTOR}")
            } catch (e1: Exception) {
                Log.w(TAG, "1280x720 MJPEG bw=1.0 failed: ${e1.message}, trying without bw param...")
                try {
                    // Fallback: simpler overload without bandwidth
                    camera.setPreviewSize(PREVIEW_WIDTH, PREVIEW_HEIGHT, PREVIEW_FORMAT)
                    Log.i(TAG, "★ Preview size set: ${PREVIEW_WIDTH}x${PREVIEW_HEIGHT} MJPEG (no bw)")
                } catch (e2: Exception) {
                    Log.w(TAG, "1280x720 MJPEG failed: ${e2.message}, trying 640x480 MJPEG...")
                    try {
                        camera.setPreviewSize(640, 480, PREVIEW_MIN_FPS, PREVIEW_MAX_FPS, UVCCamera.FRAME_FORMAT_MJPEG, BANDWIDTH_FACTOR)
                        Log.i(TAG, "★ Preview size set: 640x480 MJPEG bw=${BANDWIDTH_FACTOR}")
                    } catch (e3: Exception) {
                        Log.w(TAG, "640x480 MJPEG bw failed: ${e3.message}, trying 640x480 simple...")
                        try {
                            camera.setPreviewSize(640, 480, UVCCamera.FRAME_FORMAT_MJPEG)
                            Log.i(TAG, "★ Preview size set: 640x480 MJPEG (no bw)")
                        } catch (e4: Exception) {
                            Log.e(TAG, "ALL preview size attempts failed! ${e4.message}")
                            closeUVCCamera()
                            return
                        }
                    }
                }
            }

            // ═════════════════════════════════════════════════════
            // FIX #3: Set the PREVIEW SURFACE before startPreview
            // Use setPreviewDisplay(Surface) — NOT setPreviewTexture
            // Surface is created from TextureView's SurfaceTexture
            // ═════════════════════════════════════════════════════
            val tv = textureView
            if (tv != null && tv.isAvailable) {
                // BUG #4 FIX: Must set up surface on MAIN thread
                mainHandler.post {
                    setupPreviewSurface(camera, tv)
                }
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
            Log.i(TAG, "★ UVC CAMERA READY — waiting for surface to start preview")
            Log.i(TAG, "═══════════════════════════════════════════════════")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to open UVCCamera: ${e.message}", e)
            closeUVCCamera()
        }
    }

    /**
     * Set up the preview surface and start streaming.
     * MUST be called on the MAIN thread (UI thread).
     */
    private fun setupPreviewSurface(camera: UVCCamera, tv: TextureView) {
        try {
            val surfaceTexture = tv.surfaceTexture
            if (surfaceTexture == null) {
                Log.e(TAG, "SurfaceTexture is null even though TextureView is available!")
                return
            }

            // Create Surface from SurfaceTexture
            val surface = Surface(surfaceTexture)
            previewSurface = surface

            // Use setPreviewDisplay(Surface) — the correct method for TextureView
            // Do NOT also call setPreviewTexture() — they conflict
            camera.setPreviewDisplay(surface)
            Log.i(TAG, "★ setPreviewDisplay(Surface) called")

            // START THE PREVIEW STREAM
            camera.startPreview()
            Log.i(TAG, "★★★ startPreview() called — video stream should now be visible ★★★")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup preview surface: ${e.message}", e)
            // Try alternative: setPreviewTexture instead
            try {
                val surfaceTexture = tv.surfaceTexture
                if (surfaceTexture != null) {
                    camera.setPreviewTexture(surfaceTexture)
                    Log.i(TAG, "★ Fallback: setPreviewTexture() called")
                    camera.startPreview()
                    Log.i(TAG, "★★★ startPreview() called after fallback ★★★")
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback setPreviewTexture also failed: ${e2.message}")
            }
        }
    }

    /**
     * Called by TextureView.SurfaceTextureListener when surface becomes available.
     */
    fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        Log.i(TAG, "onSurfaceTextureAvailable: ${width}x${height}")
        val camera = uvcCamera
        val tv = textureView
        if (camera != null && tv != null && pendingSurfaceSetup) {
            pendingSurfaceSetup = false
            // BUG #4 FIX: Run on main thread
            mainHandler.post {
                setupPreviewSurface(camera, tv)
            }
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
        pendingSurfaceSetup = true  // Will need to re-setup when surface returns
        return true  // Return true = we handled it, surface can be destroyed
    }

    private fun closeUVCCamera() {
        try {
            uvcCamera?.let { cam ->
                try { cam.stopPreview() } catch (_: Exception) {}
                try { cam.close() } catch (_: Exception) {}
                try { cam.destroy() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        uvcCamera = null
        previewSurface?.release()
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
        Log.i(TAG, "setTextureView: new TextureView set (available=${tv.isAvailable})")

        // Clean up old surface
        previewSurface?.release()
        previewSurface = null

        this.textureView = tv

        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                Log.i(TAG, "TextureView.SurfaceTexture available: ${width}x${height}")
                this@UVCCameraManager.onSurfaceTextureAvailable(surface, width, height)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                Log.d(TAG, "TextureView size changed: ${width}x${height}")
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                Log.i(TAG, "TextureView.SurfaceTexture destroyed")
                return this@UVCCameraManager.onSurfaceTextureDestroyed()
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                // Called on every frame — don't log
            }
        }

        // If camera is already open and surface is available, set it up immediately
        val camera = uvcCamera
        if (camera != null && tv.isAvailable) {
            Log.i(TAG, "Camera already open + TextureView available — setting up preview NOW")
            pendingSurfaceSetup = false
            mainHandler.post {
                setupPreviewSurface(camera, tv)
            }
        }
    }

    /**
     * Remove the TextureView reference.
     */
    fun clearTextureView() {
        textureView = null
        previewSurface?.release()
        previewSurface = null
    }

    // ═══════════════════════════════════════════════════════════════
    // Photo Capture
    // ═══════════════════════════════════════════════════════════════

    /**
     * Get a bitmap from the current camera preview (for hand detection).
     * Returns a downscaled bitmap to save CPU, or null if not available.
     */
    fun getPreviewBitmap(): Bitmap? {
        val tv = textureView ?: return null
        return try {
            if (tv.isAvailable && tv.width > 0 && tv.height > 0) {
                // Downscale to 320px width for hand detection (saves CPU)
                val scale = 320f / tv.width
                val w = 320
                val h = (tv.height * scale).toInt()
                tv.getBitmap(w, h)
            } else null
        } catch (e: Exception) {
            null
        }
    }

    fun capturePhoto(onResult: (String?) -> Unit) {
        val camera = uvcCamera
        if (camera == null) {
            Log.e(TAG, "capturePhoto: No UVCCamera instance")
            onResult(null)
            return
        }

        // Strategy 1: TextureView.getBitmap()
        val tv = textureView
        if (tv != null && tv.isAvailable) {
            try {
                val bitmap = tv.bitmap
                if (bitmap != null && !bitmap.isRecycled) {
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

    private fun isBitmapBlack(bitmap: Bitmap): Boolean {
        try {
            val w = bitmap.width
            val h = bitmap.height
            val stepX = maxOf(1, w / 20)
            val stepY = maxOf(1, h / 20)
            var blackCount = 0
            var totalCount = 0

            for (x in 0 until w step stepX) {
                for (y in 0 until h step stepY) {
                    val pixel = bitmap.getPixel(x, y)
                    totalCount++
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    if (r + g + b < 30) {
                        blackCount++
                    }
                }
            }

            val blackRatio = blackCount.toFloat() / totalCount.toFloat()
            return blackRatio > 0.95f
        } catch (e: Exception) {
            Log.e(TAG, "isBitmapBlack check failed: ${e.message}")
            return false
        }
    }

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
                        val frameBytes = ByteArray(frame.remaining())
                        frame.get(frameBytes)

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
                        outputStream.close()

                        Log.i(TAG, "Photo captured via IFrameCallback (${dataUrl.length} chars)")

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

            // Timeout
            backgroundHandler?.postDelayed({
                if (captureCallback != null) {
                    Log.w(TAG, "IFrameCallback timed out")
                    try { camera.setFrameCallback(null, 0) } catch (_: Exception) {}

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
                backgroundHandler?.post {
                    closeUVCCamera()
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
            stopUsbPolling()
            unregisterUsbMonitor()
            discoveredDevices.clear()
            updateAvailableCameras()
            registerUsbMonitor()
        } catch (e: Exception) {
            Log.e(TAG, "forceRescan failed: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Compatibility Methods
    // ═══════════════════════════════════════════════════════════════

    fun showPreview() { Log.i(TAG, "showPreview — preview always visible via TextureView") }
    fun hidePreview() { Log.i(TAG, "hidePreview — TextureView stays active for streaming") }
    fun switchToCamera(cameraId: String) = switchCamera(cameraId)
    fun hasCameraPermission(): Boolean = true
    fun refreshCameraList() = forceRescan()
    fun forceSwitchToUSB() = forceRescan()
    fun setFilter(preset: String) { Log.d(TAG, "setFilter: $preset") }
    fun setFrameOverlay(base64Data: String?) { Log.d(TAG, "setFrameOverlay: ${base64Data != null}") }
    fun updateConfig(aspectRatio: Double, filterPreset: String) { setFilter(filterPreset) }
    fun getUSBMonitor(): USBMonitor? = usbMonitor

    fun onUsbPermissionResult(device: UsbDevice, granted: Boolean) {
        Log.i(TAG, "onUsbPermissionResult: ${device.deviceName}, granted=$granted")
        // USBMonitor's onConnect handles the actual camera opening
    }

    // ═══════════════════════════════════════════════════════════════
    // Lifecycle — Destroy
    // ═══════════════════════════════════════════════════════════════

    fun destroy() {
        Log.i(TAG, "destroy: Cleaning up UVCCamera")
        isDestroying = true
        isInitialized = false
        stopUsbPolling()
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
