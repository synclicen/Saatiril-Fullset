package com.saatiril.full.ui.admin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
fun AdminPanel(viewModel: FullViewModel) {
    val project by viewModel.project.collectAsState()
    val proj = project ?: Project()
    val connectionState by viewModel.connectionState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
    ) {
        // ── Project Header ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(PANEL)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "ADMIN PANEL",
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = GOLD)
                )
                Icon(
                    Icons.Default.Dashboard,
                    contentDescription = null,
                    tint = GOLD,
                    modifier = Modifier.size(20.dp)
                )
            }

            if (proj.name.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = proj.name,
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    InfoChip("Mode", proj.config.mode.replaceFirstChar { it.uppercase() })
                    InfoChip("Ratio", proj.config.ratio)
                    InfoChip("Preset", proj.config.preset.replaceFirstChar { it.uppercase() })
                }
            } else {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Belum ada proyek",
                    style = TextStyle(fontSize = 12.sp, color = MUTED)
                )
            }
        }

        // ── Stats Row ──
        val totalStudents = proj.database.size
        val doneStudents = proj.database.count { it.status == "done" }
        val activeStudents = proj.database.count { isActiveStatus(it.status) }
        val pendingStudents = totalStudents - doneStudents - activeStudents

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CARD)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatItem("Total", totalStudents.toString(), MUTED)
            StatItem("Selesai", doneStudents.toString(), GREEN)
            StatItem("Aktif", activeStudents.toString(), GOLD)
            StatItem("Menunggu", pendingStudents.toString(), CYAN)
        }

        // ── Photo Gallery ──
        if (proj.photoHistory.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Riwayat Foto (${proj.photoHistory.size})",
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MUTED)
                )
                Spacer(modifier = Modifier.height(4.dp))

                // Show last few photos as a list
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(proj.photoHistory.takeLast(5)) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(CARD)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${item.student.nim} - ${item.student.nama}",
                                style = TextStyle(fontSize = 11.sp, color = Color.White),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "Ch.${item.channel} · ${item.photos.size} foto",
                                style = TextStyle(fontSize = 10.sp, color = MUTED)
                            )
                        }
                    }
                }
            }
        }

        // ── Student List ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(
                text = "Daftar Siswa (${proj.database.size})",
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MUTED)
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(proj.database) { student ->
                StudentRow(
                    student = student,
                    onReset = {
                        viewModel.emitLanEvent(SocketEvents.STUDENT_RESET, mapOf(
                            "studentId" to student.id,
                            "channel" to student.assignedChannel
                        ))
                    }
                )
            }
        }
    }
}

@Composable
private fun InfoChip(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "$label:",
            style = TextStyle(fontSize = 11.sp, color = MUTED)
        )
        Text(
            text = value,
            style = TextStyle(fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Medium)
        )
    }
}

@Composable
private fun StatItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color)
        )
        Text(
            text = label,
            style = TextStyle(fontSize = 10.sp, color = MUTED)
        )
    }
}

@Composable
private fun StudentRow(
    student: Student,
    onReset: () -> Unit
) {
    val statusColor = when {
        student.status == "done" -> GREEN
        student.status == "pending" -> MUTED
        student.status == "sent" -> CYAN
        isActiveStatus(student.status) -> GOLD
        else -> MUTED
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(CARD)
            .border(BorderStroke(1.dp, BORDER.copy(alpha = 0.3f)), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status indicator
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(statusColor, androidx.compose.foundation.shape.CircleShape)
        )
        Spacer(modifier = Modifier.width(8.dp))

        // Student info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = student.nama.ifBlank { student.nim },
                style = TextStyle(fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${student.nim} · ${statusLabel(student.status)}",
                style = TextStyle(fontSize = 10.sp, color = MUTED),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Reset button
        if (student.status != "pending") {
            IconButton(
                onClick = onReset,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Reset",
                    tint = MUTED,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
