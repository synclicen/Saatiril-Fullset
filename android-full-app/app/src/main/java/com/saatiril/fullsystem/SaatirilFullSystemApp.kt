package com.saatiril.fullsystem

import android.util.Log
import androidx.compose.runtime.*
import com.saatiril.fullsystem.ui.connection.ConnectionScreen
import com.saatiril.fullsystem.ui.webview.WebViewScreen

/**
 * Root Compose app — manages navigation between Connection and WebView screens.
 *
 * Standalone mode: Starts LocalWebServer → auto-loads WebView
 * Server mode: ConnectionScreen → (on connect) → WebViewScreen
 */
@Composable
fun SaatirilFullSystemApp(activity: MainActivity) {
    // Track which screen is shown
    var isConnected by remember { mutableStateOf(false) }
    var connectionParams by remember { mutableStateOf<ConnectionParams?>(null) }
    var isStandalone by remember { mutableStateOf(false) }

    // Track camera permission state
    var hasCameraPermission by remember { mutableStateOf(activity.cameraPermissionGranted) }

    DisposableEffect(activity) {
        activity.setOnCameraPermissionGrantedListener {
            hasCameraPermission = true
        }
        onDispose {
            activity.setOnCameraPermissionGrantedListener(null)
        }
    }

    if (isConnected && connectionParams != null) {
        WebViewScreen(
            connectionParams = connectionParams!!,
            hasCameraPermission = hasCameraPermission,
            activity = activity,
            onDisconnect = {
                isConnected = false
                connectionParams = null
                // Stop local server when disconnecting from standalone mode
                if (isStandalone) {
                    activity.stopLocalServer()
                    isStandalone = false
                }
            }
        )
    } else {
        ConnectionScreen(
            hasCameraPermission = hasCameraPermission,
            onConnected = { params ->
                // If connecting to localhost, this is standalone mode
                val isLocal = params.serverUrl.contains("localhost") || params.serverUrl.contains("127.0.0.1")
                if (isLocal) {
                    isStandalone = true
                    // Start the local server
                    try {
                        val baseUrl = activity.startLocalServer()
                        Log.i("SaatirilApp", "Standalone mode — local server started at $baseUrl")
                        // Rebuild params with actual server URL
                        connectionParams = ConnectionParams(
                            serverUrl = baseUrl,
                            role = params.role,
                            channel = params.channel,
                            password = params.password
                        )
                    } catch (e: Exception) {
                        Log.e("SaatirilApp", "Failed to start local server", e)
                        // Still try to connect — maybe server is already running
                        connectionParams = params
                    }
                } else {
                    connectionParams = params
                }
                isConnected = true
            }
        )
    }
}
