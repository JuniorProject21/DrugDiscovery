package com.inhibitai.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inhibitai.app.data.Compound
import com.inhibitai.app.data.PredictionStatus
import com.inhibitai.app.ui.InhibitViewModel
import com.inhibitai.app.ui.components.*
import com.inhibitai.app.ui.theme.*

@Composable
fun HomeScreen(
    vm: InhibitViewModel,
    contentPadding: PaddingValues,
    onNewPrediction: () -> Unit,
    onOpenCompound: (String) -> Unit,
    onOpenHistory: (String) -> Unit,
    onOpenInsights: () -> Unit,
) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().background(Bg).padding(contentPadding)) {
        MoleculeBackdrop(HeaderScene, Modifier.align(Alignment.TopEnd).size(250.dp, 430.dp))
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            VSpace(12.dp)
            AppHeader(onBellClick = { Toast.makeText(context, "No new notifications", Toast.LENGTH_SHORT).show() })
            VSpace(30.dp)
            Text("Welcome back,", fontSize = 26.sp, color = Color(0xFFD9D5EA))
            Text(vm.userName, fontSize = 50.sp, lineHeight = 56.sp, fontWeight = FontWeight.Bold, color = PurpleLight)
            VSpace(8.dp)
            Text(
                "Advancing drug discovery\nwith AI-powered insights.",
                fontSize = 18.sp, lineHeight = 25.sp, color = TextSecondary,
            )
            vm.loadError?.let {
                VSpace(16.dp)
                ErrorBanner(it, retrying = vm.isLoading, onRetry = vm::refresh)
            }
            VSpace(28.dp)
            TotalPredictedCard(vm.total, onClick = { onOpenHistory("all") })
            VSpace(14.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MiniStatCard(vm.activeCount, PredictionStatus.ACTIVE, { onOpenHistory("active") }, Modifier.weight(1f))
                MiniStatCard(vm.inactiveCount, PredictionStatus.INACTIVE, { onOpenHistory("inactive") }, Modifier.weight(1f))
            }
            VSpace(14.dp)
            NewPredictionBanner(onNewPrediction)
            VSpace(26.dp)
            SectionHeader("Recent Activity", "View All") { onOpenHistory("all") }
            VSpace(12.dp)
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
                    val recent = vm.compounds.sortedByDescending { it.timestamp }.take(3)
                    if (recent.isEmpty()) {
                        Text(
                            if (vm.isLoading) "Loading your predictions…" else "No predictions yet. Start with a SMILES string.",
                            color = TextSecondary, fontSize = 15.sp, modifier = Modifier.padding(vertical = 18.dp),
                        )
                    }
                    recent.forEachIndexed { i, c ->
                        RecentRow(
                            c,
                            onOpen = { onOpenCompound(c.id) },
                            onToggleSave = { vm.toggleSaved(c.id) },
                            onDelete = { vm.delete(c.id) },
                        )
                        if (i < recent.lastIndex) HorizontalDivider(color = CardBorder)
                    }
                }
            }
            VSpace(14.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                InfoTile(Icons.Outlined.BookmarkBorder, "Saved Results", "${vm.savedCount}", { onOpenHistory("saved") }, Modifier.weight(1f))
                InfoTile(Icons.Outlined.BarChart, "This Week", "${vm.within(7).size}", onOpenInsights, Modifier.weight(1f))
            }
            VSpace(24.dp)
        }
    }
}

@Composable
private fun RecentRow(c: Compound, onOpen: () -> Unit, onToggleSave: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MoleculeThumb(c.canonicalSmiles, Modifier.size(width = 72.dp, height = 62.dp))
        HSpace(14.dp)
        Column(Modifier.weight(1f)) {
            Text(c.name, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            VSpace(4.dp)
            Text(c.formattedDate, fontSize = 13.sp, color = TextSecondary, maxLines = 2)
        }
        HSpace(8.dp)
        StatusPill(c.status, compact = true)
        CompoundMenu(c.isSaved, onOpen, onToggleSave, onDelete)
    }
}
