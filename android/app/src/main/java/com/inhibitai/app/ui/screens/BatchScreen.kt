package com.inhibitai.app.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inhibitai.app.data.BatchResult
import com.inhibitai.app.data.BatchRow
import com.inhibitai.app.data.PredictionStatus
import com.inhibitai.app.ui.BatchState
import com.inhibitai.app.ui.InhibitViewModel
import com.inhibitai.app.ui.components.*
import com.inhibitai.app.ui.export.CsvImport
import com.inhibitai.app.ui.theme.*
import java.util.Locale

/** How the screening table is ordered. Ranking by score is what screening a library is for. */
private enum class BatchSort(val label: String) {
    FILE("File order"), SCORE("Most active first"), PROBLEMS("Problems first")
}

/**
 * Screening a whole file of compounds at once: the researcher's own CSV in, a table of
 * predictions out, exportable as a CSV again (progress report section 6.6 did this by hand).
 */
@Composable
fun BatchScreen(
    vm: InhibitViewModel,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenCompound: (String) -> Unit,
) {
    val context = LocalContext.current
    var saveToHistory by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(BatchSort.FILE) }
    val state = vm.batchState

    // The system file picker. Some file managers label CSVs oddly, so plain text is accepted too.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.screenFile(uri, saveToHistory)
    }
    fun pick() = picker.launch(
        arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/csv",
            "application/vnd.ms-excel", "application/octet-stream"),
    )

    LazyColumn(
        Modifier.fillMaxSize().background(Bg).padding(contentPadding),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { AppHeader() }
        item {
            Column {
                VSpace(14.dp)
                Text("Batch Screening", fontSize = 38.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                VSpace(6.dp)
                Text(
                    "Upload a CSV of SMILES and get every prediction back as one table.",
                    fontSize = 18.sp, lineHeight = 25.sp, color = TextSecondary,
                )
            }
        }

        when (state) {
            BatchState.Idle -> {
                item { FormatCard() }
                item { SaveToHistoryRow(saveToHistory) { saveToHistory = it } }
                item {
                    GradientButton("Import file", onClick = { pick() },
                        leadingIcon = Icons.Outlined.UploadFile, height = 64.dp)
                }
            }

            BatchState.Reading -> item { BusyCard("Reading your file…") }

            is BatchState.Screening -> item {
                BusyCard(
                    "Screening ${state.count} ${if (state.count == 1) "compound" else "compounds"} " +
                        "from ${state.fileName}. A few hundred takes about a minute.",
                )
            }

            is BatchState.Failed -> {
                item {
                    GlassCard(
                        Modifier.fillMaxWidth(),
                        background = Brush.linearGradient(listOf(Red.copy(alpha = 0.10f), CardBg)),
                        borderColor = Red.copy(alpha = 0.45f),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Outlined.WarningAmber, null, tint = Red, modifier = Modifier.size(26.dp))
                            HSpace(12.dp)
                            Text(state.message, fontSize = 15.sp, lineHeight = 21.sp, color = TextPrimary)
                        }
                    }
                }
                item { FormatCard() }
                item {
                    GradientButton("Import another file", onClick = { vm.clearBatch(); pick() },
                        leadingIcon = Icons.Outlined.UploadFile, height = 64.dp)
                }
            }

            is BatchState.Done -> {
                val result = state.result
                item { SummaryCard(result) }
                if (state.warnings.isNotEmpty()) item { WarningsCard(state.warnings) }
                item {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        BatchSort.entries.forEach { option ->
                            ChoicePill(option.label, option == sort) { sort = option }
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        OutlineActionButton(
                            Icons.Outlined.IosShare, "Export results",
                            onClick = {
                                vm.exportBatch(context) { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        OutlineActionButton(
                            Icons.Outlined.UploadFile, "Import file",
                            onClick = { vm.clearBatch(); pick() },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                items(sorted(result.rows, sort), key = { it.index }) { row ->
                    BatchRowCard(
                        row,
                        // A saved row is an ordinary prediction, so it can be opened in full.
                        onOpen = row.savedId
                            ?.takeIf { id -> vm.compounds.any { it.id == id.toString() } }
                            ?.let { id -> { onOpenCompound(id.toString()) } },
                    )
                }
                item {
                    Text(
                        "Scores are the fraction of trees voting Active. Compounds the model is unsure " +
                            "about are marked Uncertain rather than guessed (report section 4.3.5).",
                        fontSize = 13.sp, lineHeight = 19.sp, color = TextMuted,
                    )
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Text(
                    "Back to a single prediction", fontSize = 16.sp, color = PurpleLight,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack).padding(8.dp),
                )
            }
        }
    }
}

private fun sorted(rows: List<BatchRow>, sort: BatchSort): List<BatchRow> = when (sort) {
    BatchSort.FILE -> rows
    // Rows that failed have no score, so they go last when ranking.
    BatchSort.SCORE -> rows.sortedWith(compareByDescending { it.score ?: -1f })
    BatchSort.PROBLEMS -> rows.sortedBy { it.ok }
}

@Composable
private fun FormatCard() {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text("What the file should look like", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            VSpace(10.dp)
            Text(
                "A column headed SMILES, and optionally one headed Name for your own labels. " +
                    "Up to ${CsvImport.MAX_ROWS} compounds per file.",
                fontSize = 15.sp, lineHeight = 21.sp, color = TextSecondary,
            )
            VSpace(12.dp)
            val shape = RoundedCornerShape(12.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(PurpleTint)
                    .border(1.dp, CardBorder, shape)
                    .padding(12.dp),
            ) {
                listOf(
                    "Name,SMILES",
                    "16F,NC(=O)c1ccccc1",
                    "17F,Cn1cnc2c1c(=O)n(C)c(=O)n2C",
                ).forEach {
                    Text(it, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = TextPrimary)
                }
            }
            VSpace(10.dp)
            Text(
                "A file of plain SMILES, one per line, works too. Lines that can't be read come back " +
                    "with the reason instead of stopping the rest.",
                fontSize = 14.sp, lineHeight = 20.sp, color = TextMuted,
            )
        }
    }
}

@Composable
private fun SaveToHistoryRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), onClick = { onChange(!checked) }) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Add every row to my history", fontSize = 17.sp, color = TextPrimary)
                VSpace(2.dp)
                Text(
                    if (checked) "Each compound becomes a saved prediction you can open and export."
                    else "Off: the table is shown here only, and nothing is added to your history.",
                    fontSize = 14.sp, lineHeight = 19.sp, color = TextSecondary,
                )
            }
            HSpace(12.dp)
            Switch(
                checked = checked, onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White, checkedTrackColor = Purple,
                    uncheckedTrackColor = TrackBg, uncheckedBorderColor = CardBorder,
                ),
            )
        }
    }
}

