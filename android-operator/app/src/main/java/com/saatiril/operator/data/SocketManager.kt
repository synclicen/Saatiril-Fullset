package com.saatiril.operator.data

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

/**
 * Manages Socket.io connection to the Saatiril server.
 * Handles authentication, event relay, and reconnection.
 */
class SocketManager {
    
    companion object {
        private const val TAG = "SocketManager"
        private const val IDENTIFY_TIMEOUT_MS = 30_000L
        private const val PING_INTERVAL_MS = 5_000L
    }
    
    private val gson = Gson()
    private var socket: Socket? = null
    private var connectionState = ConnectionState.DISCONNECTED
    private var passwordHash: String? = null
    private var myChannel: Int = 1
    private var pingIntervalJob: java.util.Timer? = null
    
    // Event listeners
    private val listeners = mutableMapOf<String, MutableList<(Any?) -> Unit>>()
    
    // ─── Connection ─────────────────────────────────────────────
    
    fun connect(serverUrl: String, channel: Int, password: String? = null) {
        if (socket?.connected() == true) {
            Log.w(TAG, "Already connected, disconnecting first")
            disconnect()
        }
        
        myChannel = channel
        passwordHash = password?.let { sha256(it) }
        connectionState = ConnectionState.CONNECTING
        
        try {
            val options = IO.Options().apply {
                transports = arrayOf("websocket", "polling")
                reconnection = true
                reconnectionAttempts = Int.MAX_VALUE
                reconnectionDelay = 1000
                reconnectionDelayMax = 5000
                timeout = 10_000
                maxHttpBufferLength = 20 * 1024 * 1024 // 20MB for photos
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
    }
    
    fun isConnected(): Boolean = socket?.connected() == true
    
    fun getState(): ConnectionState = connectionState
    
    // ─── Socket Event Listeners ─────────────────────────────────
    
    private fun setupSocketListeners() {
        val s = socket ?: return
        
        s.on(Socket.EVENT_CONNECT) {
            Log.i(TAG, "Socket connected")
            connectionState = ConnectionState.CONNECTED
            notifyListeners("state_changed", connectionState)
            
            // Send identify immediately
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
            connectionState = ConnectionState.DISCONNECTED
            notifyListeners("connection_error", args.getOrElse(0) { "Connection failed" })
        }
        
        s.on(SocketEvents.AUTH_REQUIREMENT) { args ->
            val json = args.firstOrNull() as? JSONObject
            val passwordRequired = json?.optBoolean("passwordRequired") ?: false
            Log.i(TAG, "Auth requirement: passwordRequired=$passwordRequired")
            
            if (passwordRequired && passwordHash == null) {
                connectionState = ConnectionState.AUTHENTICATING
                notifyListeners("password_required", null)
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
        
        s.on(SocketEvents.SAATIRIL_PONG) { args ->
            val timestamp = args.firstOrNull() as? Long ?: return@on
            val latency = System.currentTimeMillis() - timestamp
            notifyListeners("latency", latency)
        }
        
        s.on(SocketEvents.LAN_MESSAGE) { args ->
            val json = args.firstOrNull() as? JSONObject ?: return@on
            handleLanMessage(json)
        }
        
        s.on(SocketEvents.SERVER_SHUTDOWN) { args ->
            Log.w(TAG, "Server shutdown: ${args.getOrElse(0) { "" }}")
            notifyListeners("server_shutdown", args.getOrElse(0) { "" })
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
                    // Filter by channel (unless photoshoot mode)
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
            
            SocketEvents.OP_PROGRESS -> {
                // Other operator's progress, ignore
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
    
    private fun requestState() {
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
        emitLanMessage(SocketEvents.PHOTOS_SAVED, PhotosSavedData(
            student = student,
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
    
    private fun emitLanMessage(event: String, data: Any) {
        val payload = JSONObject().apply {
            put("event", event)
            put("data", JSONObject(gson.toJson(data)))
        }
        socket?.emit(SocketEvents.LAN_MESSAGE, payload)
        Log.d(TAG, "Emitted LAN message: $event")
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
    
    companion object {
        fun sha256(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
            return hashBytes.joinToString("") { "%02x".format(it) }
        }
    }
}
