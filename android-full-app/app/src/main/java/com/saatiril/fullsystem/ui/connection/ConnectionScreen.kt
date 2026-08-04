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

/**
 * Connection Screen — Enter server URL, select role, select channel, optional password.
 * Visually matches the operator APK's ConnectionScreen style.
 */
@Composable
fun ConnectionScreen(
    hasCameraPermission: Boolean,
    onConnected: (ConnectionParams) -> Unit
) {
    var serverUrl by remember { mutableStateOf("http://192.168.1.100:3000") }
    var selectedRole by remember { mutableStateOf("admin") }
    var selectedChannel by remember { mutableIntStateOf(1) }
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isConnecting by remember { mutableStateOf(false) }

    // Detect orientation and screen size
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val screenHeightDp = configuration.screenHeightDp
    val isSmallHeight = screenHeightDp < 400

    // Connect logic
    val doConnect: () -> Unit = connect@{
        errorMessage = null
        isConnecting = true

        val url = serverUrl.trim()
        if (url.isEmpty()) {
            errorMessage = "Masukkan URL server"
            isConnecting = false
            return@connect
        }

        // Validate URL format
        try {
            val uri = URI(url)
            if (uri.host.isNullOrBlank()) {
                errorMessage = "URL server tidak valid"
                isConnecting = false
            } else {
                // Build connection params and navigate
                val pwd = password.trim().ifEmpty { null }
                val params = ConnectionParams(
                    serverUrl = url,
                    role = selectedRole,
                    channel = selectedChannel,
                    password = pwd
                )
                onConnected(params)
                isConnecting = false
            }
        } catch (e: Exception) {
            errorMessage = "URL tidak valid: ${e.message}"
            isConnecting = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BG)
    ) {
        if (isLandscape && !isSmallHeight) {
            // ─── LANDSCAPE on TABLET/LARGE SCREEN: Two-column side-by-side ────
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 32.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Column: Logo & Info
                Column(
                    modifier = Modifier.weight(0.35f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Devices,
                        contentDescription = "Saatiril",
                        modifier = Modifier.size(56.dp),
                        tint = GOLD
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "SAATIRIL",
                        style = TextStyle(
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = GOLD
                        )
                    )
                    Text(
                        "Full System",
                        style = TextStyle(fontSize = 14.sp, color = MUTED)
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    SystemInfoCard(compact = false, hasCameraPermission = hasCameraPermission)
                }

                // Right Column: Connection Form
                Column(
                    modifier = Modifier
                        .weight(0.65f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    ConnectionFormCard(
                        serverUrl = serverUrl,
                        onServerUrlChange = { serverUrl = it },
                        selectedRole = selectedRole,
                        onRoleSelect = { selectedRole = it },
                        selectedChannel = selectedChannel,
                        onChannelSelect = { selectedChannel = it },
                        password = password,
                        onPasswordChange = { password = it },
                        errorMessage = errorMessage,
                        isConnecting = isConnecting,
                        onConnect = { doConnect() },
                        compact = false
                    )
                }
            }
        } else {
            // ─── PORTRAIT or LANDSCAPE on SMALL PHONE: Scrollable single column ──
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = if (isLandscape) 40.dp else 24.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Logo section
                if (isLandscape) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Devices,
                            contentDescription = "Saatiril",
                            modifier = Modifier.size(28.dp),
                            tint = GOLD
                        )
                        Text(
                            "SAATIRIL",
                            style = TextStyle(
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = GOLD
                            )
                        )
                        Text(
                            "Full System",
                            style = TextStyle(fontSize = 11.sp, color = MUTED)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                } else {
                    Icon(
                        Icons.Default.Devices,
                        contentDescription = "Saatiril",
                        modifier = Modifier.size(64.dp),
                        tint = GOLD
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "SAATIRIL",
                        style = TextStyle(
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = GOLD
                        )
                    )
                    Text(
                        "Full System",
                        style = TextStyle(fontSize = 16.sp, color = MUTED)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }

                // Connection Form
                ConnectionFormCard(
                    serverUrl = serverUrl,
                    onServerUrlChange = { serverUrl = it },
                    selectedRole = selectedRole,
                    onRoleSelect = { selectedRole = it },
                    selectedChannel = selectedChannel,
                    onChannelSelect = { selectedChannel = it },
                    password = password,
                    onPasswordChange = { password = it },
                    errorMessage = errorMessage,
                    isConnecting = isConnecting,
                    onConnect = { doConnect() },
                    compact = isLandscape
                )

                Spacer(modifier = Modifier.height(12.dp))

                // System Info
                SystemInfoCard(compact = isLandscape, hasCameraPermission = hasCameraPermission)

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

// ─── System Info Card ─────────────────────────────────────
@Composable
private fun SystemInfoCard(
    compact: Boolean,
    hasCameraPermission: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = PANEL),
        shape = RoundedCornerShape(if (compact) 8.dp else 12.dp),
    ) {
        Column(
            modifier = Modifier.padding(if (compact) PaddingValues(8.dp) else PaddingValues(14.dp)),
            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)
        ) {
            // WebView indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)
            ) {
                Icon(
                    Icons.Default.Language,
                    contentDescription = null,
                    tint = CYAN,
                    modifier = Modifier.size(if (compact) 16.dp else 24.dp)
                )
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "WebView Mode",
                            style = TextStyle(
                                color = Color.White,
                                fontWeight = FontWeight.Medium,
                                fontSize = if (compact) 11.sp else 14.sp
                            )
                        )
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(CYAN)
                        )
                    }
                    Text(
                        "Full Saatiril web app di WebView",
                        style = TextStyle(
                            color = MUTED,
                            fontSize = if (compact) 9.sp else 12.sp
                        )
                    )
                }
            }

            // Divider
            HorizontalDivider(
                modifier = Modifier.padding(vertical = if (compact) 2.dp else 4.dp),
                color = BORDER.copy(alpha = 0.5f),
                thickness = 0.5.dp
            )

            // Camera permission status
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)
            ) {
                Icon(
                    if (hasCameraPermission) Icons.Default.Videocam else Icons.Default.VideocamOff,
                    contentDescription = null,
                    tint = if (hasCameraPermission) GREEN else RED,
                    modifier = Modifier.size(if (compact) 16.dp else 24.dp)
                )
                Column {
                    Text(
                        if (hasCameraPermission) "Kamera: Diizinkan" else "Kamera: Belum diizinkan",
                        style = TextStyle(
                            color = if (hasCameraPermission) GREEN else RED,
                            fontWeight = FontWeight.Medium,
                            fontSize = if (compact) 11.sp else 14.sp
                        )
                    )
                    if (!compact) {
                        Text(
                            if (hasCameraPermission)
                                "getUserMedia akan bekerja di WebView"
                            else
                                "Berikan izin kamera untuk fitur kamera di WebView",
                            style = TextStyle(
                                color = MUTED,
                                fontSize = 11.sp
                            )
                        )
                    }
                }
            }
        }
    }
}

