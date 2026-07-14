package com.saatiril.operator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.FrameLayout
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.saatiril.operator.data.ConnectionState
import com.saatiril.operator.data.OperatorViewModel
import com.saatiril.operator.ui.operator.OperatorScreen
import com.saatiril.operator.ui.connection.ConnectionScreen

/**
 * ═════════════════════════════════════════════════════════════════════════
 * v14: Camera2 Direct Architecture (USB Capture Card Support)
 * ═════════════════════════════════════════════════════════════════════════
 *
 * Replaced WebView+getUserMedia with Camera2 API + TextureView.
 * WebView/getUserMedia CANNOT access USB capture cards on Android.
 * Camera2 API with LENS_FACING_EXTERNAL is the correct way to access them.
 *
 * Layout structure:
 * ┌──────────────────────────────┐
 * │  FrameLayout (root)          │
 * │  ┌────────────────────────┐  │
 * │  │ TextureView (preview)  │  │  ← Bottom layer, camera preview
 * │  └────────────────────────┘  │
 * │  ┌────────────────────────┐  │
 * │  │ ComposeView (UI)       │  │  ← Top layer, transparent background
 * │  │ ConnectionScreen /     │  │     on OperatorScreen so camera shows
 * │  │ OperatorScreen         │  │     through
 * │  └────────────────────────┘  │
 * └──────────────────────────────┘
 *
 * This guarantees:
 * 1. Camera2 API directly accesses USB capture cards via LENS_FACING_EXTERNAL
 * 2. TextureView renders camera preview in real-time
 * 3. Compose UI overlays on top of camera preview
 * 4. On ConnectionScreen: Compose has opaque background → camera hidden
 * 5. On OperatorScreen: Compose has transparent areas → camera visible
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        const val ACTION_USB_PERMISSION = "com.saatiril.operator.USB_PERMISSION"
    }

    private lateinit var viewModel: OperatorViewModel
    private lateinit var rootFrameLayout: FrameLayout

    private var _cameraPermissionGranted = false
    val cameraPermissionGranted: Boolean
        get() = _cameraPermissionGranted

    private var onCameraPermissionGranted: (() -> Unit)? = null

    fun setOnCameraPermissionGrantedListener(callback: (() -> Unit)?) {
        onCameraPermissionGranted = callback
        if (_cameraPermissionGranted && callback != null) {
            callback()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false

        if (cameraGranted) {
            Log.i(TAG, "Camera permission GRANTED")
            _cameraPermissionGranted = true
            onCameraPermissionGranted?.invoke()
            // Initialize camera via Camera2 API
            viewModel.initCamera()
        }

        val allGranted = permissions.all { it.value }
        if (allGranted) {
            Log.i(TAG, "All permissions granted")
        } else {
            Log.w(TAG, "Some permissions denied: $permissions")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Initialize ViewModel
        viewModel = ViewModelProvider(this)[OperatorViewModel::class.java]

        // Check existing camera permission
        _cameraPermissionGranted = (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED)

        // ═══════════════════════════════════════════════════════════
        // CRITICAL: Create root FrameLayout BEFORE setContent.
        // The TextureView goes in as the bottom layer, ComposeView
        // goes in as the top layer. Both are ALWAYS in the window.
        // ═══════════════════════════════════════════════════════════
        rootFrameLayout = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Create the TextureView for Camera2 preview and add to root as bottom layer
        val textureView = TextureView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootFrameLayout.addView(textureView, 0)
        viewModel.cameraManager.setTextureView(textureView)

        // Detect USB capture card presence
        val usbCaptureCardDetected = detectUsbCaptureCard()
        if (usbCaptureCardDetected) {
            Log.i(TAG, "═══════════════════════════════════════════════════")
            Log.i(TAG, "USB CAPTURE CARD DETECTED at startup!")
            Log.i(TAG, "═══════════════════════════════════════════════════")
            viewModel.setUvcDeviceAttached(true)
        }

        // Request permissions
        requestPermissions()

        // Handle USB device attachment
        handleUsbIntent(intent)

        // Set content with the root FrameLayout containing TextureView + Compose
        setContent {
            // Add ComposeView on top of the TextureView
            // This is automatically added as a child of the root layout
            SaatirilOperatorApp(viewModel, this, rootFrameLayout)
        }

        // If camera permission already granted, init camera immediately
        if (_cameraPermissionGranted) {
            viewModel.initCamera()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleUsbIntent(it) }
    }

    override fun onResume() {
        super.onResume()
        val nowGranted = (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED)
        if (nowGranted != _cameraPermissionGranted) {
            Log.i(TAG, "Camera permission state changed: $_cameraPermissionGranted → $nowGranted")
            _cameraPermissionGranted = nowGranted
            if (nowGranted) {
                onCameraPermissionGranted?.invoke()
                viewModel.initCamera()
            }
        }
    }

    private fun handleUsbIntent(intent: Intent) {
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                Log.i(TAG, "USB device attached")
                val isVideoDevice = detectUsbCaptureCard()
                if (isVideoDevice) {
                    Log.i(TAG, "USB VIDEO DEVICE attached — triggering camera rescan")
                }
                viewModel.setUvcDeviceAttached(true)
            }
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                Log.i(TAG, "USB device detached")
                viewModel.setUvcDeviceAttached(false)
            }
        }
    }

    /**
     * Detect USB capture card by enumerating USB devices and checking
     * for USB Video Class (UVC) interface class.
     *
     * UVC devices have:
     * - Interface class 14 (USB_CLASS_VIDEO)
     * - Or interface class 239 / subclass 2 (Miscellaneous / UVC)
     */
    private fun detectUsbCaptureCard(): Boolean {
        return try {
            val usbManager = getSystemService(USB_SERVICE) as UsbManager
            val devices = usbManager.deviceList
            for (device in devices.values) {
                try {
                    val interfaceCount = device.interfaceCount
                    for (i in 0 until interfaceCount) {
                        val iface = device.getInterface(i)
                        if (iface.interfaceClass == 14 ||          // USB video class
                            (iface.interfaceClass == 239 && iface.interfaceSubclass == 2)) { // UVC
                            Log.i(TAG, "USB video capture card detected: ${device.deviceName}, " +
                                    "class=${iface.interfaceClass}, subclass=${iface.interfaceSubclass}")
                            return true
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error checking USB device ${device.deviceName}: ${e.message}")
                }
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "Error enumerating USB devices: ${e.message}")
            false
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.CAMERA)
        }

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }
}

