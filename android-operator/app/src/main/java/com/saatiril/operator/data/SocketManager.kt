package com.saatiril.operator.data

import android.util.Log
import com.google.gson.Gson
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Manages Socket.io connection to the Saatiril server.
 * Handles authentication, event relay, and reconnection.
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
    private var connectionState = ConnectionState.DISCONNECTED
    private var passwordHash: String? = null
    private var myChannel: Int = 1
    private var pingIntervalJob: java.util.Timer? = null
    
    // Event listeners — ConcurrentHashMap for thread safety
    private val listeners = java.util.concurrent.ConcurrentHashMap<String, MutableList<(Any?) -> Unit>>()
    
    // Critical event queue — events emitted while disconnected are replayed on reconnect
    private data class QueuedEvent(
        val event: String,
        val data: Any,
        var retries: Int = 0
    )
    private val eventQueue = mutableListOf<QueuedEvent>()
    
    // ─── Connection ─────────────────────────────────────────────
    
    fun connect(serverUrl: String, channel: Int, password: String? = null) {
        // Disconnect existing socket if any, but preserve ViewModel listeners
        if (socket != null) {
            Log.w(TAG, "Existing socket found, cleaning up before reconnect")
            stopPingInterval()
            socket?.disconnect()
            socket?.off()
            socket = null
            // NOTE: Do NOT clear listeners here — ViewModel listeners must persist across reconnects
        }
        
        myChannel = channel
        passwordHash = password?.let { sha256(it) }
        connectionState = ConnectionState.CONNECTING
        notifyListeners("state_changed", connectionState)
        
        try {
            val options = IO.Options().apply {
                path = "/"  // MUST match server config (server uses path: '/')
                transports = arrayOf("websocket", "polling")
                reconnection = true
                reconnectionAttempts = Int.MAX_VALUE
                reconnectionDelay = 1000
                reconnectionDelayMax = 10_000  // Match web client
                timeout = 15_000                // Match web client
                forceNew = true                 // Match web client — prevent stale socket reuse
            }
            
            socket = IO.socket(serverUrl, options)
            setupSocketListeners()
            socket?.connect()
            
            Log.i(TAG, "Connecting to $serverUrl as operator channel $channel")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect: ${e.message}")
            connectionState = ConnectionState.DISCONNECTED
            notifyListeners("connection_error", e.message)
        }
    }
    
    fun disconnect() {
        stopPingInterval()
        socket?.disconnect()
        socket?.off()
        socket = null
        connectionState = ConnectionState.DISCONNECTED
        notifyListeners("state_changed", connectionState)
        // NOTE: Do NOT clear listeners here — they should persist for reconnect
        // Use destroy() for full cleanup when ViewModel is being destroyed
        eventQueue.clear()
    }
    
    /**
     * Full cleanup including listener removal.
     * Called only from ViewModel.onCleared() when the ViewModel is permanently destroyed.
     */
    fun destroy() {
        disconnect()
        listeners.clear()
    }
    
    fun isConnected(): Boolean = socket?.connected() == true
    
    fun isAuthenticated(): Boolean = connectionState == ConnectionState.AUTHENTICATED
    
    fun getState(): ConnectionState = connectionState
    
    // ─── Socket Event Listeners ─────────────────────────────────
    
    private fun setupSocketListeners() {
        val s = socket ?: return
        
        s.on(Socket.EVENT_CONNECT) {
            Log.i(TAG, "Socket connected")
            connectionState = ConnectionState.CONNECTED
            notifyListeners("state_changed", connectionState)
            
            // Send identify immediately (with password hash if available)
            identify()
        }
        
        s.on(Socket.EVENT_DISCONNECT) { args ->
            Log.w(TAG, "Socket disconnected: ${args.getOrElse(0) { "unknown" }}")
            connectionState = ConnectionState.DISCONNECTED
            stopPingInterval()
            notifyListeners("state_changed", connectionState)
        }
        
        s.on(Socket.EVENT_CONNECT_ERROR) { args ->
            Log.e(TAG, "Connection error: ${args.getOrElse(0) { "unknown" }}")
            // Don't set DISCONNECTED here — Socket.io auto-reconnects
            // Keep state as CONNECTING so UI shows "Menghubungkan..." instead of "Terputus"
            if (connectionState != ConnectionState.AUTHENTICATING && 
                connectionState != ConnectionState.AUTH_FAILED) {
                connectionState = ConnectionState.CONNECTING
            }
            notifyListeners("connection_error", args.getOrElse(0) { "Connection failed" })
            notifyListeners("state_changed", connectionState)
        }
        
        // ── Auth events ──────────────────────────────────────────────
        
        s.on(SocketEvents.AUTH_REQUIREMENT) { args ->
            val json = args.firstOrNull() as? JSONObject
            val passwordRequired = json?.optBoolean("passwordRequired") ?: false
            Log.i(TAG, "Auth requirement: passwordRequired=$passwordRequired")
            
            if (passwordRequired) {
                // Always show password prompt when server requires it
                // (even if we already submitted a wrong password)
                connectionState = ConnectionState.AUTHENTICATING
                notifyListeners("password_required", null)
            } else {
                // No password required — if we were in auth-failed state, re-identify
                if (connectionState == ConnectionState.AUTH_FAILED || 
                    connectionState == ConnectionState.AUTHENTICATING) {
                    passwordHash = null
                    identify()
                }
            }
            
            notifyListeners("state_changed", connectionState)
        }
        
        s.on(SocketEvents.AUTH_SUCCESS) { args ->
            val json = args.firstOrNull() as? JSONObject
            Log.i(TAG, "Auth success: $json")
            connectionState = ConnectionState.AUTHENTICATED
            notifyListeners("auth_success", json?.toString())
            notifyListeners("state_changed", connectionState)
            
            // Start ping interval for latency measurement
            startPingInterval()
            
            // Flush any queued critical events that were waiting for auth
            flushEventQueue()
            
            // Request project state from admin
            requestState()
        }
        
        s.on(SocketEvents.AUTH_FAILED) { args ->
            val json = args.firstOrNull() as? JSONObject
            val reason = json?.optString("reason") ?: "unknown"
            Log.w(TAG, "Auth failed: $reason")
            connectionState = ConnectionState.AUTH_FAILED
            notifyListeners("auth_failed", reason)
            notifyListeners("state_changed", connectionState)
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
            // Defensive number parsing — Java socket.io may return Long, Int, or Double
            val timestamp = when (val arg = args.firstOrNull()) {
                is Long -> arg
                is Int -> arg.toLong()
                is Double -> arg.toLong()
                is Number -> arg.toLong()
                else -> return@on
            }
            val latency = System.currentTimeMillis() - timestamp
            notifyListeners("latency", latency)
        }
        
        // ── LAN messages (main communication channel) ────────────────
        
        s.on(SocketEvents.LAN_MESSAGE) { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            handleLanMessage(json)
        }
    }
    
    // ─── LAN Message Handler ─────────────────────────────────────
    
    private fun handleLanMessage(json: JSONObject) {
        val event = json.optString("event")
        val data = json.opt("data")
        
        Log.d(TAG, "LAN message: $event")
        
        when (event) {
            SocketEvents.MC_CALL -> {
                val mcCallData = parseData<McCallData>(data)
                if (mcCallData != null) {
                    notifyListeners(SocketEvents.MC_CALL, mcCallData)
                }
            }
            
            SocketEvents.SYNC_DB -> {
                val syncData = parseData<SyncDbData>(data)
                if (syncData != null) {
                    notifyListeners(SocketEvents.SYNC_DB, syncData)
                }
            }
            
            SocketEvents.STUDENT_RESET -> {
                val resetData = parseData<StudentResetData>(data)
                if (resetData != null) {
                    notifyListeners(SocketEvents.STUDENT_RESET, resetData)
                }
            }
            
            SocketEvents.FRAME_DATA -> {
                val frameData = parseData<FrameDataPayload>(data)
                if (frameData != null) {
                    notifyListeners(SocketEvents.FRAME_DATA, frameData)
                }
            }
            
            SocketEvents.PHOTOS_SAVED -> {
                // Critical for dual-photoshoot mode: know when other operator finishes
                val photosData = parseData<PhotosSavedData>(data)
                if (photosData != null) {
                    notifyListeners(SocketEvents.PHOTOS_SAVED, photosData)
                }
            }
            
            SocketEvents.STUDENT_DONE -> {
                // In dual mode: know when other operator marks student as done
                val doneData = parseData<StudentDoneData>(data)
                if (doneData != null) {
                    notifyListeners(SocketEvents.STUDENT_DONE, doneData)
                }
            }
            
            SocketEvents.OP_PROGRESS -> {
                // Other operator's progress — informational only
            }
            
            SocketEvents.SERVER_SHUTDOWN -> {
                // Server sends shutdown via lan-message, not as direct event
                Log.w(TAG, "Server shutdown via LAN message: $data")
                notifyListeners("server_shutdown", data)
            }
            
            else -> {
                Log.d(TAG, "Unhandled LAN event: $event")
            }
        }
    }
    
    // ─── Outgoing Events ────────────────────────────────────────
    
    private fun identify() {
        val payload = IdentifyPayload(
            role = Roles.OPERATOR,
            channel = myChannel,
            sessionPasswordHash = passwordHash
        )
        val json = gson.toJson(payload)
        socket?.emit(SocketEvents.IDENTIFY, JSONObject(json))
        Log.i(TAG, "Identifying as operator channel $myChannel, hasPassword=${passwordHash != null}")
    }
    
    fun resendWithPassword(password: String) {
        passwordHash = sha256(password)
        identify()
    }
    
    fun requestState() {
        connectionState = ConnectionState.WAITING_FOR_DATA
        notifyListeners("state_changed", connectionState)
        
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
        val dataJsonStr = gson.toJson(data)
        val payload = JSONObject().apply {
            put("event", event)
            // Use putWithJsonAutoDetect to handle both JSONObject and JSONArray data
            // gson.toJson() may produce {...} or [...] — we need to wrap correctly
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
            if (eventQueue.size >= MAX_QUEUE_SIZE) {
                eventQueue.removeAt(0)
            }
            eventQueue.add(QueuedEvent(event, data))
            Log.w(TAG, "Queued critical event: $event (disconnected, queue: ${eventQueue.size})")
        } else {
            Log.w(TAG, "Dropped non-critical event while disconnected: $event")
        }
    }
    
    private fun flushEventQueue() {
        if (eventQueue.isEmpty()) return
        
        val toSend = eventQueue.toList()
        eventQueue.clear()
        
        for (item in toSend) {
            if (item.retries >= MAX_RETRIES) {
                Log.w(TAG, "Dropping event after ${MAX_RETRIES} retries: ${item.event}")
                continue
            }
            item.retries++
            
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
        }
    }
    
    // ─── Ping / Latency ────────────────────────────────────────
    
    private fun startPingInterval() {
        stopPingInterval()
        pingIntervalJob = java.util.Timer().apply {
            scheduleAtFixedRate(object : java.util.TimerTask() {
                override fun run() {
                    if (socket?.connected() == true) {
                        socket?.emit(SocketEvents.SAATIRIL_PING, System.currentTimeMillis())
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
        listeners.getOrPut(event) { mutableListOf() }.add(listener)
    }
    
    fun off(event: String, listener: (Any?) -> Unit) {
        listeners[event]?.remove(listener)
    }
    
    private fun notifyListeners(event: String, data: Any?) {
        listeners[event]?.toList()?.forEach { it(data) }
    }
    
    // ─── Utility ───────────────────────────────────────────────
    
    private inline fun <reified T> parseData(data: Any?): T? {
        return try {
            val jsonString = when (data) {
                is JSONObject -> data.toString()
                is String -> data
                else -> gson.toJson(data)
            }
            gson.fromJson(jsonString, T::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse data for ${T::class.java.simpleName}: ${e.message}")
            null
        }
    }
    
}
