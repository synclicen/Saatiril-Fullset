package com.saatiril.operator.data

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages Socket.io connection to the Saatiril server.
 * Handles authentication, event relay, and reconnection.
 *
 * Protocol compatibility: matches web client (socket.ts) and server (index.ts)
 * - path: "/" (must match server config)
 * - Events: identify, auth-requirement, auth-success, auth-failed, lan-message, saatiril-ping/pong
 * - LAN messages wrapped in { event, data } payload
 * - Critical events queued when disconnected and replayed on reconnect
 *
 * FIXES in this version:
 * - Gson configured with FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES for
 *   compatibility with both camelCase and snake_case JSON from server
 * - Robust MC_CALL and SYNC_DB parsing with multiple fallback strategies
 * - Comprehensive logging at every stage of data flow
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

    // Gson with lower_case_with_underscores policy for snake_case compatibility
    // but also handling camelCase via @SerializedName annotations on data classes
    private val gson: Gson = GsonBuilder()
        .setFieldNamingStrategy { f ->
            // Use @SerializedName if present
            val annotation = f.getAnnotation(com.google.gson.annotations.SerializedName::class.java)
            if (annotation != null) {
                annotation.value
            } else {
                // Default: use the field name as-is (camelCase)
                f.name
            }
        }
        .create()

    private var socket: Socket? = null

    // @Volatile ensures cross-thread visibility for connectionState
    @Volatile
    private var connectionState = ConnectionState.DISCONNECTED

    @Volatile
    private var passwordHash: String? = null

    @Volatile
    private var myChannel: Int = 1

    @Volatile
    private var connectErrorCount: Int = 0

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
        }

        myChannel = channel
        passwordHash = password?.let { sha256(it) }
        connectErrorCount = 0  // Reset error counter on new connection attempt
        connectionState = ConnectionState.CONNECTING
        notifyListenersOnUiThread("state_changed", connectionState)

        // Validate URL BEFORE passing to IO.socket()
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
                path = "/"  // MUST match server config
                transports = arrayOf("websocket", "polling")
                reconnection = true
                reconnectionAttempts = 20
                reconnectionDelay = 1000
                reconnectionDelayMax = 10_000
                timeout = 15_000
                forceNew = true
            }

            socket = try {
                IO.socket(validatedUrl, options)
            } catch (e: NoSuchMethodError) {
                Log.e(TAG, "OkHttp method not found — dependency conflict! ${e.message}", e)
                connectionState = ConnectionState.DISCONNECTED
                notifyListenersOnUiThread("state_changed", ConnectionState.DISCONNECTED)
                notifyListenersOnUiThread("connection_error", "Library conflict: ${e.message}")
                return
            } catch (e: NoClassDefFoundError) {
                Log.e(TAG, "OkHttp class not found — dependency conflict! ${e.message}", e)
                connectionState = ConnectionState.DISCONNECTED
                notifyListenersOnUiThread("state_changed", ConnectionState.DISCONNECTED)
                notifyListenersOnUiThread("connection_error", "Library conflict: ${e.message}")
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
        synchronized(eventQueueLock) {
            eventQueue.clear()
        }
    }

    fun destroy() {
        disconnect()
        listeners.clear()
        mainHandler.removeCallbacksAndMessages(null)
    }

    fun isConnected(): Boolean = socket?.connected() == true

    fun isAuthenticated(): Boolean = connectionState == ConnectionState.AUTHENTICATED ||
            connectionState == ConnectionState.WAITING_FOR_DATA

    fun getState(): ConnectionState = connectionState

    // ─── Socket Event Listeners ─────────────────────────────────

    private fun setupSocketListeners() {
        val s = socket ?: return

        s.on(Socket.EVENT_CONNECT) {
            try {
                Log.i(TAG, "Socket connected")
                connectErrorCount = 0  // Reset error counter on successful connect
                connectionState = ConnectionState.CONNECTED
                notifyListenersOnUiThread("state_changed", connectionState)
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

                // Count connect errors — if we keep failing, show DISCONNECTED
                // with a useful error instead of staying stuck at CONNECTING forever.
                connectErrorCount++
                if (connectErrorCount >= 3) {
                    Log.w(TAG, "Connection failed $connectErrorCount times — switching to DISCONNECTED state")
                    connectionState = ConnectionState.DISCONNECTED
                    notifyListenersOnUiThread("connection_error",
                        "Tidak dapat terhubung ke server. Pastikan:\n" +
                        "1. IP & Port benar\n" +
                        "2. Server berjalan di jaringan yang sama\n" +
                        "3. Tidak ada firewall yang memblokir"
                    )
                } else if (connectionState != ConnectionState.AUTHENTICATING &&
                    connectionState != ConnectionState.AUTH_FAILED) {
                    connectionState = ConnectionState.CONNECTING
                    notifyListenersOnUiThread("connection_error", "Mencoba menghubungkan... (percobaan $connectErrorCount)")
                }
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
                    connectionState = ConnectionState.AUTHENTICATING
                    notifyListenersOnUiThread("password_required", null)
                } else {
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
                startPingInterval()
                flushEventQueue()
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
            Log.i(TAG, "Session password set by admin")
        }

        s.on(SocketEvents.CLEAR_SESSION_PASSWORD) {
            Log.i(TAG, "Session password cleared by admin")
            if (connectionState == ConnectionState.AUTH_FAILED ||
                connectionState == ConnectionState.AUTHENTICATING) {
                passwordHash = null
                identify()
            }
        }

        // ── Latency measurement ──────────────────────────────────────

        s.on(SocketEvents.SAATIRIL_PONG) { args ->
            try {
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
                val json = args.firstOrNull() as? JSONObject
                if (json == null) {
                    Log.w(TAG, "LAN_MESSAGE: args are null or not JSONObject — args types: ${args?.map { it?.javaClass?.simpleName }}")
                    return@on
                }
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

        Log.d(TAG, "LAN message received: event=$event, dataType=${data?.javaClass?.simpleName}")

        when (event) {
            SocketEvents.MC_CALL -> {
                Log.i(TAG, "MC_CALL received — raw data type: ${data?.javaClass?.simpleName}")
                Log.d(TAG, "MC_CALL raw data: ${data?.toString()?.take(300)}")

                // Strategy 1: Try Gson parsing
                val mcCallData = parseData<McCallData>(data)
                if (mcCallData != null && mcCallData.student.nim.isNotBlank()) {
                    Log.i(TAG, "MC_CALL parsed (Gson): student=${mcCallData.student.nama}, nim=${mcCallData.student.nim}, ch=${mcCallData.channel}, status=${mcCallData.student.status}, assignedCh=${mcCallData.student.assignedChannel}")
                    notifyListenersOnUiThread(SocketEvents.MC_CALL, mcCallData)
                    return
                }

                // Strategy 2: Manual JSONObject extraction
                Log.w(TAG, "MC_CALL: Gson parsing failed or returned empty student — trying manual extraction")
                try {
                    val dataObj = data as? JSONObject ?: json.optJSONObject("data")
                    if (dataObj != null) {
                        val studentObj = dataObj.optJSONObject("student")
                        if (studentObj != null) {
                            val fallbackStudent = parseStudentFromJson(studentObj)
                            val fallbackMcCall = McCallData(
                                student = fallbackStudent,
                                channel = dataObj.optInt("channel", 1)
                            )
                            Log.i(TAG, "MC_CALL parsed (manual): student=${fallbackStudent.nama}, nim=${fallbackStudent.nim}, ch=${fallbackMcCall.channel}, status=${fallbackStudent.status}")
                            notifyListenersOnUiThread(SocketEvents.MC_CALL, fallbackMcCall)
                            return
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "MC_CALL manual extraction also failed: ${e.message}")
                }

                Log.e(TAG, "MC_CALL: ALL parsing strategies failed — data dropped!")
            }

            SocketEvents.SYNC_DB -> {
                Log.i(TAG, "SYNC_DB received — raw data type: ${data?.javaClass?.simpleName}")
                Log.d(TAG, "SYNC_DB raw data length: ${data?.toString()?.length}")

                // Strategy 1: Gson parsing
                val syncData = parseData<SyncDbData>(data)
                if (syncData != null && syncData.project.name.isNotBlank()) {
                    Log.i(TAG, "SYNC_DB parsed (Gson): project=${syncData.project.name}, dbSize=${syncData.project.database.size}, mode=${syncData.project.config.mode}, targetFolder=${syncData.project.config.targetFolder}")
                    if (syncData.project.database.isNotEmpty()) {
                        Log.d(TAG, "SYNC_DB first student: ${syncData.project.database.first().nama} (status=${syncData.project.database.first().status}, ch=${syncData.project.database.first().assignedChannel})")
                    }
                    notifyListenersOnUiThread(SocketEvents.SYNC_DB, syncData)
                    return
                }

                // Strategy 2: Manual JSONObject extraction
                Log.w(TAG, "SYNC_DB: Gson parsing failed — trying manual extraction")
                try {
                    val dataObj = data as? JSONObject ?: json.optJSONObject("data")
                    if (dataObj != null) {
                        val projectObj = dataObj.optJSONObject("project")
                        if (projectObj != null) {
                            val manualProject = parseProjectFromJson(projectObj)
                            Log.i(TAG, "SYNC_DB parsed (manual): project=${manualProject.name}, dbSize=${manualProject.database.size}, mode=${manualProject.config.mode}, targetFolder=${manualProject.config.targetFolder}")
                            notifyListenersOnUiThread(SocketEvents.SYNC_DB, SyncDbData(project = manualProject))
                            return
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "SYNC_DB manual extraction also failed: ${e.message}")
                }

                Log.e(TAG, "SYNC_DB: ALL parsing strategies failed — data dropped!")
            }

            SocketEvents.STUDENT_RESET -> {
                Log.d(TAG, "STUDENT_RESET received: $data")
                val resetData = parseData<StudentResetData>(data)
                if (resetData != null) {
                    Log.i(TAG, "STUDENT_RESET: studentId=${resetData.studentId}, channel=${resetData.channel}")
                    notifyListenersOnUiThread(SocketEvents.STUDENT_RESET, resetData)
                } else {
                    // Manual fallback
                    try {
                        val dataObj = data as? JSONObject
                        if (dataObj != null) {
                            val manualReset = StudentResetData(
                                studentId = dataObj.optString("studentId", dataObj.optString("student_id", "")),
                                channel = dataObj.optInt("channel", 1)
                            )
                            Log.i(TAG, "STUDENT_RESET (manual): studentId=${manualReset.studentId}, channel=${manualReset.channel}")
                            notifyListenersOnUiThread(SocketEvents.STUDENT_RESET, manualReset)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "STUDENT_RESET manual fallback failed: ${e.message}")
                    }
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
                val photosData = parseData<PhotosSavedData>(data)
                if (photosData != null) {
                    notifyListenersOnUiThread(SocketEvents.PHOTOS_SAVED, photosData)
                }
            }

            SocketEvents.STUDENT_DONE -> {
                val doneData = parseData<StudentDoneData>(data)
                if (doneData != null) {
                    notifyListenersOnUiThread(SocketEvents.STUDENT_DONE, doneData)
                }
            }

            SocketEvents.OP_PROGRESS -> {
                // Other operator's progress — informational only
            }

            SocketEvents.SERVER_SHUTDOWN -> {
                Log.w(TAG, "Server shutdown via LAN message: $data")
                notifyListenersOnUiThread("server_shutdown", data)
            }

            else -> {
                Log.d(TAG, "Unhandled LAN event: $event")
            }
        }
    }

    // ─── Manual JSON Parsing Helpers ────────────────────────────
    // These handle cases where Gson fails (e.g., field name mismatches,
    // unexpected JSON structure, etc.)

    private fun parseStudentFromJson(obj: JSONObject): Student {
        return Student(
            id = obj.optString("id", ""),
            nim = obj.optString("nim", ""),
            nama = obj.optString("nama", obj.optString("name", "")),
            status = obj.optString("status", "pending"),
            assignedChannel = obj.optInt("assignedChannel", obj.optInt("assigned_channel", 1))
        )
    }

    private fun parseProjectFromJson(obj: JSONObject): Project {
        val configObj = obj.optJSONObject("config")
        val config = if (configObj != null) {
            // Handle nullable fields carefully: optString doesn't accept null fallback
            val frameValue = configObj.optString("frame", "")
            val sessionPasswordValue = configObj.optString("sessionPassword", configObj.optString("session_password", ""))
            ProjectConfig(
                mode = configObj.optString("mode", "single"),
                ratio = configObj.optString("ratio", "4:3"),
                preset = configObj.optString("preset", "original"),
                targetFolder = configObj.optString("targetFolder", configObj.optString("target_folder", "")),
                frame = frameValue.ifBlank { null },
                sessionPassword = sessionPasswordValue.ifBlank { null }
            )
        } else {
            ProjectConfig()
        }

        val dbArray = obj.optJSONArray("database")
        val students = mutableListOf<Student>()
        if (dbArray != null) {
            for (i in 0 until dbArray.length()) {
                val sObj = dbArray.optJSONObject(i)
                if (sObj != null) {
                    students.add(parseStudentFromJson(sObj))
                }
            }
        }

        val historyArray = obj.optJSONArray("photoHistory")
        val photoHistory = mutableListOf<PhotoHistoryItem>()
        if (historyArray != null) {
            for (i in 0 until historyArray.length()) {
                val hObj = historyArray.optJSONObject(i)
                if (hObj != null) {
                    val studentObj = hObj.optJSONObject("student")
                    val photoStudent = if (studentObj != null) parseStudentFromJson(studentObj) else Student()
                    val photosArray = hObj.optJSONArray("photos")
                    val photos = mutableListOf<String>()
                    if (photosArray != null) {
                        for (j in 0 until photosArray.length()) {
                            photos.add(photosArray.getString(j))
                        }
                    }
                    photoHistory.add(PhotoHistoryItem(
                        student = photoStudent,
                        photos = photos,
                        channel = hObj.optInt("channel", 1)
                    ))
                }
            }
        }

        val versionsObj = obj.optJSONObject("captureVersions")
        val captureVersions = mutableMapOf<String, Int>()
        if (versionsObj != null) {
            val keysIterator = versionsObj.keys()
            while (keysIterator.hasNext()) {
                val rawKey = keysIterator.next()
                val key = rawKey?.toString() ?: continue
                captureVersions[key] = versionsObj.optInt(key, 0)
            }
        }

        return Project(
            id = obj.optString("id", ""),
            name = obj.optString("name", ""),
            config = config,
            database = students,
            photoHistory = photoHistory,
            captureVersions = captureVersions
        )
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
        Log.i(TAG, "sendStudentDone: studentId=$studentId, ch=$myChannel, connected=${socket?.connected()}, authenticated=${isAuthenticated()}")
        emitLanMessage(SocketEvents.STUDENT_DONE, StudentDoneData(
            studentId = studentId,
            channel = myChannel
        ))
    }

    fun sendPhotosSaved(student: Student, photos: List<String>, version: Int, filename: String) {
        val data = PhotosSavedData(
            student = student.copy(status = "done"),
            photos = photos,
            channel = myChannel,
            version = version,
            filename = filename
        )
        Log.i(TAG, "sendPhotosSaved: student=${student.nama} (id=${student.id}), photos=${photos.size}, ch=$myChannel, ver=$version, filename=$filename, connected=${socket?.connected()}, authenticated=${isAuthenticated()}")
        emitLanMessage(SocketEvents.PHOTOS_SAVED, data)
    }

    fun sendOpProgress(status: String) {
        emitLanMessage(SocketEvents.OP_PROGRESS, OpProgressData(
            channel = myChannel,
            status = status
        ))
    }

    fun sendSyncDb(project: Project) {
        // Strip frame and photos before sending
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
                    JSONArray(dataJsonStr)
                } else {
                    JSONObject(dataJsonStr)
                })
            }

            if (socket?.connected() == true && isAuthenticated()) {
                socket?.emit(SocketEvents.LAN_MESSAGE, payload)
                Log.d(TAG, "Emitted LAN message: $event")
            } else if (event in CRITICAL_EVENTS) {
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
                        JSONArray(dataJsonStr)
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

    private fun notifyListeners(event: String, data: Any?) {
        listeners[event]?.forEach { listener ->
            try {
                listener(data)
            } catch (e: Exception) {
                Log.e(TAG, "Error in listener for event '$event': ${e.message}", e)
            }
        }
    }

    private fun notifyListenersOnUiThread(event: String, data: Any?) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            notifyListeners(event, data)
        } else {
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
