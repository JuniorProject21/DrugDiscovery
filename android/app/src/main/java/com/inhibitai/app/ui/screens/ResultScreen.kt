package com.inhibitai.app.ui.screens

import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inhibitai.app.data.Compound
import com.inhibitai.app.data.PredictionStatus
import com.inhibitai.app.ui.InhibitViewModel
import com.inhibitai.app.ui.components.*
import com.inhibitai.app.ui.export.PdfReport
import com.inhibitai.app.ui.theme.*
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ResultScreen(
    vm: InhibitViewModel,
    compoundId: String,
    onBack: () -> Unit,
    onOpenCompound: (String) -> Unit,
    onPredictAnother: () -> Unit,
    onTab: (NavTab) -> Unit,
) {
    val compound = vm.compound(compoundId)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    MainScaffold(
        selected = NavTab.HOME,
        onTab = onTab,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .background(Bg)
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            // ---- top bar ----
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Back", tint = TextPrimary, modifier = Modifier.size(36.dp))
                }
                Text(
                    "Prediction Result", fontSize = 23.sp, fontWeight = FontWeight.Medium, color = TextPrimary,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { compound?.let { vm.toggleSaved(it.id) } }) {
                    Icon(
                        if (compound?.isSaved == true) Icons.Filled.Star else Icons.Outlined.StarOutline,
                        "Favourite", tint = PurpleLight, modifier = Modifier.size(34.dp),
                    )
                }
            }

            if (compound == null) {
                VSpace(80.dp)
                Text("This prediction no longer exists.", color = TextSecondary, modifier = Modifier.align(Alignment.CenterHorizontally))
                return@Column
            }

            VSpace(12.dp)
            ResultHeader(compound)
            VSpace(22.dp)
            ReliabilityNote(compound)
            CompoundCard(compound, onRename = { renaming = true })
            VSpace(16.dp)
            KeyInformationCard(compound)
            VSpace(16.dp)
            AdmeCard(compound)
            VSpace(16.dp)
            SimilarCompoundsCard(vm, compound, onOpenCompound)
            VSpace(2.dp)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlineActionButton(
                    if (compound.isSaved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    if (compound.isSaved) "Saved" else "Save Result",
                    onClick = { vm.toggleSaved(compound.id) },
                    modifier = Modifier.weight(1f),
                )
                OutlineActionButton(
                    Icons.Outlined.IosShare, if (exporting) "Exporting…" else "Export PDF",
                    onClick = {
                        if (!exporting) scope.launch {
                            exporting = true
                            try {
                                val structure = try {
                                    vm.structureImage(compound.canonicalSmiles, 600, 600, dark = false).asAndroidBitmap()
                                } catch (e: Exception) {
                                    null // the report still works without the drawing
                                }
                                PdfReport.share(context, compound, structure)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Couldn't create the PDF: ${e.message}", Toast.LENGTH_LONG).show()
                            } finally {
                                exporting = false
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            VSpace(18.dp)
            GradientButton("Predict Another Compound", onPredictAnother, leadingIcon = Icons.Filled.Autorenew, height = 64.dp)
            VSpace(24.dp)
        }
    }

    if (renaming && compound != null) {
        RenameDialog(
            initial = compound.name,
            onDismiss = { renaming = false },
            onSave = { vm.rename(compound.id, it); renaming = false },
        )
    }
}

@Composable
private fun ResultHeader(c: Compound) {
    val color = statusColor(c.status)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(150.dp)
                .shadow(28.dp, CircleShape, ambientColor = Purple, spotColor = Purple)
                .clip(CircleShape)
                .background(Color.White),
        ) {
            StructureImage(c.canonicalSmiles, dark = false, Modifier.fillMaxSize().padding(18.dp))
        }
        HSpace(16.dp)
        Column(Modifier.weight(1f)) {
            Text(
                c.name, fontSize = if (c.name.length <= 9) 32.sp else 24.sp, lineHeight = 30.sp,
                fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            VSpace(6.dp)
            val shape = RoundedCornerShape(50)
            Row(
                Modifier
                    .clip(shape)
                    .background(color.copy(alpha = 0.10f))
                    .border(1.5.dp, color.copy(alpha = 0.7f), shape)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(statusIcon(c.status), null, tint = color, modifier = Modifier.size(26.dp))
                HSpace(8.dp)
                Text(
                    c.status.label, fontSize = if (c.status.label.length > 8) 19.sp else 22.sp, color = color,
                    fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false,
                )
            }
            VSpace(8.dp)
            Text("${c.target} Inhibitor", fontSize = 17.sp, color = TextSecondary)
            VSpace(10.dp)
            ConfidenceCard(c.confidence, color)
        }
    }
}

@Composable
private fun ConfidenceCard(confidence: Int, color: Color) {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val fraction by animateFloatAsState(if (started) confidence / 100f else 0f, tween(900), label = "confidence")
    GlassCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Confidence", fontSize = 14.sp, color = TextSecondary, maxLines = 1, softWrap = false,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Text("$confidence%", fontSize = 18.sp, color = TextPrimary, fontWeight = FontWeight.Medium, maxLines = 1)
            }
            VSpace(10.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xFF26223A)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.85f), color))),
                )
            }
        }
    }
}

