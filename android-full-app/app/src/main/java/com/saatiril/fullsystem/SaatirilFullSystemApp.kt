package com.saatiril.fullsystem

import androidx.compose.runtime.*
import com.saatiril.fullsystem.ui.connection.ConnectionScreen
import com.saatiril.fullsystem.ui.webview.WebViewScreen

/**
 * Root Compose app — manages navigation between Connection and WebView screens.
 *
 * ConnectionScreen → (on connect) → WebViewScreen
 * WebViewScreen → (back/disconnect) → ConnectionScreen
 */
@Composable
fun SaatirilFullSystemApp(activity: MainActivity) {
    // Track which screen is shown
    var isConnected by remember { mutableStateOf(false) }
    var connectionParams by remember { mutableStateOf<ConnectionParams?>(null) }

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
            }
        )
    } else {
        ConnectionScreen(
            hasCameraPermission = hasCameraPermission,
            onConnected = { params ->
                connectionParams = params
                isConnected = true
            }
        )
    }
}
