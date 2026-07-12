package com.saatiril.operator.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import android.view.TextureView
import com.saatiril.operator.camera.UnifiedCameraManager
import com.saatiril.operator.camera.CameraCapture
import com.saatiril.operator.camera.UVCCameraManager
import com.saatiril.operator.util.FilenameUtils
import com.saatiril.operator.util.PhotoSaver
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
 * - Operator queue (MC_CALL buffer + project database)
 * - Photo saving pipeline (capture → process → save locally → send via socket)
 *
 * KEY FIXES in this version:
 * - Queue computation now includes active_N status students (matching Windows version)
 * - Photo saving pipeline is complete: capture → CameraCapture → base64 → PhotoSaver + socket send
 * - MC_CALL channel filtering matches Windows behavior exactly
 * - updateOpQueue is called after every state mutation that could affect the queue
 * - Comprehensive logging at every stage for debugging
 */
class OperatorViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "OperatorViewModel"
        private const val STATE_REQUEST_INTERVAL_MS = 3000L
    }

    private val socketManager = SocketManager()

    // ─── Camera Manager (v6: Camera2 ONLY — no CameraX) ──────────

    val cameraManager = UnifiedCameraManager(application)
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

    private val _shutterMode = MutableStateFlow("manual")
    val shutterMode: StateFlow<String> = _shutterMode.asStateFlow()

    private val _timerCountdown = MutableStateFlow(0)
    val timerCountdown: StateFlow<Int> = _timerCountdown.asStateFlow()

    private var timerJob: Job? = null

    // ─── Op Search ──────────────────────────────────────────────

    private val _opSearchQuery = MutableStateFlow("")
    val opSearchQuery: StateFlow<String> = _opSearchQuery.asStateFlow()

    // ─── MC Call Buffer ────────────────────────────────────────
    // Holds students from MC_CALL that aren't yet in the database with
    // 'sent' or 'active_N' status. Critical for photoshoot mode where
    // MC_CALL can arrive before SYNC_DB updates the database.

    private val _mcCallBuffer = MutableStateFlow<List<Student>>(emptyList())
    val mcCallBuffer: StateFlow<List<Student>> = _mcCallBuffer.asStateFlow()

    // ─── Operator Queue ─────────────────────────────────────────
    // The queue shown in the UI — combines database students with
    // mcCallBuffer entries. Computed reactively via updateOpQueue().

    private val _opQueue = MutableStateFlow<List<Student>>(emptyList())
    val opQueue: StateFlow<List<Student>> = _opQueue.asStateFlow()

    // channelStudents: All students for this channel (all statuses)
    private val _channelStudents = MutableStateFlow<List<Student>>(emptyList())
    val channelStudents: StateFlow<List<Student>> = _channelStudents.asStateFlow()

    // savePath: The directory where photos are being saved
    private val _savePath = MutableStateFlow("")
    val savePath: StateFlow<String> = _savePath.asStateFlow()

    /**
     * Recompute the operator queue whenever project, mcCallBuffer, or myChannel changes.
     * Called after every mutation to these sources.
     *
     * IMPORTANT: This now matches the Windows version's queue computation exactly:
     * - Photoshoot: queue = db students with status "sent" or "active_N" (not yet photographed)
     *   + mcCallBuffer entries (not yet in db with sent/active status)
     * - Non-photoshoot: queue = all students for this channel (all statuses)
     */
    private fun updateOpQueue() {
        val proj = _project.value
        val mode = proj?.config?.mode ?: CameraModes.SINGLE
        val isPhotoshoot = CameraModes.isPhotoshootMode(mode)
        val myCh = _myChannel.value

        // Update channelStudents (all students for this channel)
        _channelStudents.value = if (proj == null) {
            emptyList()
        } else if (isPhotoshoot) {
            proj.database  // All students in photoshoot mode
        } else {
            proj.database.filter { it.assignedChannel == myCh }
        }

        // Update opQueue (the searchable/clickable queue)
        val newQueue = if (proj == null) {
            // No project yet — show mcCallBuffer entries only
            _mcCallBuffer.value
        } else if (isPhotoshoot) {
            val db = proj.database
            val alreadyPhotographed = proj.photoHistory
                .filter { it.channel == myCh }
                .map { it.student.id }.toSet()

            // FIX: Include BOTH "sent" and "active_N" status students in the queue
            // Windows version checks: s.status === 'sent'
            // But MC can also send students with "active_N" status
            val sentFromDb = db.filter { student ->
                (student.status == "sent" || isActiveStatus(student.status)) &&
                !alreadyPhotographed.contains(student.id)
            }
            val sentIds = sentFromDb.map { it.id }.toSet()
            val doneIds = db.filter { it.status == "done" }.map { it.id }.toSet()

            // Add mcCallBuffer entries not already in the db queue and not done
            val bufferAdditions = _mcCallBuffer.value.filter {
                !sentIds.contains(it.id) && !doneIds.contains(it.id) && !alreadyPhotographed.contains(it.id)
            }
            sentFromDb + bufferAdditions
        } else {
            // Non-photoshoot: show all students for this channel (all statuses)
            _channelStudents.value
        }

        // Update save path
        if (proj != null) {
            val projectName = proj.name.ifBlank { "Saatiril" }
            _savePath.value = PhotoSaver.getSaveDirectoryPath(
                getApplication<Application>(), projectName, proj.config.targetFolder
            )
        }

        Log.d(TAG, "updateOpQueue: queueSize=${newQueue.size}, channelSize=${_channelStudents.value.size}, mode=$mode, isPhotoshoot=$isPhotoshoot, myCh=$myCh, mcCallBuffer=${_mcCallBuffer.value.size}")
        if (newQueue.isNotEmpty()) {
            Log.d(TAG, "updateOpQueue: first 3 queue items: ${newQueue.take(3).map { "${it.nama}(st=${it.status},ch=${it.assignedChannel})" }}")
        }
        _opQueue.value = newQueue
    }

    // ─── Camera Source ──────────────────────────────────────────

    private val _cameraSource = MutableStateFlow("none")
    val cameraSource: StateFlow<String> = _cameraSource.asStateFlow()

    private val _uvcDeviceAttached = MutableStateFlow(false)
    val uvcDeviceAttached: StateFlow<Boolean> = _uvcDeviceAttached.asStateFlow()

    private val _cameraConnected = MutableStateFlow(false)
    val cameraConnected: StateFlow<Boolean> = _cameraConnected.asStateFlow()

    // Expose reactive camera list and current camera ID from BuiltInCameraManager
    val availableCameras: StateFlow<List<Pair<String, String>>> = cameraManager.availableCameras
    val currentCameraId: StateFlow<String> = cameraManager.currentCameraIdFlow

    // v6: No more useCamera2Engine — Camera2 is ALWAYS used for ALL cameras

    // v6: No setTextureView needed — initCamera receives TextureView directly

    // ─── Frame Overlay ──────────────────────────────────────────

    private val _frameBitmap = MutableStateFlow<Bitmap?>(null)
    val frameBitmap: StateFlow<Bitmap?> = _frameBitmap.asStateFlow()

    // ─── State Request Retry ────────────────────────────────────

    private var stateRequestJob: Job? = null
    private var frameRequestJob: Job? = null

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

    /**
     * BUG FIX: Periodic frame request loop.
     * Keeps requesting the frame every 5 seconds until it's received.
     * This handles cases where:
     * - The first REQUEST_FRAME is lost
     * - Admin wasn't ready to respond yet
     * - Admin needed time to recover frame from localStorage
     */
    private fun startFrameRequestLoop() {
        frameRequestJob?.cancel()
        frameRequestJob = viewModelScope.launch {
            // Initial delay to let admin stabilize
            delay(1000L)
            while (isActive && _frameBitmap.value == null && _project.value != null) {
                requestFrameIfNeeded()
                delay(5000L)
            }
            Log.d(TAG, "Frame request loop stopped — frame received or project null")
        }
        Log.d(TAG, "Frame request loop started")
    }

    private fun stopFrameRequestLoop() {
        frameRequestJob?.cancel()
        frameRequestJob = null
    }

    /**
     * BUG FIX: Proactively request frame from admin if we need it.
     * Called after auth_success, after SYNC_DB, and periodically in
     * startFrameRequestLoop().
     *
     * FIXES: Frame appears late because:
     * 1. SYNC_DB sends __FRAME_SAVED__ marker instead of actual frame data
     * 2. REQUEST_FRAME requires admin to be listening and respond with FRAME_DATA
     * 3. Admin may not have the frame in memory yet (needs localStorage recovery)
     * 4. A single request can be lost or arrive before admin is ready
     *
     * Solution: Persistent periodic retry every 5s until frame is received.
     */
    private fun requestFrameIfNeeded() {
        val proj = _project.value
        if (proj != null && _frameBitmap.value == null) {
            // Request frame if:
            // 1. Frame field is __FRAME_SAVED__ marker
            // 2. Frame field is null/empty (admin may not have sent it yet)
            // 3. Frame field exists but bitmap hasn't been decoded yet
            val needsFrame = proj.config.frame == "__FRAME_SAVED__" ||
                    proj.config.frame.isNullOrEmpty() ||
                    (_frameBitmap.value == null && proj.config.frame != null && proj.config.frame != "__FRAME_SAVED__")
            if (needsFrame) {
                Log.i(TAG, "requestFrameIfNeeded: Requesting frame for project ${proj.id} (frame field: ${if (proj.config.frame == null) "null" else if (proj.config.frame == "__FRAME_SAVED__") "__FRAME_SAVED__" else "${proj.config.frame.length} chars"})")
                socketManager.requestFrame(proj.id)
            }
        }
    }

    // ─── Camera Lifecycle ───────────────────────────────────────

    private var cameraConnectedCollector: Job? = null
    private var cameraTypeCollector: Job? = null
    private var uvcConnectedCollector: Job? = null
    private var uvcManagerInitialized: Boolean = false
    private var periodicCameraRescanJob: Job? = null

    /**
     * v6: Initialize camera with a TextureView. Camera2 ONLY — no CameraX.
     * This is much simpler than the old dual-engine approach.
     */
    fun initCamera(textureView: TextureView) {
        Log.i(TAG, "═══════════════════════════════════════════════════")
        Log.i(TAG, "initCamera: v6 Camera2-ONLY initialization")
        Log.i(TAG, "═══════════════════════════════════════════════════")

        // Initialize UVC detector
        if (!uvcManagerInitialized) {
            uvcCameraManager.init()
            uvcManagerInitialized = true
        }

        cameraConnectedCollector?.cancel()
        cameraTypeCollector?.cancel()
        uvcConnectedCollector?.cancel()

        // v6: Single init with TextureView — Camera2 handles ALL cameras
        cameraManager.init(textureView)

        cameraConnectedCollector = viewModelScope.launch {
            cameraManager.isConnected.collect { connected ->
                _cameraConnected.value = connected
                updateCameraSource()
            }
        }

        cameraTypeCollector = viewModelScope.launch {
            cameraManager.cameraType.collect {
                updateCameraSource()
            }
        }

        // When UVC device is detected, switch to USB camera
        uvcConnectedCollector = viewModelScope.launch {
            uvcCameraManager.isConnected.collect { uvcConnected ->
                _uvcDeviceAttached.value = uvcConnected
                if (uvcConnected) {
                    Log.i(TAG, "UVC device attached — switching to USB camera")
                    delay(2000) // Wait for Camera2 to register USB camera
                    cameraManager.forceSwitchToUSB()
                } else {
                    Log.i(TAG, "UVC device detached — camera will continue with current")
                }
                updateCameraSource()
            }
        }

        // Safety net: If USB was already connected, force switch after delay
        if (uvcCameraManager.isConnected.value) {
            Log.i(TAG, "initCamera: UVC already connected — scheduling USB switch")
            viewModelScope.launch {
                delay(3000)
                val cameraType = cameraManager.cameraType.value
                if (cameraType != "external") {
                    Log.i(TAG, "initCamera: Still on $cameraType camera after 3s — forcing USB switch")
                    cameraManager.forceSwitchToUSB()
                }
            }
        }

        // Start periodic rescan
        startPeriodicCameraRescan()

        Log.i(TAG, "Camera initialized — cameraSource=${_cameraSource.value}, uvcConnected=${uvcCameraManager.isConnected.value}")
    }

    private fun startPeriodicCameraRescan() {
        periodicCameraRescanJob?.cancel()
        periodicCameraRescanJob = viewModelScope.launch {
            delay(3000)
            // Early check
            if (uvcCameraManager.isConnected.value && cameraManager.cameraType.value != "external") {
                Log.i(TAG, "Early rescan: UVC attached but not using USB, forcing switch")
                cameraManager.forceSwitchToUSB()
            }

            // Periodic check every 5 seconds
            while (isActive) {
                delay(5000)
                if (uvcCameraManager.isConnected.value && cameraManager.cameraType.value != "external") {
                    Log.i(TAG, "Periodic rescan: UVC attached but not using USB, forcing switch")
                    cameraManager.forceSwitchToUSB()
                }
            }
        }
    }

    private fun updateCameraSource() {
        val connected = cameraManager.isConnected.value
        val cameraType = cameraManager.cameraType.value

        if (!connected) {
            _cameraSource.value = "none"
            return
        }

        _cameraSource.value = when (cameraType) {
            "external" -> "uvc"
            "back" -> "builtin"
            "front" -> "builtin"
            else -> "none"
        }
    }

    fun switchCamera() {
        // Cycle through available cameras
        val cameras = cameraManager.availableCameras.value
        val currentId = cameraManager.currentCameraIdFlow.value
        val currentIndex = cameras.indexOfFirst { it.first == currentId }
        val nextIndex = (currentIndex + 1) % cameras.size
        if (cameras.isNotEmpty()) {
            cameraManager.switchToCamera(cameras[nextIndex].first)
        }
    }

    fun getAvailableCameras(): List<Pair<String, String>> {
        cameraManager.refreshCameraList()
        return cameraManager.availableCameras.value
    }

    fun switchToCameraById(cameraId: String) {
        cameraManager.switchToCamera(cameraId)
    }

    /**
     * Force rescan for USB cameras. Called when user taps "Pindai Ulang USB".
     */
    fun forceRescanUsbCamera() {
        Log.i(TAG, "═══════════════════════════════════════════════════")
        Log.i(TAG, "forceRescanUsbCamera: User requested USB camera rescan")
        Log.i(TAG, "═══════════════════════════════════════════════════")
        uvcCameraManager.scanForUVCDevices()
        viewModelScope.launch {
            delay(1500)
            cameraManager.forceSwitchToUSB()
        }
    }

    // ─── Photo Capture ──────────────────────────────────────────

    /**
     * Trigger a photo capture. Supports timer mode.
     * Flow: trigger → [timer countdown] → doCapture → CameraCapture.processFrame →
     *   handleCapturedBitmap → finalizeCapture → [save locally + send via socket]
     */
    fun triggerCapture() {
        val phase = _capturePhase.value
        if (phase == CapturePhase.SENDING || _isSending.value) return
        if (phase != CapturePhase.READY_1 && phase != CapturePhase.READY_2) return

        if (isTimerMode() && _timerCountdown.value <= 0) {
            startTimer { doCapture() }
            return
        }

        doCapture()
    }

    fun cancelTimerCapture() {
        cancelTimer()
    }

    private fun doCapture() {
        Log.i(TAG, "doCapture: phase=${_capturePhase.value}, target=${_currentTarget.value?.nama}")
        cameraManager.capturePhoto { bitmap ->
            if (bitmap == null) {
                Log.e(TAG, "Capture returned null bitmap")
                return@capturePhoto
            }

            val config = _project.value?.config
            if (config == null) {
                Log.e(TAG, "doCapture: project config is null — cannot capture!")
                return@capturePhoto
            }

            // Process with CameraCapture (crop to aspect ratio, filter, frame overlay)
            val processedBitmap = CameraCapture.processFrame(
                sourceBitmap = bitmap,
                config = config,
                frameBitmap = _frameBitmap.value
            )

            Log.d(TAG, "doCapture: bitmap processed (${processedBitmap.width}x${processedBitmap.height})")
            handleCapturedBitmap(processedBitmap)
        }
    }

    /**
     * Handle a processed bitmap from capture.
     * Converts to base64 and adds to capturedPhotos list,
     * then either waits for next pose (standard mode) or finalizes.
     */
    private fun handleCapturedBitmap(bitmap: Bitmap) {
        val mode = _project.value?.config?.mode
        val target = _currentTarget.value
        if (mode == null) {
            Log.e(TAG, "handleCapturedBitmap: mode is null — dropping captured photo!")
            return
        }
        if (target == null) {
            Log.e(TAG, "handleCapturedBitmap: target is null — dropping captured photo!")
            return
        }
        val photosPerSession = CameraModes.photosPerSession(mode)

        viewModelScope.launch {
            // Convert bitmap to base64 JPEG data URI
            val base64 = bitmapToBase64(bitmap)
            val currentPhotos = _capturedPhotos.value.toMutableList()
            currentPhotos.add(base64)
            _capturedPhotos.value = currentPhotos

            Log.i(TAG, "handleCapturedBitmap: photo ${currentPhotos.size}/${photosPerSession} captured for ${target.nama}")

            if (photosPerSession == 1 || currentPhotos.size >= photosPerSession) {
                // All photos captured — finalize (save locally + send via socket)
                finalizeCapture(target, currentPhotos)
            } else {
                // More photos needed (standard mode: Toga done, Ijazah next)
                _capturePhase.value = CapturePhase.READY_2
                socketManager.sendOpProgress("Pose 1 OK — Siap Foto 2")
            }
        }
    }

    // ─── Connection ─────────────────────────────────────────────

    private var socketListenersInitialized = false

    fun connect(serverUrl: String, channel: Int, password: String? = null) {
        _serverUrl.value = serverUrl
        _myChannel.value = channel
        _authError.value = null
        _connectionError.value = null

        if (!socketListenersInitialized) {
            setupSocketListeners()
            socketListenersInitialized = true
        }
        socketManager.connect(serverUrl, channel, password)
        updateOpQueue()
    }

    fun disconnect() {
        stopStateRequestLoop()
        stopFrameRequestLoop()
        socketManager.disconnect()
        _project.value = null
        _currentTarget.value = null
        _capturePhase.value = CapturePhase.STANDBY
        _capturedPhotos.value = emptyList()
        _mcCallBuffer.value = emptyList()
        _frameBitmap.value = null
        updateOpQueue()
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
            startStateRequestLoop()
            // BUG FIX: Start frame request loop after auth success.
            // If the project was synced before auth completed (unlikely but possible)
            // or if a previous sync had __FRAME_SAVED__, we need the frame data.
            requestFrameIfNeeded()
            startFrameRequestLoop()
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

        // ─── MC_CALL ──────────────────────────────────────────────
        // MC sends a student to the operator. Flow depends on mode:
        // - Photoshoot: add to mcCallBuffer (operator selects manually)
        // - Standard: set target directly and enter READY_1 phase
        //
        // IMPORTANT: Channel filtering matches Windows version exactly.
        // In photoshoot mode, ALL channels are processed (operator picks from queue).
        // In standard mode, only MC_CALL for this channel is processed.

        socketManager.on(SocketEvents.MC_CALL) { data ->
            val mcCall = data as? McCallData
            if (mcCall == null) {
                Log.e(TAG, "MC_CALL: data is not McCallData — type=${data?.javaClass?.simpleName}")
                return@on
            }

            Log.i(TAG, "MC_CALL received: student=${mcCall.student.nama}, nim=${mcCall.student.nim}, ch=${mcCall.channel}, status=${mcCall.student.status}, myCh=${_myChannel.value}")

            val mode = _project.value?.config?.mode
            val isPhotoshoot = mode != null && CameraModes.isPhotoshootMode(mode)

            // Channel filtering: matches Windows version
            // Windows: if (data.channel !== myChannelRef.current) return
            // BUT in photoshoot mode, both channels receive MC_CALL for the same student
            // In standard mode, only process for our channel
            if (!isPhotoshoot && mcCall.channel != _myChannel.value) {
                Log.d(TAG, "MC_CALL for channel ${mcCall.channel}, ignoring (my channel: ${_myChannel.value})")
                return@on
            }

            if (isPhotoshoot) {
                // Photoshoot mode: add to buffer — operator selects from queue
                val buffer = _mcCallBuffer.value.toMutableList()
                // Replace existing entry for same student (MC may re-send after reset)
                val without = buffer.filter { it.id != mcCall.student.id }
                _mcCallBuffer.value = without + mcCall.student
                Log.i(TAG, "MC_CALL (photoshoot): Added ${mcCall.student.nama} to mcCallBuffer (size=${without.size + 1})")
            } else {
                // Standard mode: set target directly
                _currentTarget.value = mcCall.student
                _capturePhase.value = CapturePhase.READY_1
                _capturedPhotos.value = emptyList()
                Log.i(TAG, "MC_CALL (standard): Set target to ${mcCall.student.nama}, status=${mcCall.student.status}")
            }

            // If project is null, store the MC call student in buffer so it appears in queue
            if (_project.value == null) {
                Log.d(TAG, "MC_CALL: Project is null, storing student in mcCallBuffer temporarily")
                val buffer = _mcCallBuffer.value.toMutableList()
                val without = buffer.filter { it.id != mcCall.student.id }
                _mcCallBuffer.value = without + mcCall.student
            }

            updateOpQueue()
        }

        // ─── SYNC_DB ──────────────────────────────────────────────

        socketManager.on(SocketEvents.SYNC_DB) { data ->
            val syncData = data as? SyncDbData
            if (syncData == null) {
                Log.e(TAG, "SYNC_DB: data is not SyncDbData — type=${data?.javaClass?.simpleName}")
                return@on
            }

            Log.i(TAG, "SYNC_DB received: project=${syncData.project.name}, dbSize=${syncData.project.database.size}, mode=${syncData.project.config.mode}, targetFolder='${syncData.project.config.targetFolder}'")
            handleSyncDb(syncData.project)

            if (_project.value != null) {
                stopStateRequestLoop()
            }
        }

        // ─── STUDENT_RESET ────────────────────────────────────────

        socketManager.on(SocketEvents.STUDENT_RESET) { data ->
            val resetData = data as? StudentResetData
            if (resetData == null) {
                Log.e(TAG, "STUDENT_RESET: data is not StudentResetData — type=${data?.javaClass?.simpleName}")
                return@on
            }

            Log.i(TAG, "STUDENT_RESET received: studentId=${resetData.studentId}, channel=${resetData.channel}")

            val mode = _project.value?.config?.mode
            val isPhotoshoot = mode != null && CameraModes.isPhotoshootMode(mode)

            // Filter by channel (except photoshoot mode)
            if (!isPhotoshoot && resetData.channel != _myChannel.value) return@on

            // Clear current target if it matches
            if (_currentTarget.value?.id == resetData.studentId) {
                _currentTarget.value = null
                _capturePhase.value = CapturePhase.STANDBY
                _capturedPhotos.value = emptyList()
                Log.i(TAG, "STUDENT_RESET: Cleared current target ${resetData.studentId}")
            }

            // Clear matching entry from mcCallBuffer
            val buffer = _mcCallBuffer.value.toMutableList()
            if (buffer.removeAll { it.id == resetData.studentId }) {
                _mcCallBuffer.value = buffer
                Log.d(TAG, "STUDENT_RESET: Cleared from mcCallBuffer (remaining=${buffer.size})")
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

            updateOpQueue()
        }

        // ─── FRAME_DATA ──────────────────────────────────────────

        socketManager.on(SocketEvents.FRAME_DATA) { data ->
            val frameData = data as? FrameDataPayload ?: return@on
            Log.i(TAG, "FRAME_DATA received: length=${frameData.frame.length}")
            _project.value?.let { proj ->
                _project.value = proj.copy(
                    config = proj.config.copy(frame = frameData.frame)
                )
            }
            decodeFrameBitmap(frameData.frame)
            // BUG FIX: Stop frame request loop since we now have the frame.
            // Also update opQueue which may have been stale while waiting for frame.
            stopFrameRequestLoop()
            updateOpQueue()
        }

        // ─── PHOTOS_SAVED (from other operators in dual mode) ─────

        socketManager.on(SocketEvents.PHOTOS_SAVED) { data ->
            val photosSaved = data as? PhotosSavedData ?: return@on
            val mode = _project.value?.config?.mode

            // Only relevant in dual-photoshoot mode
            if (mode == null || !CameraModes.isPhotoshootMode(mode) || !CameraModes.isDualMode(mode)) return@on

            _currentTarget.value?.let { target ->
                if (target.id == photosSaved.student.id) {
                    _currentTarget.value = null
                    _capturePhase.value = CapturePhase.STANDBY
                    _capturedPhotos.value = emptyList()
                    Log.i(TAG, "Target cleared — other operator finished: ${target.nama}")
                }
            }
        }

        // ─── STUDENT_DONE (from other operators in dual mode) ─────

        socketManager.on(SocketEvents.STUDENT_DONE) { data ->
            val doneData = data as? StudentDoneData ?: return@on
            val mode = _project.value?.config?.mode

            if (mode == null || !CameraModes.isDualMode(mode) || doneData.channel == _myChannel.value) return@on

            _project.value?.let { proj ->
                val updatedDb = proj.database.map { student ->
                    if (student.id == doneData.studentId) {
                        student.copy(status = "done")
                    } else student
                }
                _project.value = proj.copy(database = updatedDb)
            }

            updateOpQueue()
            Log.d(TAG, "STUDENT_DONE from other operator: ${doneData.studentId}")
        }
    }

    // ─── Sync DB Handler ────────────────────────────────────────

    private fun handleSyncDb(incomingProject: Project) {
        val currentProject = _project.value

        if (currentProject == null) {
            // First sync — use incoming data directly
            _project.value = incomingProject
            Log.i(TAG, "SYNC_DB (first): project=${incomingProject.name}, mode=${incomingProject.config.mode}, dbSize=${incomingProject.database.size}, targetFolder='${incomingProject.config.targetFolder}'")
            if (incomingProject.database.isNotEmpty()) {
                Log.d(TAG, "SYNC_DB (first): First 3 students: ${incomingProject.database.take(3).map { "${it.nama}(ch=${it.assignedChannel},st=${it.status})" }}")
            }

            // Clear mcCallBuffer entries that now exist in the database with same or higher status
            val dbIdSet = incomingProject.database.map { it.id }.toSet()
            val buffer = _mcCallBuffer.value.toMutableList()
            val newBuffer = buffer.filter { !dbIdSet.contains(it.id) }
            if (newBuffer.size != buffer.size) {
                _mcCallBuffer.value = newBuffer
                Log.d(TAG, "SYNC_DB (first): Cleared ${buffer.size - newBuffer.size} mcCallBuffer entries now in DB")
            }

            // Decode frame bitmap if present (synchronous for immediate display)
            decodeFrameBitmap(incomingProject.config.frame)
            if (incomingProject.config.frame == "__FRAME_SAVED__" || incomingProject.config.frame.isNullOrEmpty()) {
                // BUG FIX: Request frame and start persistent retry loop.
                // On first sync, the frame may arrive late because:
                // - __FRAME_SAVED__ marker means frame was stripped for performance
                // - Admin needs to recover frame from localStorage and respond
                // - A single request can be lost or arrive before admin is ready
                // The frame request loop will keep trying every 5s until frame arrives.
                socketManager.requestFrame(incomingProject.id)
                startFrameRequestLoop()
            }

            // Re-find active student for non-photoshoot mode after first sync
            if (!CameraModes.isPhotoshootMode(incomingProject.config.mode)) {
                val activeStudent = incomingProject.database.find {
                    it.assignedChannel == _myChannel.value && isActiveStatus(it.status)
                }
                if (activeStudent != null && _currentTarget.value?.id != activeStudent.id) {
                    _currentTarget.value = activeStudent
                    if (_capturePhase.value == CapturePhase.STANDBY) {
                        _capturePhase.value = CapturePhase.READY_1
                        _capturedPhotos.value = emptyList()
                    }
                    Log.i(TAG, "SYNC_DB (first): Found active student ${activeStudent.nama} for channel ${_myChannel.value}")
                }
            }
            updateOpQueue()
            return
        }

        // Merge databases — keep the "most advanced" status per student
        val mergedDb = mergeDatabases(currentProject.database, incomingProject.database)
        val mergedVersions = mergeCaptureVersions(currentProject.captureVersions, incomingProject.captureVersions)
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

        Log.i(TAG, "SYNC_DB (merge): dbSize=${mergedDb.size}, mode=${mergedProject.config.mode}, targetFolder='${mergedProject.config.targetFolder}'")

        // Clear mcCallBuffer entries that are now 'done' in the merged database
        val buffer = _mcCallBuffer.value.toMutableList()
        val doneIds = mergedDb.filter { it.status == "done" }.map { it.id }.toSet()
        if (buffer.removeAll { doneIds.contains(it.id) }) {
            _mcCallBuffer.value = buffer
            Log.d(TAG, "SYNC_DB: Cleared done students from mcCallBuffer (remaining=${buffer.size})")
        }

        // If current target became 'done' in merged data
        _currentTarget.value?.let { target ->
            val updatedStudent = mergedDb.find { it.id == target.id }
            if (updatedStudent?.status == "done") {
                _currentTarget.value = null
                _capturePhase.value = CapturePhase.STANDBY
                _capturedPhotos.value = emptyList()
                Log.i(TAG, "Current target became done via sync, clearing")
            }
        }

        // Re-find active student for non-photoshoot mode
        if (!CameraModes.isPhotoshootMode(mergedProject.config.mode)) {
            val activeStudent = mergedDb.find {
                it.assignedChannel == _myChannel.value && isActiveStatus(it.status)
            }
            if (activeStudent != null && _currentTarget.value?.id != activeStudent.id) {
                _currentTarget.value = activeStudent
                if (_capturePhase.value == CapturePhase.STANDBY) {
                    _capturePhase.value = CapturePhase.READY_1
                    _capturedPhotos.value = emptyList()
                }
                Log.i(TAG, "SYNC_DB: Re-found active student ${activeStudent.nama} for channel ${_myChannel.value}")
            }
        }

        updateOpQueue()

        // Handle frame bitmap
        if (preservedFrame == "__FRAME_SAVED__" || (preservedFrame.isNullOrEmpty() && _frameBitmap.value == null)) {
            socketManager.requestFrame(incomingProject.id)
            startFrameRequestLoop()
        } else if (preservedFrame != currentProject.config.frame) {
            decodeFrameBitmap(preservedFrame)
            stopFrameRequestLoop()
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

            val locPriority = statusPriority[loc.status] ?: (if (isActiveStatus(loc.status)) 2 else 3)
            val incPriority = statusPriority[inc.status] ?: (if (isActiveStatus(inc.status)) 2 else 3)

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
                loc
            } else {
                inc
            }
        }
    }

    // ─── Finalize Capture ───────────────────────────────────────

    /**
     * Complete the capture pipeline — OPTIMIZED for instant operator readiness.
     *
     * Priority order:
     * 1. IMMEDIATE: Send STUDENT_DONE (lightweight — unblocks MC instantly)
     * 2. IMMEDIATE: Reset capture state (operator can proceed to next target)
     * 3. BACKGROUND: Send PHOTOS_SAVED, save photos locally, send SYNC_DB
     *
     * This matches the Windows version's "IMMEDIATE: update local state + emit lightweight SYNC_DB (no delays!)"
     * approach where the operator UI becomes available instantly after capture.
     */
    private suspend fun finalizeCapture(student: Student, photos: List<String>) {
        val mode = _project.value?.config?.mode
        val proj = _project.value
        if (mode == null || proj == null) {
            Log.e(TAG, "finalizeCapture: project/mode is null — cannot finalize! student=${student.nama}")
            return
        }

        Log.i(TAG, "finalizeCapture: student=${student.nama}, photos=${photos.size}, mode=$mode")
        _capturePhase.value = CapturePhase.SENDING
        _isSending.value = true

        try {
            // Get capture version
            val versionKey = "${student.id}_${_myChannel.value}"
            val currentVersion = proj.captureVersions[versionKey] ?: 0
            val newVersion = currentVersion + 1

            // Build filenames
            val filenames: List<String> = if (CameraModes.isPhotoshootMode(mode)) {
                listOf(FilenameUtils.buildPhotoshootFilename(student.nim, student.nama, _myChannel.value, newVersion))
            } else {
                photos.mapIndexed { idx, _ ->
                    val suffix = idx + 1
                    val type = if (suffix == 1) "Toga" else "Ijazah"
                    FilenameUtils.buildStandardFilename(student.nim, student.nama, suffix, type, newVersion)
                }
            }
            val primaryFilename = filenames.firstOrNull() ?: ""

            // ═══ PHASE 1: IMMEDIATE — unblock MC and reset operator state ═══
            // Send STUDENT_DONE first (lightweight — lets MC call next student immediately)
            socketManager.sendStudentDone(student.id)

            // Reset capture state IMMEDIATELY so operator can proceed to next target
            _currentTarget.value = null
            _capturedPhotos.value = emptyList()
            _capturePhase.value = CapturePhase.STANDBY
            _isSending.value = false

            // Clear matching entry from mcCallBuffer
            val buffer = _mcCallBuffer.value.toMutableList()
            if (buffer.removeAll { it.id == student.id }) {
                _mcCallBuffer.value = buffer
            }

            // Update local project state (student status → "done")
            val updatedDb = proj.database.map { s ->
                if (s.id == student.id) s.copy(status = "done") else s
            }
            val updatedVersions = proj.captureVersions.toMutableMap().apply {
                this[versionKey] = newVersion
            }
            val updatedHistory = proj.photoHistory.toMutableList().apply {
                removeAll { it.student.id == student.id && it.channel == _myChannel.value }
                add(PhotoHistoryItem(student = student, photos = photos, channel = _myChannel.value))
            }
            val updatedProject = proj.copy(
                database = updatedDb,
                captureVersions = updatedVersions,
                photoHistory = updatedHistory
            )
            _project.value = updatedProject
            updateOpQueue()

            Log.i(TAG, "finalizeCapture: Operator state reset — ready for next target")

            // ═══ PHASE 2: BACKGROUND — heavy operations don't block operator ═══
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    // Send PHOTOS_SAVED with all photo data
                    socketManager.sendPhotosSaved(
                        student = student,
                        photos = photos,
                        version = newVersion,
                        filename = primaryFilename
                    )

                    // Send OP_PROGRESS
                    socketManager.sendOpProgress("Selesai — Menunggu target...")

                    // Save photos to local Android storage
                    val projectName = proj.name.ifBlank { "Saatiril" }
                    val targetFolder = proj.config.targetFolder
                    val appContext = getApplication<Application>()
                    photos.forEachIndexed { idx, photoBase64 ->
                        val filename = filenames.getOrElse(idx) { "photo_${idx + 1}.jpg" }
                        val savedPath = PhotoSaver.savePhoto(
                            context = appContext,
                            base64Data = photoBase64,
                            filename = filename,
                            projectName = projectName,
                            targetFolder = targetFolder
                        )
                        if (savedPath != null) {
                            Log.i(TAG, "Photo saved locally: $savedPath")
                        } else {
                            Log.w(TAG, "Failed to save photo locally: $filename")
                        }
                    }

                    // Send SYNC_DB to sync with other clients
                    socketManager.sendSyncDb(updatedProject)

                    Log.i(TAG, "finalizeCapture: Background tasks complete for ${student.nama}")
                } catch (e: Exception) {
                    Log.e(TAG, "finalizeCapture: Background task error: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to finalize capture: ${e.message}", e)
            _capturePhase.value = CapturePhase.READY_1
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

    fun setOpCurrentTarget(student: Student) {
        _currentTarget.value = student
        _capturePhase.value = CapturePhase.READY_1
        _capturedPhotos.value = emptyList()
        socketManager.sendOpProgress("Target dipilih: ${student.nama}")
        Log.i(TAG, "setOpCurrentTarget: ${student.nama}, status=${student.status}")
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
     * Decode frame bitmap SYNCHRONOUSLY on the calling thread.
     * Base64 decode of a PNG/JPEG frame is fast enough (typically < 50ms)
     * that we don't need a coroutine — this avoids the delay of scheduling
     * onto a coroutine dispatcher, which was causing the frame to appear
     * late at startup.
     */
    private fun decodeFrameBitmap(frameBase64: String?) {
        if (frameBase64 == null || frameBase64 == "__FRAME_SAVED__" || frameBase64.isEmpty()) {
            _frameBitmap.value = null
            return
        }

        try {
            val pureBase64 = if (frameBase64.contains(",")) {
                frameBase64.substringAfter(",")
            } else {
                frameBase64
            }
            val bytes = Base64.decode(pureBase64, Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            _frameBitmap.value = bitmap
            Log.i(TAG, "Frame bitmap decoded synchronously: ${bitmap?.width}x${bitmap?.height}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode frame bitmap: ${e.message}")
            _frameBitmap.value = null
        }
    }

    // ─── Camera Source ──────────────────────────────────────────

    fun setUvcDeviceAttached(attached: Boolean) {
        _uvcDeviceAttached.value = attached
        if (attached) {
            uvcCameraManager.scanForUVCDevices()
            viewModelScope.launch {
                delay(2000)
                cameraManager.forceSwitchToUSB()
            }
        }
    }

    // ─── Cleanup ────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        stopStateRequestLoop()
        stopFrameRequestLoop()
        cancelTimer()
        cameraConnectedCollector?.cancel()
        cameraTypeCollector?.cancel()
        uvcConnectedCollector?.cancel()
        periodicCameraRescanJob?.cancel()
        socketManager.destroy()
        cameraManager.destroy()
        uvcCameraManager.destroy()
    }
}
