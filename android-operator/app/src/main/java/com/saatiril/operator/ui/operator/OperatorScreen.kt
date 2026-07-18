package com.saatiril.operator.ui.operator

import android.Manifest
import android.util.Log
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.lifecycle.LifecycleOwner
import com.saatiril.operator.camera.HandTriggerDetector
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

// ─── Panel IDs ─────────────────────────────────────────────
object Panels {
    const val TARGET_INFO = "target_info"
    const val SHUTTER_MODE = "shutter_mode"
    const val GRIDLINE = "gridline"
    const val OP_SEARCH = "op_search"
    const val QUEUE_LIST = "queue_list"

    val ALL = listOf(TARGET_INFO, SHUTTER_MODE, GRIDLINE, OP_SEARCH, QUEUE_LIST)
    val LABELS = mapOf(
        TARGET_INFO to "Info Target",
        SHUTTER_MODE to "Mode Shutter",
        GRIDLINE to "Gridline",
        OP_SEARCH to "Antrean MC",
        QUEUE_LIST to "Daftar Antrean"
    )
}

/**
 * Main Operator Screen — matches Windows/web operator panel layout.
 *
 * Layout: Full screen split into:
 *   - Camera preview (respects admin's aspect ratio, NEVER covered by panels)
 *   - Bottom panel area (collapsible, scrollable, resizable via drag divider)
 *   - Panels are stacked vertically, each can be closed individually
 *   - Panel selector checklist to choose which panels to display
 *
 * Photos are saved to the targetFolder on the Android device
 * (matching the Windows/Electron file save behavior).
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
    val cameraSource by viewModel.cameraSource.collectAsState()
    val shutterMode by viewModel.shutterMode.collectAsState()
    val timerCountdown by viewModel.timerCountdown.collectAsState()
    val handTriggerEnabled by viewModel.handTriggerEnabled.collectAsState()
    val handState by viewModel.handState.collectAsState()
    val opSearchQuery by viewModel.opSearchQuery.collectAsState()
    val frameBitmap by viewModel.frameBitmap.collectAsState()
    val mcCallBuffer by viewModel.mcCallBuffer.collectAsState()
    val opQueue by viewModel.opQueue.collectAsState()
    val channelStudents by viewModel.channelStudents.collectAsState()
    val savePath by viewModel.savePath.collectAsState()
    val availableCameras by viewModel.availableCameras.collectAsState()
    val currentCameraId by viewModel.currentCameraId.collectAsState()
    // v6: No useCamera2Engine — Camera2 is ALWAYS used

    val config = project?.config
    val mode = config?.mode ?: CameraModes.SINGLE
    val photosPerSession = CameraModes.photosPerSession(mode)
    val isPhotoshoot = CameraModes.isPhotoshootMode(mode)

    // ─── Panel visibility state (persisted via remember) ────
    var visiblePanels by remember {
        mutableStateOf(setOf(Panels.TARGET_INFO, Panels.SHUTTER_MODE, Panels.QUEUE_LIST))
    }
    var showPanelSelector by remember { mutableStateOf(false) }
    var showCameraPicker by remember { mutableStateOf(false) }

    // ─── Bottom panel height (resizable via drag) ───────────
    // Default: 40% of screen for panels, 60% for camera
    val screenHeight = LocalContext.current.resources.displayMetrics.heightPixels
    val defaultPanelHeight = (screenHeight * 0.40f)
    var panelAreaHeight by remember { mutableStateOf(defaultPanelHeight) }
    val minPanelHeight = (screenHeight * 0.15f)
    val maxPanelHeight = (screenHeight * 0.65f)

    // ─── Camera permission + initialization (v10: DualCamera) ──
    var localPermissionState by remember {
        mutableStateOf(
            (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PermissionChecker.PERMISSION_GRANTED)
        )
    }
    val effectivePermission = hasCameraPermission || localPermissionState
    var cameraInitDone by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            val currentPermission = (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PermissionChecker.PERMISSION_GRANTED)
            if (currentPermission != localPermissionState) {
                localPermissionState = currentPermission
            }
        }
    }

    // v13: Camera is already initialized via permanent WebView at Activity level.
    // No need to re-init here — camera stream is already running.
    LaunchedEffect(effectivePermission) {
        if (effectivePermission && !cameraInitDone) {
            cameraInitDone = true
            try {
                viewModel.initCamera()
            } catch (e: SecurityException) {
                localPermissionState = false
                cameraInitDone = false
            } catch (e: Exception) {
                Log.e("OperatorScreen", "initCamera failed: ${e.message}")
                cameraInitDone = false
            }
        }
    }

    // ─── Derived data ───────────────────────────────────────
    // channelStudents and opQueue are now computed by the ViewModel reactively
    // No local computation needed — just use the ViewModel's StateFlow directly

    // opSearchResults: Search within the appropriate list based on mode
    // Photoshoot mode: search within opQueue (students sent by MC)
    // Non-photoshoot mode: search within channelStudents (all students for this channel)
    val searchableList = if (isPhotoshoot) opQueue else channelStudents
    val opSearchResults = remember(searchableList, opSearchQuery) {
        if (opSearchQuery.isBlank()) searchableList
        else {
            val q = opSearchQuery.lowercase().trim()
            searchableList.filter { it.nim.lowercase().contains(q) || it.nama.lowercase().contains(q) }
        }
    }

    val remainingCount = channelStudents.count { it.status == "pending" }
    val hasActiveTarget = currentTarget != null
    val aspectRatio = config?.parseAspectRatio() ?: (4f / 3f)

    // ═══════════════════════════════════════════════════════
    // ROOT LAYOUT: Column with Camera on top, Panels at bottom
    // Camera preview NEVER gets covered — panels sit below a
    // draggable divider
    // ═══════════════════════════════════════════════════════
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
    ) {
        // ─── TOP BAR with Camera Picker ───────────────────────
        TopBarWithCameraPicker(
            projectName = project?.name,
            connectionState = connectionState,
            latencyMs = latencyMs,
            cameraSource = cameraSource,
            myChannel = myChannel,
            config = config,
            gridlineEnabled = gridlineSettings.enabled,
            hasActiveTarget = hasActiveTarget,
            currentTarget = currentTarget,
            capturePhase = capturePhase,
            isPhotoshoot = isPhotoshoot,
            savePath = savePath,
            showCameraPicker = showCameraPicker,
            onToggleCameraPicker = { showCameraPicker = !showCameraPicker },
            onDismissCameraPicker = { showCameraPicker = false },
            availableCameras = availableCameras,
            currentCameraId = currentCameraId,
            onCameraSelected = { cameraId ->
                viewModel.switchToCameraById(cameraId)
                showCameraPicker = false
            },
            onForceRescanUsb = {
                viewModel.forceRescanUsbCamera()
                showCameraPicker = false
            },
            onTogglePanelSelector = { showPanelSelector = !showPanelSelector }
        )

        // ─── CAMERA PREVIEW AREA ────────────────────────────
        // Fills remaining space above the panel divider.
        // Camera is centered at the correct aspect ratio enforced
        // by the admin's config (e.g., 4:3, 16:9).
        // BUG FIX: The preview Box now enforces the admin's aspect ratio
        // so the camera doesn't stretch to fill arbitrary dimensions.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            // Measure the available space and constrain the preview to
            // the admin's aspect ratio. The camera, gridline, frame overlay,
            // and other overlays all go inside the aspect-ratio-constrained box.
            var cameraBoxSize by remember { mutableStateOf(IntSize.Zero) }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { cameraBoxSize = it }
            ) {
                // Calculate the correct preview modifier based on available space
                // and admin's aspect ratio. aspectRatio is w/h (e.g., 4/3 ≈ 1.333).
                val previewModifier = if (cameraBoxSize != IntSize.Zero) {
                    val availW = cameraBoxSize.width
                    val availH = cameraBoxSize.height
                    val hFromW = availW / aspectRatio  // height if we fill width
                    if (hFromW <= availH) {
                        // Width-constrained: fill width, compute height from ratio
                        Modifier.fillMaxWidth().aspectRatio(1f / aspectRatio)
                    } else {
                        // Height-constrained: fill height, compute width from ratio
                        Modifier.fillMaxHeight().aspectRatio(aspectRatio)
                    }
                } else {
                    Modifier.fillMaxSize()
                }

                // Constrained preview box — all camera-related overlays go here
                Box(
                    modifier = previewModifier,
                    contentAlignment = Alignment.Center
                ) {
                    // ═══════════════════════════════════════════════════
                    // TextureView for camera preview
                    // UVC camera OR Camera2 renders directly to this TextureView.
                    // Uses remember to prevent Compose from re-creating
                    // the TextureView during recomposition (which would break
                    // the camera stream).
                    // ═══════════════════════════════════════════════════
                    val textureViewRef = remember { android.view.TextureView(context) }
                    val activeEngine by viewModel.activeCameraEngine.collectAsState()
                    AndroidView(
                        factory = { _ ->
                            textureViewRef.apply {
                                layoutParams = android.widget.FrameLayout.LayoutParams(
                                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                                )
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        update = { tv ->
                            // Register TextureView with the active camera engine
                            if (activeEngine == "camera2") {
                                viewModel.camera2Manager.setTextureView(tv)
                            } else {
                                viewModel.cameraUVCManager.setTextureView(tv)
                            }
                        }
                    )

                    // Gridline Overlay
                    if (gridlineSettings.enabled) {
                        AndroidView(
                            factory = { ctx ->
                                GridlineOverlay(ctx).apply { updateSettings(gridlineSettings) }
                            },
                            modifier = Modifier.fillMaxSize(),
                            update = { view -> view.updateSettings(gridlineSettings) }
                        )
                    }

                    // Frame Overlay
                    frameBitmap?.let { frame ->
                        AndroidView(
                            factory = { ctx ->
                                ImageView(ctx).apply {
                                    scaleType = ImageView.ScaleType.FIT_XY
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
                                    .size(100.dp)
                                    .clip(CircleShape)
                                    .background(BG.copy(alpha = 0.85f))
                                    .border(3.dp, GOLD, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "$timerCountdown",
                                    style = TextStyle(color = GOLD, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                    }

                    // Hand Trigger Indicator (top-right corner)
                    if (handTriggerEnabled) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .background(
                                        when (handState) {
                                            HandTriggerDetector.HandState.TRIGGERED -> Color(0x8822c55e)
                                            HandTriggerDetector.HandState.CONFIRMED -> Color(0x8822c55e)
                                            HandTriggerDetector.HandState.HAND_DETECTED -> Color(0x88d4af37)
                                            else -> Color(0xAA000000)
                                        },
                                        RoundedCornerShape(12.dp)
                                    )
                                    .border(
                                        1.dp,
                                        when (handState) {
                                            HandTriggerDetector.HandState.TRIGGERED -> Color(0xFF22c55e)
                                            HandTriggerDetector.HandState.CONFIRMED -> Color(0xFF22c55e)
                                            HandTriggerDetector.HandState.HAND_DETECTED -> GOLD
                                            else -> BORDER
                                        },
                                        RoundedCornerShape(12.dp)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Icon(
                                    Icons.Default.PanTool,
                                    contentDescription = null,
                                    tint = when (handState) {
                                        HandTriggerDetector.HandState.TRIGGERED -> Color(0xFF4ade80)
                                        HandTriggerDetector.HandState.CONFIRMED -> Color(0xFF4ade80)
                                        HandTriggerDetector.HandState.HAND_DETECTED -> GOLD
                                        else -> MUTED
                                    },
                                    modifier = Modifier.size(10.dp)
                                )
                                Text(
                                    when (handState) {
                                        HandTriggerDetector.HandState.TRIGGERED -> "Timer ✓"
                                        HandTriggerDetector.HandState.CONFIRMED -> "Siap ✓"
                                        HandTriggerDetector.HandState.HAND_DETECTED -> "Tangan..."
                                        else -> "Tangan"
                                    },
                                    style = TextStyle(
                                        color = when (handState) {
                                            HandTriggerDetector.HandState.TRIGGERED -> Color(0xFF4ade80)
                                            HandTriggerDetector.HandState.CONFIRMED -> Color(0xFF4ade80)
                                            else -> MUTED
                                        },
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Camera not connected warning (full area, not constrained by aspect ratio)
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
                        Icon(Icons.Default.VideocamOff, contentDescription = null, tint = MUTED, modifier = Modifier.size(40.dp))
                        Text(
                            if (!effectivePermission) "Izin kamera diperlukan"
                            else if (cameraSource == "none") "Kamera tidak terdeteksi"
                            else "Menghubungkan kamera...",
                            style = TextStyle(color = MUTED, fontSize = 14.sp)
                        )
                        if (!effectivePermission) {
                            Button(
                                onClick = {
                                    (context as? ComponentActivity)?.let { act ->
                                        try {
                                            val intent = android.content.Intent(
                                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                android.net.Uri.fromParts("package", act.packageName, null)
                                            )
                                            act.startActivity(intent)
                                        } catch (_: Exception) {}
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = GOLD),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Buka Pengaturan", color = BG, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        } else if (cameraSource != "none") {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = GOLD, strokeWidth = 2.dp)
                        }
                    }
                }
            }

            // Standby message (no target)
            if (currentTarget == null && (connectionState == ConnectionState.AUTHENTICATED || connectionState == ConnectionState.WAITING_FOR_DATA) && cameraConnected) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = PANEL.copy(alpha = 0.85f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.PersonSearch, contentDescription = null, tint = MUTED, modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Menunggu target dari MC...", style = TextStyle(color = MUTED, fontSize = 12.sp))
                        }
                    }
                }
            }
        }

        // ─── DRAGGABLE DIVIDER ──────────────────────────────
        // Drag up/down to resize camera vs panel area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .background(PANEL)
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount ->
                        // dragAmount is in pixels; panelAreaHeight is also in pixels
                        val newHeight = panelAreaHeight - dragAmount
                        panelAreaHeight = newHeight.coerceIn(minPanelHeight, maxPanelHeight)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // Drag handle indicator
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "Resize",
                    tint = MUTED.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // ─── BOTTOM PANEL AREA ──────────────────────────────
        // Fixed height, scrollable, contains all visible panels
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(LocalDensity.current) { panelAreaHeight.toDp() })
                .background(PANEL)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                // ─── CAPTURE BUTTON (always visible at top of panel area) ──
                CaptureButtonBar(
                    capturePhase = capturePhase,
                    isSending = isSending,
                    isPhotoshoot = isPhotoshoot,
                    shutterMode = shutterMode,
                    timerCountdown = timerCountdown,
                    photosPerSession = photosPerSession,
                    capturedPhotoCount = capturedPhotos.size,
                    onCapture = { viewModel.triggerCapture() },
                    onCancelTimer = { viewModel.cancelTimerCapture() }
                )

                // ─── SCROLLABLE PANELS ──────────────────────────────
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    // Panel selector toggle
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showPanelSelector = !showPanelSelector }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                if (showPanelSelector) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = null,
                                tint = MUTED,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                "Pilih Panel",
                                style = TextStyle(color = MUTED, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            )
                            // Quick toggle chips
                            Panels.ALL.forEach { panelId ->
                                val isVisible = panelId in visiblePanels
                                val label = Panels.LABELS[panelId] ?: panelId
                                Row(
                                    modifier = Modifier
                                        .background(
                                            if (isVisible) GOLD.copy(alpha = 0.2f) else CARD,
                                            RoundedCornerShape(4.dp)
                                        )
                                        .border(
                                            BorderStroke(1.dp, if (isVisible) GOLD else BORDER),
                                            RoundedCornerShape(4.dp)
                                        )
                                        .clickable {
                                            visiblePanels = if (isVisible) {
                                                visiblePanels - panelId
                                            } else {
                                                visiblePanels + panelId
                                            }
                                        }
                                        .padding(horizontal = 4.dp, vertical = 1.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (isVisible) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (isVisible) GOLD else MUTED,
                                        modifier = Modifier.size(10.dp)
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text(label, style = TextStyle(
                                        color = if (isVisible) GOLD else MUTED,
                                        fontSize = 7.sp,
                                        fontWeight = FontWeight.Bold
                                    ))
                                }
                            }
                        }
                    }

                    // TARGET INFO panel
                    if (Panels.TARGET_INFO in visiblePanels) {
                        item {
                            CollapsiblePanel(
                                title = "Info Target",
                                onClose = { visiblePanels = visiblePanels - Panels.TARGET_INFO }
                            ) {
                                if (hasActiveTarget && currentTarget != null) {
                                    TargetInfoContent(
                                        target = currentTarget!!,
                                        capturePhase = capturePhase,
                                        isPhotoshoot = isPhotoshoot,
                                        isSending = isSending
                                    )
                                } else {
                                    Text("Menunggu panggilan MC...", style = TextStyle(color = MUTED, fontSize = 10.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic))
                                }
                            }
                        }
                    }

                    // SHUTTER MODE panel
                    if (Panels.SHUTTER_MODE in visiblePanels) {
                        item {
                            CollapsiblePanel(
                                title = "Mode Shutter",
                                onClose = { visiblePanels = visiblePanels - Panels.SHUTTER_MODE }
                            ) {
                                ShutterModeContent(
                                    currentMode = shutterMode,
                                    onModeChange = { viewModel.setShutterMode(it) },
                                    cameraMode = mode,
                                    handTriggerEnabled = handTriggerEnabled,
                                    handState = handState,
                                    onHandTriggerToggle = { viewModel.setHandTriggerEnabled(!handTriggerEnabled) }
                                )
                            }
                        }
                    }

                    // GRIDLINE panel
                    if (Panels.GRIDLINE in visiblePanels) {
                        item {
                            CollapsiblePanel(
                                title = "Gridline",
                                onClose = { visiblePanels = visiblePanels - Panels.GRIDLINE }
                            ) {
                                GridlineSettingsContent(
                                    settings = gridlineSettings,
                                    onEnabledChange = { viewModel.setGridlineEnabled(it) },
                                    onTypeChange = { viewModel.setGridlineType(it) },
                                    onThicknessChange = { viewModel.setGridlineThickness(it) },
                                    onColorChange = { viewModel.setGridlineColor(it) }
                                )
                            }
                        }
                    }

                    // OP SEARCH panel (all modes, shows searchable queue)
                    if (Panels.OP_SEARCH in visiblePanels) {
                        item {
                            CollapsiblePanel(
                                title = if (isPhotoshoot) "Antre dari MC (${opQueue.size})" else "Cari Antrean (${channelStudents.size})",
                                onClose = { visiblePanels = visiblePanels - Panels.OP_SEARCH }
                            ) {
                                OpSearchContent(
                                    searchableList = searchableList,
                                    opSearchResults = opSearchResults,
                                    opSearchQuery = opSearchQuery,
                                    currentTargetId = currentTarget?.id,
                                    onSearchQueryChange = { viewModel.setOpSearchQuery(it) },
                                    onSelectTarget = { viewModel.setOpCurrentTarget(it) },
                                    isSending = isSending,
                                    isPhotoshoot = isPhotoshoot
                                )
                            }
                        }
                    }

                    // QUEUE LIST panel
                    if (Panels.QUEUE_LIST in visiblePanels) {
                        item {
                            CollapsiblePanel(
                                title = "Antrean: $remainingCount • Ch.$myChannel",
                                onClose = { visiblePanels = visiblePanels - Panels.QUEUE_LIST }
                            ) {
                                QueueListContent(
                                    channelStudents = channelStudents,
                                    myChannel = myChannel,
                                    isPhotoshoot = isPhotoshoot
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ─── DISCONNECTION OVERLAY ──────────────────────────────
    // Only show when truly disconnected (not during CONNECTING, AUTHENTICATING, WAITING_FOR_DATA)
    if (connectionState == ConnectionState.DISCONNECTED || connectionState == ConnectionState.AUTH_FAILED) {
        androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
            Card(
                colors = CardDefaults.cardColors(containerColor = PANEL),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.WifiOff, contentDescription = null, tint = RED, modifier = Modifier.size(36.dp))
                    Text("Koneksi Terputus", style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                    Text("Mencoba menghubungkan kembali...", style = TextStyle(color = MUTED, fontSize = 12.sp))
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = GOLD, strokeWidth = 2.dp)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// TOP BAR WITH CAMERA PICKER
// The DropdownMenu is anchored inside a Box with the camera
// switch button so it appears correctly positioned.
// Camera list is reactive (from StateFlow) so USB attach/detach
// updates the picker in real-time.
// ═══════════════════════════════════════════════════════════

@Composable
private fun TopBarWithCameraPicker(
    projectName: String?,
    connectionState: ConnectionState,
    latencyMs: Long,
    cameraSource: String,
    myChannel: Int,
    config: ProjectConfig?,
    gridlineEnabled: Boolean,
    hasActiveTarget: Boolean,
    currentTarget: Student?,
    capturePhase: CapturePhase,
    isPhotoshoot: Boolean,
    savePath: String,
    showCameraPicker: Boolean,
    onToggleCameraPicker: () -> Unit,
    onDismissCameraPicker: () -> Unit,
    availableCameras: List<Pair<String, String>>,
    currentCameraId: String,
    onCameraSelected: (String) -> Unit,
    onForceRescanUsb: () -> Unit,
    onTogglePanelSelector: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PANEL)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left: Connection
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val isConnected = connectionState == ConnectionState.AUTHENTICATED ||
                    connectionState == ConnectionState.WAITING_FOR_DATA
            Icon(Icons.Default.Wifi, contentDescription = null,
                tint = if (isConnected) GREEN else RED, modifier = Modifier.size(12.dp))
            Text(if (isConnected) "Terhubung" else when (connectionState) {
                ConnectionState.CONNECTING -> "Menghubungkan..."
                ConnectionState.CONNECTED -> "Terhubung..."
                ConnectionState.AUTHENTICATING -> "Autentikasi..."
                ConnectionState.AUTH_FAILED -> "Auth Gagal"
                else -> "Terputus"
            },
                style = TextStyle(color = Color.White, fontSize = 10.sp))
            if (latencyMs >= 0) Text("${latencyMs}ms",
                style = TextStyle(color = when { latencyMs < 5 -> GREEN; latencyMs < 15 -> Color.Yellow; else -> RED }, fontSize = 9.sp))
        }

        // Center: Project + Target compact
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(projectName ?: "Saatiril", style = TextStyle(color = GOLD, fontSize = 11.sp, fontWeight = FontWeight.Bold))
            if (hasActiveTarget && currentTarget != null) {
                val statusLabel = when (capturePhase) {
                    CapturePhase.READY_1 -> if (isPhotoshoot) "Siap Foto" else "Toga"
                    CapturePhase.READY_2 -> "Ijazah"
                    CapturePhase.SENDING -> "Kirim..."
                    else -> ""
                }
                val statusColor = when (capturePhase) {
                    CapturePhase.READY_1 -> GOLD
                    CapturePhase.READY_2 -> GREEN
                    CapturePhase.SENDING -> MUTED
                    else -> MUTED
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(currentTarget!!.nama, style = TextStyle(color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (statusLabel.isNotEmpty()) {
                        Text(statusLabel, style = TextStyle(color = statusColor, fontSize = 8.sp, fontWeight = FontWeight.Bold))
                    }
                }
            } else {
                val cameraLabel = when (cameraSource) { "uvc" -> "USB"; "builtin" -> "HP"; else -> "-" }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Kamera $myChannel • $cameraLabel • ${config?.ratio ?: "4:3"}", style = TextStyle(color = MUTED, fontSize = 8.sp))
                    if (savePath.isNotBlank()) {
                        Text("💾 $savePath", style = TextStyle(color = MUTED.copy(alpha = 0.7f), fontSize = 6.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        // Right: Action buttons — camera picker is anchored in a Box
        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            // Camera switch button with dropdown menu anchored to it
            Box {
                IconButton(onClick = onToggleCameraPicker, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Default.Cameraswitch,
                        contentDescription = "Pilih Kamera",
                        tint = if (cameraSource == "uvc") GREEN else MUTED,
                        modifier = Modifier.size(14.dp)
                    )
                }
                DropdownMenu(
                    expanded = showCameraPicker,
                    onDismissRequest = onDismissCameraPicker,
                    modifier = Modifier.background(PANEL)
                ) {
                    // Header
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = GOLD, modifier = Modifier.size(14.dp))
                                Text("Pilih Kamera", style = TextStyle(color = GOLD, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                            }
                        },
                        onClick = {},
                        enabled = false
                    )
                    HorizontalDivider(color = BORDER, thickness = 0.5.dp)

                    if (availableCameras.isNotEmpty()) {
                        availableCameras.forEach { (cameraId, displayName) ->
                            val isCurrentlySelected = cameraId == currentCameraId
                            val isUsb = displayName.contains("USB", ignoreCase = true)
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Icon(
                                            when {
                                                isUsb -> Icons.Default.Usb
                                                displayName.contains("Depan", ignoreCase = true) -> Icons.Default.CameraFront
                                                else -> Icons.Default.CameraAlt
                                            },
                                            contentDescription = null,
                                            tint = if (isCurrentlySelected) GREEN else MUTED,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            displayName,
                                            style = TextStyle(
                                                color = if (isCurrentlySelected) GREEN else Color.White,
                                                fontSize = 12.sp,
                                                fontWeight = if (isCurrentlySelected) FontWeight.Bold else FontWeight.Normal
                                            )
                                        )
                                        if (isCurrentlySelected) {
                                            Text("✓", style = TextStyle(color = GREEN, fontSize = 10.sp, fontWeight = FontWeight.Bold))
                                        }
                                    }
                                },
                                onClick = { onCameraSelected(cameraId) }
                            )
                        }
                    } else {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.VideocamOff, contentDescription = null, tint = MUTED, modifier = Modifier.size(14.dp))
                                    Text("Memuat kamera...", style = TextStyle(color = MUTED, fontSize = 12.sp))
                                }
                            },
                            onClick = onDismissCameraPicker,
                            enabled = false
                        )
                    }

                    HorizontalDivider(color = BORDER, thickness = 0.5.dp)
                    // Force rescan USB button
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = CYAN, modifier = Modifier.size(14.dp))
                                Text("Pindai Ulang USB", style = TextStyle(color = CYAN, fontSize = 11.sp))
                            }
                        },
                        onClick = onForceRescanUsb
                    )
                    HorizontalDivider(color = BORDER, thickness = 0.5.dp)
                    DropdownMenuItem(
                        text = {
                            val srcLabel = when (cameraSource) { "uvc" -> "USB Capture"; "builtin" -> "Kamera HP"; else -> "Tidak ada" }
                            Text("Aktif: $srcLabel", style = TextStyle(color = MUTED.copy(alpha = 0.6f), fontSize = 9.sp))
                        },
                        onClick = {},
                        enabled = false
                    )
                }
            }
            IconButton(onClick = onTogglePanelSelector, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Apps, contentDescription = null, tint = GOLD, modifier = Modifier.size(14.dp))
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// COLLAPSIBLE PANEL WRAPPER
// ═══════════════════════════════════════════════════════════

@Composable
private fun CollapsiblePanel(
    title: String,
    onClose: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CARD),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, BORDER)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header row with title and close button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PANEL.copy(alpha = 0.5f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(title, style = TextStyle(color = GOLD, fontSize = 9.sp, fontWeight = FontWeight.Bold))
                IconButton(onClick = onClose, modifier = Modifier.size(16.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Tutup", tint = MUTED, modifier = Modifier.size(10.dp))
                }
            }
            // Content
            Box(modifier = Modifier.padding(4.dp)) {
                content()
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// CAPTURE BUTTON BAR (always visible)
// ═══════════════════════════════════════════════════════════

@Composable
private fun CaptureButtonBar(
    capturePhase: CapturePhase,
    isSending: Boolean,
    isPhotoshoot: Boolean,
    shutterMode: String,
    timerCountdown: Int,
    photosPerSession: Int,
    capturedPhotoCount: Int,
    onCapture: () -> Unit,
    onCancelTimer: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PANEL.copy(alpha = 0.8f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Photo progress dots
        if (!isPhotoshoot && capturePhase != CapturePhase.STANDBY) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(photosPerSession) { i ->
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(if (i < capturedPhotoCount) GREEN else BORDER))
                }
            }
        }

        // Capture button (takes remaining space)
        CaptureButton(
            capturePhase = capturePhase,
            isSending = isSending,
            isPhotoshoot = isPhotoshoot,
            shutterMode = shutterMode,
            timerCountdown = timerCountdown,
            onCapture = onCapture,
            onCancelTimer = onCancelTimer,
            modifier = Modifier.weight(1f).height(40.dp)
        )
    }
}

@Composable
private fun CaptureButton(
    capturePhase: CapturePhase,
    isSending: Boolean,
    isPhotoshoot: Boolean,
    shutterMode: String,
    timerCountdown: Int,
    onCapture: () -> Unit,
    onCancelTimer: () -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        capturePhase == CapturePhase.STANDBY -> {
            OutlinedButton(
                onClick = {}, enabled = false,
                modifier = modifier, shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(disabledContainerColor = PANEL.copy(alpha = 0.5f), disabledContentColor = MUTED),
                border = BorderStroke(1.dp, BORDER)
            ) { Text("STANDBY", fontWeight = FontWeight.Bold, fontSize = 13.sp) }
        }
        timerCountdown > 0 -> {
            Button(onClick = onCancelTimer, modifier = modifier, shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = RED)) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                Spacer(modifier = Modifier.width(4.dp))
                Text("BATAL ($timerCountdown)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
        capturePhase == CapturePhase.READY_1 -> {
            val isTimer = shutterMode.startsWith("timer")
            val isAi = shutterMode == "ai"
            when {
                isAi -> {
                    OutlinedButton(onClick = {}, enabled = false, modifier = modifier, shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(disabledContainerColor = DARK_GREEN.copy(alpha = 0.12f), disabledContentColor = GREEN),
                        border = BorderStroke(1.dp, DARK_GREEN)) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), color = GREEN, strokeWidth = 1.5.dp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("AI Deteksi...", color = GREEN, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
                isTimer -> {
                    val t = shutterMode.removePrefix("timer-") + "s"
                    Button(onClick = onCapture, modifier = modifier, shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GOLD)) {
                        Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(14.dp), tint = BG)
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(if (isPhotoshoot) "FOTO ($t)" else "FOTO 1 — TOGA ($t)", color = BG, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
                else -> {
                    Button(onClick = onCapture, modifier = modifier, shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (isPhotoshoot) GREEN else GOLD)) {
                        Icon(Icons.Default.Camera, contentDescription = null, modifier = Modifier.size(14.dp), tint = BG)
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(if (isPhotoshoot) "FOTO" else "FOTO 1 — TOGA", color = BG, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
        capturePhase == CapturePhase.READY_2 -> {
            val isTimer = shutterMode.startsWith("timer")
            val isAi = shutterMode == "ai"
            when {
                isAi -> {
                    OutlinedButton(onClick = {}, enabled = false, modifier = modifier, shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(disabledContainerColor = DARK_GREEN.copy(alpha = 0.12f), disabledContentColor = GREEN),
                        border = BorderStroke(1.dp, DARK_GREEN)) {
                        Text("AI Ijazah...", color = GREEN, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
                isTimer -> {
                    val t = shutterMode.removePrefix("timer-") + "s"
                    Button(onClick = onCapture, modifier = modifier, shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GOLD)) {
                        Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(14.dp), tint = BG)
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("FOTO 2 — IJAZAH ($t)", color = BG, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
                else -> {
                    Button(onClick = onCapture, modifier = modifier, shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DARK_GREEN)) {
                        Icon(Icons.Default.Camera, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.White)
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("FOTO 2 — IJAZAH", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
        capturePhase == CapturePhase.SENDING -> {
            OutlinedButton(onClick = {}, enabled = false, modifier = modifier, shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.outlinedButtonColors(disabledContainerColor = PANEL.copy(alpha = 0.5f), disabledContentColor = MUTED),
                border = BorderStroke(1.dp, BORDER)) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), color = MUTED, strokeWidth = 1.5.dp)
                Spacer(modifier = Modifier.width(4.dp))
                Text("MENGIRIM...", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// PANEL CONTENT COMPONENTS
// ═══════════════════════════════════════════════════════════

@Composable
private fun TargetInfoContent(
    target: Student,
    capturePhase: CapturePhase,
    isPhotoshoot: Boolean,
    isSending: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(modifier = Modifier.size(28.dp).clip(CircleShape).background(PANEL).border(1.dp, GOLD, CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.PersonSearch, contentDescription = null, tint = GOLD, modifier = Modifier.size(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(target.nama, style = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(target.nim, style = TextStyle(color = MUTED, fontSize = 9.sp, fontFamily = FontFamily.Monospace))
        }
        val (label, color) = when (capturePhase) {
            CapturePhase.READY_1 -> (if (isPhotoshoot) "Siap Foto" else "Toga") to GOLD
            CapturePhase.READY_2 -> "Ijazah" to GREEN
            CapturePhase.SENDING -> "Kirim" to MUTED
            else -> "Standby" to MUTED
        }
        Row(modifier = Modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (isSending) CircularProgressIndicator(modifier = Modifier.size(8.dp), color = MUTED, strokeWidth = 1.dp)
            Text(label, style = TextStyle(color = color, fontSize = 8.sp, fontWeight = FontWeight.Bold))
        }
    }
}

@Composable
private fun ShutterModeContent(
    currentMode: String,
    onModeChange: (String) -> Unit,
    cameraMode: CameraMode,
    handTriggerEnabled: Boolean,
    handState: HandTriggerDetector.HandState,
    onHandTriggerToggle: () -> Unit
) {
    // Layout: Row 1 = shutter mode buttons, Row 2 = Trigger Tangan toggle
    // This prevents Trigger Tangan from overlapping/covering other buttons
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Row 1: Shutter mode buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SHUTTER_MODES.forEach { (id, label) ->
                if (id == "ai" && cameraMode != CameraModes.SINGLE && cameraMode != CameraModes.DUAL) return@forEach
                val isActive = currentMode == id
                Row(
                    modifier = Modifier
                        .background(if (isActive) GOLD.copy(alpha = 0.2f) else PANEL, RoundedCornerShape(4.dp))
                        .border(BorderStroke(1.dp, if (isActive) GOLD else BORDER), RoundedCornerShape(4.dp))
                        .clickable { onModeChange(id) }
                        .padding(horizontal = 5.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(when (id) { "ai" -> Icons.Default.AutoAwesome; "manual" -> Icons.Default.Camera; else -> Icons.Default.Timer },
                        contentDescription = null, tint = if (isActive) GOLD else MUTED, modifier = Modifier.size(10.dp))
                    Text(label, style = TextStyle(color = if (isActive) GOLD else MUTED, fontSize = 8.sp, fontWeight = FontWeight.Bold))
                }
            }
        }

        // Row 2: Trigger Tangan toggle (photobooth: show hand → remove → timer starts)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (handTriggerEnabled) Color(0x224ade80) else PANEL,
                    RoundedCornerShape(4.dp)
                )
                .border(
                    BorderStroke(1.dp, if (handTriggerEnabled) Color(0xFF4ade80) else BORDER),
                    RoundedCornerShape(4.dp)
                )
                .clickable { onHandTriggerToggle() }
                .padding(horizontal = 5.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                Icons.Default.PanTool,
                contentDescription = null,
                tint = if (handTriggerEnabled) Color(0xFF4ade80) else MUTED,
                modifier = Modifier.size(10.dp)
            )
            Text(
                "Trigger Tangan",
                style = TextStyle(
                    color = if (handTriggerEnabled) Color(0xFF4ade80) else MUTED,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            if (handTriggerEnabled) {
                Text(
                    "•",
                    style = TextStyle(color = MUTED, fontSize = 8.sp)
                )
                Text(
                    when (handState) {
                        HandTriggerDetector.HandState.TRIGGERED -> "Timer dimulai ✓"
                        HandTriggerDetector.HandState.CONFIRMED -> "Lepas tangan = mulai"
                        HandTriggerDetector.HandState.HAND_DETECTED -> "Tangan terdeteksi..."
                        else -> "Tunjukkan tangan"
                    },
                    style = TextStyle(
                        color = when (handState) {
                            HandTriggerDetector.HandState.TRIGGERED -> Color(0xFF4ade80)
                            HandTriggerDetector.HandState.CONFIRMED -> Color(0xFF4ade80)
                            HandTriggerDetector.HandState.HAND_DETECTED -> GOLD
                            else -> MUTED
                        },
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Normal
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GridlineSettingsContent(
    settings: GridlineSettings,
    onEnabledChange: (Boolean) -> Unit,
    onTypeChange: (GridlineType) -> Unit,
    onThicknessChange: (GridlineThickness) -> Unit,
    onColorChange: (GridlineColor) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        // Toggle
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Aktifkan", style = TextStyle(color = Color.White, fontSize = 10.sp))
            Switch(checked = settings.enabled, onCheckedChange = onEnabledChange, colors = SwitchDefaults.colors(checkedTrackColor = GOLD), modifier = Modifier.height(20.dp))
        }
        if (settings.enabled) {
            // Type
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                GridlineType.entries.forEach { type ->
                    val isActive = settings.type == type
                    Row(modifier = Modifier
                        .background(if (isActive) GOLD.copy(alpha = 0.2f) else PANEL, RoundedCornerShape(3.dp))
                        .border(BorderStroke(1.dp, if (isActive) GOLD else BORDER), RoundedCornerShape(3.dp))
                        .clickable { onTypeChange(type) }
                        .padding(horizontal = 3.dp, vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(type.displayName, style = TextStyle(color = if (isActive) GOLD else MUTED, fontSize = 7.sp, fontWeight = FontWeight.Bold))
                    }
                }
            }
            // Thickness
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Ketebalan:", style = TextStyle(color = MUTED, fontSize = 8.sp))
                GridlineThickness.entries.forEach { t ->
                    val isActive = settings.thickness == t
                    Box(modifier = Modifier.size(24.dp, 16.dp)
                        .background(if (isActive) GOLD.copy(alpha = 0.2f) else PANEL, RoundedCornerShape(3.dp))
                        .border(BorderStroke(1.dp, if (isActive) GOLD else BORDER), RoundedCornerShape(3.dp))
                        .clickable { onThicknessChange(t) }, contentAlignment = Alignment.Center) {
                        Box(modifier = Modifier.size((t.value * 2 + 1).dp).clip(CircleShape).background(if (isActive) GOLD else MUTED))
                    }
                }
            }
            // Color
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Warna:", style = TextStyle(color = MUTED, fontSize = 8.sp))
                GridlineColor.entries.forEach { color ->
                    Box(modifier = Modifier.size(18.dp).clip(CircleShape)
                        .background(Color(android.graphics.Color.parseColor(color.hex)))
                        .border(width = if (settings.color == color) 2.dp else 1.dp, color = if (settings.color == color) GOLD else BORDER, shape = CircleShape)
                        .clickable { onColorChange(color) })
                }
            }
        }
    }
}

@Composable
private fun OpSearchContent(
    searchableList: List<Student>,
    opSearchResults: List<Student>,
    opSearchQuery: String,
    currentTargetId: String?,
    onSearchQueryChange: (String) -> Unit,
    onSelectTarget: (Student) -> Unit,
    isSending: Boolean,
    isPhotoshoot: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        OutlinedTextField(
            value = opSearchQuery, onValueChange = onSearchQueryChange,
            placeholder = { Text("Cari NIM / Nama...", style = TextStyle(color = BORDER, fontSize = 10.sp)) },
            textStyle = TextStyle(color = Color.White, fontSize = 10.sp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp),
            shape = RoundedCornerShape(4.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GOLD, unfocusedBorderColor = BORDER, cursorColor = GOLD,
                focusedContainerColor = PANEL, unfocusedContainerColor = PANEL),
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MUTED, modifier = Modifier.size(12.dp)) },
            singleLine = true
        )
        if (searchableList.isEmpty()) {
            Text(if (opSearchQuery.isNotBlank()) "Tidak ditemukan" else "Belum ada peserta dikirim MC",
                style = TextStyle(color = MUTED, fontSize = 8.sp), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 80.dp)) {
                items(count = opSearchResults.size, key = { opSearchResults[it].id }) { idx ->
                    val student = opSearchResults[idx]
                    val isCurrent = student.id == currentTargetId
                    Row(modifier = Modifier.fillMaxWidth()
                        .background(if (isCurrent) GOLD.copy(alpha = 0.1f) else Color.Transparent)
                        .clickable(enabled = !isSending) { onSelectTarget(student) }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(student.nim, style = TextStyle(color = MUTED, fontSize = 8.sp, fontFamily = FontFamily.Monospace), modifier = Modifier.width(45.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(student.nama, style = TextStyle(color = if (isCurrent) GOLD else Color.White, fontSize = 9.sp, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // Show status badge in non-photoshoot mode
                        if (!isPhotoshoot) {
                            val (statusLabel, statusColor) = when (student.status) {
                                "sent" -> "Kirim" to GOLD.copy(alpha = 0.7f)
                                "active_1", "active_2" -> "Foto" to GOLD
                                "done" -> "OK" to GREEN
                                else -> "" to MUTED
                            }
                            if (statusLabel.isNotEmpty()) {
                                Text(statusLabel, style = TextStyle(color = statusColor, fontSize = 6.sp, fontWeight = FontWeight.Bold),
                                    modifier = Modifier.width(25.dp), textAlign = TextAlign.End)
                            }
                        }
                        if (isCurrent) Icon(Icons.Default.Camera, contentDescription = null, tint = GOLD, modifier = Modifier.size(10.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueListContent(
    channelStudents: List<Student>,
    myChannel: Int,
    isPhotoshoot: Boolean
) {
    if (channelStudents.isEmpty()) {
        Text("Tidak ada mahasiswa", style = TextStyle(color = MUTED, fontSize = 9.sp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), textAlign = TextAlign.Center)
    } else {
        // Sort: active first, then sent, then pending, then done last
        val sortedStudents = remember(channelStudents) {
            val statusOrder = mapOf(
                "active_1" to 0, "active_2" to 0,
                "sent" to 1,
                "pending" to 2,
                "done" to 3
            )
            channelStudents.sortedBy { statusOrder[it.status] ?: 4 }
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 120.dp)) {
            itemsIndexed(sortedStudents) { idx, student ->
                val isActive = student.status.startsWith("active_")
                val isDone = student.status == "done"
                val isSent = student.status == "sent"
                val isPending = student.status == "pending"
                val rowBg = when {
                    isActive -> GOLD.copy(alpha = 0.12f)
                    isSent -> GOLD.copy(alpha = 0.04f)
                    isDone -> MUTED.copy(alpha = 0.02f)
                    else -> Color.Transparent
                }
                Row(modifier = Modifier.fillMaxWidth().background(rowBg).padding(horizontal = 3.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${idx + 1}", style = TextStyle(color = MUTED, fontSize = 7.sp, fontFamily = FontFamily.Monospace), modifier = Modifier.width(18.dp))
                    Text(student.nim, style = TextStyle(color = MUTED, fontSize = 7.sp, fontFamily = FontFamily.Monospace), modifier = Modifier.width(45.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(student.nama, style = TextStyle(color = if (isActive) GOLD else if (isDone) MUTED else Color.White, fontSize = 8.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal, textDecoration = if (isDone) TextDecoration.LineThrough else TextDecoration.None),
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val (statusLabel, statusColor) = when {
                        isActive -> "Foto" to GOLD
                        isSent -> "Kirim" to GOLD.copy(alpha = 0.6f)
                        isDone -> "Selesai" to MUTED
                        isPending -> "Tunggu" to BORDER
                        else -> "?" to MUTED
                    }
                    Text(statusLabel, style = TextStyle(color = statusColor, fontSize = 6.sp, fontWeight = FontWeight.Bold), modifier = Modifier.width(35.dp))
                }
            }
        }
    }
}