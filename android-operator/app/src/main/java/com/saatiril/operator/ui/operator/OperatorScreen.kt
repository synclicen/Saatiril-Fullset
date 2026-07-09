package com.saatiril.operator.ui.operator

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import androidx.lifecycle.compose.LocalLifecycleOwner
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperatorScreen(
    viewModel: OperatorViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

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

    var showGridlineSettings by remember { mutableStateOf(false) }

    val config = project?.config
    val mode = config?.mode ?: CameraModes.SINGLE
    val photosPerSession = CameraModes.photosPerSession(mode)
    val isPhotoshoot = CameraModes.isPhotoshootMode(mode)

    // Initialize camera when this screen enters composition
    DisposableEffect(lifecycleOwner) {
        onDispose {
            // Camera will be cleaned up in ViewModel.onCleared()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
    ) {
        // ─── Camera Preview (full screen) ──────────────────
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            // Camera preview using CameraX PreviewView
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        layoutParams = android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                        )
                        scaleType = PreviewView.ScaleType.FILL_CENTER

                        // Initialize camera with this PreviewView
                        viewModel.initCamera(lifecycleOwner, this)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Gridline Overlay
            AndroidView(
                factory = { ctx ->
                    GridlineOverlay(ctx).apply {
                        updateSettings(gridlineSettings)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { view ->
                    view.updateSettings(gridlineSettings)
                }
            )

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
                            "Menghubungkan kamera...",
                            style = TextStyle(color = MUTED, fontSize = 16.sp)
                        )
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = GOLD,
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
        }

        // ─── Top Bar ────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BG.copy(alpha = 0.7f))
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .align(Alignment.TopStart),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Connection status
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Wifi,
                    contentDescription = null,
                    tint = if (connectionState == ConnectionState.AUTHENTICATED) GREEN else RED,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    if (connectionState == ConnectionState.AUTHENTICATED) "Terhubung" else "Terputus",
                    style = TextStyle(color = Color.White, fontSize = 12.sp)
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
                            fontSize = 11.sp
                        )
                    )
                }
            }

            // Center: Project info
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    project?.name ?: "Saatiril",
                    style = TextStyle(color = GOLD, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                )
                Text(
                    "Kamera $myChannel • ${config?.ratio ?: "4:3"} • ${config?.preset ?: "original"}",
                    style = TextStyle(color = MUTED, fontSize = 11.sp)
                )
            }

            // Right: Camera switch + Gridline settings
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(
                    onClick = { viewModel.switchCamera() },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Cameraswitch,
                        contentDescription = "Switch Camera",
                        tint = MUTED,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = { showGridlineSettings = !showGridlineSettings },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Grid3x3,
                        contentDescription = "Gridline Settings",
                        tint = if (gridlineSettings.enabled) GOLD else MUTED,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // ─── Target Info (bottom left) ─────────────────────
        currentTarget?.let { target ->
            Card(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = PANEL.copy(alpha = 0.9f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    Text(
                        target.nama,
                        style = TextStyle(
                            color = GOLD,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        target.nim,
                        style = TextStyle(color = MUTED, fontSize = 14.sp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val statusText = when (capturePhase) {
                            CapturePhase.READY_1 -> if (isPhotoshoot) "Siap Foto" else "Pose 1 — Toga"
                            CapturePhase.READY_2 -> "Pose 2 — Ijazah"
                            CapturePhase.SENDING -> "Mengirim..."
                            else -> "Standby"
                        }
                        val statusColor = when (capturePhase) {
                            CapturePhase.READY_1, CapturePhase.READY_2 -> GREEN
                            CapturePhase.SENDING -> GOLD
                            else -> MUTED
                        }
                        Icon(
                            when (capturePhase) {
                                CapturePhase.READY_1, CapturePhase.READY_2 -> Icons.Default.Camera
                                CapturePhase.SENDING -> Icons.Default.Upload
                                else -> Icons.Default.Timer
                            },
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(statusText, style = TextStyle(color = statusColor, fontSize = 13.sp))
                    }
                }
            }
        }

        // ─── Standby Message (center) ──────────────────────
        if (currentTarget == null && connectionState == ConnectionState.AUTHENTICATED) {
            Card(
                modifier = Modifier.align(Alignment.Center),
                colors = CardDefaults.cardColors(containerColor = PANEL.copy(alpha = 0.8f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.PersonSearch,
                        contentDescription = null,
                        tint = MUTED,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Menunggu target dari MC...",
                        style = TextStyle(color = MUTED, fontSize = 16.sp)
                    )
                }
            }
        }

        // ─── Capture Button (bottom center) ────────────────
        if (currentTarget != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Photo count indicator
                if (!isPhotoshoot) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        repeat(photosPerSession) { i ->
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (i < capturedPhotos.size) GREEN
                                        else BORDER
                                    )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Shutter button
                FloatingActionButton(
                    onClick = {
                        viewModel.triggerCapture()
                    },
                    modifier = Modifier.size(72.dp),
                    containerColor = if (capturePhase == CapturePhase.SENDING) GOLD.copy(alpha = 0.5f) else GOLD,
                    shape = CircleShape
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = BG,
                            strokeWidth = 3.dp
                        )
                    } else {
                        Icon(
                            Icons.Default.Camera,
                            contentDescription = "Capture",
                            modifier = Modifier.size(32.dp),
                            tint = BG
                        )
                    }
                }
            }
        }

        // ─── Gridline Settings Panel ───────────────────────
        AnimatedVisibility(
            visible = showGridlineSettings,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 48.dp, end = 8.dp)
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

        // ─── Disconnection Overlay ──────────────────────────
        if (connectionState == ConnectionState.DISCONNECTED) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BG.copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.WifiOff,
                        contentDescription = null,
                        tint = RED,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        "Koneksi Terputus",
                        style = TextStyle(color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    )
                    Text(
                        "Mencoba menghubungkan kembali...",
                        style = TextStyle(color = MUTED, fontSize = 14.sp)
                    )
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
        modifier = Modifier.width(260.dp),
        colors = CardDefaults.cardColors(containerColor = PANEL),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, BORDER)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Gridline",
                    style = TextStyle(color = GOLD, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                )
                IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = MUTED, modifier = Modifier.size(16.dp))
                }
            }

            // Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Aktifkan", style = TextStyle(color = Color.White, fontSize = 14.sp))
                Switch(
                    checked = settings.enabled,
                    onCheckedChange = onEnabledChange,
                    colors = SwitchDefaults.colors(checkedTrackColor = GOLD)
                )
            }

            if (settings.enabled) {
                // Type
                Text("Tipe:", style = TextStyle(color = MUTED, fontSize = 12.sp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    GridlineType.entries.forEach { type ->
                        FilterChip(
                            selected = settings.type == type,
                            onClick = { onTypeChange(type) },
                            label = {
                                Text(type.displayName, fontSize = 10.sp)
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CARD,
                                selectedLabelColor = GOLD
                            ),
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }

                // Thickness
                Text("Ketebalan:", style = TextStyle(color = MUTED, fontSize = 12.sp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    GridlineThickness.entries.forEach { thickness ->
                        FilterChip(
                            selected = settings.thickness == thickness,
                            onClick = { onThicknessChange(thickness) },
                            label = {
                                Text(thickness.displayName, fontSize = 10.sp)
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CARD,
                                selectedLabelColor = GOLD
                            ),
                            modifier = Modifier.height(28.dp)
                        )
                    }
                }

                // Color
                Text("Warna:", style = TextStyle(color = MUTED, fontSize = 12.sp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GridlineColor.entries.forEach { color ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
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
