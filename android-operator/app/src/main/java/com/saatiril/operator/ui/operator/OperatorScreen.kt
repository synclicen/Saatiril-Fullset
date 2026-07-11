package com.saatiril.operator.ui.operator

import androidx.compose.animation.*
import android.Manifest
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.lifecycle.LifecycleOwner
import com.saatiril.operator.data.*
import com.saatiril.operator.ui.gridline.GridlineOverlay

// ─── Theme Colors ──────────────────────────────────────────
private val BG = Color(0xFF1a0b2e)
private val PANEL = Color(0xFF2a164a)
private val CARD = Color(0xFF3b2263)
private val BORDER = Color(0xFF533485)
private val GOLD = Color(0xFFd4af37)
private val MUTED = Color(0xFFc4b5fd)
private val CYAN = Color(0xFF06b6d4)
private val RED = Color(0xFFef4444)
private val GREEN = Color(0xFF4ade80)
private val DARK_GREEN = Color(0xFF22c55e)

// ─── Shutter Mode Options ──────────────────────────────────
private val SHUTTER_MODES = listOf(
    "manual" to "Manual",
    "timer-3" to "3s",
    "timer-5" to "5s",
    "timer-10" to "10s",
    "ai" to "AI"
)

// ─── Draggable Panel State ─────────────────────────────────
data class PanelOffset(val x: Float, val y: Float)

