package com.saatiril.operator.ui.connection

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
import com.saatiril.operator.data.ConnectionState
import com.saatiril.operator.data.OperatorViewModel

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

@Composable
fun ConnectionScreen(
    viewModel: OperatorViewModel,
    onConnected: () -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val passwordRequired by viewModel.passwordRequired.collectAsState()
    val authError by viewModel.authError.collectAsState()
    val uvcDeviceAttached by viewModel.uvcDeviceAttached.collectAsState()

    var serverIp by remember { mutableStateOf("") }
    var serverPort by remember { mutableStateOf("3003") }
    var password by remember { mutableStateOf("") }
    var selectedChannel by remember { mutableIntStateOf(1) }
    var showPasswordPrompt by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Monitor connection state changes
    LaunchedEffect(connectionState) {
        when (connectionState) {
            ConnectionState.AUTHENTICATED -> {
                onConnected()
            }
            ConnectionState.AUTHENTICATING, ConnectionState.WAITING_FOR_DATA -> {
                // Show loading
            }
            ConnectionState.AUTH_FAILED -> {
                if (passwordRequired) {
                    showPasswordPrompt = true
                }
            }
            else -> {}
        }
    }

    // Show auth error
    LaunchedEffect(authError) {
        if (authError != null) {
            errorMessage = when (authError) {
                "session_password_required" -> "Password salah, coba lagi"
                else -> "Autentikasi gagal: $authError"
            }
        }
    }

    // Detect orientation and screen size
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
    val screenHeightDp = configuration.screenHeightDp
    val isSmallHeight = screenHeightDp < 400 // compact phone in landscape

    // Shared connect logic
    val doConnect: () -> Unit = {
        errorMessage = null
        val ip = serverIp.trim()
        val port = serverPort.trim().ifEmpty { "3003" }
        if (ip.isEmpty()) {
            errorMessage = "Masukkan IP server"
            return@let
        }
        val url = "http://$ip:$port"
        val pwd = if (showPasswordPrompt || passwordRequired) password.trim().ifEmpty { null } else null
        viewModel.connect(url, selectedChannel, pwd)
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
                // Left Column: Logo & Camera Status
                Column(
                    modifier = Modifier
                        .weight(0.35f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.CameraAlt,
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
                        "Operator Kamera",
                        style = TextStyle(fontSize = 14.sp, color = MUTED)
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    CameraStatusCard(uvcDeviceAttached = uvcDeviceAttached, compact = false)
                }

                // Right Column: Connection Form (scrollable)
                Column(
                    modifier = Modifier
                        .weight(0.65f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    ConnectionFormCard(
                        serverIp = serverIp,
                        onServerIpChange = { serverIp = it },
                        serverPort = serverPort,
                        onServerPortChange = { serverPort = it },
                        selectedChannel = selectedChannel,
                        onChannelSelect = { selectedChannel = it },
                        showPasswordPrompt = showPasswordPrompt,
                        passwordRequired = passwordRequired,
                        password = password,
                        onPasswordChange = { password = it },
                        authError = authError,
                        errorMessage = errorMessage,
                        connectionState = connectionState,
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
                // Logo section — compact in landscape, full in portrait
                if (isLandscape) {
                    // Inline header for landscape on small phones
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.CameraAlt,
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
                            "Operator Kamera",
                            style = TextStyle(fontSize = 11.sp, color = MUTED)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                } else {
                    Icon(
                        Icons.Default.CameraAlt,
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
                        "Operator Kamera",
                        style = TextStyle(fontSize = 16.sp, color = MUTED)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }

                // Connection Form
                ConnectionFormCard(
                    serverIp = serverIp,
                    onServerIpChange = { serverIp = it },
                    serverPort = serverPort,
                    onServerPortChange = { serverPort = it },
                    selectedChannel = selectedChannel,
                    onChannelSelect = { selectedChannel = it },
                    showPasswordPrompt = showPasswordPrompt,
                    passwordRequired = passwordRequired,
                    password = password,
                    onPasswordChange = { password = it },
                    authError = authError,
                    errorMessage = errorMessage,
                    connectionState = connectionState,
                    onConnect = { doConnect() },
                    compact = isLandscape
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Camera Status
                CameraStatusCard(uvcDeviceAttached = uvcDeviceAttached, compact = isLandscape)

                // Bottom padding for scroll clearance
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

// ─── Camera Status Card ──────────────────────────────────────
@Composable
private fun CameraStatusCard(
    uvcDeviceAttached: Boolean,
    compact: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = PANEL),
        shape = RoundedCornerShape(if (compact) 8.dp else 12.dp),
    ) {
        Row(
            modifier = Modifier.padding(if (compact) PaddingValues(8.dp) else PaddingValues(14.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)
        ) {
            Icon(
                if (uvcDeviceAttached) Icons.Default.Usb else Icons.Default.Videocam,
                contentDescription = null,
                tint = if (uvcDeviceAttached) GREEN else MUTED,
                modifier = Modifier.size(if (compact) 16.dp else 24.dp)
            )
            Column {
                Text(
                    if (uvcDeviceAttached) "Capture Card" else "Kamera Built-in",
                    style = TextStyle(
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        fontSize = if (compact) 11.sp else 14.sp
                    )
                )
                Text(
                    if (uvcDeviceAttached) "USB HDMI siap" else "Sambungkan via USB",
                    style = TextStyle(
                        color = MUTED,
                        fontSize = if (compact) 9.sp else 12.sp
                    )
                )
            }
        }
    }
}

// ─── Connection Form Card ─────────────────────────────────────
@Composable
private fun ConnectionFormCard(
    serverIp: String,
    onServerIpChange: (String) -> Unit,
    serverPort: String,
    onServerPortChange: (String) -> Unit,
    selectedChannel: Int,
    onChannelSelect: (Int) -> Unit,
    showPasswordPrompt: Boolean,
    passwordRequired: Boolean,
    password: String,
    onPasswordChange: (String) -> Unit,
    authError: String?,
    errorMessage: String?,
    connectionState: ConnectionState,
    onConnect: () -> Unit,
    compact: Boolean
) {
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

            if (compact) {
                // IP and Port in a single row (compact landscape)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = serverIp,
                        onValueChange = onServerIpChange,
                        label = { Text("IP Server", color = MUTED, fontSize = 10.sp) },
                        placeholder = { Text("192.168.1.100", color = MUTED.copy(alpha = 0.5f), fontSize = 11.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true,
                        modifier = Modifier.weight(0.65f),
                        colors = textFieldColors(authError = null),
                        leadingIcon = {
                            Icon(Icons.Default.Router, contentDescription = null, tint = MUTED, modifier = Modifier.size(16.dp))
                        },
                        textStyle = TextStyle(fontSize = 13.sp)
                    )
                    OutlinedTextField(
                        value = serverPort,
                        onValueChange = onServerPortChange,
                        label = { Text("Port", color = MUTED, fontSize = 10.sp) },
                        placeholder = { Text("3003", color = MUTED.copy(alpha = 0.5f), fontSize = 11.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(0.35f),
                        colors = textFieldColors(authError = null),
                        leadingIcon = {
                            Icon(Icons.Default.Cable, contentDescription = null, tint = MUTED, modifier = Modifier.size(16.dp))
                        },
                        textStyle = TextStyle(fontSize = 13.sp)
                    )
                }
            } else {
                // IP and Port stacked (portrait)
                OutlinedTextField(
                    value = serverIp,
                    onValueChange = onServerIpChange,
                    label = { Text("IP Server", color = MUTED) },
                    placeholder = { Text("192.168.1.100", color = MUTED.copy(alpha = 0.5f)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors(authError = null),
                    leadingIcon = {
                        Icon(Icons.Default.Router, contentDescription = null, tint = MUTED)
                    }
                )
                OutlinedTextField(
                    value = serverPort,
                    onValueChange = onServerPortChange,
                    label = { Text("Port", color = MUTED) },
                    placeholder = { Text("3003", color = MUTED.copy(alpha = 0.5f)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors(authError = null),
                    leadingIcon = {
                        Icon(Icons.Default.Cable, contentDescription = null, tint = MUTED)
                    }
                )
            }

            // Channel Selection
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)
            ) {
                if (!compact) {
                    Text(
                        "Channel Kamera:",
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

            // Password (if needed)
            if (showPasswordPrompt || passwordRequired) {
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text("Password Sesi", color = MUTED, fontSize = if (compact) 10.sp else 14.sp) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = textFieldColors(authError = authError),
                    leadingIcon = {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            tint = if (authError != null) RED else MUTED,
                            modifier = Modifier.size(if (compact) 16.dp else 24.dp)
                        )
                    },
                    textStyle = TextStyle(fontSize = if (compact) 13.sp else 16.sp)
                )
                if (errorMessage != null) {
                    Text(
                        errorMessage ?: "",
                        style = TextStyle(color = RED, fontSize = if (compact) 10.sp else 13.sp)
                    )
                }
            }

            // Error message (non-auth)
            if (connectionState == ConnectionState.DISCONNECTED && errorMessage != null && !passwordRequired) {
                Text(
                    errorMessage ?: "",
                    style = TextStyle(color = RED, fontSize = if (compact) 10.sp else 13.sp)
                )
            }

            // Connect Button
            Button(
                onClick = onConnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compact) 38.dp else 52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GOLD),
                shape = RoundedCornerShape(if (compact) 8.dp else 12.dp),
                enabled = connectionState != ConnectionState.CONNECTING &&
                        connectionState != ConnectionState.AUTHENTICATING &&
                        connectionState != ConnectionState.WAITING_FOR_DATA
            ) {
                when (connectionState) {
                    ConnectionState.CONNECTING, ConnectionState.AUTHENTICATING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(if (compact) 14.dp else 20.dp),
                            color = BG,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(if (compact) 4.dp else 8.dp))
                        Text("Menghubungkan...", color = BG, fontSize = if (compact) 12.sp else 14.sp)
                    }
                    ConnectionState.WAITING_FOR_DATA -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(if (compact) 14.dp else 20.dp),
                            color = BG,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(if (compact) 4.dp else 8.dp))
                        Text("Menunggu data...", color = BG, fontSize = if (compact) 12.sp else 14.sp)
                    }
                    else -> {
                        Icon(
                            Icons.Default.Login,
                            contentDescription = null,
                            modifier = Modifier.size(if (compact) 14.dp else 20.dp)
                        )
                        Spacer(modifier = Modifier.width(if (compact) 4.dp else 8.dp))
                        Text("Hubungkan", color = BG, fontWeight = FontWeight.Bold, fontSize = if (compact) 12.sp else 14.sp)
                    }
                }
            }
        }
    }
}

// ─── Reusable TextField Colors ──────────────────────────────
@Composable
private fun textFieldColors(authError: String?) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = if (authError != null) RED else GOLD,
    unfocusedBorderColor = if (authError != null) RED else BORDER,
    focusedLabelColor = if (authError != null) RED else GOLD,
    cursorColor = GOLD,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White
)

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
            // Compact: icon + text in a row
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    Icons.Default.Videocam,
                    contentDescription = null,
                    tint = if (isSelected) GOLD else MUTED,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    "Kamera $channel",
                    style = TextStyle(
                        color = if (isSelected) GOLD else MUTED,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 11.sp
                    )
                )
            }
        } else {
            // Full: icon above text in a column
            Column(
                modifier = Modifier.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.Videocam,
                    contentDescription = null,
                    tint = if (isSelected) GOLD else MUTED,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Kamera $channel",
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
