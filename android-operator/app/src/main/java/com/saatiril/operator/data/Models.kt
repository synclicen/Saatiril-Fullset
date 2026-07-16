package com.saatiril.operator.data

import com.google.gson.annotations.SerializedName

// ─── Student ────────────────────────────────────────────────
typealias StudentStatus = String

data class Student(
    val id: String = "",
    val nim: String = "",
    val nama: String = "",
    val status: StudentStatus = "pending",
    @SerializedName("assignedChannel")
    val assignedChannel: Int = 1
)

// ─── Camera Mode ────────────────────────────────────────────
typealias CameraMode = String

object CameraModes {
    const val SINGLE = "single"
    const val DUAL = "dual"
    const val SINGLE_PHOTOSHOOT = "single-photoshoot"
    const val DUAL_PHOTOSHOOT = "dual-photoshoot"
    
    fun isPhotoshootMode(mode: CameraMode): Boolean =
        mode == SINGLE_PHOTOSHOOT || mode == DUAL_PHOTOSHOOT
    
    fun isDualMode(mode: CameraMode): Boolean =
        mode == DUAL || mode == DUAL_PHOTOSHOOT
    
    fun photosPerSession(mode: CameraMode): Int =
        if (isPhotoshootMode(mode)) 1 else 2
    
    fun channelCount(mode: CameraMode): Int =
        if (isDualMode(mode)) 2 else 1
}

// ─── Project Config ─────────────────────────────────────────
data class ProjectConfig(
    val mode: CameraMode = CameraModes.SINGLE,
    val ratio: String = "4:3",
    val preset: String = "original",
    @SerializedName("targetFolder")
    val targetFolder: String = "",
    val frame: String? = null,
    @SerializedName("sessionPassword")
    val sessionPassword: String? = null,
    @SerializedName("localFolder")
    val localFolder: String = ""
) {
    fun parseAspectRatio(): Float {
        val parts = ratio.split(":")
        if (parts.size == 2) {
            val w = parts[0].toFloatOrNull() ?: 4f
            val h = parts[1].toFloatOrNull() ?: 3f
            return w / h
        }
        return 4f / 3f
    }
}

// ─── Photo History ──────────────────────────────────────────
data class PhotoHistoryItem(
    val student: Student = Student(),
    val photos: List<String> = emptyList(),
    val channel: Int = 1
)

// ─── Project ────────────────────────────────────────────────
data class Project(
    val id: String = "",
    val name: String = "",
    val config: ProjectConfig = ProjectConfig(),
    val database: List<Student> = emptyList(),
    val photoHistory: List<PhotoHistoryItem> = emptyList(),
    @SerializedName("captureVersions")
    val captureVersions: Map<String, Int> = emptyMap()
)

// ─── Roles ──────────────────────────────────────────────────
typealias Role = String

object Roles {
    const val ADMIN = "admin"
    const val MC = "mc"
    const val OPERATOR = "operator"
}

// ─── Socket Events ──────────────────────────────────────────
object SocketEvents {
    // Transport events (direct socket events)
    const val AUTH_REQUIREMENT = "auth-requirement"
    const val AUTH_SUCCESS = "auth-success"
    const val AUTH_FAILED = "auth-failed"
    const val IDENTIFY = "identify"
    const val SAATIRIL_PING = "saatiril-ping"
    const val SAATIRIL_PONG = "saatiril-pong"
    const val SET_SESSION_PASSWORD = "SET_SESSION_PASSWORD"
    const val CLEAR_SESSION_PASSWORD = "CLEAR_SESSION_PASSWORD"
    const val LAN_MESSAGE = "lan-message"
    const val SERVER_SHUTDOWN = "SERVER_SHUTDOWN"
    const val SERVER_STATS = "server-stats"
    
    // Application events (nested inside lan-message)
    const val MC_CALL = "MC_CALL"
    const val SYNC_DB = "SYNC_DB"
    const val PHOTOS_SAVED = "PHOTOS_SAVED"
    const val STUDENT_DONE = "STUDENT_DONE"
    const val STUDENT_RESET = "STUDENT_RESET"
    const val OP_PROGRESS = "OP_PROGRESS"
    const val REQUEST_STATE = "REQUEST_STATE"
    const val REQUEST_FRAME = "REQUEST_FRAME"
    const val FRAME_DATA = "FRAME_DATA"
}

// ─── Auth Requirement ───────────────────────────────────────
data class AuthRequirement(
    @SerializedName("passwordRequired")
    val passwordRequired: Boolean = false
)

// ─── Auth Result ────────────────────────────────────────────
data class AuthSuccess(
    val role: String = "",
    val channel: Int = 1
)

data class AuthFailed(
    val reason: String = ""
)

