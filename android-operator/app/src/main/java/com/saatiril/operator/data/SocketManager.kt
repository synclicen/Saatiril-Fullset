package com.saatiril.operator.data

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages Socket.io connection to the Saatiril server.
 * Handles authentication, event relay, and reconnection.
 *
 * CRITICAL FIXES from previous crash:
 * - IO.socket() URL parsing is wrapped in comprehensive try-catch
 * - Socket creation failure no longer crashes the app — reports error to UI
 * - All socket event callbacks use defensive try-catch
 * - OkHttp dependency conflict resolved in build.gradle.kts
 *
 * Thread safety:
 * - All socket callbacks run on Socket.io's background IO thread
 * - ViewModel listeners are notified via the main handler to ensure
 *   Compose StateFlow updates happen on the main thread
 * - connectionState is @Volatile for cross-thread visibility
 * - eventQueue uses synchronized access
 * - notifyListeners wraps each callback in try-catch to prevent crashes
 *
 * Protocol compatibility: matches web client (socket.ts) and server (index.ts)
 * - path: "/" (must match server config)
 * - Events: identify, auth-requirement, auth-success, auth-failed, lan-message, saatiril-ping/pong
 * - LAN messages wrapped in { event, data } payload
 * - Critical events queued when disconnected and replayed on reconnect
 */
class SocketManager {

    companion object {
        private const val TAG = "SocketManager"
        private const val IDENTIFY_TIMEOUT_MS = 30_000L
        private const val PING_INTERVAL_MS = 5_000L

        // Critical events that must be queued when disconnected
        private val CRITICAL_EVENTS = setOf(
            SocketEvents.PHOTOS_SAVED,
            SocketEvents.MC_CALL,
            SocketEvents.SYNC_DB,
            SocketEvents.STUDENT_DONE,
            SocketEvents.STUDENT_RESET
        )
        private const val MAX_QUEUE_SIZE = 50
        private const val MAX_RETRIES = 3

        fun sha256(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
            return hashBytes.joinToString("") { "%02x".format(it) }
        }
    }

    private val gson = Gson()
    private var socket: Socket? = null

    // @Volatile ensures cross-thread visibility for connectionState
    @Volatile
    private var connectionState = ConnectionState.DISCONNECTED

    @Volatile
    private var passwordHash: String? = null

    @Volatile
    private var myChannel: Int = 1

    private var pingIntervalJob: java.util.Timer? = null

    // Main thread handler for posting listener notifications
    private val mainHandler = Handler(Looper.getMainLooper())

    // Event listeners — CopyOnWriteArrayList for thread-safe iteration
    private val listeners = java.util.concurrent.ConcurrentHashMap<String, CopyOnWriteArrayList<(Any?) -> Unit>>()

    // Critical event queue — synchronized access for thread safety
    private data class QueuedEvent(
        val event: String,
        val data: Any,
        var retries: Int = 0
    )
    private val eventQueue = mutableListOf<QueuedEvent>()
    private val eventQueueLock = Any()

    // ─── Connection ─────────────────────────────────────────────

    fun connect(serverUrl: String, channel: Int, password: String? = null) {
        // Disconnect existing socket if any, but preserve ViewModel listeners
        if (socket != null) {
            Log.w(TAG, "Existing socket found, cleaning up before reconnect")
            stopPingInterval()
            try {
                socket?.disconnect()
                socket?.off()
            } catch (e: Exception) {
                Log.w(TAG, "Error disconnecting old socket: ${e.message}")
            }
            socket = null
            // NOTE: Do NOT clear listeners here — ViewModel listeners must persist across reconnects
        }

        myChannel = channel
        passwordHash = password?.let { sha256(it) }
        connectionState = ConnectionState.CONNECTING
        notifyListenersOnUiThread("state_changed", connectionState)

        // ─── CRITICAL: Validate URL BEFORE passing to IO.socket() ───
        // IO.socket() can throw RuntimeException/URISyntaxException for bad URLs,
        // which would crash the app if uncaught.
        val validatedUrl: String
        try {
            val uri = URI(serverUrl)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                throw URISyntaxException(serverUrl, "Invalid scheme: must be http or https")
            }
            if (uri.host.isNullOrBlank()) {
                throw URISyntaxException(serverUrl, "Host is empty")
            }
            validatedUrl = serverUrl
        } catch (e: URISyntaxException) {
            Log.e(TAG, "Invalid server URL: $serverUrl — ${e.message}")
            connectionState = ConnectionState.DISCONNECTED
            notifyListenersOnUiThread("state_changed", ConnectionState.DISCONNECTED)
            notifyListenersOnUiThread("connection_error", "URL tidak valid: ${e.message}")
            return
        }

