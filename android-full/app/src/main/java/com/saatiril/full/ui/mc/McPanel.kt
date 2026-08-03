package com.saatiril.full.ui.mc

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
fun McPanel(viewModel: FullViewModel) {
    val project by viewModel.project.collectAsState()
    val proj = project ?: Project()
    val channel by viewModel.channel.collectAsState()
    val currentTarget by viewModel.currentTarget.collectAsState()
    val connectionHealth by viewModel.connectionHealth.collectAsState()

    // Filter students for this MC's channel
    val channelStudents = proj.database.filter { it.assignedChannel == channel }
    val pendingStudents = channelStudents.filter { it.status == "pending" }
    val activeStudents = channelStudents.filter { isActiveStatus(it.status) }
    val doneStudents = channelStudents.filter { it.status == "done" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
    ) {
        // ── MC Header ──
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
                    text = "MC PANEL",
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = CYAN)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Ch.$channel",
                        style = TextStyle(fontSize = 12.sp, color = GOLD, fontWeight = FontWeight.Medium)
                    )
                }
            }

            // Network quality
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val qualityColor = when {
                    !connectionHealth.connected -> RED
                    connectionHealth.latencyMs < 0 -> MUTED
                    connectionHealth.latencyMs < 15 -> GREEN
                    connectionHealth.latencyMs < 30 -> GOLD
                    else -> RED
                }
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(qualityColor, CircleShape)
                )
                Text(
                    text = if (connectionHealth.connected) "Latensi: ${connectionHealth.latencyMs}ms" else "Terputus",
                    style = TextStyle(fontSize = 10.sp, color = qualityColor)
                )
            }
        }

        // ── Current Target Display ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(CARD)
                .padding(16.dp)
        ) {
            Text(
                text = "Target Aktif",
                style = TextStyle(fontSize = 11.sp, color = MUTED, fontWeight = FontWeight.Medium)
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (currentTarget != null) {
                // Active target
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        tint = GOLD,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentTarget!!.nama,
                            style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        )
                        Text(
                            text = currentTarget!!.nim,
                            style = TextStyle(fontSize = 13.sp, color = MUTED, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Ch.${currentTarget!!.assignedChannel}",
                            style = TextStyle(fontSize = 12.sp, color = CYAN, fontWeight = FontWeight.Medium)
                        )
                        Text(
                            text = statusLabel(currentTarget!!.status),
                            style = TextStyle(fontSize = 10.sp, color = GOLD)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Action buttons for current target
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Selesai (Done) button
                    Button(
                        onClick = {
                            viewModel.emitLanEvent(SocketEvents.STUDENT_DONE, mapOf(
                                "studentId" to currentTarget!!.id,
                                "channel" to channel
                            ))
                        },
                        modifier = Modifier.weight(1f).height(40.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GREEN, contentColor = BG),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Selesai", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    // Lewati (Skip) button
                    OutlinedButton(
                        onClick = {
                            // Skip current: mark as done and call next
                            if (currentTarget != null) {
                                viewModel.emitLanEvent(SocketEvents.STUDENT_DONE, mapOf(
                                    "studentId" to currentTarget!!.id,
                                    "channel" to channel
                                ))
                            }
                            // Call next after a short delay
                            viewModel.callNext()
                        },
                        modifier = Modifier.weight(1f).height(40.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GOLD),
                        border = BorderStroke(1.dp, GOLD),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.SkipNext, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Lewati", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    // Reset button
                    OutlinedButton(
                        onClick = {
                            viewModel.emitLanEvent(SocketEvents.STUDENT_RESET, mapOf(
                                "studentId" to currentTarget!!.id,
                                "channel" to channel
                            ))
                        },
                        modifier = Modifier.weight(1f).height(40.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = RED),
                        border = BorderStroke(1.dp, RED),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reset", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                // No active target
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) {
                    Icon(
                        Icons.Default.HourglassEmpty,
                        contentDescription = null,
                        tint = MUTED.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Tidak ada target aktif",
                        style = TextStyle(fontSize = 14.sp, color = MUTED.copy(alpha = 0.6f))
                    )
                }
            }
        }

        // ── Call Next Button ──
        Button(
            onClick = { viewModel.callNext() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GOLD, contentColor = BG),
            shape = RoundedCornerShape(12.dp),
            enabled = pendingStudents.isNotEmpty()
        ) {
            Icon(Icons.Default.Campaign, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "Panggil Berikutnya (${pendingStudents.size})",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // ── Queue Stats ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PANEL)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            QueueStat("Menunggu", pendingStudents.size, CYAN)
            QueueStat("Aktif", activeStudents.size, GOLD)
            QueueStat("Selesai", doneStudents.size, GREEN)
        }

        // ── Student Queue List ──
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            // Active students first
            items(activeStudents) { student ->
                McStudentRow(
                    student = student,
                    isCurrent = currentTarget?.id == student.id,
                    onDone = {
                        viewModel.emitLanEvent(SocketEvents.STUDENT_DONE, mapOf(
                            "studentId" to student.id,
                            "channel" to channel
                        ))
                    },
                    onReset = {
                        viewModel.emitLanEvent(SocketEvents.STUDENT_RESET, mapOf(
                            "studentId" to student.id,
                            "channel" to channel
                        ))
                    }
                )
            }
            // Pending students
            items(pendingStudents) { student ->
                McStudentRow(
                    student = student,
                    isCurrent = false,
                    onDone = {},
                    onReset = {}
                )
            }
            // Done students
            items(doneStudents) { student ->
                McStudentRow(
                    student = student,
                    isCurrent = false,
                    onDone = {},
                    onReset = {
                        viewModel.emitLanEvent(SocketEvents.STUDENT_RESET, mapOf(
                            "studentId" to student.id,
                            "channel" to channel
                        ))
                    }
                )
            }
        }
    }
}

@Composable
private fun QueueStat(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count.toString(),
            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color)
        )
        Text(
            text = label,
            style = TextStyle(fontSize = 10.sp, color = MUTED)
        )
    }
}

@Composable
private fun McStudentRow(
    student: Student,
    isCurrent: Boolean,
    onDone: () -> Unit,
    onReset: () -> Unit
) {
    val statusColor = when {
        student.status == "done" -> GREEN
        student.status == "pending" -> MUTED
        student.status == "sent" -> CYAN
        isActiveStatus(student.status) -> GOLD
        else -> MUTED
    }

    val bgColor = if (isCurrent) CARD else CARD.copy(alpha = 0.6f)
    val borderColor = if (isCurrent) GOLD else BORDER.copy(alpha = 0.3f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status dot
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(statusColor, CircleShape)
        )
        Spacer(modifier = Modifier.width(8.dp))

        // Student info
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = student.nama.ifBlank { student.nim },
                style = TextStyle(
                    fontSize = 12.sp,
                    color = Color.White,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                ),
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

        // Action buttons for active students
        if (isActiveStatus(student.status)) {
            IconButton(onClick = onDone, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Check, "Selesai", tint = GREEN, modifier = Modifier.size(14.dp))
            }
            IconButton(onClick = onReset, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Refresh, "Reset", tint = MUTED, modifier = Modifier.size(14.dp))
            }
        } else if (student.status == "done") {
            IconButton(onClick = onReset, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Refresh, "Reset", tint = MUTED, modifier = Modifier.size(14.dp))
            }
        }
    }
}