// ─── Connection Form Card ─────────────────────────────────────
@Composable
private fun ConnectionFormCard(
    serverUrl: String,
    onServerUrlChange: (String) -> Unit,
    selectedRole: String,
    onRoleSelect: (String) -> Unit,
    selectedChannel: Int,
    onChannelSelect: (Int) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    errorMessage: String?,
    isConnecting: Boolean,
    onConnect: () -> Unit,
    compact: Boolean
) {
    val roles = listOf("admin" to "Admin", "mc" to "MC", "operator" to "Operator")

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = PANEL),
        shape = RoundedCornerShape(if (compact) 10.dp else 16.dp),
        border = BorderStroke(1.dp, BORDER)
    ) {
        Column(
            modifier = Modifier.padding(if (compact) PaddingValues(12.dp) else PaddingValues(20.dp)),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp)
        ) {
            // Title
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)
            ) {
                Icon(
                    Icons.Default.Wifi,
                    contentDescription = null,
                    tint = CYAN,
                    modifier = Modifier.size(if (compact) 16.dp else 20.dp)
                )
                Text(
                    "Koneksi ke Server",
                    style = TextStyle(
                        fontSize = if (compact) 14.sp else 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                )
            }

            // Server URL Input
            OutlinedTextField(
                value = serverUrl,
                onValueChange = onServerUrlChange,
                label = { Text("URL Server", color = MUTED, fontSize = if (compact) 10.sp else 14.sp) },
                placeholder = {
                    Text(
                        "http://192.168.1.100:3000",
                        color = MUTED.copy(alpha = 0.5f),
                        fontSize = if (compact) 11.sp else 14.sp
                    )
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = textFieldColors(),
                leadingIcon = {
                    Icon(
                        Icons.Default.Language,
                        contentDescription = null,
                        tint = MUTED,
                        modifier = Modifier.size(if (compact) 16.dp else 24.dp)
                    )
                },
                textStyle = TextStyle(fontSize = if (compact) 13.sp else 16.sp)
            )

            // Role Selection
            Text(
                "Peran:",
                style = TextStyle(
                    color = MUTED,
                    fontSize = if (compact) 11.sp else 14.sp
                )
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)
            ) {
                roles.forEach { (roleKey, roleLabel) ->
                    RoleButton(
                        roleKey = roleKey,
                        roleLabel = roleLabel,
                        isSelected = selectedRole == roleKey,
                        onClick = { onRoleSelect(roleKey) },
                        modifier = Modifier.weight(1f),
                        compact = compact
                    )
                }
            }

            // Channel Selection
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)
            ) {
                if (!compact) {
                    Text(
                        "Channel:",
                        style = TextStyle(color = MUTED, fontSize = 14.sp)
                    )
                }
                ChannelButton(
                    channel = 1,
                    isSelected = selectedChannel == 1,
                    onClick = { onChannelSelect(1) },
                    modifier = Modifier.weight(1f),
                    compact = compact
                )
                ChannelButton(
                    channel = 2,
                    isSelected = selectedChannel == 2,
                    onClick = { onChannelSelect(2) },
                    modifier = Modifier.weight(1f),
                    compact = compact
                )
            }

            // Session Password (optional)
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = {
                    Text(
                        "Password Sesi (opsional)",
                        color = MUTED,
                        fontSize = if (compact) 10.sp else 14.sp
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = textFieldColors(),
                leadingIcon = {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        tint = MUTED,
                        modifier = Modifier.size(if (compact) 16.dp else 24.dp)
                    )
                },
                textStyle = TextStyle(fontSize = if (compact) 13.sp else 16.sp)
            )

            // Error message
            if (errorMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = RED.copy(alpha = 0.1f)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = RED,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            errorMessage,
                            style = TextStyle(color = RED, fontSize = if (compact) 9.sp else 11.sp)
                        )
                    }
                }
            }

            // Connect Button
            Button(
                onClick = onConnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compact) 38.dp else 52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GOLD),
                shape = RoundedCornerShape(if (compact) 8.dp else 12.dp),
                enabled = !isConnecting
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(if (compact) 14.dp else 20.dp),
                        color = BG,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(if (compact) 4.dp else 8.dp))
                    Text(
                        "Menghubungkan...",
                        color = BG,
                        fontSize = if (compact) 12.sp else 14.sp
                    )
                } else {
                    Icon(
                        Icons.Default.Login,
                        contentDescription = null,
                        modifier = Modifier.size(if (compact) 14.dp else 20.dp)
                    )
                    Spacer(modifier = Modifier.width(if (compact) 4.dp else 8.dp))
                    Text(
                        "Hubungkan",
                        color = BG,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (compact) 12.sp else 14.sp
                    )
                }
            }
        }
    }
}

