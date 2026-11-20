package com.inhibitai.app.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Undo
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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

    // Which rows are ticked. Empty means the screen is in its normal, one-tap-to-open mode.
    val picked = remember { mutableStateListOf<String>() }
    val selecting = picked.isNotEmpty()

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

    // A row that was deleted, or filtered out of view, shouldn't stay ticked behind the scenes.
    val visibleIds = results.map { it.id }.toSet()
    LaunchedEffect(visibleIds) { picked.retainAll(visibleIds) }

    // Back leaves selection mode instead of leaving the screen.
    BackHandler(enabled = selecting) { picked.clear() }

    Box(Modifier.fillMaxSize().background(Bg).padding(contentPadding)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                AppHeader()
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
            // Results read from the device because the server couldn't be reached.
            vm.offlineNotice?.let { notice ->
                item { OfflineBanner(notice, retrying = vm.isLoading, onRetry = vm::refresh) }
            }
            item {
                // Stacked at a large font size, where three tiles in a row would clip their numbers.
                StatCardRow(
                    stacked = largeText(),
                    total = { modifier ->
                        SmallStatCard(null, vm.total, "Total", Purple, modifier) { filter = HistoryFilter.ALL }
                    },
                    active = { modifier ->
                        SmallStatCard(Icons.Outlined.CheckCircle, vm.activeCount, "Active", Green, modifier) {
                            filter = HistoryFilter.ACTIVE
                        }
                    },
                    inactive = { modifier ->
                        SmallStatCard(Icons.Outlined.Cancel, vm.inactiveCount, "Inactive", Red, modifier) {
                            filter = HistoryFilter.INACTIVE
                        }
                    },
                )
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
                if (selecting) {
                    val chosen = results.filter { it.id in picked }
                    SelectionBar(
                        count = chosen.size,
                        allPicked = chosen.size == results.size,
                        exporting = vm.exportingHistory,
                        onSelectAll = { picked.clear(); picked.addAll(results.map { it.id }) },
                        onClear = { picked.clear() },
                        onExport = { vm.exportHistory(context, chosen) },
                        onDelete = { vm.delete(chosen.map { it.id }); picked.clear() },
                    )
                } else {
                    // Exports exactly what's listed, so the file matches the filter and search above.
                    val export = @Composable {
                        ExportCsvButton(busy = vm.exportingHistory) { vm.exportHistory(context, results) }
                    }
                    val count = @Composable {
                        Text("${results.size} results", fontSize = 17.sp, color = TextPrimary, maxLines = 1)
                    }
                    if (largeText()) {
                        // The count would be squeezed to one character per line next to the buttons.
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            count()
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                export()
                                SortDropdown(sort) { sort = it }
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { count() }
                            export()
                            HSpace(8.dp)
                            SortDropdown(sort) { sort = it }
                        }
                    }
                }
            }
            items(results, key = { it.id }) { c ->
                // Swiping is off while picking rows, so a sideways drag can't delete the wrong one.
                SwipeToDelete(enabled = !selecting, onDelete = { vm.delete(c.id) }) {
                    HistoryCard(
                        c,
                        selecting = selecting,
                        isPicked = c.id in picked,
                        onOpen = {
                            if (selecting) {
                                if (!picked.remove(c.id)) picked.add(c.id)
                            } else {
                                onOpenCompound(c.id)
                            }
                        },
                        onStartSelecting = { if (c.id !in picked) picked.add(c.id) },
                        onToggleSave = { vm.toggleSaved(c.id) },
                        onDelete = { vm.delete(c.id) },
                    )
                }
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
        // Nothing has reached the server yet, so this bar is the way back.
        vm.pendingDelete?.let { pending ->
            UndoBar(
                label = pending.label,
                onUndo = vm::undoDelete,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            )
        }
    }
}

/** The three counters: side by side normally, one under the other at a large font size. */
@Composable
private fun StatCardRow(
    stacked: Boolean,
    total: @Composable (Modifier) -> Unit,
    active: @Composable (Modifier) -> Unit,
    inactive: @Composable (Modifier) -> Unit,
) {
    if (stacked) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            total(Modifier.fillMaxWidth())
            active(Modifier.fillMaxWidth())
            inactive(Modifier.fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            total(Modifier.weight(1f))
            active(Modifier.weight(1f))
            inactive(Modifier.weight(1f))
        }
    }
}

/**
 * Wraps a row so a right-to-left swipe deletes it. The row leaves the list straight away and the
 * undo bar holds the delete back, so there's no "are you sure?" in the way.
 */
@Composable
private fun SwipeToDelete(enabled: Boolean, onDelete: () -> Unit, row: @Composable () -> Unit) {
    if (!enabled) {
        row()
        return
    }
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Row(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Red.copy(alpha = 0.16f))
                    .padding(horizontal = 22.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.DeleteOutline, null, tint = Red, modifier = Modifier.size(26.dp))
                HSpace(8.dp)
                Text("Delete", fontSize = 16.sp, color = Red, fontWeight = FontWeight.SemiBold)
            }
        },
    ) {
        row()
    }
}

