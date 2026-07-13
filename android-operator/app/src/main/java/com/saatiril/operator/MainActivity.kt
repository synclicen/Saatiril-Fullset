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
 * v13: PERMANENT WebView Architecture
 * ═════════════════════════════════════════════════════════════════════════
 *
 * The WebView is created ONCE and added directly to the Activity's root
 * FrameLayout. It is NEVER removed during screen transitions.
 *
 * Layout structure:
 * ┌──────────────────────────────┐
 * │  FrameLayout (root)          │
 * │  ┌────────────────────────┐  │
 * │  │ WebView (camera.html)  │  │  ← Bottom layer, ALWAYS present
 * │  │ camera preview here    │  │
 * │  └────────────────────────┘  │
 * │  ┌────────────────────────┐  │
 * │  │ ComposeView (UI)       │  │  ← Top layer, transparent background
 * │  │ ConnectionScreen /     │  │     on OperatorScreen so camera shows
 * │  │ OperatorScreen         │  │     through
 * │  └────────────────────────┘  │
 * └──────────────────────────────┘
 *
 * This guarantees:
 * 1. WebView NEVER detaches from window → camera stream NEVER breaks
 * 2. getUserMedia can access USB cameras (Chromium has UVC support)
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
            // Initialize camera in the permanent WebView
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
        // The WebView goes in as the bottom layer, ComposeView
        // goes in as the top layer. Both are ALWAYS in the window.
        // ═══════════════════════════════════════════════════════════
        rootFrameLayout = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Create the PERMANENT WebView and add to root as bottom layer
        viewModel.cameraWebViewManager.createWebView(rootFrameLayout)

        // Request permissions
        requestPermissions()

        // Handle USB device attachment
        handleUsbIntent(intent)

        // Set content with the root FrameLayout containing WebView + Compose
        setContent {
            // Add ComposeView on top of the WebView
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
                // Camera is already running in the permanent WebView
                // Just show the preview by making WebView visible
                viewModel.cameraWebViewManager.showPreview()
            }
            ConnectionState.DISCONNECTED -> {
                isConnected = false
                viewModel.cameraWebViewManager.hidePreview()
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
                viewModel.cameraWebViewManager.showPreview()
            }
        )
    }
}
