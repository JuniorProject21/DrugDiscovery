package com.inhibitai.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Science
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
import com.inhibitai.app.data.PasswordRules
import com.inhibitai.app.data.UserRole
import com.inhibitai.app.ui.InhibitViewModel
import com.inhibitai.app.ui.components.*
import com.inhibitai.app.ui.theme.*

/**
 * Who the user is (name and the research details that go on an exported report), how they sign in,
 * and changing - or, for Google-only accounts, setting - a password.
 */
@Composable
fun AccountScreen(vm: InhibitViewModel, contentPadding: PaddingValues, onBack: () -> Unit) {
    val user = vm.user ?: return
    var name by rememberSaveable(user.fullName) { mutableStateOf(user.fullName) }
    var institution by rememberSaveable(user.institution) { mutableStateOf(user.institution.orEmpty()) }
    var role by remember(user.role) { mutableStateOf(user.role) }
    var researchArea by rememberSaveable(user.researchArea) { mutableStateOf(user.researchArea.orEmpty()) }
    var age by rememberSaveable(user.age) { mutableStateOf(user.age?.toString().orEmpty()) }
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
        // An account created with a phone number has no email address, and vice versa.
        Text(user.identifier, fontSize = 16.sp, color = TextSecondary, modifier = Modifier.padding(start = 6.dp))
        Text(
            when {
                user.googleLinked && user.hasPassword -> "Signs in with Google or with email and password"
                user.googleLinked -> "Signs in with Google"
                user.signsInWithPhone -> "Signs in with this phone number and a password"
                else -> "Signs in with email and password"
            },
            fontSize = 14.sp, color = TextMuted, modifier = Modifier.padding(start = 6.dp, top = 2.dp),
        )
        VSpace(18.dp)

        // The rest of the profile: optional, and shown on an exported report so a shared result
        // says who ran it.
        AuthField(institution, { institution = it; error = null; vm.clearAccountError() },
            "Institution (optional)", Icons.Outlined.School)
        VSpace(14.dp)
        RolePicker(role) { role = it }
        VSpace(14.dp)
        AuthField(researchArea, { researchArea = it; error = null; vm.clearAccountError() },
            "Research area (optional)", Icons.Outlined.Science)
        VSpace(14.dp)
        AuthField(age, { age = it.filter(Char::isDigit).take(3); error = null; vm.clearAccountError() },
            "Age (optional)", Icons.Outlined.Cake, keyboardType = KeyboardType.Number)

        VSpace(18.dp)
        val ageNumber = age.toIntOrNull()
        val profileChanged = name.trim() != user.fullName ||
            institution.trim() != user.institution.orEmpty() ||
            role != user.role ||
            researchArea.trim() != user.researchArea.orEmpty() ||
            ageNumber != user.age
        GradientButton(
            "Save profile",
            loading = vm.accountBusy && profileChanged,
            enabled = profileChanged && name.isNotBlank(),
            onClick = {
                error = when {
                    name.isBlank() -> "Please enter your name."
                    age.isNotBlank() && (ageNumber == null || ageNumber < 13 || ageNumber > 120) ->
                        "Please enter an age between 13 and 120, or leave it empty."
                    else -> null
                }
                if (error == null) {
                    vm.updateProfile(
                        onDone = { vm.consumeMessage() },
                        fullName = name,
                        institution = institution,          // empty clears it on the server
                        role = role,
                        clearRole = role == null,
                        researchArea = researchArea,
                        age = ageNumber,
                    )
                }
            },
        )

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
            newPassword, { newPassword = it; error = null; vm.clearAccountError() }, "New password (${PasswordRules.MIN_LENGTH}+ characters)",
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
            loading = vm.accountBusy && !profileChanged,
            onClick = {
                error = when {
                    user.hasPassword && current.isBlank() -> "Enter your current password."
                    else -> PasswordRules.problem(newPassword)
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

/** Student / researcher / supervisor / other, or none at all. */
@Composable
private fun RolePicker(selected: UserRole?, onSelect: (UserRole?) -> Unit) {
    Column {
        Text("Role (optional)", fontSize = 14.sp, color = TextSecondary,
            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UserRole.entries.forEach { option ->
                // Tapping the chosen role again takes it off the profile.
                ChoicePill(option.label, option == selected) {
                    onSelect(if (option == selected) null else option)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
    VSpace(12.dp)
}
