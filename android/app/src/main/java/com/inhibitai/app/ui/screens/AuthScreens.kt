package com.inhibitai.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inhibitai.app.ui.InhibitViewModel
import com.inhibitai.app.ui.auth.rememberGoogleSignIn
import com.inhibitai.app.ui.components.*
import com.inhibitai.app.ui.theme.*

// ======================= ONBOARDING =======================

private data class OnboardingPage(val line1: String, val line2: String, val body: String)

private val onboardingPages = listOf(
    OnboardingPage("AI-powered insights", "for molecular discovery",
        "Predict PARP-1 inhibitor activity from SMILES structures with advanced AI models."),
    OnboardingPage("From SMILES", "to answers in seconds",
        "Paste a structure, preview the molecule and get an activity prediction with a confidence score."),
    OnboardingPage("Track every", "compound you test",
        "Review your prediction history, save promising hits and follow your discovery analytics."),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(onGetStarted: () -> Unit, onSignUp: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { onboardingPages.size })
    Box(Modifier.fillMaxSize().background(Bg)) {
        MoleculeBackdrop(OnboardingScene, Modifier.fillMaxWidth().fillMaxHeight(0.6f))
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 28.dp),
        ) {
            VSpace(48.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandLogo(Modifier.size(84.dp))
                HSpace(12.dp)
                BrandTitle(titleSize = 38.sp, subtitleSize = 18.sp)
            }
            Spacer(Modifier.weight(1f))
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth()) { page ->
                val p = onboardingPages[page]
                Column(Modifier.fillMaxWidth()) {
                    Text(p.line1, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text(p.line2, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, color = PurpleLight)
                    VSpace(18.dp)
                    Text(p.body, fontSize = 18.sp, lineHeight = 26.sp, color = TextSecondary)
                }
            }
            VSpace(28.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                repeat(onboardingPages.size) { i ->
                    Box(
                        Modifier
                            .padding(horizontal = 7.dp)
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(if (pagerState.currentPage == i) PurpleLight else Color(0xFF2E2A40)),
                    )
                }
            }
            VSpace(36.dp)
            GradientButton("Get Started", onGetStarted, trailingIcon = Icons.AutoMirrored.Filled.ArrowForward)
            VSpace(16.dp)
            OutlinePillButton("Sign Up", onSignUp)
            VSpace(32.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                HorizontalDivider(Modifier.weight(1f), color = CardBorder)
                Text(
                    "Science drives tomorrow.", fontSize = 13.sp, letterSpacing = 3.sp, color = TextMuted,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                HorizontalDivider(Modifier.weight(1f), color = CardBorder)
            }
            VSpace(24.dp)
        }
    }
}

// ======================= LOGIN =======================

@Composable
fun LoginScreen(vm: InhibitViewModel, onBack: () -> Unit, onSignUp: () -> Unit, onForgotPassword: () -> Unit) {
    val context = LocalContext.current
    val googleSignIn = rememberGoogleSignIn(vm)
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.clearAuthError() }
    fun edited() {
        error = null
        vm.clearAuthError()
    }

    AuthLayout(onBack) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = TextPrimary)) { append("Welcome ") }
                withStyle(SpanStyle(color = PurpleLight)) { append("back") }
            },
            fontSize = 44.sp, fontWeight = FontWeight.Bold,
        )
        Text("Sign in to your InhibitAI account", fontSize = 19.sp, color = TextSecondary)
        VSpace(36.dp)

        AuthField(email, { email = it; edited() }, "Email address", Icons.Outlined.Email, keyboardType = KeyboardType.Email)
        VSpace(16.dp)
        AuthField(
            password, { password = it; edited() }, "Password", Icons.Outlined.Lock,
            isPassword = true, passwordVisible = showPassword, onTogglePassword = { showPassword = !showPassword },
            keyboardType = KeyboardType.Password,
        )
        VSpace(14.dp)
        Text(
            "Forgot password?", fontSize = 17.sp, color = PurpleLight,
            modifier = Modifier
                .align(Alignment.End)
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onForgotPassword)
                .padding(4.dp),
        )
        (error ?: vm.authError)?.let {
            VSpace(8.dp)
            Text(it, color = Red, fontSize = 14.sp)
        }
        VSpace(28.dp)
        GradientButton("Login", loading = vm.authBusy, onClick = {
            if (email.isBlank() || password.isBlank()) error = "Please enter your email and password."
            else vm.login(email, password)
        })
        OrDivider()
        SocialButton("Continue with Google", googleSignIn) { GoogleMark() }
        VSpace(14.dp)
        // Sign in with Apple needs a paid Apple developer account and isn't offered on Android here.
        SocialButton("Continue with Apple", {
            Toast.makeText(context, "Sign in with Apple isn't available. Please use email or Google.", Toast.LENGTH_SHORT).show()
        }) { AppleMark() }
        VSpace(32.dp)
        AuthSwitchLink("Don't have an account? ", "Sign Up", onSignUp)
        VSpace(28.dp)
    }
}

