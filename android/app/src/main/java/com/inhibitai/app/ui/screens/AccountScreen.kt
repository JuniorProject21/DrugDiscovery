package com.inhibitai.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inhibitai.app.ui.InhibitViewModel
import com.inhibitai.app.ui.components.*
import com.inhibitai.app.ui.theme.*

/** Name, email and sign-in methods, plus changing (or, for Google-only accounts, setting) a password. */
@Composable
fun AccountScreen(vm: InhibitViewModel, contentPadding: PaddingValues, onBack: () -> Unit) {
    val user = vm.user ?: return
    var name by rememberSaveable(user.fullName) { mutableStateOf(user.fullName) }
    var current by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }
    var showPasswords by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.clearAccountError() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(contentPadding)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Back", tint = TextPrimary, modifier = Modifier.size(36.dp))
            }
            Text(
                "Account details", fontSize = 23.sp, fontWeight = FontWeight.Medium, color = TextPrimary,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(48.dp))
        }
        VSpace(12.dp)

        SectionTitle("Profile")
        AuthField(name, { name = it; error = null; vm.clearAccountError() }, "Full name", Icons.Outlined.Person)
        VSpace(10.dp)
        Text(user.email, fontSize = 16.sp, color = TextSecondary, modifier = Modifier.padding(start = 6.dp))
        Text(
            when {
                user.googleLinked && user.hasPassword -> "Signs in with Google or with email and password"
                user.googleLinked -> "Signs in with Google"
                else -> "Signs in with email and password"
            },
            fontSize = 14.sp, color = TextMuted, modifier = Modifier.padding(start = 6.dp, top = 2.dp),
        )
        VSpace(16.dp)
        val nameChanged = name.trim() != user.fullName && name.isNotBlank()
        GradientButton("Save name", loading = vm.accountBusy && nameChanged, enabled = nameChanged, onClick = {
            vm.updateProfile(name) { vm.consumeMessage() }
        })

        VSpace(32.dp)
        SectionTitle("Password")
        if (!user.hasPassword) {
            Text(
                "You sign in with Google. Set a password to also sign in with your email.",
                fontSize = 15.sp, lineHeight = 21.sp, color = TextSecondary,
            )
            VSpace(12.dp)
        }
        if (user.hasPassword) {
            AuthField(
                current, { current = it; error = null; vm.clearAccountError() }, "Current password", Icons.Outlined.Lock,
                isPassword = true, passwordVisible = showPasswords, onTogglePassword = { showPasswords = !showPasswords },
                keyboardType = KeyboardType.Password,
            )
            VSpace(14.dp)
        }
        AuthField(
            newPassword, { newPassword = it; error = null; vm.clearAccountError() }, "New password (8+ characters)",
            Icons.Outlined.Lock, isPassword = true, passwordVisible = showPasswords,
            onTogglePassword = { showPasswords = !showPasswords }, keyboardType = KeyboardType.Password,
        )
        (error ?: vm.accountError)?.let {
            VSpace(12.dp)
            Text(it, color = Red, fontSize = 14.sp)
        }
        VSpace(16.dp)
        GradientButton(
            if (user.hasPassword) "Change password" else "Set password",
            loading = vm.accountBusy && !nameChanged,
            onClick = {
                error = when {
                    user.hasPassword && current.isBlank() -> "Enter your current password."
                    newPassword.length < 8 -> "The new password must be at least 8 characters."
                    else -> null
                }
                if (error == null) {
                    vm.changePassword(current.ifBlank { null }, newPassword) {
                        current = ""
                        newPassword = ""
                    }
                }
            },
        )
        VSpace(28.dp)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
    VSpace(12.dp)
}