@Composable
private fun KeyInformationCard(c: Compound) {
    val statusCol = statusColor(c.status)
    // How close the compound is to the training chemistry: the team's model notes say to read this first.
    val reliabilityColor = when (c.reliability) {
        "High" -> Green
        "Medium" -> Yellow
        "Low" -> Red
        else -> TextSecondary
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 20.dp)) {
            CardTitle("Key Information", trailing = { Icon(Icons.Outlined.Info, null, tint = TextSecondary, modifier = Modifier.size(28.dp)) })
            VSpace(18.dp)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                InfoCell(Icons.Outlined.TrackChanges, PurpleLight, "Target", c.target, TextPrimary, Modifier.weight(1f))
                CellDivider()
                InfoCell(Icons.Outlined.Psychology, Blue, "Prediction", c.status.label, statusCol, Modifier.weight(1f))
                CellDivider()
                InfoCell(
                    Icons.Outlined.VerifiedUser, reliabilityColor,
                    "Reliability", c.reliability ?: "—", reliabilityColor, Modifier.weight(1f),
                )
                CellDivider()
                InfoCell(Icons.Outlined.Science, Yellow, "Model", c.modelVersion, TextPrimary, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AdmeCard(c: Compound) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 20.dp)) {
            CardTitle("ADME Overview", trailing = { Icon(Icons.Outlined.Info, null, tint = TextSecondary, modifier = Modifier.size(28.dp)) })
            VSpace(18.dp)
            val adme = c.adme
            if (adme == null) {
                Text(
                    "ADME predictions are not available yet.", fontSize = 16.sp, color = TextSecondary,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                return@Column
            }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                InfoCell(Icons.Outlined.ControlCamera, PurpleLight, "Absorption", adme.absorption.label, admeColor(adme.absorption), Modifier.weight(1f))
                CellDivider()
                InfoCell(Icons.Outlined.RadioButtonChecked, PurpleLight, "Distribution", adme.distribution.label, admeColor(adme.distribution), Modifier.weight(1f))
                CellDivider()
                InfoCell(Icons.Outlined.Science, PurpleLight, "Metabolism", adme.metabolism.label, admeColor(adme.metabolism), Modifier.weight(1f))
                CellDivider()
                InfoCell(Icons.Outlined.VerifiedUser, PurpleLight, "Toxicity", adme.toxicity.label, toxicityColor(adme.toxicity), Modifier.weight(1f))
            }
        }
    }
}

/** Name, IUPAC name, formula and identifiers. Names come from PubChem when the compound is known there. */
@Composable
private fun CompoundCard(c: Compound, onRename: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 20.dp)) {
            CardTitle("Compound", trailing = {
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onRename).padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Edit, null, tint = PurpleLight, modifier = Modifier.size(20.dp))
                    HSpace(6.dp)
                    Text("Rename", fontSize = 16.sp, color = PurpleLight)
                }
            })
            VSpace(12.dp)
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                DetailRow(
                    "Name",
                    c.commonName ?: if (c.pubchemCid != null) "No common name" else "Not in PubChem: likely a new compound",
                    valueColor = if (c.commonName != null) TextPrimary else TextSecondary,
                )
                c.iupacName?.let { DetailRow("IUPAC name", it, valueSize = 14.sp) }
                if (c.formula != null) {
                    DetailRow("Formula", listOfNotNull(c.formula, c.molecularWeight?.let {
                        String.format(Locale.US, "%.1f g/mol", it)
                    }).joinToString("  ·  "))
                }
                c.pubchemUrl?.let { url ->
                    DetailRow("PubChem", "CID ${c.pubchemCid}  ↗", valueColor = PurpleLight, onClick = { uriHandler.openUri(url) })
                }
                DetailRow("SMILES (tap to copy)", c.canonicalSmiles, valueSize = 13.sp, monospace = true, onClick = {
                    clipboard.setText(AnnotatedString(c.canonicalSmiles))
                    Toast.makeText(context, "SMILES copied", Toast.LENGTH_SHORT).show()
                })
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    valueColor: Color = TextPrimary,
    valueSize: TextUnit = 16.sp,
    monospace: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Text(label, fontSize = 13.sp, color = TextSecondary)
        VSpace(3.dp)
        Text(
            value, fontSize = valueSize, lineHeight = valueSize * 1.35f, color = valueColor,
            fontFamily = if (monospace) FontFamily.Monospace else null,
        )
    }
}

