package com.saatiril.full.ui.connection

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BG = Color(0xFF1a0b2e)
private val PANEL = Color(0xFF2a164a)
private val CARD = Color(0xFF3b2263)
private val BORDER = Color(0xFF533485)
private val GOLD = Color(0xFFd4af37)
private val MUTED = Color(0xFFc4b5fd)
private val CYAN = Color(0xFF06b6d4)

@Composable
fun ModeSelectionScreen(
    onStandalone: () -> Unit,
    onConnectToServer: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BG),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "SATIRIL",
                style = TextStyle(
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = GOLD,
                    letterSpacing = 6.sp
                )
            )
            Text(
                text = "Full System",
                style = TextStyle(fontSize = 14.sp, color = MUTED, letterSpacing = 4.sp)
            )

            Spacer(modifier = Modifier.height(40.dp))

            // Standalone Mode Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(PANEL)
                    .border(BorderStroke(1.dp, GOLD.copy(alpha = 0.4f)), RoundedCornerShape(16.dp))
                    .clickable { onStandalone() }
                    .padding(20.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = GOLD, modifier = Modifier.size(28.dp))
                    Column {
                        Text(text = "Mode Mandiri (Standalone)", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White))
                        Text(text = "Buat & kelola proyek langsung di HP", style = TextStyle(fontSize = 12.sp, color = MUTED))
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "• Buat proyek baru\n• Kelola database siswa\n• Foto dengan kamera HP/USB\n• Semua data tersimpan lokal",
                    style = TextStyle(fontSize = 11.sp, color = MUTED.copy(alpha = 0.8f), lineHeight = 16.sp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Server Mode Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(PANEL)
                    .border(BorderStroke(1.dp, BORDER), RoundedCornerShape(16.dp))
                    .clickable { onConnectToServer() }
                    .padding(20.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Default.Cloud, contentDescription = null, tint = CYAN, modifier = Modifier.size(28.dp))
                    Column {
                        Text(text = "Hubungkan ke Server", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White))
                        Text(text = "Untuk multi-perangkat via LAN", style = TextStyle(fontSize = 12.sp, color = MUTED))
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "• Hubung ke PC/server via WiFi\n• Admin, MC, Operator di perangkat berbeda\n• Sinkronisasi real-time via Socket.io",
                    style = TextStyle(fontSize = 11.sp, color = MUTED.copy(alpha = 0.8f), lineHeight = 16.sp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(text = "v1.0.0-full-native", style = TextStyle(fontSize = 10.sp, color = MUTED.copy(alpha = 0.5f)))
        }
    }
}
