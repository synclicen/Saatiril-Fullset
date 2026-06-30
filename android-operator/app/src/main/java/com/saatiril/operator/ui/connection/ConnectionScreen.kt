package com.saatiril.operator.ui.connection

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
    val context = LocalContext.current
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
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BG),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .fillMaxHeight(0.9f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // ─── Logo / Title ─────────────────────────────────
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
                style = TextStyle(
                    fontSize = 16.sp,
                    color = MUTED
                )
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // ─── Connection Card ──────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PANEL),
                shape = RoundedCornerShape(16.dp),
                border = ButtonDefaults.outlinedButtonBorder(enabled = true)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Title
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Wifi,
                            contentDescription = null,
                            tint = CYAN,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            "Koneksi ke Server",
                            style = TextStyle(
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )
                    }
                    
                    // Server IP
                    OutlinedTextField(
                        value = serverIp,
                        onValueChange = { serverIp = it },
                        label = { Text("IP Server", color = MUTED) },
                        placeholder = { Text("192.168.1.100", color = MUTED.copy(alpha = 0.5f)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GOLD,
                            unfocusedBorderColor = BORDER,
                            focusedLabelColor = GOLD,
                            cursorColor = GOLD,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        leadingIcon = {
                            Icon(Icons.Default.Router, contentDescription = null, tint = MUTED)
                        }
                    )
                    
                    // Server Port
                    OutlinedTextField(
                        value = serverPort,
                        onValueChange = { serverPort = it },
                        label = { Text("Port", color = MUTED) },
                        placeholder = { Text("3003", color = MUTED.copy(alpha = 0.5f)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = GOLD,
                            unfocusedBorderColor = BORDER,
                            focusedLabelColor = GOLD,
                            cursorColor = GOLD,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        leadingIcon = {
                            Icon(Icons.Default.Cable, contentDescription = null, tint = MUTED)
                        }
                    )
                    
                    // Channel Selection
                    Text(
                        "Channel Kamera:",
                        style = TextStyle(color = MUTED, fontSize = 14.sp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Channel 1
                        ChannelButton(
                            channel = 1,
                            isSelected = selectedChannel == 1,
                            onClick = { selectedChannel = 1 }
                        )
                        // Channel 2
                        ChannelButton(
                            channel = 2,
                            isSelected = selectedChannel == 2,
                            onClick = { selectedChannel = 2 }
                        )
                    }
                    
                    // Password (if needed)
                    if (showPasswordPrompt || passwordRequired) {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password Sesi", color = MUTED) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = if (authError != null) RED else GOLD,
                                unfocusedBorderColor = if (authError != null) RED else BORDER,
                                focusedLabelColor = if (authError != null) RED else GOLD,
                                cursorColor = GOLD,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            leadingIcon = {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = if (authError != null) RED else MUTED)
                            }
                        )
                        
                        if (errorMessage != null) {
                            Text(
                                errorMessage ?: "",
                                style = TextStyle(color = RED, fontSize = 13.sp)
                            )
                        }
                    }
                    
                    // Error message (non-auth)
                    if (connectionState == ConnectionState.DISCONNECTED && errorMessage != null && !passwordRequired) {
                        Text(
                            errorMessage ?: "",
                            style = TextStyle(color = RED, fontSize = 13.sp)
                        )
                    }
                    
                    // Connect Button
                    Button(
                        onClick = {
                            errorMessage = null
                            val ip = serverIp.trim()
                            val port = serverPort.trim().ifEmpty { "3003" }
                            
                            if (ip.isEmpty()) {
                                errorMessage = "Masukkan IP server"
                                return@Button
                            }
                            
                            val url = "http://$ip:$port"
                            val pwd = if (showPasswordPrompt || passwordRequired) password.trim().ifEmpty { null } else null
                            
                            viewModel.connect(url, selectedChannel, pwd)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GOLD),
                        shape = RoundedCornerShape(12.dp),
                        enabled = connectionState != ConnectionState.CONNECTING && 
                                  connectionState != ConnectionState.AUTHENTICATING &&
                                  connectionState != ConnectionState.WAITING_FOR_DATA
                    ) {
                        when (connectionState) {
                            ConnectionState.CONNECTING, ConnectionState.AUTHENTICATING -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = BG,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Menghubungkan...", color = BG)
                            }
                            ConnectionState.WAITING_FOR_DATA -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = BG,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Menunggu data...", color = BG)
                            }
                            else -> {
                                Icon(Icons.Default.Login, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Hubungkan", color = BG, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // ─── Camera Status ────────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = PANEL),
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        if (uvcDeviceAttached) Icons.Default.Usb else Icons.Default.Videocam,
                        contentDescription = null,
                        tint = if (uvcDeviceAttached) GREEN else MUTED,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            if (uvcDeviceAttached) "Capture Card Terdeteksi" else "Kamera Built-in",
                            style = TextStyle(color = Color.White, fontWeight = FontWeight.Medium)
                        )
                        Text(
                            if (uvcDeviceAttached) "USB HDMI Capture Card siap digunakan" 
                            else "Sambungkan capture card via USB untuk kamera eksternal",
                            style = TextStyle(color = MUTED, fontSize = 12.sp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelButton(
    channel: Int,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .border(
                width = 2.dp,
                color = if (isSelected) GOLD else BORDER,
                shape = RoundedCornerShape(12.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) CARD.copy(alpha = 0.8f) else PANEL
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
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
