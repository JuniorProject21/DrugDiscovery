package com.inhibitai.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inhibitai.app.ui.theme.*

enum class NavTab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOME("home", "Home", Icons.Outlined.Home, Icons.Filled.Home),
    HISTORY("history", "History", Icons.Outlined.Schedule, Icons.Filled.WatchLater),
    PREDICT("predict", "Predict", Icons.Outlined.AutoAwesome, Icons.Filled.AutoAwesome),
    INSIGHTS("insights", "Insights", Icons.Outlined.BarChart, Icons.Filled.BarChart),
    PROFILE("profile", "Profile", Icons.Outlined.Person, Icons.Filled.Person),
}

@Composable
fun BottomNavBar(tabs: List<NavTab>, selected: NavTab, onSelect: (NavTab) -> Unit) {
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(BgElevated)
            .border(1.dp, CardBorder, shape)
            .navigationBarsPadding()
            .padding(top = 10.dp, bottom = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            tabs.forEach { tab ->
                val sel = tab == selected
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onSelect(tab) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        if (sel) tab.selectedIcon else tab.icon, tab.label,
                        tint = if (sel) PurpleLight else TextSecondary,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        tab.label, fontSize = 15.sp,
                        color = if (sel) PurpleLight else TextSecondary,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Spacer(Modifier.height(5.dp))
                    Box(Modifier.size(5.dp).clip(CircleShape).background(if (sel) PurpleLight else Color.Transparent))
                }
            }
        }
    }
}

@Composable
fun MainScaffold(
    selected: NavTab,
    onTab: (NavTab) -> Unit,
    tabs: List<NavTab> = NavTab.entries,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = Bg,
        bottomBar = { BottomNavBar(tabs, selected, onTab) },
        content = content,
    )
}
