package com.saatiril.full

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.saatiril.full.data.ConnectionState
import com.saatiril.full.data.FullViewModel
import com.saatiril.full.ui.connection.ConnectionScreen
import com.saatiril.full.ui.main.MainScreen

/**
 * ═════════════════════════════════════════════════════════════════════════
 * Saatiril Full — All-in-one Android app (Admin + MC + Operator)
 * ═════════════════════════════════════════════════════════════════════════
 *
 * Single Android app that runs the entire Saatiril system:
 * - Admin panel: Project dashboard, student management, photo gallery
 * - MC panel: Queue management, calling, student status
 * - Operator panel: Camera capture, photo sending
 *
 * Architecture:
 * ┌──────────────────────────────────┐
 * │  MainActivity                    │
 * │  ┌────────────────────────────┐  │
 * │  │ ConnectionScreen           │  │  ← Server URL, role, auth
 * │  └────────────────────────────┘  │
 * │  ┌────────────────────────────┐  │
 * │  │ MainScreen (Bottom Nav)    │  │
 * │  │  ┌──┐ ┌──┐ ┌──┐          │  │
 * │  │  │Ad│ │MC│ │Op│          │  │  ← 3 tabs
 * │  │  └──┘ └──┘ └──┘          │  │
 * │  └────────────────────────────┘  │
 * └──────────────────────────────────┘
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        const val ACTION_USB_PERMISSION = "com.saatiril.full.USB_PERMISSION"
    }

    private lateinit var viewModel: FullViewModel

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
        viewModel = ViewModelProvider(this)[FullViewModel::class.java]

        // Check existing camera permission
        _cameraPermissionGranted = (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED)

        // Request permissions
        requestPermissions()

        // Handle USB device attachment
        handleUsbIntent(intent)

        // Set content
        setContent {
            SaatirilFullApp(viewModel, this)
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

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }
}

@Composable
fun SaatirilFullApp(
    viewModel: FullViewModel,
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
        MainScreen(
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
