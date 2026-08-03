package com.saatiril.full.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.saatiril.full.data.AppTab
import com.saatiril.full.data.FullViewModel
import com.saatiril.full.ui.admin.AdminPanel
import com.saatiril.full.ui.mc.McPanel
import com.saatiril.full.ui.operator.OperatorPanel

// ─── Theme Colors ───────────────────────────────────────────
private val BG = Color(0xFF1a0b2e)
private val PANEL = Color(0xFF2a164a)
private val CARD = Color(0xFF3b2263)
private val BORDER = Color(0xFF533485)
private val GOLD = Color(0xFFd4af37)
private val MUTED = Color(0xFFc4b5fd)
private val CYAN = Color(0xFF06b6d4)

data class TabItem(
    val tab: AppTab,
    val label: String,
    val icon: ImageVector
)

@OptIn(androidx.compose.material.ExperimentalMaterialApi::class)
@Composable
fun MainScreen(
    viewModel: FullViewModel,
    hasCameraPermission: Boolean
) {
    var selectedTab by remember { mutableStateOf(AppTab.OPERATOR) }

    val tabs = listOf(
        TabItem(AppTab.ADMIN, "Admin", Icons.Default.Dashboard),
        TabItem(AppTab.MC, "MC", Icons.Default.QueueMusic),
        TabItem(AppTab.OPERATOR, "Operator", Icons.Default.CameraAlt)
    )

    Scaffold(
        containerColor = BG,
        bottomBar = {
            Column {
                // Connection health bar
                val connectionHealth by viewModel.connectionHealth.collectAsState()
                val healthColor = when {
                    !connectionHealth.connected -> Color(0xFFef4444)
                    connectionHealth.latencyMs < 0 -> MUTED
                    connectionHealth.latencyMs < 15 -> Color(0xFF4ade80)
                    connectionHealth.latencyMs < 30 -> GOLD
                    else -> Color(0xFFef4444)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PANEL)
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(healthColor, androidx.compose.foundation.shape.CircleShape)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (connectionHealth.connected) "Terhubung (${connectionHealth.latencyMs}ms)" else "Terputus",
                            style = TextStyle(fontSize = 10.sp, color = healthColor)
                        )
                    }
                    val role by viewModel.role.collectAsState()
                    val channel by viewModel.channel.collectAsState()
                    Text(
                        text = "${role.uppercase()} Ch.$channel",
                        style = TextStyle(fontSize = 10.sp, color = MUTED, fontWeight = FontWeight.Medium)
                    )
                }

                // Bottom Navigation
                androidx.compose.material.BottomNavigation(
                    backgroundColor = PANEL,
                    contentColor = GOLD
                ) {
                    tabs.forEach { tabItem ->
                        androidx.compose.material.BottomNavigationItem(
                            icon = { Icon(tabItem.icon, contentDescription = tabItem.label) },
                            label = {
                                Text(
                                    tabItem.label,
                                    style = TextStyle(fontSize = 11.sp)
                                )
                            },
                            selected = selectedTab == tabItem.tab,
                            onClick = { selectedTab = tabItem.tab },
                            selectedContentColor = GOLD,
                            unselectedContentColor = MUTED.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(BG)
        ) {
            when (selectedTab) {
                AppTab.ADMIN -> AdminPanel(viewModel = viewModel)
                AppTab.MC -> McPanel(viewModel = viewModel)
                AppTab.OPERATOR -> OperatorPanel(
                    viewModel = viewModel,
                    hasCameraPermission = hasCameraPermission
                )
            }
        }
    }
}