/**
 * Main Operator Screen — matches the Windows/web operator panel layout.
 * Features draggable/resizable floating panels so the operator can freely
 * arrange the UI without blocking the camera preview.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperatorScreen(
    viewModel: OperatorViewModel,
    hasCameraPermission: Boolean = false
) {
    val context = LocalContext.current
    val lifecycleOwner: LifecycleOwner = when (context) {
        is LifecycleOwner -> context
        else -> (context as ComponentActivity)
    }

    // ─── Collect all ViewModel state ────────────────────────
    val project by viewModel.project.collectAsState()
    val currentTarget by viewModel.currentTarget.collectAsState()
    val capturePhase by viewModel.capturePhase.collectAsState()
    val capturedPhotos by viewModel.capturedPhotos.collectAsState()
    val isSending by viewModel.isSending.collectAsState()
    val gridlineSettings by viewModel.gridlineSettings.collectAsState()
    val latencyMs by viewModel.latencyMs.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val myChannel by viewModel.myChannel.collectAsState()
    val cameraConnected by viewModel.cameraConnected.collectAsState()
    val uvcDeviceAttached by viewModel.uvcDeviceAttached.collectAsState()
    val cameraSource by viewModel.cameraSource.collectAsState()
    val shutterMode by viewModel.shutterMode.collectAsState()
    val timerCountdown by viewModel.timerCountdown.collectAsState()
    val opSearchQuery by viewModel.opSearchQuery.collectAsState()
    val frameBitmap by viewModel.frameBitmap.collectAsState()

    val config = project?.config
    val mode = config?.mode ?: CameraModes.SINGLE
    val photosPerSession = CameraModes.photosPerSession(mode)
    val isPhotoshoot = CameraModes.isPhotoshootMode(mode)

    // ─── Panel visibility state ─────────────────────────────
    var showGridlineSettings by remember { mutableStateOf(false) }
    var showQueueList by remember { mutableStateOf(true) }
    var showShutterMode by remember { mutableStateOf(true) }
    var showTargetInfo by remember { mutableStateOf(true) }

    // ─── Draggable panel offsets ────────────────────────────
    var targetInfoOffset by remember { mutableStateOf(PanelOffset(0f, 0f)) }
    var shutterModeOffset by remember { mutableStateOf(PanelOffset(0f, 0f)) }
    var gridlineSettingsOffset by remember { mutableStateOf(PanelOffset(0f, 0f)) }
    var queueListOffset by remember { mutableStateOf(PanelOffset(0f, 0f)) }
    var opSearchOffset by remember { mutableStateOf(PanelOffset(0f, 0f)) }

    // ─── Camera permission + initialization ─────────────────
    var localPermissionState by remember {
        mutableStateOf(
            (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PermissionChecker.PERMISSION_GRANTED)
        )
    }
    val effectivePermission = hasCameraPermission || localPermissionState
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            val currentPermission = (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PermissionChecker.PERMISSION_GRANTED)
            if (currentPermission != localPermissionState) {
                localPermissionState = currentPermission
            }
        }
    }

    LaunchedEffect(effectivePermission, previewViewRef) {
        val pv = previewViewRef
        if (effectivePermission && pv != null) {
            try {
                viewModel.initCamera(lifecycleOwner, pv)
            } catch (e: SecurityException) {
                localPermissionState = false
            } catch (_: Exception) {}
        }
    }

    // ─── Derived data ───────────────────────────────────────
    val channelStudents = remember(project, myChannel, isPhotoshoot) {
        val db = project?.database ?: emptyList()
        if (isPhotoshoot) db
        else db.filter { it.assignedChannel == myChannel }
    }

    val remainingCount = channelStudents.count { it.status == "pending" }

    val opQueue = remember(project, myChannel, isPhotoshoot) {
        if (!isPhotoshoot || project == null) emptyList()
        else {
            val alreadyPhotographed = project!!.photoHistory
                .filter { it.channel == myChannel }
                .map { it.student.id }.toSet()
            val doneIds = project!!.database.filter { it.status == "done" }.map { it.id }.toSet()
            project!!.database.filter { it.status == "sent" && !alreadyPhotographed.contains(it.id) }
        }
    }

    val opSearchResults = remember(opQueue, opSearchQuery) {
        if (opSearchQuery.isBlank()) opQueue
        else {
            val q = opSearchQuery.lowercase().trim()
            opQueue.filter { it.nim.lowercase().contains(q) || it.nama.lowercase().contains(q) }
        }
    }

    val hasActiveTarget = currentTarget != null

    // Progress text for capture button
    val progressText = when (capturePhase) {
        CapturePhase.STANDBY -> "Standby"
        CapturePhase.READY_1 -> if (isPhotoshoot) "Siap Foto" else "Pose 1 — Toga"
        CapturePhase.READY_2 -> "Pose 2 — Ijazah"
        CapturePhase.SENDING -> "Mengirim..."
    }

    // ─── ROOT LAYOUT ────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
    ) {
        // ─── CAMERA PREVIEW (full screen) ───────────────────
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        layoutParams = android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                        )
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        previewViewRef = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Gridline Overlay
            if (gridlineSettings.enabled) {
                AndroidView(
                    factory = { ctx ->
                        GridlineOverlay(ctx).apply {
                            updateSettings(gridlineSettings)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { view -> view.updateSettings(gridlineSettings) }
                )
            }

            // Frame Overlay
            frameBitmap?.let { frame ->
                AndroidView(
                    factory = { ctx ->
                        android.widget.ImageView(ctx).apply {
                            scaleType = android.widget.ImageView.ScaleType.FIT_XY
                            layoutParams = android.widget.FrameLayout.LayoutParams(
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { view -> view.setImageBitmap(frame) }
                )
            }

            // Timer Countdown Overlay
            if (timerCountdown > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BG.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(CircleShape)
                            .background(BG.copy(alpha = 0.8f))
                            .border(4.dp, GOLD, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "$timerCountdown",
                            style = TextStyle(
                                color = GOLD,
                                fontSize = 56.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
            }

            // Camera not connected warning
            if (!cameraConnected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(BG.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.VideocamOff,
                            contentDescription = null,
                            tint = MUTED,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            if (!effectivePermission) "Izin kamera diperlukan"
                            else if (cameraSource == "none") "Kamera tidak terdeteksi"
                            else "Menghubungkan kamera...",
                            style = TextStyle(color = MUTED, fontSize = 16.sp)
                        )
                        if (!effectivePermission) {
                            Button(
                                onClick = {
                                    val activity = context as? ComponentActivity
                                    activity?.let {
                                        try {
                                            val intent = android.content.Intent(
                                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                android.net.Uri.fromParts("package", it.packageName, null)
                                            )
                                            it.startActivity(intent)
                                        } catch (_: Exception) {}
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = GOLD),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Buka Pengaturan", color = BG, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            Text(
                                "Buka Settings > Izin > Kamera > Izinkan",
                                style = TextStyle(color = MUTED.copy(alpha = 0.6f), fontSize = 11.sp)
                            )
                        } else if (cameraSource != "none") {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = GOLD,
                                strokeWidth = 2.dp
                            )
                        }
                    }
                }
            }
        }

        // ─── TOP BAR ────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BG.copy(alpha = 0.85f))
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .align(Alignment.TopStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Connection status
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Default.Wifi,
                    contentDescription = null,
                    tint = if (connectionState == ConnectionState.AUTHENTICATED) GREEN else RED,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    if (connectionState == ConnectionState.AUTHENTICATED) "Terhubung" else "Terputus",
                    style = TextStyle(color = Color.White, fontSize = 11.sp)
                )
                if (latencyMs >= 0) {
                    Text(
                        "${latencyMs}ms",
                        style = TextStyle(
                            color = when {
                                latencyMs < 5 -> GREEN
                                latencyMs < 15 -> Color.Yellow
                                latencyMs < 30 -> Color(0xFFfb923c)
                                else -> RED
                            },
                            fontSize = 10.sp
                        )
                    )
                }
            }

            // Center: Project info
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    project?.name ?: "Saatiril",
                    style = TextStyle(color = GOLD, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                )
                val cameraLabel = when (cameraSource) {
                    "uvc" -> "USB Capture"
                    "builtin" -> "Kamera HP"
                    else -> "Kamera"
                }
                Text(
                    "Kamera $myChannel • $cameraLabel • ${config?.ratio ?: "4:3"} • ${config?.preset ?: "original"}",
                    style = TextStyle(color = MUTED, fontSize = 10.sp)
                )
            }

            // Right: Quick action buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                IconButton(
                    onClick = { viewModel.switchCamera() },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.Cameraswitch, contentDescription = "Switch Camera", tint = MUTED, modifier = Modifier.size(16.dp))
                }
                IconButton(
                    onClick = { showShutterMode = !showShutterMode },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        when (shutterMode) {
                            "ai" -> Icons.Default.AutoAwesome
                            else -> if (shutterMode.startsWith("timer")) Icons.Default.Timer else Icons.Default.Camera
                        },
                        contentDescription = "Shutter Mode",
                        tint = if (showShutterMode) GOLD else MUTED,
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(
                    onClick = { showGridlineSettings = !showGridlineSettings },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.Grid3x3, contentDescription = "Gridline", tint = if (gridlineSettings.enabled) GOLD else MUTED, modifier = Modifier.size(16.dp))
                }
                IconButton(
                    onClick = { showQueueList = !showQueueList },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.List, contentDescription = "Queue", tint = if (showQueueList) GOLD else MUTED, modifier = Modifier.size(16.dp))
                }
            }
        }

        // ─── DRAGGABLE: TARGET INFO PANEL ──────────────────
        if (showTargetInfo && hasActiveTarget) {
            currentTarget?.let { target ->
                DraggablePanel(
                    initialOffset = targetInfoOffset,
                    onOffsetChange = { targetInfoOffset = it }
                ) {
                    TargetInfoCard(
                        target = target,
                        capturePhase = capturePhase,
                        isPhotoshoot = isPhotoshoot,
                        isSending = isSending
                    )
                }
            }
        }

        // ─── DRAGGABLE: STANDBY MESSAGE ────────────────────
        if (currentTarget == null && connectionState == ConnectionState.AUTHENTICATED) {
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = PANEL.copy(alpha = 0.85f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.PersonSearch, contentDescription = null, tint = MUTED, modifier = Modifier.size(40.dp))
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Menunggu target dari MC...", style = TextStyle(color = MUTED, fontSize = 14.sp))
                }
            }
        }

        // ─── DRAGGABLE: SHUTTER MODE PANEL ─────────────────
        if (showShutterMode) {
            DraggablePanel(
                initialOffset = shutterModeOffset,
                onOffsetChange = { shutterModeOffset = it }
            ) {
                ShutterModePanel(
                    currentMode = shutterMode,
                    onModeChange = { viewModel.setShutterMode(it) },
                    mode = mode
                )
            }
        }

        // ─── DRAGGABLE: GRIDLINE SETTINGS PANEL ────────────
        if (showGridlineSettings) {
            DraggablePanel(
                initialOffset = gridlineSettingsOffset,
                onOffsetChange = { gridlineSettingsOffset = it }
            ) {
                GridlineSettingsPanel(
                    settings = gridlineSettings,
                    onEnabledChange = { viewModel.setGridlineEnabled(it) },
                    onTypeChange = { viewModel.setGridlineType(it) },
                    onThicknessChange = { viewModel.setGridlineThickness(it) },
                    onColorChange = { viewModel.setGridlineColor(it) },
                    onClose = { showGridlineSettings = false }
                )
            }
        }

        // ─── DRAGGABLE: OP SEARCH PANEL (Photoshoot Mode) ──
        if (isPhotoshoot && showQueueList) {
            DraggablePanel(
                initialOffset = opSearchOffset,
                onOffsetChange = { opSearchOffset = it }
            ) {
                OpSearchPanel(
                    opQueue = opQueue,
                    opSearchResults = opSearchResults,
                    opSearchQuery = opSearchQuery,
                    currentTargetId = currentTarget?.id,
                    onSearchQueryChange = { viewModel.setOpSearchQuery(it) },
                    onSelectTarget = { viewModel.setOpCurrentTarget(it) },
                    isSending = isSending
                )
            }
        }

        // ─── DRAGGABLE: QUEUE LIST PANEL ───────────────────
        if (showQueueList) {
            DraggablePanel(
                initialOffset = queueListOffset,
                onOffsetChange = { queueListOffset = it }
            ) {
                QueueListPanel(
                    channelStudents = channelStudents,
                    myChannel = myChannel,
                    remainingCount = remainingCount,
                    isPhotoshoot = isPhotoshoot
                )
            }
        }

        // ─── CAPTURE BUTTON BAR (Bottom) ───────────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(PANEL.copy(alpha = 0.9f))
                .border(BorderStroke(1.dp, BORDER.copy(alpha = 0.5f)))
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Photo count indicator
            if (hasActiveTarget && !isPhotoshoot) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    repeat(photosPerSession) { i ->
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (i < capturedPhotos.size) GREEN else BORDER)
                        )
                    }
                }
            }

            // Capture button — matches Windows version's states
            CaptureButton(
                capturePhase = capturePhase,
                isSending = isSending,
                isPhotoshoot = isPhotoshoot,
                shutterMode = shutterMode,
                timerCountdown = timerCountdown,
                onCapture = { viewModel.triggerCapture() },
                onCancelTimer = { viewModel.cancelTimerCapture() }
            )
        }

        // ─── DISCONNECTION OVERLAY ──────────────────────────
        if (connectionState == ConnectionState.DISCONNECTED) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BG.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.WifiOff, contentDescription = null, tint = RED, modifier = Modifier.size(44.dp))
                    Text("Koneksi Terputus", style = TextStyle(color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold))
                    Text("Mencoba menghubungkan kembali...", style = TextStyle(color = MUTED, fontSize = 13.sp))
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = GOLD, strokeWidth = 2.dp)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// DRAGGABLE PANEL WRAPPER
// ═══════════════════════════════════════════════════════════

@Composable
fun DraggablePanel(
    initialOffset: PanelOffset,
    onOffsetChange: (PanelOffset) -> Unit,
    content: @Composable () -> Unit
) {
    var offsetX by remember { mutableStateOf(initialOffset.x) }
    var offsetY by remember { mutableStateOf(initialOffset.y) }

    Box(
        modifier = Modifier
            .offset { androidx.compose.ui.unit.IntOffset(offsetX.toInt(), offsetY.toInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offsetX += dragAmount.x
                    offsetY += dragAmount.y
                    onOffsetChange(PanelOffset(offsetX, offsetY))
                }
            }
    ) {
        content()
    }
}

// ═══════════════════════════════════════════════════════════
// TARGET INFO CARD
// ═══════════════════════════════════════════════════════════

@Composable
fun TargetInfoCard(
    target: Student,
    capturePhase: CapturePhase,
    isPhotoshoot: Boolean,
    isSending: Boolean
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CARD.copy(alpha = 0.95f)),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(2.dp, GOLD)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Avatar circle
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(PANEL)
                    .border(2.dp, GOLD, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Person, contentDescription = null, tint = GOLD, modifier = Modifier.size(18.dp))
            }

            // Info
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    target.nama,
                    style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(target.nim, style = TextStyle(color = MUTED, fontSize = 11.sp, fontFamily = FontFamily.Monospace))
            }

            // Status badge
            val (badgeBg, badgeText, badgeBorder) = when (capturePhase) {
                CapturePhase.READY_1 -> Triple(GOLD.copy(alpha = 0.2f), GOLD, GOLD.copy(alpha = 0.4f))
                CapturePhase.READY_2 -> Triple(DARK_GREEN.copy(alpha = 0.2f), GREEN, DARK_GREEN.copy(alpha = 0.4f))
                CapturePhase.SENDING -> Triple(BORDER.copy(alpha = 0.4f), MUTED, BORDER)
                else -> Triple(BORDER.copy(alpha = 0.3f), MUTED, BORDER)
            }

            val statusLabel = when (capturePhase) {
                CapturePhase.READY_1 -> if (isPhotoshoot) "Siap Foto" else "Toga"
                CapturePhase.READY_2 -> "Ijazah"
                CapturePhase.SENDING -> "Kirim..."
                else -> "Standby"
            }

            val statusIcon = when (capturePhase) {
                CapturePhase.READY_1, CapturePhase.READY_2 -> Icons.Default.Camera
                CapturePhase.SENDING -> Icons.Default.Sync
                else -> Icons.Default.Timer
            }

            Row(
                modifier = Modifier
                    .background(badgeBg, RoundedCornerShape(4.dp))
                    .border(BorderStroke(1.dp, badgeBorder), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                if (isSending) {
                    CircularProgressIndicator(modifier = Modifier.size(10.dp), color = MUTED, strokeWidth = 1.5.dp)
                } else {
                    Icon(statusIcon, contentDescription = null, tint = badgeText, modifier = Modifier.size(10.dp))
                }
                Text(statusLabel, style = TextStyle(color = badgeText, fontSize = 9.sp, fontWeight = FontWeight.Bold))
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// SHUTTER MODE PANEL
// ═══════════════════════════════════════════════════════════

@Composable
fun ShutterModePanel(
    currentMode: String,
    onModeChange: (String) -> Unit,
    mode: CameraMode
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CARD.copy(alpha = 0.95f)),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, BORDER)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                "MODE SHUTTER",
                style = TextStyle(color = MUTED, fontSize = 8.sp, fontWeight = FontWeight.Bold),
                letterSpacing = 2.sp
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SHUTTER_MODES.forEach { (id, label) ->
                    // AI mode only allowed in single/dual
                    if (id == "ai" && mode != CameraModes.SINGLE && mode != CameraModes.DUAL) return@forEach

                    val isActive = currentMode == id
                    val icon = when (id) {
                        "ai" -> Icons.Default.AutoAwesome
                        "manual" -> Icons.Default.Camera
                        else -> Icons.Default.Timer
                    }

                    Row(
                        modifier = Modifier
                            .background(
                                if (isActive) GOLD.copy(alpha = 0.2f) else PANEL,
                                RoundedCornerShape(6.dp)
                            )
                            .border(
                                BorderStroke(1.dp, if (isActive) GOLD else BORDER),
                                RoundedCornerShape(6.dp)
                            )
                            .clickable { onModeChange(id) }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Icon(icon, contentDescription = null, tint = if (isActive) GOLD else MUTED, modifier = Modifier.size(12.dp))
                        Text(label, style = TextStyle(color = if (isActive) GOLD else MUTED, fontSize = 9.sp, fontWeight = FontWeight.Bold))
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// CAPTURE BUTTON — matches Windows version state machine
// ═══════════════════════════════════════════════════════════

@Composable
fun CaptureButton(
    capturePhase: CapturePhase,
    isSending: Boolean,
    isPhotoshoot: Boolean,
    shutterMode: String,
    timerCountdown: Int,
    onCapture: () -> Unit,
    onCancelTimer: () -> Unit
) {
    val modifier = Modifier
        .fillMaxWidth()
        .height(56.dp)

    when {
        // STANDBY
        capturePhase == CapturePhase.STANDBY -> {
            OutlinedButton(
                onClick = {},
                enabled = false,
                modifier = modifier,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    disabledContainerColor = PANEL.copy(alpha = 0.6f),
                    disabledContentColor = MUTED
                ),
                border = BorderStroke(2.dp, BORDER)
            ) {
                Icon(Icons.Default.Camera, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("STANDBY", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        // TIMER COUNTING DOWN — show cancel
        timerCountdown > 0 -> {
            Button(
                onClick = onCancelTimer,
                modifier = modifier,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RED),
                border = BorderStroke(2.dp, RED)
            ) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(20.dp), tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text("BATAL (${timerCountdown}s)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        // READY 1
        capturePhase == CapturePhase.READY_1 -> {
            val isTimer = shutterMode.startsWith("timer")
            val isAi = shutterMode == "ai"

            if (isAi) {
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = modifier,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        disabledContainerColor = DARK_GREEN.copy(alpha = 0.15f),
                        disabledContentColor = GREEN
                    ),
                    border = BorderStroke(2.dp, DARK_GREEN)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = GREEN, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("AI Mendeteksi...", color = GREEN, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            } else if (isTimer) {
                val timerLabel = shutterMode.removePrefix("timer-") + "s"
                Button(
                    onClick = onCapture,
                    modifier = modifier,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GOLD),
                    border = BorderStroke(2.dp, GOLD)
                ) {
                    Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(20.dp), tint = BG)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (isPhotoshoot) "FOTO ($timerLabel)" else "FOTO 1 — TOGA ($timerLabel)",
                        color = BG, fontWeight = FontWeight.Bold, fontSize = 15.sp
                    )
                }
            } else {
                // Manual
                Button(
                    onClick = onCapture,
                    modifier = modifier,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isPhotoshoot) GREEN else GOLD
                    ),
                    border = BorderStroke(2.dp, if (isPhotoshoot) GREEN else GOLD)
                ) {
                    Icon(Icons.Default.Camera, contentDescription = null, modifier = Modifier.size(20.dp), tint = if (isPhotoshoot) BG else BG)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (isPhotoshoot) "FOTO" else "FOTO 1 — TOGA",
                        color = BG, fontWeight = FontWeight.Bold, fontSize = 16.sp
                    )
                }
            }
        }

        // READY 2
        capturePhase == CapturePhase.READY_2 -> {
            val isTimer = shutterMode.startsWith("timer")
            val isAi = shutterMode == "ai"

            if (isAi) {
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = modifier,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        disabledContainerColor = DARK_GREEN.copy(alpha = 0.15f),
                        disabledContentColor = GREEN
                    ),
                    border = BorderStroke(2.dp, DARK_GREEN)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = GREEN, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("AI Ijazah...", color = GREEN, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            } else if (isTimer) {
                val timerLabel = shutterMode.removePrefix("timer-") + "s"
                Button(
                    onClick = onCapture,
                    modifier = modifier,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GOLD),
                    border = BorderStroke(2.dp, GOLD)
                ) {
                    Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(20.dp), tint = BG)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("FOTO 2 — IJAZAH ($timerLabel)", color = BG, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            } else {
                Button(
                    onClick = onCapture,
                    modifier = modifier,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DARK_GREEN),
                    border = BorderStroke(2.dp, DARK_GREEN)
                ) {
                    Icon(Icons.Default.Camera, contentDescription = null, modifier = Modifier.size(20.dp), tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("FOTO 2 — IJAZAH", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        }

        // SENDING
        capturePhase == CapturePhase.SENDING -> {
            OutlinedButton(
                onClick = {},
                enabled = false,
                modifier = modifier,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    disabledContainerColor = PANEL.copy(alpha = 0.6f),
                    disabledContentColor = MUTED
                ),
                border = BorderStroke(2.dp, BORDER)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MUTED, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("MENGIRIM...", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// OP SEARCH PANEL (Photoshoot Mode)
// ═══════════════════════════════════════════════════════════

@Composable
fun OpSearchPanel(
    opQueue: List<Student>,
    opSearchResults: List<Student>,
    opSearchQuery: String,
    currentTargetId: String?,
    onSearchQueryChange: (String) -> Unit,
    onSelectTarget: (Student) -> Unit,
    isSending: Boolean
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CARD.copy(alpha = 0.95f)),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, GOLD)
    ) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .widthIn(max = 280.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Default.List, contentDescription = null, tint = GOLD, modifier = Modifier.size(12.dp))
                    Text(
                        "Antre dari MC (${opQueue.size})",
                        style = TextStyle(color = GOLD, fontSize = 9.sp, fontWeight = FontWeight.Bold),
                        letterSpacing = 1.sp
                    )
                }
            }

            // Search input
            OutlinedTextField(
                value = opSearchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Cari NIM / Nama...", style = TextStyle(color = BORDER, fontSize = 11.sp)) },
                textStyle = TextStyle(color = Color.White, fontSize = 11.sp),
                modifier = Modifier.fillMaxWidth().height(32.dp),
                shape = RoundedCornerShape(6.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = GOLD,
                    unfocusedBorderColor = BORDER,
                    cursorColor = GOLD,
                    focusedContainerColor = PANEL,
                    unfocusedContainerColor = PANEL
                ),
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = MUTED, modifier = Modifier.size(14.dp))
                },
                singleLine = true
            )

            // Results
            if (opQueue.isEmpty()) {
                Text(
                    if (opSearchQuery.isNotBlank()) "Tidak ditemukan" else "Belum ada peserta dikirim MC",
                    style = TextStyle(color = MUTED, fontSize = 9.sp),
                    modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 140.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    items(opSearchResults) { student ->
                        val isCurrentTarget = student.id == currentTargetId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isCurrentTarget) GOLD.copy(alpha = 0.1f) else Color.Transparent
                                )
                                .border(
                                    BorderStroke(
                                        if (isCurrentTarget) 2.dp else 0.dp,
                                        if (isCurrentTarget) GOLD else Color.Transparent
                                    )
                                )
                                .clickable(enabled = !isSending) { onSelectTarget(student) }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                student.nim,
                                style = TextStyle(color = MUTED, fontSize = 9.sp, fontFamily = FontFamily.Monospace),
                                modifier = Modifier.width(50.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                student.nama,
                                style = TextStyle(
                                    color = if (isCurrentTarget) GOLD else Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = if (isCurrentTarget) FontWeight.Bold else FontWeight.Normal
                                ),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (isCurrentTarget) {
                                Icon(Icons.Default.Camera, contentDescription = null, tint = GOLD, modifier = Modifier.size(12.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// QUEUE LIST PANEL
// ═══════════════════════════════════════════════════════════

@Composable
fun QueueListPanel(
    channelStudents: List<Student>,
    myChannel: Int,
    remainingCount: Int,
    isPhotoshoot: Boolean
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CARD.copy(alpha = 0.95f)),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, BORDER)
    ) {
        Column(
            modifier = Modifier
                .padding(6.dp)
                .widthIn(max = 300.dp)
                .heightIn(max = 250.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PANEL)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Antrean: $remainingCount",
                    style = TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                )
                Text("Ch.$myChannel", style = TextStyle(color = MUTED, fontSize = 9.sp))
            }

            // Column headers
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PANEL.copy(alpha = 0.5f))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text("No", style = TextStyle(color = MUTED, fontSize = 8.sp, fontWeight = FontWeight.Bold), modifier = Modifier.width(22.dp))
                Text("NIM", style = TextStyle(color = MUTED, fontSize = 8.sp, fontWeight = FontWeight.Bold), modifier = Modifier.width(55.dp))
                Text("Nama", style = TextStyle(color = MUTED, fontSize = 8.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                Text("Status", style = TextStyle(color = MUTED, fontSize = 8.sp, fontWeight = FontWeight.Bold), modifier = Modifier.width(50.dp))
            }

            // Student rows
            if (channelStudents.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Tidak ada mahasiswa", style = TextStyle(color = MUTED, fontSize = 10.sp))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    itemsIndexed(channelStudents) { idx, student ->
                        val isActive = student.status == "active_$myChannel"
                        val isDone = student.status == "done"
                        val isSent = student.status == "sent"

                        val rowBg = when {
                            isActive -> GOLD.copy(alpha = 0.15f)
                            isSent -> GOLD.copy(alpha = 0.05f)
                            isDone -> MUTED.copy(alpha = 0.03f)
                            else -> Color.Transparent
                        }

                        val nameColor = when {
                            isActive -> GOLD
                            isDone -> MUTED
                            else -> Color.White
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(rowBg)
                                .padding(horizontal = 4.dp, vertical = 3.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                "${idx + 1}",
                                style = TextStyle(color = MUTED, fontSize = 8.sp, fontFamily = FontFamily.Monospace),
                                modifier = Modifier.width(22.dp)
                            )
                            Text(
                                student.nim,
                                style = TextStyle(color = MUTED, fontSize = 8.sp, fontFamily = FontFamily.Monospace),
                                modifier = Modifier.width(55.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                student.nama,
                                style = TextStyle(
                                    color = nameColor,
                                    fontSize = 9.sp,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                    textDecoration = if (isDone) TextDecoration.LineThrough else TextDecoration.None
                                ),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            // Status badge
                            val (statusLabel, statusColor) = when {
                                isActive -> "Foto" to GOLD
                                isSent -> "Kirim" to GOLD.copy(alpha = 0.7f)
                                isDone -> "Selesai" to MUTED
                                else -> "Tunggu" to BORDER
                            }
                            Text(
                                statusLabel,
                                style = TextStyle(color = statusColor, fontSize = 7.sp, fontWeight = FontWeight.Bold),
                                modifier = Modifier.width(50.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// GRIDLINE SETTINGS PANEL
// ═══════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GridlineSettingsPanel(
    settings: GridlineSettings,
    onEnabledChange: (Boolean) -> Unit,
    onTypeChange: (GridlineType) -> Unit,
    onThicknessChange: (GridlineThickness) -> Unit,
    onColorChange: (GridlineColor) -> Unit,
    onClose: () -> Unit
) {
    Card(
        modifier = Modifier.width(240.dp),
        colors = CardDefaults.cardColors(containerColor = CARD.copy(alpha = 0.95f)),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, BORDER)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Gridline", style = TextStyle(color = GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp))
                IconButton(onClick = onClose, modifier = Modifier.size(22.dp)) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = MUTED, modifier = Modifier.size(14.dp))
                }
            }

            // Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Aktifkan", style = TextStyle(color = Color.White, fontSize = 12.sp))
                Switch(
                    checked = settings.enabled,
                    onCheckedChange = onEnabledChange,
                    colors = SwitchDefaults.colors(checkedTrackColor = GOLD)
                )
            }

            if (settings.enabled) {
                // Type
                Text("Tipe:", style = TextStyle(color = MUTED, fontSize = 10.sp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    GridlineType.entries.forEach { type ->
                        val isActive = settings.type == type
                        Row(
                            modifier = Modifier
                                .background(
                                    if (isActive) GOLD.copy(alpha = 0.2f) else PANEL,
                                    RoundedCornerShape(4.dp)
                                )
                                .border(
                                    BorderStroke(1.dp, if (isActive) GOLD else BORDER),
                                    RoundedCornerShape(4.dp)
                                )
                                .clickable { onTypeChange(type) }
                                .padding(horizontal = 5.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            val typeIcon = when (type) {
                                GridlineType.THIRDS -> Icons.Default.List
                                GridlineType.QUARTERS -> Icons.Default.List
                                GridlineType.CROSSHAIR -> Icons.Default.Add
                                GridlineType.DIAGONAL -> Icons.Default.Close
                            }
                            Icon(typeIcon, contentDescription = null, tint = if (isActive) GOLD else MUTED, modifier = Modifier.size(10.dp))
                            Text(type.displayName, style = TextStyle(color = if (isActive) GOLD else MUTED, fontSize = 8.sp, fontWeight = FontWeight.Bold))
                        }
                    }
                }

                // Thickness
                Text("Ketebalan:", style = TextStyle(color = MUTED, fontSize = 10.sp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    GridlineThickness.entries.forEach { thickness ->
                        val isActive = settings.thickness == thickness
                        Box(
                            modifier = Modifier
                                .size(32.dp, 24.dp)
                                .background(
                                    if (isActive) GOLD.copy(alpha = 0.2f) else PANEL,
                                    RoundedCornerShape(4.dp)
                                )
                                .border(
                                    BorderStroke(1.dp, if (isActive) GOLD else BORDER),
                                    RoundedCornerShape(4.dp)
                                )
                                .clickable { onThicknessChange(thickness) },
                            contentAlignment = Alignment.Center
                        ) {
                            // Dot size represents thickness
                            Box(
                                modifier = Modifier
                                    .size((thickness.value * 2 + 1).dp)
                                    .clip(CircleShape)
                                    .background(if (isActive) GOLD else MUTED)
                            )
                        }
                    }
                    // Labels
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 0.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        GridlineThickness.entries.forEach { thickness ->
                            Text(thickness.displayName, style = TextStyle(color = MUTED, fontSize = 7.sp))
                        }
                    }
                }

                // Color
                Text("Warna:", style = TextStyle(color = MUTED, fontSize = 10.sp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    GridlineColor.entries.forEach { color ->
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(android.graphics.Color.parseColor(color.hex)))
                                .border(
                                    width = if (settings.color == color) 3.dp else 1.dp,
                                    color = if (settings.color == color) GOLD else BORDER,
                                    shape = CircleShape
                                )
                                .clickable { onColorChange(color) }
                        )
                    }
                }
            }
        }
    }
}
