package com.saatiril.operator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
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
 * v17: UVCCamera Direct Architecture (MacroSilicon Black Screen Fix)
 * ═════════════════════════════════════════════════════════════════════════
 *
 * Replaced WebView approach with native UVCCamera library.
 * USB capture cards are UVC devices — Chrome/WebView getUserMedia CANNOT
 * access them on Android. Only USB Host API + UVC library works.
 *
 * Architecture:
 * ┌──────────────────────────────────┐
 * │  Activity                        │
 * │  ┌────────────────────────────┐  │
 * │  │ ComposeView                │  │
 * │  │  ┌──────────────────────┐  │  │
 * │  │  │ TextureView (UVC)    │  │  │  ← Camera preview via AndroidView
 * │  │  │ camera preview here  │  │  │
 * │  │  └──────────────────────┘  │  │
 * │  │  ┌──────────────────────┐  │  │
 * │  │  │ UI Overlays          │  │  │  ← Gridline, frame, controls
 * │  │  └──────────────────────┘  │  │
 * │  └────────────────────────────┘  │
 * └──────────────────────────────────┘
 *
 * Key Changes:
 * - USB Host API: UsbManager + USBMonitor for device detection
 * - UVCCamera: Direct UVC device access (not Camera2/WebView)
 * - TextureView: Preview surface for UVC camera stream
 * - MJPEG forced: MacroSilicon fix for black screen
 * - BandwidthFactor: USB bandwidth negotiation fix
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        const val ACTION_USB_PERMISSION = "com.saatiril.operator.USB_PERMISSION"
    }

    private lateinit var viewModel: OperatorViewModel

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

        // Request permissions
        requestPermissions()

        // Handle USB device attachment
        handleUsbIntent(intent)

        // Set content
        setContent {
            SaatirilOperatorApp(viewModel, this)
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
                viewModel.setUvcDeviceAttached(true)
            }
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                Log.i(TAG, "USB device detached")
                viewModel.setUvcDeviceAttached(false)
            }
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
    activity: MainActivity
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
                viewModel.cameraUVCManager.showPreview()
            }
            ConnectionState.DISCONNECTED -> {
                isConnected = false
                viewModel.cameraUVCManager.hidePreview()
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
                viewModel.cameraUVCManager.showPreview()
            }
        )
    }
}
