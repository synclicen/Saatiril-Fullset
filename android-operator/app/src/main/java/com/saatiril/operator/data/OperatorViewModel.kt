package com.saatiril.operator.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.saatiril.operator.camera.UVCCameraManager
import com.saatiril.operator.util.FilenameUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ═════════════════════════════════════════════════════════════════════════
 * Central ViewModel — v10 Dual Camera Engine (UVCCamera + CameraX)
 * ═════════════════════════════════════════════════════════════════════════
 *
 * v10 CHANGES (fundamental architectural shift — NATIVE USB camera):
 * - Camera: DualCameraManager replaces WebViewCameraManager.
 *   UVCCamera (com.herohan:UVCAndroid) talks DIRECTLY to USB hardware
 *   via USB Host API, bypassing Android's broken Camera2 HAL entirely.
 *   CameraX handles built-in cameras (which work fine).
 * - USB Detection: UVCCamera's USBMonitor detects USB devices natively.
 *   No more WebView/getUserMedia (which couldn't see USB cameras on Android).
 * - Photo Capture: UVCCamera IFrameCallback → NV21 → JPEG → base64.
 *   CameraX ImageCapture → JPEG → base64. No JavaScript involved.
 * - Photo Saving: REMOVED. Photos are NOT saved on the operator device.
 *   They are sent via socket.io to the admin who saves them (matching
 *   the Electron browser-mode behavior exactly).
 * - Frame Overlay: TODO: apply in Kotlin (not JavaScript).
 *
 * Camera priority:
 * 1. USB/External camera (UVCCamera) — auto-selected if present
 * 2. Built-in back camera (CameraX) — fallback
 * 3. Built-in front camera (CameraX) — last resort
 */
class OperatorViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "OperatorViewModel"
        private const val STATE_REQUEST_INTERVAL_MS = 3000L
    }

    private val socketManager = SocketManager()

    // ─── Camera Manager (v17: UVC Direct Access for MacroSilicon) ──────────

    val cameraUVCManager = UVCCameraManager(application)

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

    private val _mcCallBuffer = MutableStateFlow<List<Student>>(emptyList())
    val mcCallBuffer: StateFlow<List<Student>> = _mcCallBuffer.asStateFlow()

    // ─── Operator Queue ─────────────────────────────────────────

    private val _opQueue = MutableStateFlow<List<Student>>(emptyList())
    val opQueue: StateFlow<List<Student>> = _opQueue.asStateFlow()

    private val _channelStudents = MutableStateFlow<List<Student>>(emptyList())
    val channelStudents: StateFlow<List<Student>> = _channelStudents.asStateFlow()

    private val _savePath = MutableStateFlow("")
    val savePath: StateFlow<String> = _savePath.asStateFlow()

    /**
     * Recompute the operator queue whenever project, mcCallBuffer, or myChannel changes.
     */
    private fun updateOpQueue() {
        val proj = _project.value
        val mode = proj?.config?.mode ?: CameraModes.SINGLE
        val isPhotoshoot = CameraModes.isPhotoshootMode(mode)
        val myCh = _myChannel.value

        _channelStudents.value = if (proj == null) {
            emptyList()
        } else if (isPhotoshoot) {
            proj.database
        } else {
            proj.database.filter { it.assignedChannel == myCh }
        }

        val newQueue = if (proj == null) {
            _mcCallBuffer.value
        } else if (isPhotoshoot) {
            val db = proj.database
            val alreadyPhotographed = proj.photoHistory
                .filter { it.channel == myCh }
                .map { it.student.id }.toSet()

            val sentFromDb = db.filter { student ->
                (student.status == "sent" || isActiveStatus(student.status)) &&
                !alreadyPhotographed.contains(student.id)
            }
            val sentIds = sentFromDb.map { it.id }.toSet()
            val doneIds = db.filter { it.status == "done" }.map { it.id }.toSet()

            val bufferAdditions = _mcCallBuffer.value.filter {
                !sentIds.contains(it.id) && !doneIds.contains(it.id) && !alreadyPhotographed.contains(it.id)
            }
            sentFromDb + bufferAdditions
        } else {
            _channelStudents.value
        }

        _opQueue.value = newQueue

        Log.d(TAG, "updateOpQueue: queueSize=${newQueue.size}, channelSize=${_channelStudents.value.size}, mode=$mode, isPhotoshoot=$isPhotoshoot, myCh=$myCh")
    }

    // ─── Camera Source ──────────────────────────────────────────

    private val _cameraSource = MutableStateFlow("none")
    val cameraSource: StateFlow<String> = _cameraSource.asStateFlow()

    private val _uvcDeviceAttached = MutableStateFlow(false)
    val uvcDeviceAttached: StateFlow<Boolean> = _uvcDeviceAttached.asStateFlow()

    private val _cameraConnected = MutableStateFlow(false)
    val cameraConnected: StateFlow<Boolean> = _cameraConnected.asStateFlow()

    val availableCameras: StateFlow<List<Pair<String, String>>> = cameraUVCManager.availableCameras
    val currentCameraId: StateFlow<String> = cameraUVCManager.currentCameraIdFlow

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
    }

    private fun stopStateRequestLoop() {
        stateRequestJob?.cancel()
        stateRequestJob = null
    }

    private fun startFrameRequestLoop() {
        frameRequestJob?.cancel()
        frameRequestJob = viewModelScope.launch {
            delay(1000L)
            while (isActive && _frameBitmap.value == null && _project.value != null) {
                requestFrameIfNeeded()
                delay(5000L)
            }
        }
    }

    private fun stopFrameRequestLoop() {
        frameRequestJob?.cancel()
        frameRequestJob = null
    }

    private fun requestFrameIfNeeded() {
        val proj = _project.value
        if (proj != null && _frameBitmap.value == null) {
            val needsFrame = proj.config.frame == "__FRAME_SAVED__" ||
                    proj.config.frame.isNullOrEmpty() ||
                    (_frameBitmap.value == null && proj.config.frame != null && proj.config.frame != "__FRAME_SAVED__")
            if (needsFrame) {
                Log.i(TAG, "requestFrameIfNeeded: Requesting frame for project ${proj.id}")
                socketManager.requestFrame(proj.id)
            }
        }
    }

    // ─── Camera Lifecycle ───────────────────────────────────────

    private var cameraConnectedCollector: Job? = null
    private var cameraTypeCollector: Job? = null

    /**
     * v17: Initialize camera via UVCCamera library.
     * USB Host API directly accesses UVC capture cards.
     */
    fun initCamera() {
        Log.i(TAG, "═══════════════════════════════════════════════════")
        Log.i(TAG, "initCamera: v17 UVCCamera Direct (MacroSilicon Fix)")
        Log.i(TAG, "═══════════════════════════════════════════════════")

        cameraUVCManager.initCamera()

        // Set up collectors if not already set up
        if (cameraConnectedCollector?.isActive != true) {
            cameraConnectedCollector?.cancel()
            cameraConnectedCollector = viewModelScope.launch {
                cameraUVCManager.isConnected.collect { connected ->
                    _cameraConnected.value = connected
                    updateCameraSource()
                }
            }
        }

        if (cameraTypeCollector?.isActive != true) {
            cameraTypeCollector?.cancel()
            cameraTypeCollector = viewModelScope.launch {
                cameraUVCManager.cameraType.collect {
                    updateCameraSource()
                }
            }
        }

        Log.i(TAG, "Camera initialized — UVCCamera active, cameraSource=${_cameraSource.value}")
    }

    private fun updateCameraSource() {
        val connected = cameraUVCManager.isConnected.value
        val cameraType = cameraUVCManager.cameraType.value
        val isUSB = cameraUVCManager.isUSBCamera.value

        if (!connected) {
            _cameraSource.value = "none"
            return
        }

        _cameraSource.value = if (isUSB || cameraType == "external") "uvc" else "builtin"
    }

    fun switchCamera() {
        val cameras = cameraUVCManager.availableCameras.value
        val currentId = cameraUVCManager.currentCameraIdFlow.value
        val currentIndex = cameras.indexOfFirst { it.first == currentId }
        val nextIndex = (currentIndex + 1) % cameras.size
        if (cameras.isNotEmpty()) {
            cameraUVCManager.switchCamera(cameras[nextIndex].first)
        }
    }

    fun getAvailableCameras(): List<Pair<String, String>> {
        cameraUVCManager.refreshCameraList()
        return cameraUVCManager.availableCameras.value
    }

    fun switchToCameraById(cameraId: String) {
        cameraUVCManager.switchCamera(cameraId)
    }

    /**
     * Force rescan for USB cameras. Called when user taps "Pindai Ulang USB".
     * v13: WebView's devicechange + forceRescan() handles this.
     */
    fun forceRescanUsbCamera() {
        Log.i(TAG, "forceRescanUsbCamera: User requested USB camera rescan")
        cameraUVCManager.forceRescan()
    }

    fun setUvcDeviceAttached(attached: Boolean) {
        _uvcDeviceAttached.value = attached
        if (attached) {
            viewModelScope.launch {
                delay(1000)
                cameraUVCManager.forceRescan()
            }
        }
    }

    // ─── Photo Capture ──────────────────────────────────────────

    /**
     * Trigger a photo capture. Supports timer mode.
     * v10: Capture via DualCameraManager (UVCCamera or CameraX).
     * Native capture — no JavaScript involved.
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

    /**
     * v10: Capture via DualCameraManager (UVCCamera or CameraX).
     * USB: IFrameCallback → NV21 → JPEG → base64
     * Built-in: ImageCapture → JPEG → base64
     *
     * The result is a base64 data URL string, ready to send via socket.
     * Photos are NOT saved locally — only sent to admin via socket.
     */
    private fun doCapture() {
        Log.i(TAG, "doCapture: phase=${_capturePhase.value}, target=${_currentTarget.value?.nama}")

        // Update camera config before capture (aspect ratio + filter + frame)
        val config = _project.value?.config
        if (config != null) {
            val aspectRatio = config.parseAspectRatio().toDouble()
            cameraUVCManager.updateConfig(aspectRatio, config.preset ?: "original")
        }

        // Send frame overlay to JS if available
        val frameBase64 = _project.value?.config?.frame
        if (frameBase64 != null && frameBase64 != "__FRAME_SAVED__" && frameBase64.isNotEmpty()) {
            cameraUVCManager.setFrameOverlay("data:image/png;base64,${if (frameBase64.contains(",")) frameBase64.substringAfter(",") else frameBase64}")
        } else {
            cameraUVCManager.setFrameOverlay(null)
        }

        cameraUVCManager.capturePhoto { base64DataUrl ->
            if (base64DataUrl == null) {
                Log.e(TAG, "Capture returned null from camera engine")
                return@capturePhoto
            }

            Log.i(TAG, "doCapture: Photo captured (${base64DataUrl.length} chars)")
            handleCapturedPhoto(base64DataUrl)
        }
    }

    /**
     * Handle a captured photo (base64 data URL from camera engine).
     * v10: No WebView/JS processing — native capture returns base64 directly.
     * v10: Photos NOT saved on operator device — only sent via socket.
     */
    private fun handleCapturedPhoto(base64DataUrl: String) {
        val mode = _project.value?.config?.mode
        val target = _currentTarget.value
        if (mode == null || target == null) {
            Log.e(TAG, "handleCapturedPhoto: mode or target is null — dropping photo!")
            return
        }
        val photosPerSession = CameraModes.photosPerSession(mode)

        val currentPhotos = _capturedPhotos.value.toMutableList()
        currentPhotos.add(base64DataUrl)
        _capturedPhotos.value = currentPhotos

        Log.i(TAG, "handleCapturedPhoto: photo ${currentPhotos.size}/${photosPerSession} captured for ${target.nama}")

        if (photosPerSession == 1 || currentPhotos.size >= photosPerSession) {
            // All photos captured — finalize (send via socket only, NO local save)
            viewModelScope.launch {
                finalizeCapture(target, currentPhotos)
            }
        } else {
            // More photos needed (standard mode: Toga done, Ijazah next)
            _capturePhase.value = CapturePhase.READY_2
            socketManager.sendOpProgress("Pose 1 OK — Siap Foto 2")
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

        socketManager.on(SocketEvents.MC_CALL) { data ->
            val mcCall = data as? McCallData
            if (mcCall == null) {
                Log.e(TAG, "MC_CALL: data is not McCallData — type=${data?.javaClass?.simpleName}")
                return@on
            }

            Log.i(TAG, "MC_CALL received: student=${mcCall.student.nama}, ch=${mcCall.channel}, myCh=${_myChannel.value}")

            val mode = _project.value?.config?.mode
            val isPhotoshoot = mode != null && CameraModes.isPhotoshootMode(mode)

            if (!isPhotoshoot && mcCall.channel != _myChannel.value) {
                Log.d(TAG, "MC_CALL for channel ${mcCall.channel}, ignoring (my channel: ${_myChannel.value})")
                return@on
            }

            if (isPhotoshoot) {
                val buffer = _mcCallBuffer.value.toMutableList()
                val without = buffer.filter { it.id != mcCall.student.id }
                _mcCallBuffer.value = without + mcCall.student
            } else {
                _currentTarget.value = mcCall.student
                _capturePhase.value = CapturePhase.READY_1
                _capturedPhotos.value = emptyList()
            }

            if (_project.value == null) {
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
                Log.e(TAG, "SYNC_DB: data is not SyncDbData")
                return@on
            }

            Log.i(TAG, "SYNC_DB received: project=${syncData.project.name}, dbSize=${syncData.project.database.size}")
            handleSyncDb(syncData.project)

            if (_project.value != null) {
                stopStateRequestLoop()
            }
        }

        // ─── STUDENT_RESET ────────────────────────────────────────

        socketManager.on(SocketEvents.STUDENT_RESET) { data ->
            val resetData = data as? StudentResetData ?: return@on

            Log.i(TAG, "STUDENT_RESET received: studentId=${resetData.studentId}, channel=${resetData.channel}")

            val mode = _project.value?.config?.mode
            val isPhotoshoot = mode != null && CameraModes.isPhotoshootMode(mode)

            if (!isPhotoshoot && resetData.channel != _myChannel.value) return@on

            if (_currentTarget.value?.id == resetData.studentId) {
                _currentTarget.value = null
                _capturePhase.value = CapturePhase.STANDBY
                _capturedPhotos.value = emptyList()
            }

            val buffer = _mcCallBuffer.value.toMutableList()
            if (buffer.removeAll { it.id == resetData.studentId }) {
                _mcCallBuffer.value = buffer
            }

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
            stopFrameRequestLoop()
            updateOpQueue()
        }

        // ─── PHOTOS_SAVED (from other operators in dual mode) ─────

        socketManager.on(SocketEvents.PHOTOS_SAVED) { data ->
            val photosSaved = data as? PhotosSavedData ?: return@on
            val mode = _project.value?.config?.mode

            if (mode == null || !CameraModes.isPhotoshootMode(mode) || !CameraModes.isDualMode(mode)) return@on

            _currentTarget.value?.let { target ->
                if (target.id == photosSaved.student.id) {
                    _currentTarget.value = null
                    _capturePhase.value = CapturePhase.STANDBY
                    _capturedPhotos.value = emptyList()
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
        }
    }

    // ─── Sync DB Handler ────────────────────────────────────────

    private fun handleSyncDb(incomingProject: Project) {
        val currentProject = _project.value

        if (currentProject == null) {
            _project.value = incomingProject
            Log.i(TAG, "SYNC_DB (first): project=${incomingProject.name}, mode=${incomingProject.config.mode}, dbSize=${incomingProject.database.size}")

            val dbIdSet = incomingProject.database.map { it.id }.toSet()
            val buffer = _mcCallBuffer.value.toMutableList()
            val newBuffer = buffer.filter { !dbIdSet.contains(it.id) }
            if (newBuffer.size != buffer.size) {
                _mcCallBuffer.value = newBuffer
            }

            decodeFrameBitmap(incomingProject.config.frame)
            if (incomingProject.config.frame == "__FRAME_SAVED__" || incomingProject.config.frame.isNullOrEmpty()) {
                socketManager.requestFrame(incomingProject.id)
                startFrameRequestLoop()
            }

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
                }
            }
            updateOpQueue()
            return
        }

        val mergedDb = mergeDatabases(currentProject.database, incomingProject.database)
        val mergedVersions = mergeCaptureVersions(currentProject.captureVersions, incomingProject.captureVersions)
        val mergedHistory = mergePhotoHistory(currentProject.photoHistory, incomingProject.photoHistory)

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

        val buffer = _mcCallBuffer.value.toMutableList()
        val doneIds = mergedDb.filter { it.status == "done" }.map { it.id }.toSet()
        if (buffer.removeAll { doneIds.contains(it.id) }) {
            _mcCallBuffer.value = buffer
        }

        _currentTarget.value?.let { target ->
            val updatedStudent = mergedDb.find { it.id == target.id }
            if (updatedStudent?.status == "done") {
                _currentTarget.value = null
                _capturePhase.value = CapturePhase.STANDBY
                _capturedPhotos.value = emptyList()
            }
        }

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
            }
        }

        updateOpQueue()

        if (preservedFrame == "__FRAME_SAVED__" || (preservedFrame.isNullOrEmpty() && _frameBitmap.value == null)) {
            socketManager.requestFrame(incomingProject.id)
            startFrameRequestLoop()
        } else if (preservedFrame != currentProject.config.frame) {
            decodeFrameBitmap(preservedFrame)
            stopFrameRequestLoop()
        }
    }

    private fun mergeDatabases(local: List<Student>, incoming: List<Student>): List<Student> {
        val statusPriority = mapOf("pending" to 0, "sent" to 1)
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
     * Complete the capture pipeline.
     *
     * v10: Photos NOT saved on operator device (matching Electron browser mode).
     * Only sent via socket to admin who saves them.
     *
     * Priority order:
     * 1. IMMEDIATE: Send STUDENT_DONE (lightweight — unblocks MC instantly)
     * 2. IMMEDIATE: Reset capture state (operator can proceed to next target)
     * 3. BACKGROUND: Send PHOTOS_SAVED, send SYNC_DB
     */
    private suspend fun finalizeCapture(student: Student, photos: List<String>) {
        val mode = _project.value?.config?.mode
        val proj = _project.value
        if (mode == null || proj == null) {
            Log.e(TAG, "finalizeCapture: project/mode is null — cannot finalize!")
            return
        }

        Log.i(TAG, "finalizeCapture: student=${student.nama}, photos=${photos.size}, mode=$mode")
        _capturePhase.value = CapturePhase.SENDING
        _isSending.value = true

        try {
            val versionKey = "${student.id}_${_myChannel.value}"
            val currentVersion = proj.captureVersions[versionKey] ?: 0
            val newVersion = currentVersion + 1

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
            socketManager.sendStudentDone(student.id)

            _currentTarget.value = null
            _capturedPhotos.value = emptyList()
            _capturePhase.value = CapturePhase.STANDBY
            _isSending.value = false

            val buffer = _mcCallBuffer.value.toMutableList()
            if (buffer.removeAll { it.id == student.id }) {
                _mcCallBuffer.value = buffer
            }

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

            // ═══ PHASE 2: BACKGROUND — send photos via socket ═══
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

                    // v10: NO local photo save — photos only sent via socket
                    // Admin (Electron) saves photos via PHOTOS_SAVED event
                    Log.i(TAG, "finalizeCapture: Photos sent via socket (NOT saved locally — matching Electron browser mode)")

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
            Log.i(TAG, "Frame bitmap decoded: ${bitmap?.width}x${bitmap?.height}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode frame bitmap: ${e.message}")
            _frameBitmap.value = null
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
        socketManager.destroy()
        cameraUVCManager.destroy()
    }
}
