package com.saatiril.fullsystem

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.webkit.ValueCallback
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import com.saatiril.fullsystem.server.LocalWebServer

/**
 * Main Activity for Saatiril Full System — WebView wrapper app.
 *
 * Two modes:
 * 1. STANDALONE — Starts local NanoHTTPD server serving the embedded Next.js
 *    static export from assets. No external server needed. Admin can create
 *    projects directly on the phone (stored in WebView localStorage).
 * 2. SERVER — Connects to an external Saatiril server via URL.
 *    For multi-device scenarios (MC, Operator, or Admin connecting to a PC).
 *
 * Architecture:
 * ┌──────────────────────────────────────────────┐
 * │  Activity                                    │
 * │  ┌────────────────────────────────────────┐  │
 * │  │ ComposeView                            │  │
 * │  │  ┌──────────────────────────────────┐  │  │
 * │  │  │ ConnectionScreen                 │  │  │  ← Mode selection + Server URL
 * │  │  └──────────────────────────────────┘  │  │
 * │  │  ┌──────────────────────────────────┐  │  │
 * │  │  │ WebViewScreen                    │  │  │  ← Full Saatiril web app
 * │  │  │ (after connecting/standalone)    │  │  │
 * │  │  └──────────────────────────────────┘  │  │
 * │  └────────────────────────────────────────┘  │
 * └──────────────────────────────────────────────┘
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        const val FILE_CHOOSER_REQUEST_CODE = 1001
    }

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

    // File upload callback for WebView file chooser
    var filePathCallback: ValueCallback<Array<Uri>>? = null

    // Local web server for standalone mode
    private var localWebServer: LocalWebServer? = null

    fun startLocalServer(): String {
        if (localWebServer == null) {
            localWebServer = LocalWebServer(this)
        }
        if (!localWebServer!!.isServerRunning()) {
            return localWebServer!!.startServer()
        }
        return localWebServer!!.getBaseUrl()
    }

    fun stopLocalServer() {
        localWebServer?.stopServer()
    }

    fun isLocalServerRunning(): Boolean = localWebServer?.isServerRunning() == true

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false

        if (cameraGranted) {
            Log.i(TAG, "Camera permission GRANTED")
            _cameraPermissionGranted = true
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

        // Check existing camera permission
        _cameraPermissionGranted = (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED)

        // Request permissions
        requestPermissions()

        // Set content
        setContent {
            SaatirilFullSystemApp(activity = this)
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            val results: Array<Uri>? = if (resultCode == RESULT_OK && data != null) {
                data.dataString?.let { arrayOf(Uri.parse(it)) }
            } else {
                null
            }
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
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
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop local server when activity is destroyed
        localWebServer?.stopServer()
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

/**
 * Data class holding connection parameters to pass to WebView
 */
data class ConnectionParams(
    val serverUrl: String,
    val role: String,
    val channel: Int,
    val password: String?
) {
    /** Build the full URL with query parameters for the WebView */
    fun buildUrl(): String {
        val base = serverUrl.trimEnd('/')
        val params = mutableListOf("role=$role", "channel=$channel")
        password?.let { if (it.isNotEmpty()) params.add("password=$it") }
        return "$base?${params.joinToString("&")}"
    }
}
