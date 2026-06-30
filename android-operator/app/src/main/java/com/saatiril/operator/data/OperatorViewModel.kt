package com.saatiril.operator.data

import android.app.Application
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.saatiril.operator.util.FilenameUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Central ViewModel managing all operator state:
 * - Socket.io connection and authentication
 * - Current project data (students, config, frame)
 * - Capture state machine (standby → ready-1 → ready-2 → sending)
 * - Gridline settings
 * - Connection health
 */
class OperatorViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "OperatorViewModel"
    }
    
    private val socketManager = SocketManager()
    
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
    
    // ─── Camera Source ──────────────────────────────────────────
    
    private val _cameraSource = MutableStateFlow("none") // "uvc", "builtin", "none"
    val cameraSource: StateFlow<String> = _cameraSource.asStateFlow()
    
    private val _uvcDeviceAttached = MutableStateFlow(false)
    val uvcDeviceAttached: StateFlow<Boolean> = _uvcDeviceAttached.asStateFlow()
    
    // ─── Frame Overlay ──────────────────────────────────────────
    
    private val _frameBitmap = MutableStateFlow<Bitmap?>(null)
    val frameBitmap: StateFlow<Bitmap?> = _frameBitmap.asStateFlow()
    
    // ─── Connection ─────────────────────────────────────────────
    
    fun connect(serverUrl: String, channel: Int, password: String? = null) {
        _serverUrl.value = serverUrl
        _myChannel.value = channel
        _authError.value = null
        
        setupSocketListeners()
        socketManager.connect(serverUrl, channel, password)
    }
    
    fun disconnect() {
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
        }
        
        socketManager.on("auth_failed") { reason ->
            val reasonStr = reason as? String ?: "unknown"
            _authError.value = reasonStr
            if (reasonStr == "session_password_required") {
                _passwordRequired.value = true
            }
        }
        
        socketManager.on("connection_error") { error ->
            Log.e(TAG, "Connection error: $error")
        }
        
        socketManager.on("latency") { latency ->
            _latencyMs.value = latency as? Long ?: -1L
        }
        
        socketManager.on("server_shutdown") {
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
            _capturePhase.value = if (photosPerSession == 1) CapturePhase.READY_1 else CapturePhase.READY_1
            _capturedPhotos.value = emptyList()
            
            Log.i(TAG, "MC_CALL: ${mcCall.student.nama} (channel ${mcCall.channel})")
        }
        
        socketManager.on(SocketEvents.SYNC_DB) { data ->
            val syncData = data as? SyncDbData ?: return@on
            handleSyncDb(syncData.project)
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
        }
    }
    
    // ─── Sync DB Handler ────────────────────────────────────────
    
    private fun handleSyncDb(incomingProject: Project) {
        val currentProject = _project.value
        
        if (currentProject == null) {
            // First sync — use incoming data directly
            _project.value = incomingProject
            Log.i(TAG, "SYNC_DB: First sync, project: ${incomingProject.name}")
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
        
        // If we have __FRAME_SAVED__ marker, request actual frame
        if (preservedFrame == "__FRAME_SAVED__") {
            socketManager.requestFrame(incomingProject.id)
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
    
    fun capturePhoto(bitmap: Bitmap) {
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
    
    // ─── Camera Source ──────────────────────────────────────────
    
    fun setUvcDeviceAttached(attached: Boolean) {
        _uvcDeviceAttached.value = attached
        if (attached) {
            _cameraSource.value = "uvc"
        } else {
            _cameraSource.value = if (hasBuiltinCamera()) "builtin" else "none"
        }
    }
    
    private fun hasBuiltinCamera(): Boolean {
        return getApplication<Application>().packageManager
            .hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)
    }
    
    // ─── Cleanup ────────────────────────────────────────────────
    
    override fun onCleared() {
        super.onCleared()
        socketManager.disconnect()
    }
}
