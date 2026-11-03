package com.inhibitai.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.util.Locale
import com.inhibitai.app.ui.InhibitViewModel
import com.inhibitai.app.ui.SmilesCheck
import com.inhibitai.app.ui.components.*
import com.inhibitai.app.ui.theme.*

@Composable
fun PredictScreen(
    vm: InhibitViewModel,
    contentPadding: PaddingValues,
    onResult: (String) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    var smiles by rememberSaveable { mutableStateOf("Cc1ccc(cc1)C(=O)N2CCCc3nccc(Cl)c3C2") }
    var fullscreen by remember { mutableStateOf(false) }
    // Ask the server whether the SMILES is valid as the user types (debounced in the ViewModel).
    LaunchedEffect(smiles) { vm.checkSmiles(smiles) }
    val check = vm.smilesCheck
    val fieldError = vm.predictionError ?: (check as? SmilesCheck.Invalid)?.message

    Box(Modifier.fillMaxSize().background(Bg).padding(contentPadding)) {
        MoleculeBackdrop(HeaderScene, Modifier.align(Alignment.TopEnd).size(250.dp, 430.dp))
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            VSpace(12.dp)
            AppHeader(onBellClick = { Toast.makeText(context, "No new notifications", Toast.LENGTH_SHORT).show() })
            VSpace(34.dp)
            Text("New Prediction", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            VSpace(8.dp)
            Text(
                "Enter a SMILES string to predict\nPARP-1 inhibitor activity with AI.",
                fontSize = 19.sp, lineHeight = 26.sp, color = TextSecondary,
            )
            VSpace(34.dp)

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("SMILES String", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = TextPrimary, modifier = Modifier.weight(1f))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { uriHandler.openUri("https://en.wikipedia.org/wiki/Simplified_Molecular_Input_Line_Entry_System") }
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("What is SMILES?", fontSize = 16.sp, color = PurpleLight)
                    HSpace(6.dp)
                    Icon(Icons.Outlined.OpenInNew, null, tint = PurpleLight, modifier = Modifier.size(20.dp))
                }
            }
            VSpace(12.dp)

            // ---- SMILES input ----
            val fieldShape = RoundedCornerShape(20.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(fieldShape)
                    .background(FieldBg)
                    .border(1.dp, if (fieldError != null) Red else Color(0xFF2E2846), fieldShape)
                    .padding(start = 18.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (smiles.isEmpty()) Text("e.g. c1ccccc1C(=O)N", color = TextMuted, fontSize = 16.sp)
                    BasicTextField(
                        value = smiles,
                        onValueChange = { smiles = it.replace("\n", ""); vm.clearError() },
                        singleLine = true,
                        textStyle = TextStyle(color = TextPrimary, fontSize = 16.sp),
                        cursorBrush = SolidColor(PurpleLight),
                        // A password-type keyboard (text still shown) is the only reliable way to stop keyboards
                        // "correcting" SMILES, e.g. Gboard turning "c(" into "C(", which silently changes the
                        // molecule. It also keeps unpublished structures out of the keyboard's learned words.
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password, imeAction = ImeAction.Done,
                            autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(40.dp).background(CardBorder))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            clipboard.getText()?.text?.let { smiles = it.trim(); vm.clearError() }
                        }
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.ContentPaste, null, tint = PurpleLight, modifier = Modifier.size(24.dp))
                    HSpace(6.dp)
                    Text("Paste", fontSize = 18.sp, color = PurpleLight)
                }
            }
            fieldError?.let {
                VSpace(8.dp)
                Text(it, color = Red, fontSize = 14.sp)
            }
            VSpace(20.dp)

            // ---- Molecule preview ----
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Molecule Preview", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = TextPrimary,
                            modifier = Modifier.weight(1f).padding(start = 6.dp),
                        )
                        Row(
                            Modifier.clip(RoundedCornerShape(8.dp)).clickable { smiles = ""; vm.clearError() }.padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.Replay, null, tint = TextSecondary, modifier = Modifier.size(22.dp))
                            HSpace(6.dp)
                            Text("Clear", fontSize = 17.sp, color = TextSecondary)
                        }
                        Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(28.dp).background(CardBorder))
                        IconButton(onClick = { fullscreen = true }, enabled = check is SmilesCheck.Valid) {
                            Icon(Icons.Outlined.Fullscreen, "Full screen", tint = TextSecondary)
                        }
                    }
                    (check as? SmilesCheck.Valid)?.let { valid ->
                        valid.commonName?.let {
                            Text(
                                it, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = PurpleLight,
                                modifier = Modifier.padding(start = 6.dp, top = 2.dp),
                            )
                        }
                        val details = listOfNotNull(
                            valid.formula,
                            valid.molecularWeight?.let { String.format(Locale.US, "%.1f g/mol", it) },
                        )
                        if (details.isNotEmpty()) {
                            Text(
                                details.joinToString("  ·  "), fontSize = 15.sp, color = TextSecondary,
                                modifier = Modifier.padding(start = 6.dp, top = 2.dp),
                            )
                        }
                    }
                    VSpace(10.dp)
                    val previewShape = RoundedCornerShape(16.dp)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(340.dp)
                            .clip(previewShape)
                            .background(Color(0xFF0A0912))
                            .border(1.dp, Color(0xFF1C1929), previewShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (check) {
                            null -> PreviewMessage("Enter a SMILES string to see\nthe structure preview")
                            SmilesCheck.Checking -> CircularProgressIndicator(color = PurpleLight, strokeWidth = 2.dp)
                            is SmilesCheck.Invalid -> PreviewMessage("No preview: the SMILES is not valid")
                            is SmilesCheck.Unavailable -> PreviewMessage(check.message)
                            is SmilesCheck.Valid -> StructureImage(check.input, dark = true, Modifier.fillMaxSize().padding(20.dp))
                        }
                    }
                }
            }
            VSpace(26.dp)
            GradientButton(
                "Predict Activity",
                onClick = { vm.predict(smiles, onResult) },
                leadingIcon = Icons.Filled.AutoAwesome,
                loading = vm.isPredicting,
                enabled = smiles.isNotBlank() && check !is SmilesCheck.Invalid,
            )
            VSpace(26.dp)
        }
    }

    val validated = (check as? SmilesCheck.Valid)?.input
    if (fullscreen && validated != null) {
        Dialog(onDismissRequest = { fullscreen = false }) {
            val shape = RoundedCornerShape(24.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.8f)
                    .clip(shape)
                    .background(CardBg)
                    .border(1.dp, CardBorder, shape),
            ) {
                StructureImage(validated, dark = true, Modifier.fillMaxSize().padding(28.dp))
                IconButton(onClick = { fullscreen = false }, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(Icons.Filled.Close, "Close", tint = TextPrimary)
                }
            }
        }
    }
}

@Composable
private fun PreviewMessage(text: String) {
    Text(
        text, color = TextMuted, fontSize = 15.sp, textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
}
