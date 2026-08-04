package com.saatiril.fullsystem.server

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLConnection

/**
 * Embedded HTTP server that serves the Next.js static export from APK assets.
 *
 * This enables standalone mode — the app works without an external server,
 * just like the Electron version. All project data is stored in the WebView's
 * localStorage (Zustand persist).
 *
 * Architecture:
 * ┌──────────────────────────────────────────┐
 * │  Android App                             │
 * │  ┌────────────────────────────────────┐  │
 * │  │ LocalWebServer (NanoHTTPD)         │  │
 * │  │ Serves assets/web on localhost     │  │
 * │  └────────────────────────────────────┘  │
 * │  ┌────────────────────────────────────┐  │
 * │  │ WebView → http://localhost:PORT/   │  │
 * │  │ Full Saatiril web app (React)      │  │
 * │  │ localStorage for persistence       │  │
 * │  └────────────────────────────────────┘  │
 * └──────────────────────────────────────────┘
 */
class LocalWebServer(
    private val context: Context,
    port: Int = DEFAULT_PORT
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "LocalWebServer"
        const val DEFAULT_PORT = 18080
        const val WEB_ASSETS_PATH = "web"

        // MIME type mapping for common file extensions
        private val MIME_TYPES = mapOf(
            "html" to "text/html",
            "htm" to "text/html",
            "css" to "text/css",
            "js" to "application/javascript",
            "json" to "application/json",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "svg" to "image/svg+xml",
            "ico" to "image/x-icon",
            "webp" to "image/webp",
            "woff" to "font/woff",
            "woff2" to "font/woff2",
            "ttf" to "font/ttf",
            "otf" to "font/otf",
            "map" to "application/json",
            "txt" to "text/plain",
            "xml" to "application/xml",
            "wasm" to "application/wasm",
            "bin" to "application/octet-stream",
            "data" to "application/octet-stream",
            "task" to "application/octet-stream",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "xls" to "application/vnd.ms-excel",
        )

        private val DEFAULT_MIME = "application/octet-stream"
    }

    private var isRunning = false

    /**
     * Start the local web server.
     * @return The base URL (e.g., "http://localhost:18080")
     */
    fun startServer(): String {
        if (isRunning) {
            Log.w(TAG, "Server already running on port $listeningPort")
            return getBaseUrl()
        }

        try {
            start(SOCKET_READ_TIMEOUT, false)
            isRunning = true
            Log.i(TAG, "Local web server started on port $listeningPort")
            Log.i(TAG, "Base URL: ${getBaseUrl()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start local web server", e)
            throw RuntimeException("Failed to start local web server: ${e.message}", e)
        }

        return getBaseUrl()
    }

    fun stopServer() {
        if (isRunning) {
            stop()
            isRunning = false
            Log.i(TAG, "Local web server stopped")
        }
    }

    fun getBaseUrl(): String = "http://localhost:$listeningPort"

    fun isServerRunning(): Boolean = isRunning

    override fun serve(session: IHTTPSession): Response {
        var uri = session.uri ?: "/"

        // Normalize URI: remove trailing slash for file resolution (except root)
        if (uri != "/" && uri.endsWith("/")) {
            uri = uri.removeSuffix("/")
        }

        Log.d(TAG, "Request: ${session.method} $uri")

        // Resolve the asset path
        val assetPath = resolveAssetPath(uri)

        try {
            // Try to open the asset
            val inputStream = context.assets.open(assetPath)
            val mimeType = getMimeType(assetPath)

            // Read the asset into a byte array (NanoHTTPD needs a stream it can rewind)
            val bytes = inputStream.readBytes()
            inputStream.close()

            // Add caching headers for static assets (_next dir)
            val headers = mutableMapOf<String, String>()
            if (assetPath.startsWith("_next/") || assetPath.startsWith("js/") || assetPath.startsWith("ai/")) {
                // Cache static assets for 1 year (Next.js uses content-hashed filenames)
                headers["Cache-Control"] = "public, max-age=31536000, immutable"
            } else {
                // Don't cache HTML pages (they may change between builds)
                headers["Cache-Control"] = "no-cache, no-store, must-revalidate"
            }

            // Add CORS headers for local development
            headers["Access-Control-Allow-Origin"] = "*"
            headers["Access-Control-Allow-Methods"] = "GET, HEAD, OPTIONS"
            headers["Access-Control-Allow-Headers"] = "*"

            return newFixedLengthResponse(
                Response.Status.OK,
                mimeType,
                ByteArrayInputStream(bytes),
                bytes.size.toLong()
            ).apply {
                for ((key, value) in headers) {
                    addHeader(key, value)
                }
            }

        } catch (e: Exception) {
            // Asset not found — try fallbacks

            // 1. Try adding /index.html for directory paths
            val indexPath = if (assetPath.endsWith("/index.html")) {
                null // already tried
            } else {
                "${assetPath.trimEnd('/')}/index.html"
            }

            if (indexPath != null) {
                try {
                    val is2 = context.assets.open(indexPath)
                    val bytes = is2.readBytes()
                    is2.close()
                    return newFixedLengthResponse(
                        Response.Status.OK,
                        "text/html",
                        ByteArrayInputStream(bytes),
                        bytes.size.toLong()
                    ).apply {
                        addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                        addHeader("Access-Control-Allow-Origin", "*")
                    }
                } catch (_: Exception) {
                    // index.html not found either
                }
            }

            // 2. For SPA routing — serve root index.html for all non-asset paths
            //    (Next.js client-side routing needs this)
            if (!uri.startsWith("/_next/") && !uri.startsWith("/js/") && !uri.contains(".")) {
                try {
                    val is3 = context.assets.open("$WEB_ASSETS_PATH/index.html")
                    val bytes = is3.readBytes()
                    is3.close()
                    Log.d(TAG, "SPA fallback: serving index.html for $uri")
                    return newFixedLengthResponse(
                        Response.Status.OK,
                        "text/html",
                        ByteArrayInputStream(bytes),
                        bytes.size.toLong()
                    ).apply {
                        addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                        addHeader("Access-Control-Allow-Origin", "*")
                    }
                } catch (_: Exception) {
                    // No index.html either
                }
            }

            // 3. 404
            Log.w(TAG, "404 Not Found: $uri (tried asset: $assetPath)")
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/html",
                "<html><body><h1>404 Not Found</h1><p>Resource: $uri</p></body></html>"
            )
        }
    }

    /**
     * Resolve a URI to an asset path within the /assets/web/ directory.
     *
     * Examples:
     *   /               → web/index.html
     *   /index.html     → web/index.html
     *   /_next/static/  → web/_next/static/index.html
     *   /admin/         → web/admin/index.html
     *   /logo.svg       → web/logo.svg
     */
    private fun resolveAssetPath(uri: String): String {
        val normalizedUri = if (uri.startsWith("/")) uri.substring(1) else uri

        // Root path → index.html
        if (normalizedUri.isEmpty() || normalizedUri == "/") {
            return "$WEB_ASSETS_PATH/index.html"
        }

        // Prepend the web assets path
        return "$WEB_ASSETS_PATH/$normalizedUri"
    }

    /**
     * Determine MIME type from file extension.
     */
    private fun getMimeType(path: String): String {
        val extension = path.substringAfterLast('.', "").lowercase()
        return MIME_TYPES[extension] ?: run {
            // Fallback to URLConnection guess
            val guessed = URLConnection.guessContentTypeFromName(path)
            guessed ?: DEFAULT_MIME
        }
    }
}