// ─── Identify Payload ───────────────────────────────────────
data class IdentifyPayload(
    val role: String,
    val channel: Int,
    @SerializedName("sessionPasswordHash")
    val sessionPasswordHash: String? = null
)

// ─── Lan Message Wrapper ────────────────────────────────────
data class LanMessage(
    val event: String,
    val data: Any? = null
)

// ─── MC Call ────────────────────────────────────────────────
data class McCallData(
    val student: Student,
    val channel: Int
)

// ─── Sync DB ────────────────────────────────────────────────
data class SyncDbData(
    val project: Project
)

// ─── Photos Saved ───────────────────────────────────────────
data class PhotosSavedData(
    val student: Student,
    val photos: List<String>,
    val channel: Int,
    val version: Int = 1,
    val filename: String = ""
)

// ─── Student Done ───────────────────────────────────────────
data class StudentDoneData(
    val studentId: String,
    val channel: Int
)

// ─── Student Reset ──────────────────────────────────────────
data class StudentResetData(
    val studentId: String,
    val channel: Int
)

// ─── OP Progress ────────────────────────────────────────────
data class OpProgressData(
    val channel: Int,
    val status: String
)

// ─── Request State ──────────────────────────────────────────
data class RequestStateData(
    val role: String,
    val channel: Int
)

// ─── Request Frame / Frame Data ─────────────────────────────
data class RequestFrameData(
    @SerializedName("projectId")
    val projectId: String,
    @SerializedName("requesterRole")
    val requesterRole: String
)

data class FrameDataPayload(
    @SerializedName("projectId")
    val projectId: String,
    val frame: String
)

// ─── Connection State ───────────────────────────────────────
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    AUTHENTICATING,
    AUTHENTICATED,
    AUTH_FAILED,
    WAITING_FOR_DATA
}

// ─── Capture Phase ──────────────────────────────────────────
enum class CapturePhase {
    STANDBY,      // No active target
    READY_1,      // Active target, 0 photos captured
    READY_2,      // Active target, 1 photo captured (standard mode)
    SENDING       // Finalizing capture
}

// ─── Gridline Settings ──────────────────────────────────────
enum class GridlineType(val displayName: String) {
    THIRDS("Rule of Thirds"),
    QUARTERS("Quarters"),
    CROSSHAIR("Crosshair"),
    DIAGONAL("Diagonal")
}

enum class GridlineThickness(val value: Int, val displayName: String) {
    THIN(1, "Tipis"),
    MEDIUM(2, "Sedang"),
    THICK(3, "Tebal")
}

enum class GridlineColor(val hex: String, val opacity: Float, val displayName: String) {
    WHITE("#ffffff", 0.25f, "Putih"),
    YELLOW("#facc15", 0.35f, "Kuning"),
    RED("#ef4444", 0.35f, "Merah"),
    CYAN("#06b6d4", 0.35f, "Cyan"),
    GREEN("#22c55e", 0.35f, "Hijau")
}

data class GridlineSettings(
    val enabled: Boolean = true,
    val type: GridlineType = GridlineType.THIRDS,
    val thickness: GridlineThickness = GridlineThickness.THIN,
    val color: GridlineColor = GridlineColor.WHITE
)

// ─── Connection Health ──────────────────────────────────────
data class ConnectionHealth(
    val connected: Boolean = false,
    val latencyMs: Long = -1,
    val avgLatencyMs: Long = -1,
    val reconnectCount: Int = 0,
    val networkQuality: String = "unknown"
) {
    fun getQualityLabel(): String = when {
        latencyMs < 0 -> "unknown"
        latencyMs < 5 -> "excellent"
        latencyMs < 15 -> "good"
        latencyMs < 30 -> "fair"
        else -> "poor"
    }
}

// ─── Utility: Active Status Detection ──────────────────────

/**
 * Check if a student status indicates they are currently being photographed.
 * Status format: "active_1", "active_2", etc.
 */
fun isActiveStatus(status: StudentStatus): Boolean {
    return status.startsWith("active")
}

/**
 * Extract the channel number from an active status string.
 * "active_1" → 1, "active_2" → 2, else → null
 */
fun getActiveChannel(status: StudentStatus): Int? {
    if (!isActiveStatus(status)) return null
    val parts = status.split("_")
    return parts.getOrNull(1)?.toIntOrNull()
}

/**
 * Get human-readable status label for display.
 */
fun statusLabel(status: StudentStatus): String = when {
    status == "pending" -> "Menunggu"
    status == "sent" -> "Dikirim"
    status == "done" -> "Selesai"
    isActiveStatus(status) -> "Foto Ch.${getActiveChannel(status) ?: "?"}"
    else -> "Aktif"
}