@Composable
fun SaatirilOperatorApp(
    viewModel: OperatorViewModel,
    activity: MainActivity,
    rootFrameLayout: FrameLayout
) {
    val connectionState by viewModel.connectionState.collectAsState()
    var isConnected by remember { mutableStateOf(false) }

    // Track camera permission
    var hasCameraPermission by remember { mutableStateOf(activity.cameraPermissionGranted) }

    // Initialize camera when permission is granted
    LaunchedEffect(hasCameraPermission) {
        if (hasCameraPermission) {
            viewModel.initCamera()
        }
    }

    DisposableEffect(activity) {
        activity.setOnCameraPermissionGrantedListener {
            hasCameraPermission = true
        }
        onDispose {
            activity.setOnCameraPermissionGrantedListener(null)
        }
    }

    // Track connection state for screen transitions
    LaunchedEffect(connectionState) {
        when (connectionState) {
            ConnectionState.AUTHENTICATED, ConnectionState.WAITING_FOR_DATA -> {
                isConnected = true
                // Camera preview is in the TextureView underneath Compose UI
                // Just show the preview by making TextureView visible
                viewModel.cameraManager.showPreview()
            }
            ConnectionState.DISCONNECTED -> {
                isConnected = false
                viewModel.cameraManager.hidePreview()
            }
            else -> {}
        }
    }

    if (isConnected && connectionState != ConnectionState.DISCONNECTED) {
        OperatorScreen(
            viewModel = viewModel,
            hasCameraPermission = hasCameraPermission
        )
    } else {
        ConnectionScreen(
            viewModel = viewModel,
            onConnected = {
                isConnected = true
                viewModel.cameraManager.showPreview()
            }
        )
    }
}