/** The user's earlier predictions that look like this compound: a quick "have I tested this before?". */
@Composable
private fun SimilarCompoundsCard(vm: InhibitViewModel, c: Compound, onOpen: (String) -> Unit) {
    val similar by produceState<List<Pair<Compound, Float>>>(emptyList(), c.id, vm.compounds.size) {
        value = vm.similarTo(c)
    }
    if (similar.isEmpty()) return
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 20.dp)) {
            CardTitle("Similar compounds you tested", trailing = {})
            VSpace(8.dp)
            similar.forEachIndexed { i, (other, similarity) ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(other.id) }.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MoleculeThumb(other.canonicalSmiles, Modifier.size(width = 60.dp, height = 54.dp))
                    HSpace(12.dp)
                    Column(Modifier.weight(1f)) {
                        Text(other.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (similarity >= 0.999f) "Same compound" else "${(similarity * 100).roundToInt()}% similar",
                            fontSize = 13.sp, color = TextSecondary,
                        )
                    }
                    StatusPill(other.status, compact = true)
                }
                if (i < similar.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = CardBorder)
            }
        }
    }
    VSpace(16.dp)
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val valid = name.isNotBlank() && name.trim().length <= 50
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        title = { Text("Rename compound", color = TextPrimary) },
        text = {
            Column {
                Text("Give it a name you'll recognise, for example 16F.", color = TextSecondary, fontSize = 15.sp)
                VSpace(14.dp)
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(50) }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = PurpleLight,
                        focusedBorderColor = Purple, unfocusedBorderColor = CardBorder,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }, enabled = valid) { Text("Save", color = if (valid) PurpleLight else TextMuted) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) } },
    )
}

/** Why a result should be read with care (report sections 4.3.5 and 6.5). Also printed in the PDF. */
internal fun reliabilityNotes(c: Compound): List<String> = buildList {
    if (c.isStubResult) {
        add("Placeholder result: no trained model is loaded on the server, so this is not a real prediction.")
    }
    if (c.status == PredictionStatus.UNCERTAIN) {
        add("The model could not commit to Active or Inactive at its stated error rate. " +
            String.format(Locale.US, "Its score is %.2f against a decision threshold of %.2f.", c.score, c.threshold))
    }
    if (c.reliability == "Low" || c.inDomain == false) {
        val sim = c.nearestSimilarity?.let { String.format(Locale.US, ": the closest training compound has %.2f similarity", it) }.orEmpty()
        add("Low reliability$sim. On chemistry this unfamiliar the model is much less accurate, " +
            "so treat this as a ranking to guide testing, not an answer.")
    }
}

@Composable
private fun ReliabilityNote(c: Compound) {
    val notes = reliabilityNotes(c)
    if (notes.isEmpty()) return
    GlassCard(
        Modifier.fillMaxWidth(),
        background = Brush.linearGradient(listOf(Yellow.copy(alpha = 0.10f), CardBg)),
        borderColor = Yellow.copy(alpha = 0.45f),
    ) {
        Row(Modifier.padding(16.dp)) {
            Icon(Icons.Outlined.WarningAmber, null, tint = Yellow, modifier = Modifier.size(26.dp))
            HSpace(12.dp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                notes.forEach { Text(it, fontSize = 15.sp, lineHeight = 21.sp, color = TextPrimary) }
            }
        }
    }
    VSpace(16.dp)
}

@Composable
private fun CardTitle(title: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 21.sp, fontWeight = FontWeight.Medium, color = TextPrimary, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun CellDivider() {
    Box(Modifier.fillMaxHeight().width(1.dp).background(CardBorder))
}

@Composable
private fun InfoCell(
    icon: ImageVector,
    iconTint: Color,
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(40.dp))
        VSpace(12.dp)
        Text(label, fontSize = 13.sp, color = TextSecondary, maxLines = 1, textAlign = TextAlign.Center)
        VSpace(6.dp)
        Text(
            value, fontSize = if (value.length > 8) 15.sp else 18.sp, color = valueColor, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