// ======================= SIGN UP =======================

@Composable
fun SignUpScreen(vm: InhibitViewModel, onBack: () -> Unit, onLogin: () -> Unit) {
    val googleSignIn = rememberGoogleSignIn(vm)
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.clearAuthError() }
    fun edited() {
        error = null
        vm.clearAuthError()
    }

    AuthLayout(onBack) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = TextPrimary)) { append("Create ") }
                withStyle(SpanStyle(color = PurpleLight)) { append("account") }
            },
            fontSize = 44.sp, fontWeight = FontWeight.Bold,
        )
        Text("Start predicting PARP-1 inhibitor activity", fontSize = 19.sp, color = TextSecondary)
        VSpace(36.dp)

        AuthField(name, { name = it; edited() }, "Full name", Icons.Outlined.Person)
        VSpace(16.dp)
        AuthField(email, { email = it; edited() }, "Email address", Icons.Outlined.Email, keyboardType = KeyboardType.Email)
        VSpace(16.dp)
        AuthField(
            password, { password = it; edited() }, "Password (8+ characters)", Icons.Outlined.Lock,
            isPassword = true, passwordVisible = showPassword, onTogglePassword = { showPassword = !showPassword },
            keyboardType = KeyboardType.Password,
        )
        (error ?: vm.authError)?.let {
            VSpace(12.dp)
            Text(it, color = Red, fontSize = 14.sp)
        }
        VSpace(32.dp)
        GradientButton("Sign Up", loading = vm.authBusy, onClick = {
            error = when {
                name.isBlank() || email.isBlank() || password.isBlank() -> "Please fill in all fields."
                "@" !in email -> "Please enter a valid email address."
                password.length < 8 -> "Password must be at least 8 characters."
                else -> null
            }
            if (error == null) vm.register(name, email, password)
        })
        OrDivider()
        SocialButton("Continue with Google", googleSignIn) { GoogleMark() }
        VSpace(32.dp)
        AuthSwitchLink("Already have an account? ", "Log in", onLogin)
        VSpace(28.dp)
    }
}

// ======================= FORGOT PASSWORD =======================

/** Two steps: email a 6-digit code, then enter it with a new password (which also signs you in). */
@Composable
fun ForgotPasswordScreen(vm: InhibitViewModel, onBack: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.clearAuthError() }
    fun edited() {
        error = null
        vm.clearAuthError()
    }
    val sentTo = vm.resetCodeSentTo

    AuthLayout(onBack) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = TextPrimary)) { append("Reset ") }
                withStyle(SpanStyle(color = PurpleLight)) { append("password") }
            },
            fontSize = 44.sp, fontWeight = FontWeight.Bold,
        )
        if (sentTo == null) {
            Text("We'll email you a 6-digit code.", fontSize = 19.sp, color = TextSecondary)
            VSpace(36.dp)
            AuthField(email, { email = it; edited() }, "Email address", Icons.Outlined.Email, keyboardType = KeyboardType.Email)
            (error ?: vm.authError)?.let {
                VSpace(12.dp)
                Text(it, color = Red, fontSize = 14.sp)
            }
            VSpace(32.dp)
            GradientButton("Send code", loading = vm.authBusy, onClick = {
                if ("@" !in email) error = "Please enter the email you signed up with."
                else vm.requestPasswordReset(email)
            })
        } else {
            Text("Enter the code we sent to $sentTo and choose a new password.", fontSize = 19.sp,
                lineHeight = 26.sp, color = TextSecondary)
            VSpace(36.dp)
            AuthField(
                code, { code = it.filter(Char::isDigit).take(6); edited() }, "6-digit code", Icons.Outlined.Pin,
                keyboardType = KeyboardType.NumberPassword,
            )
            VSpace(16.dp)
            AuthField(
                password, { password = it; edited() }, "New password (8+ characters)", Icons.Outlined.Lock,
                isPassword = true, passwordVisible = showPassword, onTogglePassword = { showPassword = !showPassword },
                keyboardType = KeyboardType.Password,
            )
            (error ?: vm.authError)?.let {
                VSpace(12.dp)
                Text(it, color = Red, fontSize = 14.sp)
            }
            VSpace(32.dp)
            GradientButton("Set new password", loading = vm.authBusy, onClick = {
                error = when {
                    code.length != 6 -> "Enter the 6-digit code from the email."
                    password.length < 8 -> "Password must be at least 8 characters."
                    else -> null
                }
                if (error == null) vm.confirmPasswordReset(code, password)
            })
            VSpace(24.dp)
            AuthSwitchLink("Didn't get it? ", "Send again") { code = ""; vm.requestPasswordReset(sentTo) }
            VSpace(12.dp)
            AuthSwitchLink("", "Use a different email") { code = ""; vm.clearPasswordReset() }
        }
        VSpace(28.dp)
    }
}

