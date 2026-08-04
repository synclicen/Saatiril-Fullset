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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saatiril.full.data.*

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
fun ProjectSetupScreen(
    viewModel: FullViewModel,
    onProjectCreated: () -> Unit
) {
    var projectName by remember { mutableStateOf("") }
    var selectedMode by remember { mutableStateOf(CameraModes.SINGLE) }
    var selectedRatio by remember { mutableStateOf("4:3") }
    var selectedPreset by remember { mutableStateOf("original") }
    var sessionPassword by remember { mutableStateOf("") }
    var currentStep by remember { mutableIntStateOf(0) }

    var newNim by remember { mutableStateOf("") }
    var newNama by remember { mutableStateOf("") }
    var newChannel by remember { mutableIntStateOf(1) }

    val project by viewModel.project.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().background(BG)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().background(PANEL).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AddCircle, contentDescription = null, tint = GOLD, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (currentStep == 0) "Buat Proyek" else "Tambah Siswa",
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = GOLD)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(2) { step ->
                    Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(if (step <= currentStep) GOLD else BORDER))
                }
            }
        }

        if (currentStep == 0) {
            // ── Step 1: Project Configuration ──
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = projectName, onValueChange = { projectName = it },
                    label = { Text("Nama Proyek", color = MUTED) },
                    placeholder = { Text("Contoh: Wisuda 2025", color = MUTED.copy(alpha = 0.5f)) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GOLD, unfocusedBorderColor = BORDER, cursorColor = GOLD),
                    leadingIcon = { Icon(Icons.Default.Folder, null, tint = MUTED) }
                )

                Text("Mode Kamera", style = TextStyle(fontSize = 13.sp, color = MUTED, fontWeight = FontWeight.Medium))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeChip("Single", selectedMode == CameraModes.SINGLE) { selectedMode = CameraModes.SINGLE }
                    ModeChip("Dual", selectedMode == CameraModes.DUAL) { selectedMode = CameraModes.DUAL }
                    ModeChip("Photoshoot", selectedMode == CameraModes.SINGLE_PHOTOSHOOT) { selectedMode = CameraModes.SINGLE_PHOTOSHOOT }
                }

                Text("Rasio Foto", style = TextStyle(fontSize = 13.sp, color = MUTED, fontWeight = FontWeight.Medium))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RatioChip("4:3", selectedRatio == "4:3") { selectedRatio = "4:3" }
                    RatioChip("3:4", selectedRatio == "3:4") { selectedRatio = "3:4" }
                    RatioChip("16:9", selectedRatio == "16:9") { selectedRatio = "16:9" }
                    RatioChip("2:3", selectedRatio == "2:3") { selectedRatio = "2:3" }
                }

                Text("Preset", style = TextStyle(fontSize = 13.sp, color = MUTED, fontWeight = FontWeight.Medium))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PresetChip("Original", selectedPreset == "original") { selectedPreset = "original" }
                    PresetChip("Studio", selectedPreset == "studio") { selectedPreset = "studio" }
                    PresetChip("Outdoor", selectedPreset == "outdoor") { selectedPreset = "outdoor" }
                }

                OutlinedTextField(
                    value = sessionPassword, onValueChange = { sessionPassword = it },
                    label = { Text("Password Sesi (opsional)", color = MUTED) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GOLD, unfocusedBorderColor = BORDER, cursorColor = GOLD),
                    leadingIcon = { Icon(Icons.Default.Lock, null, tint = MUTED) }
                )
            }

            Button(
                onClick = {
                    if (projectName.isNotBlank()) {
                        viewModel.createProject(name = projectName, mode = selectedMode, ratio = selectedRatio, preset = selectedPreset, sessionPassword = sessionPassword.ifBlank { null })
                        currentStep = 1
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GOLD, contentColor = BG),
                shape = RoundedCornerShape(12.dp),
                enabled = projectName.isNotBlank()
            ) {
                Icon(Icons.Default.ArrowForward, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Lanjut", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            // ── Step 2: Add Students ──
            val proj = project ?: Project()

            Column(modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(text = "${proj.database.size} siswa ditambahkan", style = TextStyle(fontSize = 12.sp, color = MUTED))
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newNim, onValueChange = { newNim = it },
                        placeholder = { Text("NIM", color = MUTED.copy(alpha = 0.5f)) },
                        modifier = Modifier.weight(1f),
                        textStyle = TextStyle(color = Color.White, fontSize = 13.sp), singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GOLD, unfocusedBorderColor = BORDER, cursorColor = GOLD)
                    )
                    OutlinedTextField(
                        value = newNama, onValueChange = { newNama = it },
                        placeholder = { Text("Nama", color = MUTED.copy(alpha = 0.5f)) },
                        modifier = Modifier.weight(1.5f),
                        textStyle = TextStyle(color = Color.White, fontSize = 13.sp), singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GOLD, unfocusedBorderColor = BORDER, cursorColor = GOLD)
                    )
                    ChannelChipMini(newChannel == 1, "1") { newChannel = 1 }
                    ChannelChipMini(newChannel == 2, "2") { newChannel = 2 }
                    IconButton(
                        onClick = {
                            if (newNim.isNotBlank() && newNama.isNotBlank()) {
                                viewModel.addStudent(newNim, newNama, newChannel)
                                newNim = ""; newNama = ""
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Add, "Tambah", tint = GOLD, modifier = Modifier.size(20.dp))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(proj.database) { student ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(CARD).padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = student.nim, style = TextStyle(fontSize = 11.sp, color = MUTED, fontWeight = FontWeight.Medium), modifier = Modifier.weight(0.3f))
                            Text(text = student.nama, style = TextStyle(fontSize = 12.sp, color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.5f))
                            Text(text = "Ch.${student.assignedChannel}", style = TextStyle(fontSize = 10.sp, color = CYAN), modifier = Modifier.weight(0.1f))
                            IconButton(onClick = { viewModel.removeStudent(student.id) }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, "Hapus", tint = RED.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }

            Button(
                onClick = onProjectCreated,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GREEN, contentColor = BG),
                shape = RoundedCornerShape(12.dp),
                enabled = proj.database.isNotEmpty()
            ) {
                Icon(Icons.Default.Check, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Mulai (${proj.database.size} siswa)", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }

            if (proj.database.isEmpty()) {
                TextButton(
                    onClick = onProjectCreated,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text("Lewati (tambah siswa nanti)", color = MUTED, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, isSelected: Boolean, onSelect: () -> Unit) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (isSelected) GOLD.copy(alpha = 0.2f) else CARD)
            .border(BorderStroke(1.dp, if (isSelected) GOLD else BORDER), RoundedCornerShape(8.dp))
            .clickable { onSelect() }.padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, style = TextStyle(fontSize = 12.sp, color = if (isSelected) GOLD else MUTED, fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal))
    }
}

@Composable
private fun RatioChip(label: String, isSelected: Boolean, onSelect: () -> Unit) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (isSelected) CYAN.copy(alpha = 0.2f) else CARD)
            .border(BorderStroke(1.dp, if (isSelected) CYAN else BORDER), RoundedCornerShape(8.dp))
            .clickable { onSelect() }.padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, style = TextStyle(fontSize = 12.sp, color = if (isSelected) CYAN else MUTED, fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal))
    }
}

@Composable
private fun PresetChip(label: String, isSelected: Boolean, onSelect: () -> Unit) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (isSelected) GREEN.copy(alpha = 0.2f) else CARD)
            .border(BorderStroke(1.dp, if (isSelected) GREEN else BORDER), RoundedCornerShape(8.dp))
            .clickable { onSelect() }.padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, style = TextStyle(fontSize = 12.sp, color = if (isSelected) GREEN else MUTED, fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal))
    }
}

@Composable
private fun ChannelChipMini(isSelected: Boolean, label: String, onSelect: () -> Unit) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (isSelected) CYAN.copy(alpha = 0.3f) else CARD)
            .border(BorderStroke(1.dp, if (isSelected) CYAN else BORDER.copy(alpha = 0.5f)), RoundedCornerShape(6.dp))
            .clickable { onSelect() }.padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Text(label, style = TextStyle(fontSize = 10.sp, color = if (isSelected) CYAN else MUTED))
    }
}