/** What the toolbar turns into once rows are ticked. Two lines, so it survives a large font size. */
@Composable
private fun SelectionBar(
    count: Int,
    allPicked: Boolean,
    exporting: Boolean,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    GlassCard(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(18.dp),
        background = Brush.linearGradient(listOf(Purple.copy(alpha = 0.12f), CardBg)),
        borderColor = PurpleLight.copy(alpha = 0.45f),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$count selected", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    color = TextPrimary, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Close, "Stop selecting", tint = TextSecondary)
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmallPillButton(
                    icon = Icons.Outlined.Checklist,
                    text = if (allPicked) "Select none" else "Select all",
                    tint = TextPrimary,
                    onClick = if (allPicked) onClear else onSelectAll,
                )
                SmallPillButton(
                    icon = Icons.Outlined.IosShare,
                    text = "Export",
                    tint = PurpleLight,
                    busy = exporting,
                    onClick = onExport,
                )
                SmallPillButton(
                    icon = Icons.Outlined.DeleteOutline,
                    text = "Delete",
                    tint = Red,
                    onClick = onDelete,
                )
            }
        }
    }
}

@Composable
private fun SmallPillButton(
    icon: ImageVector,
    text: String,
    tint: Color,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .clip(shape)
            .border(1.dp, tint.copy(alpha = 0.55f), shape)
            .clickable(enabled = !busy, onClick = onClick, role = Role.Button)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(color = tint, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        } else {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        }
        HSpace(6.dp)
        Text(text, fontSize = 16.sp, color = tint, maxLines = 1)
    }
}

/** Sits over the list while a delete can still be taken back. */
@Composable
private fun UndoBar(label: String, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(BgElevated)
            .border(1.dp, CardBorder, shape)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
            // Read out by TalkBack as soon as it appears, so the way back isn't missed.
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 16.sp, color = TextPrimary, modifier = Modifier.weight(1f))
        HSpace(8.dp)
        SmallPillButton(icon = Icons.Outlined.Undo, text = "Undo", tint = PurpleLight, onClick = onUndo)
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
        // One phrase instead of "24" then "Total", and it says what the tap does.
        clickLabel = "Show $label predictions",
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 16.dp)
                .clearAndSetSemantics { contentDescription = "$count $label" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(SearchFieldBg)
            .border(1.dp, Purple.copy(alpha = 0.45f), shape)
            .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
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
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Search compounds" },
            )
        }
        if (value.isNotEmpty()) {
            IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Close, "Clear the search", tint = TextSecondary) }
        }
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .heightIn(min = 50.dp)
            .clip(shape)
            .background(if (selected) PrimaryGradient else SolidColor(Color.Transparent))
            .border(1.dp, if (selected) Color.Transparent else CardBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            // Reads as "All, selected" rather than just "All".
            .semantics {
                role = Role.Tab
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 16.sp, color = if (selected) Color.White else TextPrimary, maxLines = 1)
    }
}

/** Hands the listed predictions to the share sheet as a spreadsheet, for Excel. */
@Composable
private fun ExportCsvButton(busy: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .clip(shape)
            .border(1.dp, PurpleLight.copy(alpha = 0.6f), shape)
            .clickable(enabled = !busy, onClick = onClick, role = Role.Button)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics { contentDescription = "Export these results to a spreadsheet" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(color = PurpleLight, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        } else {
            Icon(Icons.Outlined.IosShare, null, tint = PurpleLight, modifier = Modifier.size(20.dp))
        }
        HSpace(6.dp)
        Text("Export", fontSize = 16.sp, color = PurpleLight)
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
                .clickable(onClick = { open = true }, role = Role.DropdownList)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .semantics { contentDescription = "Sort by ${selected.label}" },
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
private fun HistoryCard(
    c: Compound,
    selecting: Boolean,
    isPicked: Boolean,
    onOpen: () -> Unit,
    onStartSelecting: () -> Unit,
    onToggleSave: () -> Unit,
    onDelete: () -> Unit,
) {
    GlassCard(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        borderColor = if (isPicked) PurpleLight else CardBorder,
        background = if (isPicked) {
            Brush.linearGradient(listOf(Purple.copy(alpha = 0.16f), CardBg))
        } else {
            SolidColor(CardBg)
        },
        onClick = onOpen,
        onLongClick = onStartSelecting,
        clickLabel = if (selecting) "Tick or untick this prediction" else "Open this prediction",
        longClickLabel = "Start selecting predictions",
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                Icon(
                    if (isPicked) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    null,
                    tint = if (isPicked) PurpleLight else TextSecondary,
                    modifier = Modifier.size(26.dp),
                )
                HSpace(10.dp)
            } else if (!largeText()) {
                // At a large font size the name and date need the whole row.
                MoleculeThumb(c.canonicalSmiles, Modifier.size(width = 72.dp, height = 66.dp))
                HSpace(12.dp)
            }
            Column(Modifier.weight(1f)) {
                Text(c.name, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                VSpace(4.dp)
                Text(c.formattedDate, fontSize = 13.sp, color = TextSecondary, maxLines = 2)
            }
            HSpace(6.dp)
            StatusPill(c.status, compact = true)
            if (!selecting) {
                HSpace(6.dp)
                Box(Modifier.width(1.dp).height(52.dp).background(CardBorder))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CompoundMenu(
                        c.isSaved, onOpen, onToggleSave, onDelete,
                        label = "More actions for ${c.name}",
                        onSelect = onStartSelecting,
                    )
                    IconButton(onClick = onToggleSave, modifier = Modifier.size(36.dp)) {
                        Icon(
                            if (c.isSaved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                            if (c.isSaved) "Remove ${c.name} from saved" else "Save ${c.name}",
                            tint = if (c.isSaved) PurpleLight else TextSecondary,
                        )
                    }
                }
            }
        }
    }
}
