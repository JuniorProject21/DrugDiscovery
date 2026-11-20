package com.inhibitai.app.ui.screens

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
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.inhibitai.app.data.PasswordRules
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
            VSpace(8.dp)
            // Light / dark switch, available before signing in.
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                ThemeToggleButton()
            }
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
                            .background(if (pagerState.currentPage == i) PurpleLight else PagerDotIdle),
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
fun LoginScreen(
    vm: InhibitViewModel,
    onBack: () -> Unit,
    onSignUp: () -> Unit,
    onForgotPassword: () -> Unit,
    onForgotPasswordByPhone: () -> Unit,
) {
    val googleSignIn = rememberGoogleSignIn(vm)
    var usePhone by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
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
        VSpace(26.dp)

        // An account is identified by an email address or by a phone number, so both can sign in.
        SegmentedChoice(listOf("Email", "Phone"), if (usePhone) 1 else 0) {
            usePhone = it == 1
            edited()
        }
        VSpace(20.dp)
        if (usePhone) {
            AuthField(phone, { phone = it; edited() }, "Phone number (with country code)",
                Icons.Outlined.Phone, keyboardType = KeyboardType.Phone)
        } else {
            AuthField(email, { email = it; edited() }, "Email address", Icons.Outlined.Email,
                keyboardType = KeyboardType.Email)
        }
        VSpace(16.dp)
        AuthField(
            password, { password = it; edited() }, "Password", Icons.Outlined.Lock,
            isPassword = true, passwordVisible = showPassword, onTogglePassword = { showPassword = !showPassword },
            keyboardType = KeyboardType.Password,
        )
        VSpace(14.dp)
        if (!usePhone) {
            Text(
                "Forgot password?", fontSize = 17.sp, color = PurpleLight,
                modifier = Modifier
                    .align(Alignment.End)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onForgotPassword)
                    .padding(4.dp),
            )
        } else {
            // A phone account gets its reset code by text instead of by email.
            Text(
                "Forgot password?", fontSize = 17.sp, color = PurpleLight,
                modifier = Modifier
                    .align(Alignment.End)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onForgotPasswordByPhone)
                    .padding(4.dp),
            )
        }
        (error ?: vm.authError)?.let {
            VSpace(8.dp)
            Text(it, color = Red, fontSize = 14.sp)
        }
        VSpace(28.dp)
        GradientButton("Login", loading = vm.authBusy, onClick = {
            when {
                password.isBlank() -> error = "Please enter your password."
                usePhone && phone.isBlank() -> error = "Please enter your phone number."
                usePhone -> vm.phoneLogin(phone, password)
                email.isBlank() -> error = "Please enter your email address."
                else -> vm.login(email, password)
            }
        })
        OrDivider()
        // Sign in with Apple is not offered: it needs a paid Apple developer account.
        SocialButton("Continue with Google", googleSignIn) { GoogleMark() }
        VSpace(32.dp)
        AuthSwitchLink("Don't have an account? ", "Sign Up", onSignUp)
        VSpace(28.dp)
    }
}

// ======================= SIGN UP =======================

