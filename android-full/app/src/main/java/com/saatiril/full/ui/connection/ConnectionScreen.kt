package com.saatiril.full.ui.connection

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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saatiril.full.data.ConnectionState
import com.saatiril.full.data.FullViewModel
import com.saatiril.full.data.Roles
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

@Composable
fun ConnectionScreen(
    viewModel: FullViewModel,
    onConnected: () -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsState()
    
    var serverUrl by remember { mutableStateOf("http://192.168.1.100:3000") }
    var selectedRole by remember { mutableStateOf(Roles.OPERATOR) }
    var selectedChannel by remember { mutableStateOf(1) }
    var sessionPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    val isConnecting = connectionState == ConnectionState.CONNECTING ||
                       connectionState == ConnectionState.RECONNECTING ||
                       connectionState == ConnectionState.AUTHENTICATING

    // Auto-transition on authentication
    LaunchedEffect(connectionState) {
        when (connectionState) {
            ConnectionState.AUTHENTICATED, ConnectionState.WAITING_FOR_DATA -> {
                onConnected()
            }
            ConnectionState.AUTH_FAILED -> {
                errorMessage = "Autentikasi gagal. Periksa password."
            }
            else -> {}
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
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Logo / Title
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
                style = TextStyle(
                    fontSize = 14.sp,
                    color = MUTED,
                    letterSpacing = 4.sp
                )
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Connection Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(PANEL)
                    .border(BorderStroke(1.dp, BORDER), RoundedCornerShape(16.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Text(
                    text = "Koneksi ke Server",
                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                )

                // Server URL
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it; errorMessage = "" },
                    label = { Text("Server URL", color = MUTED) },
                    placeholder = { Text("http://192.168.1.100:3000", color = MUTED.copy(alpha = 0.5f)) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GOLD,
                        unfocusedBorderColor = BORDER,
                        cursorColor = GOLD
                    ),
                    leadingIcon = {
                        Icon(Icons.Default.Language, contentDescription = null, tint = MUTED)
                    }
                )

                // Role Selection
                Text(
                    text = "Peran (Role)",
                    style = TextStyle(fontSize = 13.sp, color = MUTED, fontWeight = FontWeight.Medium)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RoleButton("Admin", Roles.ADMIN, selectedRole == Roles.ADMIN) { selectedRole = Roles.ADMIN }
                    RoleButton("MC", Roles.MC, selectedRole == Roles.MC) { selectedRole = Roles.MC }
                    RoleButton("Operator", Roles.OPERATOR, selectedRole == Roles.OPERATOR) { selectedRole = Roles.OPERATOR }
                }

                // Channel Selection (only for MC and Operator)
                if (selectedRole != Roles.ADMIN) {
                    Text(
                        text = "Channel",
                        style = TextStyle(fontSize = 13.sp, color = MUTED, fontWeight = FontWeight.Medium)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ChannelButton("Ch. 1", 1, selectedChannel == 1) { selectedChannel = 1 }
                        ChannelButton("Ch. 2", 2, selectedChannel == 2) { selectedChannel = 2 }
                    }
                }

                // Session Password
                OutlinedTextField(
                    value = sessionPassword,
                    onValueChange = { sessionPassword = it; errorMessage = "" },
                    label = { Text("Password Sesi (opsional)", color = MUTED) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                    singleLine = true,
                    visualTransformation = if (showPassword) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GOLD,
                        unfocusedBorderColor = BORDER,
                        cursorColor = GOLD
                    ),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle password",
                                tint = MUTED
                            )
                        }
                    }
                )

                // Error Message
                if (errorMessage.isNotEmpty()) {
                    Text(
                        text = errorMessage,
                        style = TextStyle(fontSize = 12.sp, color = RED),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                // Connection Status Indicator
                if (connectionState != ConnectionState.DISCONNECTED) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val statusColor = when (connectionState) {
                            ConnectionState.CONNECTED, ConnectionState.AUTHENTICATED, ConnectionState.WAITING_FOR_DATA -> GREEN
                            ConnectionState.AUTH_FAILED -> RED
                            else -> GOLD
                        }
                        val statusText = when (connectionState) {
                            ConnectionState.CONNECTING -> "Menghubungkan..."
                            ConnectionState.RECONNECTING -> "Menghubungkan kembali..."
                            ConnectionState.CONNECTED -> "Terhubung"
                            ConnectionState.AUTHENTICATING -> "Autentikasi..."
                            ConnectionState.AUTHENTICATED -> "Terautentikasi"
                            ConnectionState.AUTH_FAILED -> "Autentikasi gagal"
                            ConnectionState.WAITING_FOR_DATA -> "Menunggu data..."
                            else -> ""
                        }
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(statusColor)
                        )
                        Text(
                            text = statusText,
                            style = TextStyle(fontSize = 12.sp, color = statusColor)
                        )
                    }
                }

                // Connect / Disconnect Button
                Button(
                    onClick = {
                        if (connectionState == ConnectionState.DISCONNECTED || connectionState == ConnectionState.AUTH_FAILED) {
                            errorMessage = ""
                            val url = serverUrl.trim()
                            if (url.isBlank()) {
                                errorMessage = "Masukkan URL server"
                                return@Button
                            }
                            try {
                                val uri = URI(url)
                                viewModel.connect(url, selectedRole, selectedChannel, sessionPassword.ifBlank { null })
                            } catch (e: Exception) {
                                errorMessage = "URL tidak valid: ${e.message}"
                            }
                        } else {
                            viewModel.disconnect()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isConnecting) BORDER else GOLD,
                        contentColor = BG
                    ),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isConnecting
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Menghubungkan...")
                    } else if (connectionState != ConnectionState.DISCONNECTED && connectionState != ConnectionState.AUTH_FAILED) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Putuskan")
                    } else {
                        Icon(Icons.Default.Link, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Hubungkan")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Version info
            Text(
                text = "v1.0.0-full-native",
                style = TextStyle(fontSize = 10.sp, color = MUTED.copy(alpha = 0.5f))
            )
        }
    }
}

@Composable
private fun RowScope.RoleButton(
    label: String,
    role: String,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) GOLD else CARD)
            .border(
                BorderStroke(1.dp, if (isSelected) GOLD else BORDER),
                RoundedCornerShape(8.dp)
            )
            .clickable { onSelect() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (isSelected) BG else Color.White
            )
        )
    }
}

@Composable
private fun RowScope.ChannelButton(
    label: String,
    channel: Int,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) CYAN else CARD)
            .border(
                BorderStroke(1.dp, if (isSelected) CYAN else BORDER),
                RoundedCornerShape(8.dp)
            )
            .clickable { onSelect() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (isSelected) BG else Color.White
            )
        )
    }
}
