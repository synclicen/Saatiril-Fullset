package com.saatiril.full.ui.operator

import android.Manifest
import android.util.Log
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.saatiril.full.data.*
import com.saatiril.full.data.isActiveStatus

// ─── Theme Colors ───────────────────────────────────────────
private val BG = Color(0xFF1a0b2e)
private val PANEL = Color(0xFF2a164a)
private val CARD = Color(0xFF3b2263)
private val BORDER = Color(0xFF533485)
private val GOLD = Color(0xFFd4af37)
private val MUTED = Color(0xFFc4b5fd)
private val CYAN = Color(0xFF06b6d4)
private val RED = Color(0xFFef4444)
private val GREEN = Color(0xFF4ade80)

@Composable
fun OperatorPanel(
    viewModel: FullViewModel,
    hasCameraPermission: Boolean
) {
    val currentTarget by viewModel.currentTarget.collectAsState()
    val capturePhase by viewModel.capturePhase.collectAsState()
    val project by viewModel.project.collectAsState()
    val proj = project ?: Project()
    val channel by viewModel.channel.collectAsState()
    val shutterMode by viewModel.shutterMode.collectAsState()
    val camera2Manager = viewModel.camera2Manager
    val cameraUVCManager = viewModel.cameraUVCManager
    val isUvcAvailable by cameraUVCManager.isAvailable.collectAsState()
    val isCamera2Available by camera2Manager.isAvailable.collectAsState()
    val camera2CameraList by camera2Manager.cameraList.collectAsState()
    val selectedCameraId by camera2Manager.selectedCameraId.collectAsState()

    val context = LocalContext.current

    // Filter students for this operator's channel
    val channelStudents = proj.database.filter { it.assignedChannel == channel }
    val pendingStudents = channelStudents.filter { it.status == "pending" || it.status == "sent" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
    ) {
        // ── Header ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PANEL)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "OPERATOR · Ch.$channel",
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = CYAN)
            )
            Text(
                text = "Ch.$channel",
                style = TextStyle(fontSize = 11.sp, color = GOLD, fontWeight = FontWeight.Medium)
            )
        }

        // ── Camera Preview Area ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (hasCameraPermission) {
                // Camera preview via TextureView
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        android.view.TextureView(ctx).also { textureView ->
                            // Try UVC first, then Camera2
                            if (isUvcAvailable) {
                                cameraUVCManager.setTextureView(textureView)
                                cameraUVCManager.showPreview()
                            } else {
                                camera2Manager.setTextureView(textureView)
                                if (!isCamera2Available) {
                                    camera2Manager.openCamera(ctx, selectedCameraId ?: "0")
                                }
                            }
                        }
                    },
                    update = { textureView ->
                        // Update on state changes
                    }
                )

                // Gridline overlay would go here (simplified for now)
            } else {
                // No camera permission
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.VideocamOff,
                        contentDescription = null,
                        tint = MUTED.copy(alpha = 0.3f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Izin kamera diperlukan",
                        style = TextStyle(fontSize = 13.sp, color = MUTED)
                    )
                }
            }

            // ── Capture Phase Overlay ──
            if (currentTarget != null) {
                val (phaseText, phaseColor) = when (capturePhase) {
                    CapturePhase.STANDBY -> "STANDBY" to MUTED
                    CapturePhase.READY_1 -> "SIAP FOTO" to GOLD
                    CapturePhase.READY_2 -> "POSE 2" to GOLD
                    CapturePhase.SENDING -> "MENGIRIM..." to CYAN
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(BG.copy(alpha = 0.7f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = phaseText,
                        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = phaseColor)
                    )
                }
            }

            // ── Target Info Overlay ──
            if (currentTarget != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(BG.copy(alpha = 0.8f))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = currentTarget!!.nama,
                                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = currentTarget!!.nim,
                                style = TextStyle(fontSize = 11.sp, color = MUTED, fontFamily = FontFamily.Monospace)
                            )
                        }
                        Text(
                            text = "Ch.${currentTarget!!.assignedChannel}",
                            style = TextStyle(fontSize = 11.sp, color = CYAN)
                        )
                    }
                }
            }
        }

        // ── Camera Source Picker ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PANEL)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Kamera:",
                style = TextStyle(fontSize = 11.sp, color = MUTED)
            )

            if (isUvcAvailable) {
                CameraSourceChip("USB/UVC", true, CYAN) {
                    camera2Manager.closeCamera()
                }
            }

            camera2CameraList.forEach { camInfo ->
                val isSelected = !isUvcAvailable && selectedCameraId == camInfo.id
                CameraSourceChip(camInfo.label, isSelected, GOLD) {
                    camera2Manager.closeCamera()
                    camera2Manager.openCamera(context, camInfo.id)
                }
            }
        }

        // ── Controls Row ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PANEL)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Shutter Mode Selector
            var showShutterMenu by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(
                    onClick = { showShutterMenu = true },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MUTED),
                    border = BorderStroke(1.dp, BORDER),
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(
                        when (shutterMode) {
                            "manual" -> Icons.Default.TouchApp
                            "timer" -> Icons.Default.Timer
                            "ai" -> Icons.Default.AutoAwesome
                            else -> Icons.Default.TouchApp
                        },
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(shutterMode.replaceFirstChar { it.uppercase() }, fontSize = 11.sp)
                }
                DropdownMenu(
                    expanded = showShutterMenu,
                    onDismissRequest = { showShutterMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Manual") },
                        onClick = { viewModel.setShutterMode("manual"); showShutterMenu = false },
                        leadingIcon = { Icon(Icons.Default.TouchApp, null, modifier = Modifier.size(16.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("Timer (3s)") },
                        onClick = { viewModel.setShutterMode("timer"); showShutterMenu = false },
                        leadingIcon = { Icon(Icons.Default.Timer, null, modifier = Modifier.size(16.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("AI (Tangan)") },
                        onClick = { viewModel.setShutterMode("ai"); showShutterMenu = false },
                        leadingIcon = { Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(16.dp)) }
                    )
                }
            }

            // Capture Button
            val canCapture = currentTarget != null && capturePhase != CapturePhase.SENDING
            Button(
                onClick = { viewModel.capturePhoto() },
                modifier = Modifier.size(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (canCapture) RED else CARD,
                    contentColor = Color.White,
                    disabledContainerColor = CARD,
                    disabledContentColor = MUTED
                ),
                shape = CircleShape,
                enabled = canCapture
            ) {
                Icon(
                    Icons.Default.Camera,
                    contentDescription = "Tangkap",
                    modifier = Modifier.size(24.dp)
                )
            }

            // Queue count
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${pendingStudents.size}",
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = CYAN)
                )
                Text(
                    text = "antrian",
                    style = TextStyle(fontSize = 9.sp, color = MUTED)
                )
            }
        }

        // ── Mini Queue ──
        if (pendingStudents.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 80.dp)
                    .background(BG.copy(alpha = 0.8f))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(pendingStudents.take(5)) { student ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .background(MUTED, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${student.nim} - ${student.nama}",
                            style = TextStyle(fontSize = 10.sp, color = MUTED),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraSourceChip(
    label: String,
    isSelected: Boolean,
    accentColor: Color,
    onSelect: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (isSelected) accentColor.copy(alpha = 0.2f) else CARD)
            .border(
                BorderStroke(1.dp, if (isSelected) accentColor else BORDER.copy(alpha = 0.3f)),
                RoundedCornerShape(6.dp)
            )
            .clickable { onSelect() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontSize = 10.sp,
                color = if (isSelected) accentColor else MUTED,
                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
            )
        )
    }
}
