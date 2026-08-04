package com.saatiril.fullsystem.ui.webview

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.saatiril.fullsystem.BuildConfig
import com.saatiril.fullsystem.ConnectionParams
import com.saatiril.fullsystem.MainActivity

// ─── Saatiril Theme Colors ──────────────────────────────────
private val BG = Color(0xFF1a0b2e)
private val GOLD = Color(0xFFd4af37)
private val MUTED = Color(0xFFc4b5fd)
private val RED = Color(0xFFef4444)

private const val TAG = "WebViewScreen"

/**
 * WebView Screen — Full-screen WebView that loads the Saatiril web app.
 *
 * Features:
 * - JavaScript enabled
 * - DOM storage enabled
 * - Camera permission handling for getUserMedia
 * - File upload handling for Excel import
 * - Loading progress bar
 * - Back button: navigates WebView history, or goes back to connection screen
 * - Error handling for connection failures
 */
@Composable
fun WebViewScreen(
    connectionParams: ConnectionParams,
    hasCameraPermission: Boolean,
    activity: MainActivity,
    onDisconnect: () -> Unit
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var pageTitle by remember { mutableStateOf("Saatiril") }
    var canGoBack by remember { mutableStateOf(false) }

    // Build the URL with query parameters
    val targetUrl = remember(connectionParams) { connectionParams.buildUrl() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // ─── Top Bar ───
            TopBar(
                pageTitle = pageTitle,
                role = connectionParams.role,
                channel = connectionParams.channel,
                onDisconnect = onDisconnect
            )

            // ─── Loading Progress Bar ───
            if (isLoading) {
                LinearProgressIndicator(
                    progress = { loadProgress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = GOLD,
                    trackColor = BG
                )
            }

            // ─── Error Display ───
            if (errorMessage != null) {
                ErrorBanner(
                    message = errorMessage!!,
                    onRetry = {
                        errorMessage = null
                        webView?.reload()
                    }
                )
            }

            // ─── WebView ───
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        setupWebViewSettings(this)
                        setupWebViewClient(
                            webView = this,
                            onLoadStarted = { isLoading = true; loadProgress = 0 },
                            onLoadProgress = { loadProgress = it },
                            onLoadFinished = { isLoading = false; loadProgress = 100 },
                            onPageTitleChanged = { pageTitle = it },
                            onCanGoBackChanged = { canGoBack = it },
                            onError = { errorMessage = it }
                        )
                        setupWebChromeClient(
                            webView = this,
                            activity = activity,
                            hasCameraPermission = hasCameraPermission,
                            onProgressChanged = { loadProgress = it }
                        )

                        // Load the URL
                        Log.i(TAG, "Loading URL: $targetUrl")
                        loadUrl(targetUrl)
                    }.also {
                        webView = it
                    }
                },
                update = { wv ->
                    webView = wv
                },
                modifier = Modifier.weight(1f)
            )
        }
    }

    // ─── Back Handler ───
    BackHandler(enabled = canGoBack) {
        webView?.let {
            if (it.canGoBack()) {
                it.goBack()
            }
        }
    }
}

// ─── Top Bar ──────────────────────────────────────────────
@Composable
private fun TopBar(
    pageTitle: String,
    role: String,
    channel: Int,
    onDisconnect: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF2a164a),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Title & info
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Devices,
                    contentDescription = null,
                    tint = GOLD,
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        pageTitle,
                        style = TextStyle(
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        ),
                        maxLines = 1
                    )
                    Text(
                        "${role.replaceFirstChar { it.uppercase() }} · Ch. $channel",
                        style = TextStyle(
                            color = MUTED,
                            fontSize = 10.sp
                        )
                    )
                }
            }

            // Right: Disconnect button
            IconButton(onClick = onDisconnect) {
                Icon(
                    Icons.Default.Logout,
                    contentDescription = "Disconnect",
                    tint = RED,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// ─── Error Banner ─────────────────────────────────────────
@Composable
private fun ErrorBanner(
    message: String,
    onRetry: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = RED.copy(alpha = 0.15f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.WifiOff,
                    contentDescription = null,
                    tint = RED,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    message,
                    style = TextStyle(color = RED, fontSize = 12.sp),
                    maxLines = 2
                )
            }
            TextButton(onClick = onRetry) {
                Text("Coba Lagi", color = RED, fontSize = 12.sp)
            }
        }
    }
}

// ─── WebView Setup Helpers ────────────────────────────────