@Composable
private fun BusyCard(message: String) {
    GlassCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(color = PurpleLight, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
            HSpace(16.dp)
            Text(message, fontSize = 16.sp, lineHeight = 22.sp, color = TextPrimary)
        }
    }
}

@Composable
private fun SummaryCard(result: BatchResult) {
    GlassCard(Modifier.fillMaxWidth(), background = TotalCardGradient, borderColor = Purple.copy(alpha = 0.45f)) {
        Column(Modifier.padding(18.dp)) {
            Text(result.fileName, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            VSpace(14.dp)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                SummaryCell("${result.succeeded}", "Predicted", TextPrimary, Modifier.weight(1f))
                Box(Modifier.fillMaxHeight().width(1.dp).background(CardBorder))
                SummaryCell("${result.activeCount}", "Active", Green, Modifier.weight(1f))
                Box(Modifier.fillMaxHeight().width(1.dp).background(CardBorder))
                SummaryCell("${result.failed}", "Problems", if (result.failed > 0) Red else TextSecondary, Modifier.weight(1f))
            }
            if (result.saved) {
                VSpace(12.dp)
                Text(
                    "Added to your history, so these results are on the Home and Insights screens too.",
                    fontSize = 14.sp, lineHeight = 20.sp, color = PurpleText,
                )
            }
        }
    }
}

@Composable
private fun SummaryCell(value: String, label: String, color: Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = 14.sp, color = TextSecondary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun WarningsCard(warnings: List<String>) {
    GlassCard(
        Modifier.fillMaxWidth(),
        background = Brush.linearGradient(listOf(Yellow.copy(alpha = 0.10f), CardBg)),
        borderColor = Yellow.copy(alpha = 0.45f),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.WarningAmber, null, tint = Yellow, modifier = Modifier.size(24.dp))
            HSpace(12.dp)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                warnings.forEach { Text(it, fontSize = 14.sp, lineHeight = 20.sp, color = TextPrimary) }
            }
        }
    }
}

/** One row of the table; tapping it shows the structure and the numbers behind the verdict. */
@Composable
private fun BatchRowCard(row: BatchRow, onOpen: (() -> Unit)?) {
    var expanded by remember(row.index) { mutableStateOf(false) }
    GlassCard(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        borderColor = if (row.ok) CardBorder else Red.copy(alpha = 0.40f),
        onClick = { expanded = !expanded },
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(PurpleTint),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${row.index + 1}", fontSize = 13.sp, color = PurpleText, fontWeight = FontWeight.Medium)
                }
                HSpace(12.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        row.displayName, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        row.inputSmiles, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = TextSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                HSpace(8.dp)
                if (row.status != null) {
                    Column(horizontalAlignment = Alignment.End) {
                        StatusPill(row.status, compact = true)
                        row.confidence?.let {
                            VSpace(3.dp)
                            Text("$it% confident", fontSize = 12.sp, color = TextSecondary)
                        }
                    }
                } else {
                    Text("Can't read", fontSize = 14.sp, color = Red, fontWeight = FontWeight.Medium)
                }
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    null, tint = TextSecondary, modifier = Modifier.size(24.dp),
                )
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 12.dp)) {
                    HorizontalDivider(color = CardBorder)
                    VSpace(12.dp)
                    if (!row.ok) {
                        Text(row.error.orEmpty(), fontSize = 14.sp, lineHeight = 20.sp, color = Red)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MoleculeThumb(row.canonicalSmiles.orEmpty(), Modifier.size(width = 86.dp, height = 76.dp))
                            HSpace(14.dp)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                row.score?.let { score ->
                                    Text(
                                        String.format(Locale.US, "Score %.3f  ·  threshold %.3f", score, row.threshold ?: 0f),
                                        fontSize = 14.sp, color = TextPrimary,
                                    )
                                }
                                val facts = listOfNotNull(
                                    row.formula,
                                    row.molecularWeight?.let { String.format(Locale.US, "%.1f g/mol", it) },
                                ).joinToString("  ·  ")
                                if (facts.isNotEmpty()) Text(facts, fontSize = 14.sp, color = TextSecondary)
                                row.reliability?.let {
                                    Text(
                                        "Reliability: $it", fontSize = 14.sp,
                                        color = when (it) {
                                            "High" -> Green
                                            "Medium" -> Yellow
                                            else -> Red
                                        },
                                    )
                                }
                            }
                        }
                        if (onOpen != null) {
                            VSpace(10.dp)
                            Text(
                                "Open the full result  ›", fontSize = 15.sp, color = PurpleLight,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(onClick = onOpen)
                                    .padding(vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
