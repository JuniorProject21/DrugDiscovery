package com.inhibitai.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inhibitai.app.ui.InhibitViewModel
import com.inhibitai.app.ui.components.*
import com.inhibitai.app.ui.theme.*
import java.util.Locale

// The prototype has no Profile design, so this follows the same visual language.
@Composable
fun ProfileScreen(
    vm: InhibitViewModel,
    contentPadding: PaddingValues,
    onAccountDetails: () -> Unit,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<ProfileDialog?>(null) }

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
            Box(
                Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFF9F67FF), Color(0xFF5B21B6))))
                    .border(2.dp, PurpleLight.copy(alpha = 0.6f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(initial(vm.userName), fontSize = 40.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            VSpace(14.dp)
            Text(vm.userName, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text(vm.userEmail, fontSize = 16.sp, color = TextSecondary)
            VSpace(24.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProfileStat("${vm.total}", "Predictions", Modifier.weight(1f))
                ProfileStat("${vm.activeCount}", "Active hits", Modifier.weight(1f))
                ProfileStat("${vm.savedCount}", "Saved", Modifier.weight(1f))
            }
            VSpace(20.dp)
            GlassCard(Modifier.fillMaxWidth()) {
                Column {
                    SettingRow(Icons.Outlined.Person, "Account details", onClick = onAccountDetails)
                    HorizontalDivider(color = CardBorder)
                    SettingRow(Icons.Outlined.Tune, "Model", value = vm.modelVersion ?: "Unavailable",
                        onClick = { dialog = ProfileDialog.MODEL })
                    HorizontalDivider(color = CardBorder)
                    SettingRow(Icons.Outlined.SupportAgent, "Help: reading results", onClick = { dialog = ProfileDialog.HELP })
                    HorizontalDivider(color = CardBorder)
                    SettingRow(Icons.Outlined.Info, "About InhibitAI", value = "v1.0", onClick = { dialog = ProfileDialog.ABOUT })
                }
            }
            VSpace(16.dp)
            GlassCard(Modifier.fillMaxWidth(), borderColor = Red.copy(alpha = 0.35f), onClick = onLogout) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.PowerSettingsNew, null, tint = Red)
                    HSpace(14.dp)
                    Text("Log out", fontSize = 18.sp, color = Red)
                }
            }
            VSpace(24.dp)
        }
    }

    when (dialog) {
        ProfileDialog.MODEL -> InfoDialog("Model", modelText(vm)) { dialog = null }
        ProfileDialog.HELP -> InfoDialog("Reading a result", HELP_TEXT) { dialog = null }
        ProfileDialog.ABOUT -> InfoDialog("About InhibitAI", ABOUT_TEXT) { dialog = null }
        null -> Unit
    }
}

private enum class ProfileDialog { MODEL, HELP, ABOUT }

private fun modelText(vm: InhibitViewModel): String {
    val info = vm.modelInfo ?: return "The server hasn't reported a model. Check that the backend is running."
    if (info.isStub) return "The server has no trained model loaded, so results are placeholders."
    return buildString {
        appendLine("Version: ${info.version}")
        appendLine("Extra Trees classifier on Morgan fingerprints (radius 3, 2048 bits).")
        info.threshold?.let { appendLine(String.format(Locale.US, "Decision threshold: %.3f", it)) }
        info.conformalConfidence?.let {
            appendLine(String.format(Locale.US, "Commits to Active or Inactive only at %.0f%% confidence; otherwise it says Uncertain.", it * 100))
        }
        if (info.minSimilarity != null && info.highSimilarity != null) {
            appendLine(String.format(Locale.US, "Reliability: High from %.1f similarity to the nearest training compound, Medium from %.1f, Low below.",
                info.highSimilarity, info.minSimilarity))
        }
        if (info.metrics.isNotEmpty()) {
            appendLine()
            info.metrics.forEach { (key, value) -> appendLine("${key.replace('_', ' ')}: $value") }
        }
    }.trim()
}

private const val HELP_TEXT =
    "Verdict: Active or Inactive when the model is confident enough, Uncertain when it can't rule either out.\n\n" +
        "Reliability: how similar the compound is to anything the model was trained on. Read this first: on " +
        "Low-reliability compounds the model is much less accurate, so treat those results as a ranking to guide " +
        "testing, not an answer.\n\n" +
        "Score: the fraction of trees voting Active. It isn't a probability of being right.\n\n" +
        "Names come from PubChem. New compounds have no name there: tap Rename on the result to label them."

private const val ABOUT_TEXT =
    "InhibitAI v1.0 predicts whether a compound inhibits human PARP-1, from its SMILES.\n\n" +
        "Senior project, Computer Science Department, College of Computing and Informatics, University of Sharjah " +
        "(Fall 2026/2027).\n\n" +
        "Team: Ayah Daoud Aldhaher, Iman Musharaf, Maryam Muhammad Yusuf, Rawan Yasir Albarakati.\n" +
        "Supervisor: Dr. Karam Sallam.\n\n" +
        "Predictions are computational and need confirming in the lab."

@Composable
private fun InfoDialog(title: String, body: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardBg,
        title = { Text(title, color = TextPrimary) },
        text = {
            Text(body, color = TextSecondary, fontSize = 15.sp, lineHeight = 21.sp,
                modifier = Modifier.verticalScroll(rememberScrollState()))
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = PurpleLight) } },
    )
}

/** First letter of the name, skipping a title such as "Dr." */
private fun initial(name: String): String {
    val words = name.split(" ").filter { it.isNotBlank() }
    val word = words.firstOrNull { !it.endsWith(".") } ?: words.firstOrNull()
    return word?.first()?.uppercase() ?: "?"
}

@Composable
private fun ProfileStat(value: String, label: String, modifier: Modifier) {
    GlassCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text(label, fontSize = 14.sp, color = TextSecondary)
        }
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    value: String? = null,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, Modifier.size(44.dp), iconSize = 22.dp)
        HSpace(14.dp)
        Text(title, fontSize = 17.sp, color = TextPrimary, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(value, fontSize = 15.sp, color = TextSecondary)
            HSpace(6.dp)
        }
        if (trailing != null) trailing() else Chevron(size = 24.dp)
    }
}