@Composable
fun SignUpScreen(vm: InhibitViewModel, onBack: () -> Unit, onLogin: () -> Unit) {
    val googleSignIn = rememberGoogleSignIn(vm)
    var usePhone by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.clearAuthError() }
    fun edited() {
        error = null
        vm.clearAuthError()
    }

    // Set once the server has texted a code: the screen then asks for it instead (figure 11-3).
    val codeSentTo = vm.phoneCodeSentTo

    AuthLayout(onBack = {
        // Leaving the code step goes back to the form rather than off the screen.
        if (codeSentTo != null) {
            code = ""
            vm.clearPhoneSignUp()
        } else {
            onBack()
        }
    }) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = TextPrimary)) { append(if (codeSentTo != null) "Verify " else "Create ") }
                withStyle(SpanStyle(color = PurpleLight)) { append(if (codeSentTo != null) "number" else "account") }
            },
            fontSize = 44.sp, fontWeight = FontWeight.Bold,
        )
        if (codeSentTo != null) {
            PhoneCodeStep(vm, code, onCodeChange = { code = it; edited() }, error = error, onError = { error = it })
            VSpace(28.dp)
            return@AuthLayout
        }

        Text("Start predicting PARP-1 inhibitor activity", fontSize = 19.sp, color = TextSecondary)
        VSpace(26.dp)

        SegmentedChoice(listOf("Email", "Phone"), if (usePhone) 1 else 0) {
            usePhone = it == 1
            edited()
        }
        VSpace(20.dp)
        AuthField(name, { name = it; edited() }, "Full name", Icons.Outlined.Person)
        VSpace(16.dp)
        if (usePhone) {
            AuthField(phone, { phone = it; edited() }, "Phone number (with country code)",
                Icons.Outlined.Phone, keyboardType = KeyboardType.Phone)
            VSpace(8.dp)
            Text(
                "We'll text you a 6-digit code to confirm the number.",
                fontSize = 14.sp, color = TextMuted, modifier = Modifier.padding(start = 6.dp),
            )
            VSpace(8.dp)
        } else {
            AuthField(email, { email = it; edited() }, "Email address", Icons.Outlined.Email,
                keyboardType = KeyboardType.Email)
        }
        VSpace(16.dp)
        AuthField(
            password, { password = it; edited() }, "Password (${PasswordRules.MIN_LENGTH}+ characters)", Icons.Outlined.Lock,
            isPassword = true, passwordVisible = showPassword, onTogglePassword = { showPassword = !showPassword },
            keyboardType = KeyboardType.Password,
        )
        (error ?: vm.authError)?.let {
            VSpace(12.dp)
            Text(it, color = Red, fontSize = 14.sp)
        }
        VSpace(32.dp)
        GradientButton(if (usePhone) "Send code" else "Sign Up", loading = vm.authBusy, onClick = {
            error = when {
                name.isBlank() || password.isBlank() -> "Please fill in all fields."
                usePhone && phone.isBlank() -> "Please enter your phone number."
                // The server normalises the number; this only catches an obviously unusable one.
                usePhone && phone.count(Char::isDigit) < 7 -> "Please enter the full number, including the country code."
                !usePhone && email.isBlank() -> "Please fill in all fields."
                !usePhone && "@" !in email -> "Please enter a valid email address."
                else -> PasswordRules.problem(password)
            }
            if (error == null) {
                if (usePhone) vm.startPhoneSignUp(name, phone, password) else vm.register(name, email, password)
            }
        })
        OrDivider()
        SocialButton("Continue with Google", googleSignIn) { GoogleMark() }
        VSpace(32.dp)
        AuthSwitchLink("Already have an account? ", "Log in", onLogin)
        VSpace(28.dp)
    }
}

/** Second step of a phone sign-up: the code that was texted, which also signs the account in. */
@Composable
private fun ColumnScope.PhoneCodeStep(
    vm: InhibitViewModel,
    code: String,
    onCodeChange: (String) -> Unit,
    error: String?,
    onError: (String?) -> Unit,
) {
    Text(
        vm.authInfo ?: "Enter the 6-digit code we sent you.",
        fontSize = 19.sp, lineHeight = 26.sp, color = TextSecondary,
    )
    VSpace(36.dp)
    AuthField(
        code, { onCodeChange(it.filter(Char::isDigit).take(6)) }, "6-digit code", Icons.Outlined.Pin,
        keyboardType = KeyboardType.NumberPassword,
    )
    (error ?: vm.authError)?.let {
        VSpace(12.dp)
        Text(it, color = Red, fontSize = 14.sp)
    }
    VSpace(32.dp)
    GradientButton("Verify and continue", loading = vm.authBusy, onClick = {
        if (code.length != 6) onError("Enter the 6-digit code we sent you.") else vm.verifyPhone(code)
    })
    VSpace(24.dp)
    AuthSwitchLink("Didn't get it? ", "Send again") {
        onCodeChange("")
        vm.resendPhoneCode()
    }
    VSpace(12.dp)
    AuthSwitchLink("", "Use a different number") {
        onCodeChange("")
        vm.clearPhoneSignUp()
    }
}

// ======================= FORGOT PASSWORD =======================

/**
 * Two steps: send a 6-digit code, then enter it with a new password (which also signs you in).
 * The code goes to the email address or to the phone number, depending on how the account was
 * made - a phone account has no email to send it to.
 */
