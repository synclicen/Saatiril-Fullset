package com.saatiril.fullsystem.ui.connection

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saatiril.fullsystem.ConnectionParams
import com.saatiril.fullsystem.MainActivity
import com.saatiril.fullsystem.server.LocalWebServer.Companion.DEFAULT_PORT
import java.net.URI

// ─── Saatiril Theme Colors ──────────────────────────────────
private val BG = Color(0xFF1a0b2e)
private val PANEL = Color(0xFF2a164a)
private val CARD = Color(0xFF3b2263)
private val BORDER = Color(0xFF533485)
private val GOLD = Color(0xFFd4af37)
private val MUTED = Color(0xFFc4b5fd)
private val CYAN = Color(0xFF06b6d4)
private val RED = Color(0xFFef4444)
private val GREEN = Color(0xFF4ade80)
private val EMERALD = Color(0xFF10b981)

/**
 * Connection Screen — Two modes:
 *
 * 1. STANDALONE (default) — Starts local server, loads web app from assets.
 *    Admin can create projects directly on the phone. No server needed.
 *    Data stored in WebView localStorage.
 *
 * 2. SERVER — Connect to external Saatiril server via URL.
 *    For multi-device scenarios (MC/Operator connecting to Admin's server).
 */
@Composable
fun ConnectionScreen(
    hasCameraPermission: Boolean,
    onConnected: (ConnectionParams) -> Unit
) {
    var selectedMode by remember { mutableStateOf("standalone") } // "standalone" or "server"
    var serverUrl by remember { mutableStateOf("http://192.168.1.100:3000") }
    var selectedRole by remember { mutableStateOf("admin") }
    var selectedChannel by remember { mutableIntStateOf(1) }
    var password by remember { mutableStateOf("") }
    var isConnecting by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ─── App Logo ───
            Spacer(modifier = Modifier.height(12.dp))
            Icon(
                Icons.Default.Devices,
                contentDescription = null,
                tint = GOLD,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "SAATIRIL",
                style = TextStyle(color = GOLD, fontWeight = FontWeight.Bold, fontSize = 28.sp, letterSpacing = 3.sp)
            )
            Text(
                "Full System",
                style = TextStyle(color = Color.White, fontSize = 14.sp)
            )
            Spacer(modifier = Modifier.height(24.dp))

            // ═══════════════════════════════════════════════════════════
            //  STANDALONE MODE BUTTON (PRIMARY — BIG AND PROMINENT)
            // ═══════════════════════════════════════════════════════════
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        selectedMode = "standalone"
                        errorMessage = null
                    },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (selectedMode == "standalone") EMERALD.copy(alpha = 0.15f) else CARD.copy(alpha = 0.6f)
                ),
                border = BorderStroke(
                    width = if (selectedMode == "standalone") 2.dp else 1.dp,
                    color = if (selectedMode == "standalone") EMERALD else BORDER
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.PhoneAndroid,
                            contentDescription = null,
                            tint = if (selectedMode == "standalone") EMERALD else MUTED,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            "Mode Mandiri (Standalone)",
                            style = TextStyle(
                                color = if (selectedMode == "standalone") Color.White else MUTED,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Buat & kelola proyek langsung dari HP.\nTidak perlu server atau PC. Data tersimpan lokal.",
                        style = TextStyle(
                            color = if (selectedMode == "standalone") EMERALD.copy(alpha = 0.8f) else MUTED.copy(alpha = 0.6f),
                            fontSize = 12.sp
                        ),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    if (selectedMode == "standalone") {
                        Spacer(modifier = Modifier.height(8.dp))
                        Badge(
                            containerColor = EMERALD.copy(alpha = 0.2f),
                            contentColor = EMERALD
                        ) {
                            Text("✓ Direkomendasikan", fontSize = 11.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ═══════════════════════════════════════════════════════════
            //  SERVER MODE (SECONDARY — FOR MULTI-DEVICE)
            // ═══════════════════════════════════════════════════════════
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        selectedMode = "server"
                        errorMessage = null
                    },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (selectedMode == "server") GOLD.copy(alpha = 0.08f) else CARD.copy(alpha = 0.4f)
                ),
                border = BorderStroke(
                    width = if (selectedMode == "server") 1.5.dp else 1.dp,
                    color = if (selectedMode == "server") GOLD.copy(alpha = 0.6f) else BORDER.copy(alpha = 0.6f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Cloud,
                        contentDescription = null,
                        tint = if (selectedMode == "server") GOLD else MUTED.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Hubungkan ke Server",
                            style = TextStyle(
                                color = if (selectedMode == "server") Color.White else MUTED.copy(alpha = 0.7f),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp
                            )
                        )
                        Text(
                            "Untuk multi-perangkat (MC/Operator ke server Admin)",
                            style = TextStyle(
                                color = MUTED.copy(alpha = 0.5f),
                                fontSize = 11.sp
                            )
                        )
                    }
                    if (selectedMode == "server") {
                        Icon(Icons.Default.Check, contentDescription = null, tint = GOLD, modifier = Modifier.size(18.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ═══════════════════════════════════════════════════════════
            //  SERVER MODE SETTINGS (only shown when server mode selected)
            // ═══════════════════════════════════════════════════════════
            if (selectedMode == "server") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = PANEL),
                    border = BorderStroke(1.dp, BORDER)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // URL Server
                        Text("URL Server", style = TextStyle(color = GOLD, fontWeight = FontWeight.SemiBold, fontSize = 13.sp))
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = { serverUrl = it; errorMessage = null },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("http://192.168.1.100:3000", color = MUTED.copy(alpha = 0.4f)) },
                            leadingIcon = { Icon(Icons.Default.Language, null, tint = MUTED, modifier = Modifier.size(18.dp)) },
                            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = GOLD,
                                unfocusedBorderColor = BORDER,
                                cursorColor = GOLD
                            )
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Password
                        Text("Password Sesi (opsional)", style = TextStyle(color = MUTED, fontSize = 12.sp))
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("••••••", color = MUTED.copy(alpha = 0.3f)) },
                            leadingIcon = { Icon(Icons.Default.Lock, null, tint = MUTED, modifier = Modifier.size(18.dp)) },
                            visualTransformation = PasswordVisualTransformation(),
                            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = GOLD,
                                unfocusedBorderColor = BORDER,
                                cursorColor = GOLD
                            )
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Role selection
                        Text("Peran", style = TextStyle(color = GOLD, fontWeight = FontWeight.SemiBold, fontSize = 13.sp))
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            RoleButton("Admin", Icons.Default.Shield, selectedRole == "admin", GOLD) { selectedRole = "admin" }
                            RoleButton("MC", Icons.Default.Mic, selectedRole == "mc", CYAN) { selectedRole = "mc" }
                            RoleButton("Operator", Icons.Default.Videocam, selectedRole == "operator", MUTED) { selectedRole = "operator" }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Channel selection
                        Text("Channel", style = TextStyle(color = GOLD, fontWeight = FontWeight.SemiBold, fontSize = 13.sp))
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ChannelButton(1, selectedChannel == 1) { selectedChannel = 1 }
                            ChannelButton(2, selectedChannel == 2) { selectedChannel = 2 }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }

            // ═══════════════════════════════════════════════════════════
            //  CONNECT / START BUTTON
            // ═══════════════════════════════════════════════════════════
            if (errorMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = RED.copy(alpha = 0.1f)),
                    border = BorderStroke(1.dp, RED.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Warning, null, tint = RED, modifier = Modifier.size(18.dp))
                        Text(errorMessage!!, style = TextStyle(color = RED, fontSize = 12.sp))
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            Button(
                onClick = {
                    isConnecting = true
                    errorMessage = null

                    if (selectedMode == "standalone") {
                        // Standalone mode — start local server and connect
                        try {
                            val baseUrl = DEFAULT_PORT.let { port ->
                                "http://localhost:$port"
                            }
                            onConnected(
                                ConnectionParams(
                                    serverUrl = baseUrl,
                                    role = "admin",
                                    channel = 1,
                                    password = null
                                )
                            )
                        } catch (e: Exception) {
                            errorMessage = "Gagal memulai server lokal: ${e.message}"
                            isConnecting = false
                        }
                    } else {
                        // Server mode — validate URL and connect
                        val url = serverUrl.trim()
                        if (url.isEmpty()) {
                            errorMessage = "URL Server wajib diisi"
                            isConnecting = false
                            return@Button
                        }
                        try {
                            val uri = URI(url)
                            if (uri.scheme !in listOf("http", "https")) {
                                errorMessage = "URL harus dimulai dengan http:// atau https://"
                                isConnecting = false
                                return@Button
                            }
                        } catch (e: Exception) {
                            errorMessage = "URL tidak valid: ${e.message}"
                            isConnecting = false
                            return@Button
                        }

                        onConnected(
                            ConnectionParams(
                                serverUrl = url,
                                role = selectedRole,
                                channel = selectedChannel,
                                password = password.ifEmpty { null }
                            )
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedMode == "standalone") EMERALD else GOLD
                )
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = BG,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                } else {
                    Icon(
                        if (selectedMode == "standalone") Icons.Default.PlayArrow else Icons.Default.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    if (selectedMode == "standalone") "Buka Saatiril" else "Hubungkan",
                    style = TextStyle(
                        color = BG,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ─── Status Info ───
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusChip(
                    icon = if (selectedMode == "standalone") Icons.Default.PhoneAndroid else Icons.Default.Language,
                    label = if (selectedMode == "standalone") "Standalone" else "Server Mode",
                    isActive = true,
                    color = if (selectedMode == "standalone") EMERALD else CYAN,
                    modifier = Modifier.weight(1f)
                )
                StatusChip(
                    icon = Icons.Default.CameraAlt,
                    label = if (hasCameraPermission) "Kamera ✓" else "Kamera ✗",
                    isActive = hasCameraPermission,
                    color = if (hasCameraPermission) GREEN else RED,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Standalone info
            if (selectedMode == "standalone") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = EMERALD.copy(alpha = 0.05f)),
                    border = BorderStroke(1.dp, EMERALD.copy(alpha = 0.15f))
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Info, null, tint = EMERALD.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
                            Text("Mode Mandiri", style = TextStyle(color = EMERALD.copy(alpha = 0.8f), fontWeight = FontWeight.SemiBold, fontSize = 11.sp))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "• Buat proyek baru langsung dari HP\n• Semua data tersimpan di perangkat\n• Kamera HP & USB bisa digunakan\n• Untuk multi-perangkat, gunakan Mode Server",
                            style = TextStyle(color = MUTED.copy(alpha = 0.7f), fontSize = 11.sp, lineHeight = 16.sp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

// ─── Role Button ───────────────────────────────────────────
@Composable
private fun RoleButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    accentColor: Color,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.height(44.dp),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) accentColor else BORDER
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) accentColor.copy(alpha = 0.15f) else Color.Transparent,
            contentColor = if (selected) accentColor else MUTED
        ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

// ─── Channel Button ────────────────────────────────────────
@Composable
private fun ChannelButton(
    channel: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.height(44.dp),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) GOLD else BORDER
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) GOLD.copy(alpha = 0.15f) else Color.Transparent,
            contentColor = if (selected) GOLD else MUTED
        )
    ) {
        Icon(Icons.Default.ViewInAr, null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text("Ch. $channel", fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

// ─── Status Chip ───────────────────────────────────────────
@Composable
private fun StatusChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f)),
        border = BorderStroke(1.dp, color.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (isActive) color else RED.copy(alpha = 0.5f))
            )
            Text(label, style = TextStyle(color = color.copy(alpha = 0.8f), fontSize = 11.sp))
        }
    }
}