@Composable
private fun OrDivider() {
    VSpace(24.dp)
    Row(verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = CardBorder)
        Text("OR", color = TextSecondary, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 24.dp))
        HorizontalDivider(Modifier.weight(1f), color = CardBorder)
    }
    VSpace(24.dp)
}

/** Shared frame of the sign-in screens: backdrop, back arrow and logo above [content]. */
@Composable
private fun AuthLayout(onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Bg)) {
        MoleculeBackdrop(HeaderScene, Modifier.align(Alignment.TopEnd).statusBarsPadding().size(220.dp, 460.dp))
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            VSpace(8.dp)
            IconButton(onClick = onBack, modifier = Modifier.offset(x = (-12).dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Back", tint = TextPrimary, modifier = Modifier.size(38.dp))
            }
            VSpace(30.dp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandLogo(Modifier.size(72.dp))
                HSpace(10.dp)
                BrandTitle(titleSize = 32.sp, subtitleSize = 16.sp)
            }
            VSpace(40.dp)
            content()
        }
    }
}

@Composable
private fun AuthSwitchLink(prompt: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text(prompt, color = TextSecondary, fontSize = 17.sp)
        Text(
            action, color = PurpleLight, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable(onClick = onClick),
        )
    }
}

@Composable
internal fun AuthField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icon: ImageVector,
    isPassword: Boolean = false,
    passwordVisible: Boolean = false,
    onTogglePassword: () -> Unit = {},
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(shape)
            .background(FieldBg)
            .border(1.dp, Color(0xFF2E2846), shape)
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color(0xFFC4B5FD), modifier = Modifier.size(28.dp))
        HSpace(18.dp)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, color = TextSecondary, fontSize = 19.sp)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 19.sp),
                cursorBrush = SolidColor(PurpleLight),
                visualTransformation = if (isPassword && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (isPassword) {
            IconButton(onClick = onTogglePassword) {
                Icon(
                    if (passwordVisible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                    "Toggle password", tint = Color(0xFF8B85A8),
                )
            }
        }
    }
}

@Composable
private fun SocialButton(label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .fillMaxWidth()
            .height(66.dp)
            .clip(shape)
            .border(1.dp, Color(0xFF3A3354), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { icon() }
        Text(label, fontSize = 19.sp, color = TextPrimary, modifier = Modifier.weight(1f).padding(start = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        HSpace(44.dp)
    }
}

// Simple stand-ins. Replace with the official brand assets from Google & Apple sign-in guidelines.
@Composable
private fun GoogleMark() {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = Color(0xFF4285F4))) { append("G") }
        },
        fontSize = 28.sp, fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun AppleMark() {
    Canvas(Modifier.size(26.dp)) {
        val w = size.width
        val h = size.height
        drawCircle(Color.White, w * 0.30f, Offset(w * 0.36f, h * 0.60f))
        drawCircle(Color.White, w * 0.30f, Offset(w * 0.64f, h * 0.60f))
        drawOval(Color.White, Offset(w * 0.50f, h * 0.02f), Size(w * 0.18f, h * 0.24f))
    }
}
