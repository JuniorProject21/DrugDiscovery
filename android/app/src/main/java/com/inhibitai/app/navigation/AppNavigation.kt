package com.inhibitai.app.navigation

import android.widget.Toast
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.inhibitai.app.ui.AuthState
import com.inhibitai.app.ui.InhibitViewModel
import com.inhibitai.app.ui.components.LocalStructureImageLoader
import com.inhibitai.app.ui.components.MainScaffold
import com.inhibitai.app.ui.components.NavTab
import com.inhibitai.app.ui.components.StructureImageLoader
import com.inhibitai.app.ui.screens.*
import com.inhibitai.app.ui.theme.Bg

private object Routes {
    const val ONBOARDING = "onboarding"
    const val LOGIN = "login"
    const val SIGN_UP = "signup"
    const val FORGOT_PASSWORD = "forgot-password"
    const val RESULT = "result"
    const val ACCOUNT = "account"
    val AUTH = setOf(ONBOARDING, LOGIN, SIGN_UP, FORGOT_PASSWORD)
}

@Composable
fun InhibitApp(vm: InhibitViewModel = viewModel()) {
    val authState = vm.authState
    if (authState == AuthState.Checking) {
        Box(Modifier.fillMaxSize().background(Bg)) // reading the saved session takes a moment
        return
    }

    val nav = rememberNavController()
    val context = LocalContext.current

    // Signing in, signing out and an expired session all move the user between the
    // sign-in screens and the main app from here.
    LaunchedEffect(authState) {
        val onAuthScreen = nav.currentDestination?.route in Routes.AUTH
        val signedIn = authState is AuthState.SignedIn
        if (signedIn && onAuthScreen) {
            nav.navigate(NavTab.HOME.route) { popUpTo(nav.graph.id) { inclusive = true } }
        } else if (!signedIn && !onAuthScreen) {
            nav.navigate(Routes.ONBOARDING) { popUpTo(nav.graph.id) { inclusive = true } }
        }
    }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            vm.consumeMessage()
        }
    }

    fun goTab(tab: NavTab) {
        if (tab == NavTab.HOME) {
            // Go back to the Home screen already in the back stack (or open it if it isn't there).
            if (!nav.popBackStack(NavTab.HOME.route, inclusive = false)) {
                nav.navigate(NavTab.HOME.route) { launchSingleTop = true }
            }
            return
        }
        // Other tabs sit directly on top of Home, so Back / the Home tab always returns there.
        nav.navigate(tab.route) {
            popUpTo(NavTab.HOME.route)
            launchSingleTop = true
        }
    }

    fun openHistory(filter: String) {
        nav.navigate("${NavTab.HISTORY.route}?filter=$filter") {
            popUpTo(NavTab.HOME.route)
            launchSingleTop = true
        }
    }

    fun openResult(id: String) = nav.navigate("${Routes.RESULT}/$id")

    val structureLoader = remember(vm) { StructureImageLoader { smiles, w, h, dark -> vm.structureImage(smiles, w, h, dark) } }
    CompositionLocalProvider(LocalStructureImageLoader provides structureLoader) {
        NavHost(
            navController = nav,
            startDestination = if (authState is AuthState.SignedIn) NavTab.HOME.route else Routes.ONBOARDING,
            modifier = Modifier.fillMaxSize().background(Bg),
            enterTransition = { fadeIn(tween(220)) },
            exitTransition = { fadeOut(tween(220)) },
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    onGetStarted = { nav.navigate(Routes.LOGIN) },
                    onSignUp = { nav.navigate(Routes.SIGN_UP) },
                )
            }
            composable(Routes.LOGIN) {
                LoginScreen(
                    vm,
                    onBack = { nav.popBackStack() },
                    onSignUp = {
                        nav.navigate(Routes.SIGN_UP) { popUpTo(Routes.ONBOARDING) }
                    },
                    onForgotPassword = { nav.navigate(Routes.FORGOT_PASSWORD) },
                )
            }
            composable(Routes.FORGOT_PASSWORD) {
                ForgotPasswordScreen(vm, onBack = {
                    vm.clearPasswordReset()
                    nav.popBackStack()
                })
            }
            composable(Routes.SIGN_UP) {
                SignUpScreen(
                    vm,
                    onBack = { nav.popBackStack() },
                    onLogin = {
                        nav.navigate(Routes.LOGIN) { popUpTo(Routes.ONBOARDING) }
                    },
                )
            }
            composable(NavTab.HOME.route) {
                MainScaffold(NavTab.HOME, { goTab(it) }) { padding ->
                    HomeScreen(
                        vm, padding,
                        onNewPrediction = { goTab(NavTab.PREDICT) },
                        onOpenCompound = { openResult(it) },
                        onOpenHistory = { openHistory(it) },
                        onOpenInsights = { goTab(NavTab.INSIGHTS) },
                    )
                }
            }
            composable(
                "${NavTab.HISTORY.route}?filter={filter}",
                arguments = listOf(navArgument("filter") { type = NavType.StringType; defaultValue = "all" }),
            ) { entry ->
                val filter = entry.arguments?.getString("filter") ?: "all"
                MainScaffold(NavTab.HISTORY, { goTab(it) }) { padding ->
                    HistoryScreen(vm, padding, filter, onOpenCompound = { openResult(it) })
                }
            }
            composable(NavTab.PREDICT.route) {
                MainScaffold(NavTab.PREDICT, { goTab(it) }) { padding ->
                    PredictScreen(vm, padding, onResult = { openResult(it) })
                }
            }
            composable(NavTab.INSIGHTS.route) {
                MainScaffold(NavTab.INSIGHTS, { goTab(it) }) { padding ->
                    InsightsScreen(vm, padding, onOpenHistory = { openHistory(it) })
                }
            }
            composable(NavTab.PROFILE.route) {
                MainScaffold(NavTab.PROFILE, { goTab(it) }) { padding ->
                    ProfileScreen(
                        vm, padding,
                        onAccountDetails = { nav.navigate(Routes.ACCOUNT) },
                        onLogout = { vm.logout() },
                    )
                }
            }
            composable(Routes.ACCOUNT) {
                MainScaffold(NavTab.PROFILE, { goTab(it) }) { padding ->
                    AccountScreen(vm, padding, onBack = { nav.popBackStack() })
                }
            }
            composable("${Routes.RESULT}/{id}") { entry ->
                ResultScreen(
                    vm,
                    compoundId = entry.arguments?.getString("id").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onOpenCompound = { openResult(it) },
                    onPredictAnother = {
                        nav.navigate(NavTab.PREDICT.route) {
                            popUpTo(NavTab.HOME.route)
                            launchSingleTop = true
                        }
                    },
                    onTab = { goTab(it) },
                )
            }
        }
    }
}