@SuppressLint("SetJavaScriptEnabled")
private fun setupWebViewSettings(webView: WebView) {
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        allowFileAccess = true
        allowContentAccess = true

        // Enable WebView debugging in debug builds
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        // Responsive viewport
        useWideViewPort = true
        loadWithOverviewMode = true

        // Scaling
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false

        // Cache
        cacheMode = WebSettings.LOAD_DEFAULT

        // Mixed content — allow HTTP resources on HTTPS pages (for LAN)
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // Allow file access from file URLs
        @Suppress("DEPRECATION")
        allowFileAccessFromFileURLs = true
        @Suppress("DEPRECATION")
        allowUniversalAccessFromFileURLs = true

        // Media playback
        mediaPlaybackRequiresUserGesture = false

        // Text scaling
        textZoom = 100
    }
}

private fun setupWebViewClient(
    webView: WebView,
    onLoadStarted: () -> Unit,
    onLoadProgress: (Int) -> Unit,
    onLoadFinished: () -> Unit,
    onPageTitleChanged: (String) -> Unit,
    onCanGoBackChanged: (Boolean) -> Unit,
    onError: (String) -> Unit
) {
    webView.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val url = request.url.toString()
            Log.d(TAG, "Navigation: $url")

            // Allow only http/https URLs in WebView
            val scheme = request.url.scheme
            if (scheme != "http" && scheme != "https") {
                // Let the system handle non-web URLs (tel:, mailto:, etc.)
                return false
            }
            return false
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            Log.i(TAG, "Page started: $url")
            onLoadStarted()
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            Log.i(TAG, "Page finished: $url")
            onLoadFinished()
            onPageTitleChanged(view.title ?: "Saatiril")
            onCanGoBackChanged(view.canGoBack())
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            super.onReceivedError(view, request, error)
            // Only report main frame errors
            if (request.isForMainFrame) {
                val errMsg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    "Gagal memuat: ${error.description}"
                } else {
                    "Gagal memuat halaman"
                }
                Log.e(TAG, "WebView error: $errMsg")
                onError(errMsg)
            }
        }
    }
}

private fun setupWebChromeClient(
    webView: WebView,
    activity: MainActivity,
    hasCameraPermission: Boolean,
    onProgressChanged: (Int) -> Unit
) {
    webView.webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            onProgressChanged(newProgress)
        }

        override fun onReceivedTitle(view: WebView, title: String) {
            super.onReceivedTitle(view, title)
            Log.d(TAG, "Page title: $title")
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            Log.d(
                TAG,
                "Console [${consoleMessage.messageLevel()}]: ${consoleMessage.message()} " +
                        "at ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}"
            )
            return true
        }

        // ── Camera/Media Permission Request (for getUserMedia) ──
        override fun onPermissionRequest(request: PermissionRequest) {
            Log.i(TAG, "WebView permission request: ${request.resources.contentToString()}")

            val resources = request.resources
            val hasVideo = resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
            val hasAudio = resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
            val hasGeolocation = resources.contains(PermissionRequest.RESOURCE_GEOLOCATION)

            if ((hasVideo || hasAudio) && hasCameraPermission) {
                // Camera/audio permission already granted at Android level
                Log.i(TAG, "Granting WebView camera/audio permission")
                request.grant(resources)
            } else if (hasGeolocation) {
                // Grant geolocation
                Log.i(TAG, "Granting WebView geolocation permission")
                request.grant(resources)
            } else if (hasVideo || hasAudio) {
                // Camera not granted — deny video but grant other resources
                Log.w(TAG, "Camera permission not granted, denying video capture")
                val grantedResources = resources.filter {
                    it != PermissionRequest.RESOURCE_VIDEO_CAPTURE &&
                    it != PermissionRequest.RESOURCE_AUDIO_CAPTURE
                }.toTypedArray()
                if (grantedResources.isNotEmpty()) {
                    request.grant(grantedResources)
                } else {
                    request.deny()
                }
            } else {
                // Grant other permissions by default
                request.grant(resources)
            }
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: GeolocationPermissions.Callback
        ) {
            Log.i(TAG, "Geolocation permission request from: $origin")
            callback.invoke(origin, true, false)
        }

        // ── File Upload Handler (for Excel import in admin) ──
        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            Log.i(TAG, "File chooser requested: ${fileChooserParams.acceptTypes.contentToString()}")

            // Store the callback
            activity.filePathCallback?.onReceiveValue(null)
            activity.filePathCallback = filePathCallback

            try {
                val intent = fileChooserParams.createIntent()
                @Suppress("DEPRECATION")
                activity.startActivityForResult(intent, MainActivity.FILE_CHOOSER_REQUEST_CODE)
            } catch (e: Exception) {
                Log.e(TAG, "File chooser error: ${e.message}")
                activity.filePathCallback = null
                return false
            }
            return true
        }
    }
}