@Composable
fun ForgotPasswordScreen(vm: InhibitViewModel, onBack: () -> Unit, startWithPhone: Boolean = false) {
    var usePhone by rememberSaveable { mutableStateOf(startWithPhone) }
    var email by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.clearAuthError() }
    fun edited() {
        error = null
        vm.clearAuthError()
    }
    // Whichever flow is in progress; only one can be, since the screen asks for one at a time.
    val sentToEmail = vm.resetCodeSentTo
    val sentToPhone = vm.phoneResetSentTo
    val waitingForCode = sentToEmail != null || sentToPhone != null

    fun clearFlow() {
        code = ""
        vm.clearPasswordReset()
        vm.clearPhoneReset()
    }

    AuthLayout(onBack = { if (waitingForCode) clearFlow() else onBack() }) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = TextPrimary)) { append("Reset ") }
                withStyle(SpanStyle(color = PurpleLight)) { append("password") }
            },
            fontSize = 44.sp, fontWeight = FontWeight.Bold,
        )
        if (!waitingForCode) {
            Text(
                if (usePhone) "We'll text you a 6-digit code." else "We'll email you a 6-digit code.",
                fontSize = 19.sp, color = TextSecondary,
            )
            VSpace(26.dp)
            SegmentedChoice(listOf("Email", "Phone"), if (usePhone) 1 else 0) {
                usePhone = it == 1
                edited()
            }
            VSpace(20.dp)
            if (usePhone) {
                AuthField(phone, { phone = it; edited() }, "Phone number (with country code)",
                    Icons.Outlined.Phone, keyboardType = KeyboardType.Phone)
            } else {
                AuthField(email, { email = it; edited() }, "Email address", Icons.Outlined.Email,
                    keyboardType = KeyboardType.Email)
            }
            (error ?: vm.authError)?.let {
                VSpace(12.dp)
                Text(it, color = Red, fontSize = 14.sp)
            }
            VSpace(32.dp)
            GradientButton("Send code", loading = vm.authBusy, onClick = {
                if (usePhone) {
                    if (phone.count(Char::isDigit) < 7) {
                        error = "Please enter the full number you signed up with, including the country code."
                    } else {
                        vm.requestPhonePasswordReset(phone)
                    }
                } else {
                    if ("@" !in email) error = "Please enter the email you signed up with."
                    else vm.requestPasswordReset(email)
                }
            })
        } else {
            Text(
                vm.authInfo ?: "Enter the code we sent you and choose a new password.",
                fontSize = 19.sp, lineHeight = 26.sp, color = TextSecondary,
            )
            VSpace(36.dp)
            AuthField(
                code, { code = it.filter(Char::isDigit).take(6); edited() }, "6-digit code", Icons.Outlined.Pin,
                keyboardType = KeyboardType.NumberPassword,
            )
            VSpace(16.dp)
            AuthField(
                password, { password = it; edited() }, "New password (${PasswordRules.MIN_LENGTH}+ characters)", Icons.Outlined.Lock,
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
                    code.length != 6 -> "Enter the 6-digit code we sent you."
                    else -> PasswordRules.problem(password)
                }
                if (error == null) {
                    if (sentToPhone != null) vm.confirmPhonePasswordReset(code, password)
                    else vm.confirmPasswordReset(code, password)
                }
            })
            VSpace(24.dp)
            AuthSwitchLink("Didn't get it? ", "Send again") {
                code = ""
                if (sentToPhone != null) vm.requestPhonePasswordReset(sentToPhone)
                else sentToEmail?.let { vm.requestPasswordReset(it) }
            }
            VSpace(12.dp)
            AuthSwitchLink("", if (sentToPhone != null) "Use a different number" else "Use a different email") {
                clearFlow()
            }
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.offset(x = (-12).dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Back", tint = TextPrimary, modifier = Modifier.size(38.dp))
                }
                Spacer(Modifier.weight(1f))
                ThemeToggleButton()
            }
            VSpace(22.dp)
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
            .heightIn(min = 72.dp)
            .clip(shape)
            .background(FieldBg)
            .border(1.dp, FieldBorder, shape)
            .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = FieldIcon, modifier = Modifier.size(28.dp))
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
                // The placeholder is gone once there's text in it, so the name lives here too.
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
            )
        }
        if (isPassword) {
            IconButton(onClick = onTogglePassword) {
                Icon(
                    if (passwordVisible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                    if (passwordVisible) "Hide the password" else "Show the password", tint = FieldIconMuted,
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
            .heightIn(min = 66.dp)
            .clip(shape)
            .border(1.dp, SocialBorder, shape)
            .clickable(onClick = onClick, role = Role.Button)
            .padding(horizontal = 32.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { icon() }
        Text(label, fontSize = 19.sp, color = TextPrimary, modifier = Modifier.weight(1f).padding(start = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        HSpace(44.dp)
    }
}

// A simple stand-in. Replace with the official brand asset from Google's sign-in guidelines.
@Composable
private fun GoogleMark() {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = Color(0xFF4285F4))) { append("G") }
        },
        fontSize = 28.sp, fontWeight = FontWeight.Bold,
    )
}

