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
import com.saatiril.operator.ui.connection.ConnectionScreen
import com.saatiril.operator.ui.operator.OperatorScreen

class MainActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
        const val ACTION_USB_PERMISSION = "com.saatiril.operator.USB_PERMISSION"
    }
    
    private lateinit var viewModel: OperatorViewModel
    
    // CRITICAL FIX: Track camera permission state so the ViewModel/UI can react
    private var _cameraPermissionGranted = false
    val cameraPermissionGranted: Boolean
        get() = _cameraPermissionGranted

    // Callback for when camera permission is granted
    private var onCameraPermissionGranted: (() -> Unit)? = null

    fun setOnCameraPermissionGrantedListener(callback: (() -> Unit)?) {
        onCameraPermissionGranted = callback
        // If permission was already granted, fire immediately
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
            // Notify listener (OperatorScreen) that permission was granted
            onCameraPermissionGranted?.invoke()
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
        
        // Check if camera permission is already granted (returning user)
        _cameraPermissionGranted = (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        
        // Request permissions
        requestPermissions()
        
        // Handle USB device attachment
        handleUsbIntent(intent)
        
        // Set content
        setContent {
            SaatirilOperatorApp(viewModel, this)
        }
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleUsbIntent(it) }
    }

    // CRITICAL FIX: Re-check camera permission when returning from Settings
    override fun onResume() {
        super.onResume()
        val nowGranted = (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        if (nowGranted != _cameraPermissionGranted) {
            Log.i(TAG, "Camera permission state changed on resume: $_cameraPermissionGranted → $nowGranted")
            _cameraPermissionGranted = nowGranted
            if (nowGranted) {
                onCameraPermissionGranted?.invoke()
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
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }
        
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }
}

/**
 * ═════════════════════════════════════════════════════════════════════════
 * v10: No more WebView! UVCCamera + CameraX handle cameras natively.
 *
 * ROOT CAUSE of USB camera reverting to built-in (v8/v9):
 * - WebView/getUserMedia couldn't see USB cameras on Android.
 * - Even when Chromium detected USB cameras, the stream would revert
 *   to built-in after screen transitions.
 *
 * v10 FIX: Replace WebView entirely with native camera engines:
 * - UVCCamera library (com.herohan:UVCAndroid) talks directly to USB
 *   hardware via USB Host API, bypassing Android's broken Camera2 HAL.
 * - CameraX handles built-in cameras (which work fine).
 * - No WebView, no JavaScript, no getUserMedia — all native Kotlin.
 *
 * initCamera() starts USB detection IMMEDIATELY when permission is granted.
 * No WebView parameter needed.
 * ═════════════════════════════════════════════════════════════════════════
 */
@Composable
fun SaatirilOperatorApp(viewModel: OperatorViewModel, activity: MainActivity) {
    val connectionState by viewModel.connectionState.collectAsState()
    var isConnected by remember { mutableStateOf(false) }

    // Track camera permission state from the Activity
    var hasCameraPermission by remember { mutableStateOf(activity.cameraPermissionGranted) }

    // v10: Initialize USB camera detection IMMEDIATELY at app start
    // No WebView needed — UVCCamera detects USB devices natively
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
    
    // Track when we transition to authenticated or disconnected
    LaunchedEffect(connectionState) {
        when (connectionState) {
            ConnectionState.AUTHENTICATED, ConnectionState.WAITING_FOR_DATA -> {
                isConnected = true
            }
            ConnectionState.DISCONNECTED -> {
                isConnected = false
            }
            // Keep current state for CONNECTING, CONNECTED, AUTHENTICATING, AUTH_FAILED
            // These intermediate states shouldn't change the screen
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
            onConnected = { isConnected = true }
        )
    }
}
