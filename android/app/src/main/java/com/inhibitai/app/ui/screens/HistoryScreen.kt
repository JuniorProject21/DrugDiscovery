package com.inhibitai.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inhibitai.app.data.Compound
import com.inhibitai.app.data.PredictionStatus
import com.inhibitai.app.ui.InhibitViewModel
import com.inhibitai.app.ui.components.*
import com.inhibitai.app.ui.theme.*

enum class HistoryFilter(val label: String, val key: String) {
    ALL("All", "all"), ACTIVE("Active", "active"), INACTIVE("Inactive", "inactive"),
    UNCERTAIN("Uncertain", "uncertain"), SAVED("Saved", "saved");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: ALL
    }
}

enum class SortOrder(val label: String) { NEWEST("Newest"), OLDEST("Oldest"), CONFIDENCE("Confidence") }

@Composable
fun HistoryScreen(
    vm: InhibitViewModel,
    contentPadding: PaddingValues,
    initialFilter: String,
    onOpenCompound: (String) -> Unit,
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable(initialFilter) { mutableStateOf(HistoryFilter.fromKey(initialFilter)) }
    var sort by rememberSaveable { mutableStateOf(SortOrder.NEWEST) }

    val results = vm.compounds
        .filter { c ->
            when (filter) {
                HistoryFilter.ALL -> true
                HistoryFilter.ACTIVE -> c.status == PredictionStatus.ACTIVE
                HistoryFilter.INACTIVE -> c.status == PredictionStatus.INACTIVE
                HistoryFilter.UNCERTAIN -> c.status == PredictionStatus.UNCERTAIN
                HistoryFilter.SAVED -> c.isSaved
            }
        }
        .filter { c ->
            query.isBlank() || listOfNotNull(c.name, c.smiles, c.commonName, c.iupacName).any { it.contains(query, true) }
        }
        .let { list ->
            when (sort) {
                SortOrder.NEWEST -> list.sortedByDescending { it.timestamp }
                SortOrder.OLDEST -> list.sortedBy { it.timestamp }
                SortOrder.CONFIDENCE -> list.sortedByDescending { it.confidence }
            }
        }

    LazyColumn(
        Modifier.fillMaxSize().background(Bg).padding(contentPadding),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            AppHeader(onBellClick = { Toast.makeText(context, "No new notifications", Toast.LENGTH_SHORT).show() })
        }
        item {
            Column {
                VSpace(14.dp)
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = TextPrimary)) { append("Prediction ") }
                        withStyle(SpanStyle(color = PurpleLight)) { append("History") }
                    },
                    fontSize = 38.sp, fontWeight = FontWeight.Bold,
                )
                Text("View and manage your past predictions.", fontSize = 18.sp, color = TextSecondary)
                VSpace(6.dp)
            }
        }
        vm.loadError?.let { error ->
            item { ErrorBanner(error, retrying = vm.isLoading, onRetry = vm::refresh) }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallStatCard(null, vm.total, "Total", Purple, Modifier.weight(1f)) { filter = HistoryFilter.ALL }
                SmallStatCard(Icons.Outlined.CheckCircle, vm.activeCount, "Active", Green, Modifier.weight(1f)) { filter = HistoryFilter.ACTIVE }
                SmallStatCard(Icons.Outlined.Cancel, vm.inactiveCount, "Inactive", Red, Modifier.weight(1f)) { filter = HistoryFilter.INACTIVE }
            }
        }
        item { SearchField(query) { query = it } }
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HistoryFilter.entries.forEach { f ->
                    FilterPill(f.label, f == filter, Modifier.widthIn(min = 76.dp)) { filter = f }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${results.size} results", fontSize = 17.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                SortDropdown(sort) { sort = it }
            }
        }
        items(results, key = { it.id }) { c ->
            HistoryCard(
                c,
                onOpen = { onOpenCompound(c.id) },
                onToggleSave = { vm.toggleSaved(c.id) },
                onDelete = { vm.delete(c.id) },
            )
        }
        if (results.isEmpty()) {
            item {
                Text(
                    when {
                        vm.compounds.isEmpty() && vm.isLoading -> "Loading your predictions…"
                        vm.compounds.isEmpty() -> "No predictions yet."
                        else -> "No predictions match your search."
                    },
                    color = TextSecondary, fontSize = 16.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun SmallStatCard(icon: ImageVector?, count: Int, label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    GlassCard(
        modifier,
        shape = RoundedCornerShape(18.dp),
        background = Brush.linearGradient(listOf(color.copy(alpha = 0.10f), CardBg)),
        borderColor = color.copy(alpha = 0.35f),
        onClick = onClick,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon == null) {
                FlaskTile(Modifier.size(40.dp), iconSize = 22.dp, corner = 12.dp)
            } else {
                Icon(icon, null, tint = color, modifier = Modifier.size(40.dp))
            }
            HSpace(8.dp)
            Column {
                Text("$count", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1)
                Text(label, fontSize = 14.sp, color = TextSecondary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(shape)
            .background(Color(0xFF100E1C))
            .border(1.dp, Purple.copy(alpha = 0.45f), shape)
            .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = TextSecondary, modifier = Modifier.size(28.dp))
        HSpace(14.dp)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Search compounds, e.g. XYZ-001...", color = TextMuted, fontSize = 17.sp, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 17.sp),
                cursorBrush = SolidColor(PurpleLight),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Close, "Clear", tint = TextSecondary) }
        }
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .height(50.dp)
            .clip(shape)
            .background(if (selected) PrimaryGradient else SolidColor(Color.Transparent))
            .border(1.dp, if (selected) Color.Transparent else CardBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 16.sp, color = if (selected) Color.White else TextPrimary, maxLines = 1)
    }
}

@Composable
private fun SortDropdown(selected: SortOrder, onSelect: (SortOrder) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Box {
        Row(
            Modifier
                .clip(shape)
                .border(1.dp, CardBorder, shape)
                .clickable { open = true }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.SwapVert, null, tint = TextPrimary, modifier = Modifier.size(22.dp))
            HSpace(8.dp)
            Text(selected.label, fontSize = 16.sp, color = TextPrimary)
            HSpace(14.dp)
            Icon(Icons.Filled.KeyboardArrowDown, null, tint = TextSecondary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(CardBg)) {
            SortOrder.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.label, color = if (s == selected) PurpleLight else TextPrimary) },
                    onClick = { onSelect(s); open = false },
                )
            }
        }
    }
}

@Composable
private fun HistoryCard(c: Compound, onOpen: () -> Unit, onToggleSave: () -> Unit, onDelete: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), onClick = onOpen) {
        Row(
            Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MoleculeThumb(c.canonicalSmiles, Modifier.size(width = 72.dp, height = 66.dp))
            HSpace(12.dp)
            Column(Modifier.weight(1f)) {
                Text(c.name, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                VSpace(4.dp)
                Text(c.formattedDate, fontSize = 13.sp, color = TextSecondary, maxLines = 2)
            }
            HSpace(6.dp)
            StatusPill(c.status, compact = true)
            HSpace(6.dp)
            Box(Modifier.width(1.dp).height(52.dp).background(CardBorder))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CompoundMenu(c.isSaved, onOpen, onToggleSave, onDelete)
                IconButton(onClick = onToggleSave, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (c.isSaved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        "Save", tint = if (c.isSaved) PurpleLight else TextSecondary,
                    )
                }
            }
        }
    }
}