// ─── Reusable TextField Colors ──────────────────────────────
@Composable
private fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = GOLD,
    unfocusedBorderColor = BORDER,
    focusedLabelColor = GOLD,
    cursorColor = GOLD,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White
)

// ─── Role Button ────────────────────────────────────────────
@Composable
private fun RoleButton(
    roleKey: String,
    roleLabel: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val icon = when (roleKey) {
        "admin" -> Icons.Default.AdminPanelSettings
        "mc" -> Icons.Default.Mic
        "operator" -> Icons.Default.Videocam
        else -> Icons.Default.Person
    }

    Card(
        modifier = modifier
            .clip(RoundedCornerShape(if (compact) 8.dp else 12.dp))
            .clickable(onClick = onClick)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) GOLD else BORDER,
                shape = RoundedCornerShape(if (compact) 8.dp else 12.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) CARD.copy(alpha = 0.8f) else PANEL
        ),
        shape = RoundedCornerShape(if (compact) 8.dp else 12.dp)
    ) {
        if (compact) {
            Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (isSelected) GOLD else MUTED,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    roleLabel,
                    style = TextStyle(
                        color = if (isSelected) GOLD else MUTED,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 10.sp
                    )
                )
            }
        } else {
            Column(
                modifier = Modifier.padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (isSelected) GOLD else MUTED,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    roleLabel,
                    style = TextStyle(
                        color = if (isSelected) GOLD else MUTED,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 13.sp
                    )
                )
            }
        }
    }
}

// ─── Channel Button ─────────────────────────────────────────
@Composable
private fun ChannelButton(
    channel: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    Card(
        modifier = modifier
            .clip(RoundedCornerShape(if (compact) 8.dp else 12.dp))
            .clickable(onClick = onClick)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) GOLD else BORDER,
                shape = RoundedCornerShape(if (compact) 8.dp else 12.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) CARD.copy(alpha = 0.8f) else PANEL
        ),
        shape = RoundedCornerShape(if (compact) 8.dp else 12.dp)
    ) {
        if (compact) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    Icons.Default.ViewInAr,
                    contentDescription = null,
                    tint = if (isSelected) GOLD else MUTED,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    "Ch. $channel",
                    style = TextStyle(
                        color = if (isSelected) GOLD else MUTED,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 11.sp
                    )
                )
            }
        } else {
            Column(
                modifier = Modifier.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.ViewInAr,
                    contentDescription = null,
                    tint = if (isSelected) GOLD else MUTED,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Ch. $channel",
                    style = TextStyle(
                        color = if (isSelected) GOLD else MUTED,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 14.sp
                    )
                )
            }
        }
    }
}