        try {
            val options = IO.Options().apply {
                path = "/"  // MUST match server config (server uses path: '/')
                transports = arrayOf("websocket", "polling")
                reconnection = true
                reconnectionAttempts = 20  // Reasonable limit instead of Int.MAX_VALUE
                reconnectionDelay = 1000
                reconnectionDelayMax = 10_000  // Match web client
                timeout = 15_000                // Match web client
                forceNew = true                 // Match web client — prevent stale socket reuse
            }

            // ─── CRITICAL: IO.socket() can throw if OkHttp classes are missing ───
            // This was the #1 crash cause — OkHttp version conflict between
            // socket.io-client (3.12.x) and coil-compose (4.12.x)
            socket = try {
                IO.socket(validatedUrl, options)
            } catch (e: NoSuchMethodError) {
                Log.e(TAG, "OkHttp method not found — dependency conflict! ${e.message}", e)
                connectionState = ConnectionState.DISCONNECTED
                notifyListenersOnUiThread("state_changed", ConnectionState.DISCONNECTED)
                notifyListenersOnUiThread("connection_error", "Library conflict — report to developer: ${e.message}")
                return
            } catch (e: NoClassDefFoundError) {
                Log.e(TAG, "OkHttp class not found — dependency conflict! ${e.message}", e)
                connectionState = ConnectionState.DISCONNECTED
                notifyListenersOnUiThread("state_changed", ConnectionState.DISCONNECTED)
                notifyListenersOnUiThread("connection_error", "Library conflict — report to developer: ${e.message}")
                return
            } catch (e: RuntimeException) {
                Log.e(TAG, "Failed to create socket (runtime): ${e.message}", e)
                connectionState = ConnectionState.DISCONNECTED
                notifyListenersOnUiThread("state_changed", ConnectionState.DISCONNECTED)
                notifyListenersOnUiThread("connection_error", "Gagal membuat koneksi: ${e.message}")
                return
            }

            setupSocketListeners()
            socket?.connect()

            Log.i(TAG, "Connecting to $validatedUrl as operator channel $channel")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create socket connection: ${e.message}", e)
            connectionState = ConnectionState.DISCONNECTED
            notifyListenersOnUiThread("state_changed", ConnectionState.DISCONNECTED)
            notifyListenersOnUiThread("connection_error", e.message ?: "Connection failed")
        }
    }

    fun disconnect() {
        stopPingInterval()
        try {
            socket?.disconnect()
            socket?.off()
        } catch (e: Exception) {
            Log.w(TAG, "Error during disconnect: ${e.message}")
        }
        socket = null
        connectionState = ConnectionState.DISCONNECTED
        notifyListenersOnUiThread("state_changed", connectionState)
        // NOTE: Do NOT clear listeners here — they should persist for reconnect
        // Use destroy() for full cleanup when ViewModel is being destroyed
        synchronized(eventQueueLock) {
            eventQueue.clear()
        }
    }

    /**
     * Full cleanup including listener removal.
     * Called only from ViewModel.onCleared() when the ViewModel is permanently destroyed.
     */
    fun destroy() {
        disconnect()
        listeners.clear()
        mainHandler.removeCallbacksAndMessages(null)
    }

    fun isConnected(): Boolean = socket?.connected() == true

    fun isAuthenticated(): Boolean = connectionState == ConnectionState.AUTHENTICATED

    fun getState(): ConnectionState = connectionState

    // ─── Socket Event Listeners ─────────────────────────────────

    private fun setupSocketListeners() {
        val s = socket ?: return

        s.on(Socket.EVENT_CONNECT) {
            try {
                Log.i(TAG, "Socket connected")
                connectionState = ConnectionState.CONNECTED
                notifyListenersOnUiThread("state_changed", connectionState)

                // Send identify immediately (with password hash if available)
                identify()
            } catch (e: Exception) {
                Log.e(TAG, "Error in CONNECT handler: ${e.message}", e)
            }
        }

        s.on(Socket.EVENT_DISCONNECT) { args ->
            try {
                Log.w(TAG, "Socket disconnected: ${args.getOrElse(0) { "unknown" }}")
                connectionState = ConnectionState.DISCONNECTED
                stopPingInterval()
                notifyListenersOnUiThread("state_changed", connectionState)
            } catch (e: Exception) {
                Log.e(TAG, "Error in DISCONNECT handler: ${e.message}", e)
            }
        }

        s.on(Socket.EVENT_CONNECT_ERROR) { args ->
            try {
                val errorMsg = args.getOrElse(0) { "unknown" }
                Log.e(TAG, "Connection error: $errorMsg")
                // Don't set DISCONNECTED here — Socket.io auto-reconnects
                // Keep state as CONNECTING so UI shows "Menghubungkan..." instead of "Terputus"
                if (connectionState != ConnectionState.AUTHENTICATING &&
                    connectionState != ConnectionState.AUTH_FAILED) {
                    connectionState = ConnectionState.CONNECTING
                }
                notifyListenersOnUiThread("connection_error", errorMsg?.toString() ?: "Connection failed")
                notifyListenersOnUiThread("state_changed", connectionState)
            } catch (e: Exception) {
                Log.e(TAG, "Error in CONNECT_ERROR handler: ${e.message}", e)
            }
        }

        // ── Auth events ──────────────────────────────────────────────

        s.on(SocketEvents.AUTH_REQUIREMENT) { args ->
            try {
                val json = args.firstOrNull() as? JSONObject
                val passwordRequired = json?.optBoolean("passwordRequired") ?: false
                Log.i(TAG, "Auth requirement: passwordRequired=$passwordRequired")

                if (passwordRequired) {
                    // Always show password prompt when server requires it
                    // (even if we already submitted a wrong password)
                    connectionState = ConnectionState.AUTHENTICATING
                    notifyListenersOnUiThread("password_required", null)
                } else {
                    // No password required — if we were in auth-failed state, re-identify
                    if (connectionState == ConnectionState.AUTH_FAILED ||
                        connectionState == ConnectionState.AUTHENTICATING) {
                        passwordHash = null
                        identify()
                    }
                }

                notifyListenersOnUiThread("state_changed", connectionState)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling auth-requirement: ${e.message}", e)
            }
        }

        s.on(SocketEvents.AUTH_SUCCESS) { args ->
            try {
                val json = args.firstOrNull() as? JSONObject
                Log.i(TAG, "Auth success: $json")
                connectionState = ConnectionState.AUTHENTICATED
                notifyListenersOnUiThread("auth_success", json?.toString())
                notifyListenersOnUiThread("state_changed", connectionState)

                // Start ping interval for latency measurement
                startPingInterval()

                // Flush any queued critical events that were waiting for auth
                flushEventQueue()

                // Request project state from admin
                requestState()
            } catch (e: Exception) {
                Log.e(TAG, "Error handling auth-success: ${e.message}", e)
            }
        }

        s.on(SocketEvents.AUTH_FAILED) { args ->
            try {
                val json = args.firstOrNull() as? JSONObject
                val reason = json?.optString("reason") ?: "unknown"
                Log.w(TAG, "Auth failed: $reason")
                connectionState = ConnectionState.AUTH_FAILED
                notifyListenersOnUiThread("auth_failed", reason)
                notifyListenersOnUiThread("state_changed", connectionState)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling auth-failed: ${e.message}", e)
            }
        }

        // ── Session password lifecycle ───────────────────────────────

        s.on(SocketEvents.SET_SESSION_PASSWORD) { args ->
            Log.i(TAG, "Session password set by admin — will need to re-authenticate")
            // The auth-requirement broadcast from the server handles the flow
        }

        s.on(SocketEvents.CLEAR_SESSION_PASSWORD) {
            Log.i(TAG, "Session password cleared by admin")
            // If we were stuck in auth-failed, re-identify without password
            if (connectionState == ConnectionState.AUTH_FAILED ||
                connectionState == ConnectionState.AUTHENTICATING) {
                passwordHash = null
                identify()
            }
        }

        // ── Latency measurement ──────────────────────────────────────

        s.on(SocketEvents.SAATIRIL_PONG) { args ->
            try {
                // Defensive number parsing — Java socket.io may return Long, Int, or Double
                val timestamp = when (val arg = args.firstOrNull()) {
                    is Long -> arg
                    is Int -> arg.toLong()
                    is Double -> arg.toLong()
                    is Number -> arg.toLong()
                    else -> return@on
                }
                val latency = System.currentTimeMillis() - timestamp
                notifyListenersOnUiThread("latency", latency)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling saatiril-pong: ${e.message}", e)
            }
        }

        // ── LAN messages (main communication channel) ────────────────

        s.on(SocketEvents.LAN_MESSAGE) { args ->
            try {
                val json = args.firstOrNull() as? JSONObject ?: return@on
                handleLanMessage(json)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling lan-message: ${e.message}", e)
            }
        }
    }

    // ─── LAN Message Handler ─────────────────────────────────────

    private fun handleLanMessage(json: JSONObject) {
        val event = json.optString("event")
        val data = json.opt("data")

        Log.d(TAG, "LAN message: $event")

        when (event) {
            SocketEvents.MC_CALL -> {
                Log.d(TAG, "MC_CALL raw data type: ${data?.javaClass?.simpleName}")
                val mcCallData = parseData<McCallData>(data)
                if (mcCallData != null) {
                    Log.i(TAG, "MC_CALL parsed: student=${mcCallData.student.nama}, nim=${mcCallData.student.nim}, ch=${mcCallData.channel}, status=${mcCallData.student.status}")
                    notifyListenersOnUiThread(SocketEvents.MC_CALL, mcCallData)
                } else {
                    Log.e(TAG, "MC_CALL: Failed to parse McCallData — trying manual extraction")
                    // Fallback: manually extract student from JSONObject
                    try {
                        val dataObj = (data as? JSONObject)
                        val studentObj = dataObj?.optJSONObject("student")
                        if (studentObj != null) {
                            val fallbackStudent = Student(
                                id = studentObj.optString("id", ""),
                                nim = studentObj.optString("nim", ""),
                                nama = studentObj.optString("nama", ""),
                                status = studentObj.optString("status", "sent"),
                                assignedChannel = studentObj.optInt("assignedChannel", studentObj.optInt("assigned_channel", 1))
                            )
                            val fallbackMcCall = McCallData(student = fallbackStudent, channel = dataObj.optInt("channel", 1))
                            Log.i(TAG, "MC_CALL manual fallback: student=${fallbackStudent.nama}, ch=${fallbackMcCall.channel}")
                            notifyListenersOnUiThread(SocketEvents.MC_CALL, fallbackMcCall)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "MC_CALL manual fallback also failed: ${e.message}")
                    }
                }
            }

            SocketEvents.SYNC_DB -> {
                Log.d(TAG, "SYNC_DB raw data type: ${data?.javaClass?.simpleName}")
                val syncData = parseData<SyncDbData>(data)
                if (syncData != null) {
                    Log.i(TAG, "SYNC_DB parsed: project=${syncData.project.name}, dbSize=${syncData.project.database.size}, mode=${syncData.project.config.mode}")
                    if (syncData.project.database.isNotEmpty()) {
                        Log.d(TAG, "SYNC_DB first student: ${syncData.project.database.first().nama} (status=${syncData.project.database.first().status}, ch=${syncData.project.database.first().assignedChannel})")
                    }
                    notifyListenersOnUiThread(SocketEvents.SYNC_DB, syncData)
                } else {
                    Log.e(TAG, "SYNC_DB: Failed to parse SyncDbData — trying manual extraction")
                    try {
                        val dataObj = (data as? JSONObject)
                        val projectObj = dataObj?.optJSONObject("project")
                        if (projectObj != null) {
                            Log.d(TAG, "SYNC_DB manual: Found project object, name=${projectObj.optString("name")}")
                            // Try parsing with a more lenient approach
                            val configObj = projectObj.optJSONObject("config")
                            val dbArray = projectObj.optJSONArray("database")
                            val dbSize = dbArray?.length() ?: 0
                            Log.d(TAG, "SYNC_DB manual: db size=$dbSize, config mode=${configObj?.optString("mode")}")
                            
                            // Build students list manually
                            val students = mutableListOf<Student>()
                            if (dbArray != null) {
                                for (i in 0 until dbArray.length()) {
                                    val sObj = dbArray.optJSONObject(i)
                                    if (sObj != null) {
                                        students.add(Student(
                                            id = sObj.optString("id", ""),
                                            nim = sObj.optString("nim", ""),
                                            nama = sObj.optString("nama", ""),
                                            status = sObj.optString("status", "pending"),
                                            assignedChannel = sObj.optInt("assignedChannel", sObj.optInt("assigned_channel", 1))
                                        ))
                                    }
                                }
                            }
                            
                            val config = ProjectConfig(
                                mode = configObj?.optString("mode", "single") ?: "single",
                                ratio = configObj?.optString("ratio", "4:3") ?: "4:3",
                                preset = configObj?.optString("preset", "original") ?: "original",
                                targetFolder = configObj?.optString("targetFolder", "") ?: "",
                                frame = configObj?.optString("frame")
                            )
                            
                            val fallbackProject = Project(
                                id = projectObj.optString("id", ""),
                                name = projectObj.optString("name", ""),
                                config = config,
                                database = students
                            )
                            val fallbackSyncData = SyncDbData(project = fallbackProject)
                            Log.i(TAG, "SYNC_DB manual fallback: project=${fallbackProject.name}, dbSize=${students.size}")
                            notifyListenersOnUiThread(SocketEvents.SYNC_DB, fallbackSyncData)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "SYNC_DB manual fallback also failed: ${e.message}")
                    }
                }
            }

            SocketEvents.STUDENT_RESET -> {
                val resetData = parseData<StudentResetData>(data)
                if (resetData != null) {
                    notifyListenersOnUiThread(SocketEvents.STUDENT_RESET, resetData)
                }
            }

            SocketEvents.FRAME_DATA -> {
                Log.d(TAG, "FRAME_DATA received (length: ${data?.toString()?.length})")
                val frameData = parseData<FrameDataPayload>(data)
                if (frameData != null) {
                    notifyListenersOnUiThread(SocketEvents.FRAME_DATA, frameData)
                }
            }

            SocketEvents.PHOTOS_SAVED -> {
                // Critical for dual-photoshoot mode: know when other operator finishes
                val photosData = parseData<PhotosSavedData>(data)
                if (photosData != null) {
                    notifyListenersOnUiThread(SocketEvents.PHOTOS_SAVED, photosData)
                }
            }

            SocketEvents.STUDENT_DONE -> {
                // In dual mode: know when other operator marks student as done
                val doneData = parseData<StudentDoneData>(data)
                if (doneData != null) {
                    notifyListenersOnUiThread(SocketEvents.STUDENT_DONE, doneData)
                }
            }

            SocketEvents.OP_PROGRESS -> {
                // Other operator's progress — informational only
            }

            SocketEvents.SERVER_SHUTDOWN -> {
                // Server sends shutdown via lan-message, not as direct event
                Log.w(TAG, "Server shutdown via LAN message: $data")
                notifyListenersOnUiThread("server_shutdown", data)
            }

            else -> {
                Log.d(TAG, "Unhandled LAN event: $event")
            }
        }
    }

    // ─── Outgoing Events ────────────────────────────────────────

    private fun identify() {
        try {
            val payload = IdentifyPayload(
                role = Roles.OPERATOR,
                channel = myChannel,
                sessionPasswordHash = passwordHash
            )
            val json = gson.toJson(payload)
            socket?.emit(SocketEvents.IDENTIFY, JSONObject(json))
            Log.i(TAG, "Identifying as operator channel $myChannel, hasPassword=${passwordHash != null}")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending identify: ${e.message}", e)
        }
    }

    fun resendWithPassword(password: String) {
        passwordHash = sha256(password)
        identify()
    }

    fun requestState() {
        connectionState = ConnectionState.WAITING_FOR_DATA
        notifyListenersOnUiThread("state_changed", connectionState)

        emitLanMessage(SocketEvents.REQUEST_STATE, RequestStateData(
            role = Roles.OPERATOR,
            channel = myChannel
        ))
    }

    fun requestFrame(projectId: String) {
        emitLanMessage(SocketEvents.REQUEST_FRAME, RequestFrameData(
            projectId = projectId,
            requesterRole = Roles.OPERATOR
        ))
    }

    fun sendStudentDone(studentId: String) {
        emitLanMessage(SocketEvents.STUDENT_DONE, StudentDoneData(
            studentId = studentId,
            channel = myChannel
        ))
    }

    fun sendPhotosSaved(student: Student, photos: List<String>, version: Int, filename: String) {
        // IMPORTANT: Set student status to "done" before sending (matches web client behavior)
        emitLanMessage(SocketEvents.PHOTOS_SAVED, PhotosSavedData(
            student = student.copy(status = "done"),
            photos = photos,
            channel = myChannel,
            version = version,
            filename = filename
        ))
    }

    fun sendOpProgress(status: String) {
        emitLanMessage(SocketEvents.OP_PROGRESS, OpProgressData(
            channel = myChannel,
            status = status
        ))
    }

    fun sendSyncDb(project: Project) {
        // Strip frame and photos before sending (like web app does)
        val strippedProject = project.copy(
            config = project.config.copy(
                frame = if (project.config.frame != null) "__FRAME_SAVED__" else null,
                sessionPassword = if (project.config.sessionPassword != null) "__PASSWORD_SET__" else null
            ),
            photoHistory = project.photoHistory.map { it.copy(photos = emptyList()) }
        )
        emitLanMessage(SocketEvents.SYNC_DB, SyncDbData(project = strippedProject))
    }

    // ─── Critical Event Queue ────────────────────────────────────

    private fun emitLanMessage(event: String, data: Any) {
        try {
            val dataJsonStr = gson.toJson(data)
            val payload = JSONObject().apply {
                put("event", event)
                put("data", if (dataJsonStr.trimStart().startsWith("[")) {
                    org.json.JSONArray(dataJsonStr)
                } else {
                    JSONObject(dataJsonStr)
                })
            }

            if (socket?.connected() == true && isAuthenticated()) {
                socket?.emit(SocketEvents.LAN_MESSAGE, payload)
                Log.d(TAG, "Emitted LAN message: $event")
            } else if (event in CRITICAL_EVENTS) {
                // Queue critical events for later delivery
                synchronized(eventQueueLock) {
                    if (eventQueue.size >= MAX_QUEUE_SIZE) {
                        eventQueue.removeAt(0)
                    }
                    eventQueue.add(QueuedEvent(event, data))
                    Log.w(TAG, "Queued critical event: $event (disconnected, queue: ${eventQueue.size})")
                }
            } else {
                Log.w(TAG, "Dropped non-critical event while disconnected: $event")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error emitting LAN message $event: ${e.message}", e)
        }
    }

    private fun flushEventQueue() {
        val toSend: List<QueuedEvent>
        synchronized(eventQueueLock) {
            if (eventQueue.isEmpty()) return
            toSend = eventQueue.toList()
            eventQueue.clear()
        }

        for (item in toSend) {
            if (item.retries >= MAX_RETRIES) {
                Log.w(TAG, "Dropping event after $MAX_RETRIES retries: ${item.event}")
                continue
            }
            item.retries++

            try {
                val dataJsonStr = gson.toJson(item.data)
                val payload = JSONObject().apply {
                    put("event", item.event)
                    put("data", if (dataJsonStr.trimStart().startsWith("[")) {
                        org.json.JSONArray(dataJsonStr)
                    } else {
                        JSONObject(dataJsonStr)
                    })
                }
                socket?.emit(SocketEvents.LAN_MESSAGE, payload)
                Log.i(TAG, "Flushed queued event: ${item.event} (attempt ${item.retries})")
            } catch (e: Exception) {
                Log.e(TAG, "Error flushing queued event ${item.event}: ${e.message}", e)
            }
        }
    }

    // ─── Ping / Latency ────────────────────────────────────────

    private fun startPingInterval() {
        stopPingInterval()
        pingIntervalJob = java.util.Timer("SaatirilPing", true).apply {
            scheduleAtFixedRate(object : java.util.TimerTask() {
                override fun run() {
                    try {
                        if (socket?.connected() == true) {
                            socket?.emit(SocketEvents.SAATIRIL_PING, System.currentTimeMillis())
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error sending ping: ${e.message}", e)
                    }
                }
            }, PING_INTERVAL_MS, PING_INTERVAL_MS)
        }
    }

    private fun stopPingInterval() {
        pingIntervalJob?.cancel()
        pingIntervalJob = null
    }

    // ─── Event System ──────────────────────────────────────────

    fun on(event: String, listener: (Any?) -> Unit) {
        listeners.getOrPut(event) { CopyOnWriteArrayList() }.add(listener)
    }

    fun off(event: String, listener: (Any?) -> Unit) {
        listeners[event]?.remove(listener)
    }

    /**
     * Notify listeners directly (for calls from the main thread).
     * Each callback is wrapped in try-catch to prevent one failing
     * listener from crashing the entire app.
     */
    private fun notifyListeners(event: String, data: Any?) {
        listeners[event]?.forEach { listener ->
            try {
                listener(data)
            } catch (e: Exception) {
                Log.e(TAG, "Error in listener for event '$event': ${e.message}", e)
            }
        }
    }

    /**
     * Notify listeners on the UI thread.
     * Socket.io callbacks run on background IO threads, but Compose
     * StateFlow collection and UI updates should happen on the main thread.
     * This ensures all ViewModel state changes are thread-safe.
     */
    private fun notifyListenersOnUiThread(event: String, data: Any?) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Already on main thread — notify directly
            notifyListeners(event, data)
        } else {
            // Post to main thread
            mainHandler.post {
                notifyListeners(event, data)
            }
        }
    }

    // ─── Utility ───────────────────────────────────────────────

    private inline fun <reified T> parseData(data: Any?): T? {
        return try {
            val jsonString = when (data) {
                is JSONObject -> data.toString()
                is String -> data
                else -> gson.toJson(data)
            }
            if (jsonString.length > 500) {
                Log.d(TAG, "parseData<${T::class.java.simpleName}>: JSON length=${jsonString.length}, preview=${jsonString.take(200)}...")
            } else {
                Log.d(TAG, "parseData<${T::class.java.simpleName}>: JSON=$jsonString")
            }
            val result = gson.fromJson(jsonString, T::class.java)
            if (result == null) {
                Log.e(TAG, "parseData<${T::class.java.simpleName}>: GSON returned null!")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse data for ${T::class.java.simpleName}: ${e.message}")
            Log.e(TAG, "Raw data type: ${data?.javaClass?.simpleName}, data preview: ${data?.toString()?.take(200)}")
            null
        }
    }

}
