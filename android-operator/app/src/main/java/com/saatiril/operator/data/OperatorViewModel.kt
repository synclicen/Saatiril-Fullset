package com.saatiril.operator.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.saatiril.operator.camera.BuiltInCameraManager
import com.saatiril.operator.camera.CameraCapture
import com.saatiril.operator.camera.UVCCameraManager
import com.saatiril.operator.util.FilenameUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Central ViewModel managing all operator state:
 * - Socket.io connection and authentication
 * - Camera management (built-in + UVC)
 * - Current project data (students, config, frame)
 * - Capture state machine (standby → ready-1 → ready-2 → sending)
 * - Gridline settings
 * - Connection health
 * - REQUEST_STATE retry loop (matches web client behavior)
 */
class OperatorViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "OperatorViewModel"
        private const val STATE_REQUEST_INTERVAL_MS = 3000L  // Match web client
    }

    private val socketManager = SocketManager()

    // ─── Camera Managers ───────────────────────────────────────

    val builtInCameraManager = BuiltInCameraManager(application)
    val uvcCameraManager = UVCCameraManager(application)

    // ─── Connection State ───────────────────────────────────────

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _serverUrl = MutableStateFlow("")
    val serverUrl: StateFlow<String> = _serverUrl.asStateFlow()

    private val _myChannel = MutableStateFlow(1)
    val myChannel: StateFlow<Int> = _myChannel.asStateFlow()

    private val _latencyMs = MutableStateFlow(-1L)
    val latencyMs: StateFlow<Long> = _latencyMs.asStateFlow()

    private val _passwordRequired = MutableStateFlow(false)
    val passwordRequired: StateFlow<Boolean> = _passwordRequired.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    // ─── Project State ──────────────────────────────────────────

    private val _project = MutableStateFlow<Project?>(null)
    val project: StateFlow<Project?> = _project.asStateFlow()

    private val _currentTarget = MutableStateFlow<Student?>(null)
    val currentTarget: StateFlow<Student?> = _currentTarget.asStateFlow()

    // ─── Capture State ──────────────────────────────────────────

    private val _capturePhase = MutableStateFlow(CapturePhase.STANDBY)
    val capturePhase: StateFlow<CapturePhase> = _capturePhase.asStateFlow()

    private val _capturedPhotos = MutableStateFlow<List<String>>(emptyList())
    val capturedPhotos: StateFlow<List<String>> = _capturedPhotos.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    // ─── Gridline Settings ──────────────────────────────────────

    private val _gridlineSettings = MutableStateFlow(GridlineSettings())
    val gridlineSettings: StateFlow<GridlineSettings> = _gridlineSettings.asStateFlow()

    // ─── Shutter Mode ──────────────────────────────────────────

    private val _shutterMode = MutableStateFlow("manual") // "manual", "timer-3", "timer-5", "timer-10", "ai"
    val shutterMode: StateFlow<String> = _shutterMode.asStateFlow()

    private val _timerCountdown = MutableStateFlow(0)
    val timerCountdown: StateFlow<Int> = _timerCountdown.asStateFlow()

    private var timerJob: Job? = null

    // ─── Op Search (Photoshoot Mode) ────────────────────────────

    private val _opSearchQuery = MutableStateFlow("")
    val opSearchQuery: StateFlow<String> = _opSearchQuery.asStateFlow()

    // ─── Camera Source ──────────────────────────────────────────

    private val _cameraSource = MutableStateFlow("none") // "uvc", "builtin", "none"
    val cameraSource: StateFlow<String> = _cameraSource.asStateFlow()

    private val _uvcDeviceAttached = MutableStateFlow(false)
    val uvcDeviceAttached: StateFlow<Boolean> = _uvcDeviceAttached.asStateFlow()

    private val _cameraConnected = MutableStateFlow(false)
    val cameraConnected: StateFlow<Boolean> = _cameraConnected.asStateFlow()

    // ─── Frame Overlay ──────────────────────────────────────────

    private val _frameBitmap = MutableStateFlow<Bitmap?>(null)
    val frameBitmap: StateFlow<Bitmap?> = _frameBitmap.asStateFlow()

    // ─── State Request Retry ────────────────────────────────────
    // Matches web client behavior: keep requesting state until project is received

    private var stateRequestJob: Job? = null

    private fun startStateRequestLoop() {
        stateRequestJob?.cancel()
        stateRequestJob = viewModelScope.launch {
            while (isActive && _project.value == null) {
                socketManager.requestState()
                delay(STATE_REQUEST_INTERVAL_MS)
            }
        }
        Log.d(TAG, "State request loop started")
    }

    private fun stopStateRequestLoop() {
        stateRequestJob?.cancel()
        stateRequestJob = null
    }

    // ─── Camera Lifecycle ───────────────────────────────────────

    // CRITICAL FIX: Track camera collector Jobs to prevent duplicate collectors
    // on re-init (e.g., when permission is granted after first attempt)
    private var cameraConnectedCollector: Job? = null
    private var cameraTypeCollector: Job? = null
    private var uvcConnectedCollector: Job? = null
    private var uvcManagerInitialized: Boolean = false

    /**
     * Initialize camera when entering OperatorScreen.
     * Must be called with the Activity's LifecycleOwner and a PreviewView.
     *
     * IDEMPOTENT: Safe to call multiple times. Old flow collectors are cancelled
     * before creating new ones. BuiltInCameraManager.init() is also idempotent.
     */
    fun initCamera(lifecycleOwner: LifecycleOwner, previewView: androidx.camera.view.PreviewView) {
        // UVC manager init is also idempotent, but we only need to init once
        if (!uvcManagerInitialized) {
            uvcCameraManager.init()
            uvcManagerInitialized = true
        }

        // CRITICAL FIX: Cancel old collectors FIRST to prevent receiving transient state
        // when BuiltInCameraManager.init() resets _isConnected during camera selection
        cameraConnectedCollector?.cancel()
        cameraTypeCollector?.cancel()
        uvcConnectedCollector?.cancel()

        // BuiltInCameraManager.init() is now idempotent — handles re-init gracefully
        builtInCameraManager.init(lifecycleOwner, previewView)

        // Create new collectors after init
        cameraConnectedCollector = viewModelScope.launch {
            builtInCameraManager.isConnected.collect { connected ->
                _cameraConnected.value = connected
                updateCameraSource()
            }
        }

        cameraTypeCollector = viewModelScope.launch {
            builtInCameraManager.cameraType.collect { type ->
                updateCameraSource()
            }
        }

        uvcConnectedCollector = viewModelScope.launch {
            uvcCameraManager.isConnected.collect { uvcConnected ->
                _uvcDeviceAttached.value = uvcConnected
                builtInCameraManager.rescanForExternalCamera()
                updateCameraSource()
            }
        }

        Log.i(TAG, "Camera initialized — collectors active, cameraSource=${_cameraSource.value}")
    }

    /**
     * Update camera source based on the actual camera type reported by BuiltInCameraManager.
     */
    private fun updateCameraSource() {
        val connected = builtInCameraManager.isConnected.value
        val cameraType = builtInCameraManager.cameraType.value

        if (!connected) {
            _cameraSource.value = "none"
            return
        }

        _cameraSource.value = when (cameraType) {
            "external" -> "uvc"      // USB HDMI capture card → display as "USB Capture Card"
            "back" -> "builtin"       // Built-in back camera → display as "Kamera HP"
            "front" -> "builtin"      // Built-in front camera → display as "Kamera HP"
            else -> "none"
        }
    }

    /**
     * Switch between front and back camera
     */
    fun switchCamera() {
        builtInCameraManager.switchCamera()
    }

    /**
     * Capture a photo from the current camera.
     * Supports timer mode: starts countdown before capture.
     * The flow: camera captures → CameraCapture processes (crop, filter, frame) → ViewModel receives Bitmap → base64 encode → send
     */
    fun triggerCapture() {
        val phase = _capturePhase.value
        if (phase == CapturePhase.SENDING || _isSending.value) return
        if (phase != CapturePhase.READY_1 && phase != CapturePhase.READY_2) return

        // If timer mode, start countdown instead of immediate capture
        if (isTimerMode() && _timerCountdown.value <= 0) {
            startTimer { doCapture() }
            return
        }

        doCapture()
    }

    /**
     * Cancel timer countdown and revert to ready state.
     */
    fun cancelTimerCapture() {
        cancelTimer()
    }

    private fun doCapture() {
        builtInCameraManager.capturePhoto { bitmap ->
            if (bitmap == null) {
                Log.e(TAG, "Capture returned null bitmap")
                return@capturePhoto
            }

            // Process with CameraCapture (crop, filter, frame overlay)
            val config = _project.value?.config ?: return@capturePhoto
            val processedBitmap = CameraCapture.processFrame(
                sourceBitmap = bitmap,
                config = config,
                frameBitmap = _frameBitmap.value
            )

            // Now handle the processed bitmap through the capture flow
            handleCapturedBitmap(processedBitmap)
        }
    }

    /**
     * Handle a processed bitmap from capture.
     * Converts to base64 and adds to capturedPhotos list,
     * then either waits for next pose or finalizes.
     */
    private fun handleCapturedBitmap(bitmap: Bitmap) {
        val mode = _project.value?.config?.mode ?: return
        val target = _currentTarget.value ?: return
        val photosPerSession = CameraModes.photosPerSession(mode)

        viewModelScope.launch {
            // Convert bitmap to base64 JPEG
            val base64 = bitmapToBase64(bitmap)
            val currentPhotos = _capturedPhotos.value.toMutableList()
            currentPhotos.add(base64)
            _capturedPhotos.value = currentPhotos

            if (photosPerSession == 1 || currentPhotos.size >= photosPerSession) {
                // All photos captured — finalize
                finalizeCapture(target, currentPhotos)
            } else {
                // More photos needed
                _capturePhase.value = CapturePhase.READY_2
                socketManager.sendOpProgress("Pose 1 OK — Siap Foto 2")
            }
        }
    }

    // ─── Connection ─────────────────────────────────────────────

    // Track whether socket listeners have been set up to prevent double registration
    private var socketListenersInitialized = false

    fun connect(serverUrl: String, channel: Int, password: String? = null) {
        _serverUrl.value = serverUrl
        _myChannel.value = channel
        _authError.value = null
        _connectionError.value = null

        // Only set up listeners once — they persist across reconnects
        if (!socketListenersInitialized) {
            setupSocketListeners()
            socketListenersInitialized = true
        }
        socketManager.connect(serverUrl, channel, password)
    }

    fun disconnect() {
        stopStateRequestLoop()
        socketManager.disconnect()
        _project.value = null
        _currentTarget.value = null
        _capturePhase.value = CapturePhase.STANDBY
        _capturedPhotos.value = emptyList()
    }

    fun submitPassword(password: String) {
        _authError.value = null
        socketManager.resendWithPassword(password)
    }

    // ─── Socket Event Listeners ─────────────────────────────────

    private fun setupSocketListeners() {
        socketManager.on("state_changed") { state ->
            val cs = state as? ConnectionState ?: return@on
            _connectionState.value = cs
        }

        socketManager.on("password_required") {
            _passwordRequired.value = true
        }

        socketManager.on("auth_success") {
            _passwordRequired.value = false
            _authError.value = null

            // Start requesting state from admin (retry loop)
            startStateRequestLoop()
        }

        socketManager.on("auth_failed") { reason ->
            val reasonStr = reason as? String ?: "unknown"
            _authError.value = reasonStr
            if (reasonStr == "session_password_required") {
                _passwordRequired.value = true
            }
        }

        socketManager.on("connection_error") { error ->
            val errorStr = error?.toString() ?: "Connection failed"
            Log.e(TAG, "Connection error: $errorStr")
            _connectionError.value = errorStr
        }

        socketManager.on("latency") { latency ->
            _latencyMs.value = latency as? Long ?: -1L
        }

        socketManager.on("server_shutdown") {
            stopStateRequestLoop()
            disconnect()
        }

        // ─── Application Events ────────────────────────────────

        socketManager.on(SocketEvents.MC_CALL) { data ->
            val mcCall = data as? McCallData ?: return@on
            val mode = _project.value?.config?.mode ?: return@on

            // Filter by channel (except photoshoot mode where all calls are relevant)
            if (!CameraModes.isPhotoshootMode(mode) && mcCall.channel != _myChannel.value) {
                Log.d(TAG, "MC_CALL for channel ${mcCall.channel}, ignoring (my channel: ${_myChannel.value})")
                return@on
            }

            _currentTarget.value = mcCall.student

            val photosPerSession = CameraModes.photosPerSession(mode)
            _capturePhase.value = CapturePhase.READY_1
            _capturedPhotos.value = emptyList()

            Log.i(TAG, "MC_CALL: ${mcCall.student.nama} (channel ${mcCall.channel})")
        }

        socketManager.on(SocketEvents.SYNC_DB) { data ->
            val syncData = data as? SyncDbData ?: return@on
            handleSyncDb(syncData.project)

            // Stop the state request loop once we have project data
            if (_project.value != null) {
                stopStateRequestLoop()
            }
        }

        socketManager.on(SocketEvents.STUDENT_RESET) { data ->
            val resetData = data as? StudentResetData ?: return@on

            // Filter by channel
            val mode = _project.value?.config?.mode ?: return@on
            if (!CameraModes.isPhotoshootMode(mode) && resetData.channel != _myChannel.value) return@on

            // Clear current target if it matches
            if (_currentTarget.value?.id == resetData.studentId) {
                _currentTarget.value = null
                _capturePhase.value = CapturePhase.STANDBY
                _capturedPhotos.value = emptyList()
            }

            // Update project database — reset student status
            _project.value?.let { proj ->
                val updatedDb = proj.database.map { student ->
                    if (student.id == resetData.studentId) {
                        student.copy(status = "pending")
                    } else student
                }
                _project.value = proj.copy(database = updatedDb)
            }

            Log.i(TAG, "STUDENT_RESET: ${resetData.studentId}")
        }

        socketManager.on(SocketEvents.FRAME_DATA) { data ->
            val frameData = data as? FrameDataPayload ?: return@on
            Log.i(TAG, "FRAME_DATA received: ${frameData.frame.take(50)}...")
            _project.value?.let { proj ->
                _project.value = proj.copy(
                    config = proj.config.copy(frame = frameData.frame)
                )
            }
            // Decode base64 frame to Bitmap for overlay rendering
            decodeFrameBitmap(frameData.frame)
        }

        // ── PHOTOS_SAVED from other operators (dual-photoshoot mode) ──
        // When another operator finishes photographing the same student,
        // we may need to clear our target (dual-photoshoot: either camera is enough)

        socketManager.on(SocketEvents.PHOTOS_SAVED) { data ->
            val photosSaved = data as? PhotosSavedData ?: return@on
            val mode = _project.value?.config?.mode ?: return@on

            // Only relevant in dual-photoshoot mode
            if (!CameraModes.isPhotoshootMode(mode) || !CameraModes.isDualMode(mode)) return@on

            _currentTarget.value?.let { target ->
                if (target.id == photosSaved.student.id) {
                    // Other operator finished our target — clear it
                    _currentTarget.value = null
                    _capturePhase.value = CapturePhase.STANDBY
                    _capturedPhotos.value = emptyList()
                    Log.i(TAG, "Target cleared — other operator finished: ${target.nama}")
                }
            }
        }

        // ── STUDENT_DONE from other operators (dual mode) ──
        // In standard dual mode, we want to know when the other operator marks done

        socketManager.on(SocketEvents.STUDENT_DONE) { data ->
            val doneData = data as? StudentDoneData ?: return@on
            val mode = _project.value?.config?.mode ?: return@on

            // Only relevant in dual mode and from a different channel
            if (!CameraModes.isDualMode(mode) || doneData.channel == _myChannel.value) return@on

            // Update local project state for this student
            _project.value?.let { proj ->
                val updatedDb = proj.database.map { student ->
                    if (student.id == doneData.studentId) {
                        student.copy(status = "done")
                    } else student
                }
                _project.value = proj.copy(database = updatedDb)
            }

            Log.d(TAG, "STUDENT_DONE from other operator: ${doneData.studentId}")
        }
    }

    // ─── Sync DB Handler ────────────────────────────────────────

    private fun handleSyncDb(incomingProject: Project) {
        val currentProject = _project.value

        if (currentProject == null) {
            // First sync — use incoming data directly
            _project.value = incomingProject
            Log.i(TAG, "SYNC_DB: First sync, project: ${incomingProject.name}")
            // Decode frame bitmap if present
            decodeFrameBitmap(incomingProject.config.frame)
            // If we have __FRAME_SAVED__ marker, request actual frame
            if (incomingProject.config.frame == "__FRAME_SAVED__") {
                socketManager.requestFrame(incomingProject.id)
            }
            return
        }

        // Merge databases — keep the "most advanced" status per student
        val mergedDb = mergeDatabases(currentProject.database, incomingProject.database)

        // Merge captureVersions — take max per key
        val mergedVersions = mergeCaptureVersions(
            currentProject.captureVersions, incomingProject.captureVersions
        )

        // Merge photoHistory — if incoming has photos use incoming; if stripped keep local
        val mergedHistory = mergePhotoHistory(currentProject.photoHistory, incomingProject.photoHistory)

        // Preserve frame if incoming has marker
        val preservedFrame = if (incomingProject.config.frame == "__FRAME_SAVED__") {
            currentProject.config.frame
        } else {
            incomingProject.config.frame
        }

        val mergedProject = incomingProject.copy(
            database = mergedDb,
            captureVersions = mergedVersions,
            photoHistory = mergedHistory,
            config = incomingProject.config.copy(frame = preservedFrame)
        )

        _project.value = mergedProject

        // If current target became 'done' in merged data (dual-photoshoot race condition)
        _currentTarget.value?.let { target ->
            val updatedStudent = mergedDb.find { it.id == target.id }
            if (updatedStudent?.status == "done") {
                _currentTarget.value = null
                _capturePhase.value = CapturePhase.STANDBY
                _capturedPhotos.value = emptyList()
                Log.i(TAG, "Current target became done via sync, clearing")
            }
        }

        // Handle frame bitmap decoding
        if (preservedFrame == "__FRAME_SAVED__") {
            // Request actual frame data from admin
            socketManager.requestFrame(incomingProject.id)
        } else if (preservedFrame != currentProject.config.frame) {
            // Frame changed — decode the new frame
            decodeFrameBitmap(preservedFrame)
        }
    }

    private fun mergeDatabases(local: List<Student>, incoming: List<Student>): List<Student> {
        val statusPriority = mapOf(
            "pending" to 0, "sent" to 1
        )

        val localMap = local.associateBy { it.id }

        return incoming.map { inc ->
            val loc = localMap[inc.id]
            if (loc == null) return@map inc

            val locPriority = statusPriority[loc.status] ?: (if (loc.status.startsWith("active_")) 2 else 3)
            val incPriority = statusPriority[inc.status] ?: (if (inc.status.startsWith("active_")) 2 else 3)

            if (locPriority >= incPriority) loc else inc
        }
    }

    private fun mergeCaptureVersions(
        local: Map<String, Int>, incoming: Map<String, Int>
    ): Map<String, Int> {
        val allKeys = local.keys + incoming.keys
        return allKeys.associateWith { key ->
            maxOf(local[key] ?: 0, incoming[key] ?: 0)
        }
    }

    private fun mergePhotoHistory(
        local: List<PhotoHistoryItem>, incoming: List<PhotoHistoryItem>
    ): List<PhotoHistoryItem> {
        val localMap = local.associateBy { "${it.student.id}_${it.channel}" }

        return incoming.map { inc ->
            val key = "${inc.student.id}_${inc.channel}"
            val loc = localMap[key]

            if (loc != null && inc.photos.isEmpty() && loc.photos.isNotEmpty()) {
                loc // Keep local if incoming is stripped
            } else {
                inc
            }
        }
    }

    // ─── Photo Capture ──────────────────────────────────────────

    private suspend fun finalizeCapture(student: Student, photos: List<String>) {
        val mode = _project.value?.config?.mode ?: return
        val proj = _project.value ?: return

        _capturePhase.value = CapturePhase.SENDING
        _isSending.value = true

        try {
            // Get capture version
            val versionKey = "${student.id}_${_myChannel.value}"
            val currentVersion = proj.captureVersions[versionKey] ?: 0
            val newVersion = currentVersion + 1

            // Build filename
            val filename = if (CameraModes.isPhotoshootMode(mode)) {
                FilenameUtils.buildPhotoshootFilename(
                    student.nim, student.nama, _myChannel.value, newVersion
                )
            } else {
                // Standard mode: first file uses suffix 1 (Toga), second uses suffix 2 (Ijazah)
                // But we send all photos in one event, so use channel as suffix
                val suffix = _myChannel.value
                FilenameUtils.buildStandardFilename(
                    student.nim, student.nama, suffix, "Toga", newVersion
                )
            }

            // 1. Send STUDENT_DONE first (lightweight, lets MC call next student immediately)
            if (!CameraModes.isPhotoshootMode(mode)) {
                socketManager.sendStudentDone(student.id)
            }

            // 2. Send PHOTOS_SAVED with all photo data
            // Student status is set to "done" inside sendPhotosSaved (matches web client)
            socketManager.sendPhotosSaved(
                student = student,
                photos = photos,
                version = newVersion,
                filename = filename
            )

            // 3. Send OP_PROGRESS
            socketManager.sendOpProgress("Selesai — Menunggu target...")

            // 4. Update local project state
            val updatedDb = proj.database.map { s ->
                if (s.id == student.id) s.copy(status = "done") else s
            }
            val updatedVersions = proj.captureVersions.toMutableMap().apply {
                this[versionKey] = newVersion
            }
            val updatedHistory = proj.photoHistory.toMutableList().apply {
                // Remove existing entry for this student+channel if any
                removeAll { it.student.id == student.id && it.channel == _myChannel.value }
                add(PhotoHistoryItem(student = student, photos = photos, channel = _myChannel.value))
            }

            val updatedProject = proj.copy(
                database = updatedDb,
                captureVersions = updatedVersions,
                photoHistory = updatedHistory
            )
            _project.value = updatedProject

            // 5. Send SYNC_DB to sync with other clients
            socketManager.sendSyncDb(updatedProject)

            // 6. Reset capture state
            _currentTarget.value = null
            _capturedPhotos.value = emptyList()
            _capturePhase.value = CapturePhase.STANDBY

            Log.i(TAG, "Capture finalized for ${student.nama}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to finalize capture: ${e.message}")
            _capturePhase.value = CapturePhase.READY_1 // Revert on error
        } finally {
            _isSending.value = false
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
        val bytes = outputStream.toByteArray()
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:image/jpeg;base64,$base64"
    }

    // ─── Shutter Mode ──────────────────────────────────────────

    fun setShutterMode(mode: String) {
        _shutterMode.value = mode
        cancelTimer()
    }

    fun setOpSearchQuery(query: String) {
        _opSearchQuery.value = query
    }

    /**
     * Manually set the current target (used in photoshoot mode when operator selects from search).
     */
    fun setOpCurrentTarget(student: Student) {
        _currentTarget.value = student
        _capturePhase.value = CapturePhase.READY_1
        _capturedPhotos.value = emptyList()
        socketManager.sendOpProgress("Target dipilih: ${student.nama}")
    }

    fun getTimerDuration(): Int = when (_shutterMode.value) {
        "timer-3" -> 3
        "timer-5" -> 5
        "timer-10" -> 10
        else -> 0
    }

    fun isTimerMode(): Boolean = _shutterMode.value.startsWith("timer-")

    private fun startTimer(onComplete: () -> Unit) {
        cancelTimer()
        val duration = getTimerDuration()
        if (duration <= 0) { onComplete(); return }

        _timerCountdown.value = duration
        timerJob = viewModelScope.launch {
            while (_timerCountdown.value > 0) {
                delay(1000)
                _timerCountdown.value -= 1
            }
            if (_timerCountdown.value <= 0) {
                onComplete()
            }
        }
    }

    fun cancelTimer() {
        timerJob?.cancel()
        timerJob = null
        _timerCountdown.value = 0
    }

    // ─── Gridline Settings ──────────────────────────────────────

    fun setGridlineEnabled(enabled: Boolean) {
        _gridlineSettings.value = _gridlineSettings.value.copy(enabled = enabled)
    }

    fun setGridlineType(type: GridlineType) {
        _gridlineSettings.value = _gridlineSettings.value.copy(type = type)
    }

    fun setGridlineThickness(thickness: GridlineThickness) {
        _gridlineSettings.value = _gridlineSettings.value.copy(thickness = thickness)
    }

    fun setGridlineColor(color: GridlineColor) {
        _gridlineSettings.value = _gridlineSettings.value.copy(color = color)
    }

    // ─── Frame Bitmap Decoding ──────────────────────────────────

    /**
     * Decode base64 frame string to Bitmap for overlay rendering.
     * Called when FRAME_DATA is received or when project config has a frame.
     * Runs on a background coroutine to avoid blocking the main thread.
     */
    private fun decodeFrameBitmap(frameBase64: String?) {
        if (frameBase64 == null || frameBase64 == "__FRAME_SAVED__" || frameBase64.isEmpty()) {
            _frameBitmap.value = null
            return
        }
        
        viewModelScope.launch {
            try {
                val pureBase64 = if (frameBase64.contains(",")) {
                    frameBase64.substringAfter(",")
                } else {
                    frameBase64
                }
                val bytes = Base64.decode(pureBase64, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                _frameBitmap.value = bitmap
                Log.i(TAG, "Frame bitmap decoded: ${bitmap?.width}x${bitmap?.height}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode frame bitmap: ${e.message}")
                _frameBitmap.value = null
            }
        }
    }

    // ─── Camera Source ──────────────────────────────────────────

    fun setUvcDeviceAttached(attached: Boolean) {
        _uvcDeviceAttached.value = attached
        if (attached) {
            uvcCameraManager.scanForUVCDevices()
        }
        // Tell BuiltInCameraManager to rescan — it will auto-detect external cameras
        // and update cameraType flow, which triggers updateCameraSource()
        builtInCameraManager.rescanForExternalCamera()
    }

    private fun hasBuiltinCamera(): Boolean {
        return getApplication<Application>().packageManager
            .hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)
    }

    // ─── Cleanup ────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        stopStateRequestLoop()
        cancelTimer()
        // Cancel camera collectors
        cameraConnectedCollector?.cancel()
        cameraTypeCollector?.cancel()
        uvcConnectedCollector?.cancel()
        socketManager.destroy()
        builtInCameraManager.destroy()
        uvcCameraManager.destroy()
    }
}
