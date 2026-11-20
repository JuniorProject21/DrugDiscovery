package com.inhibitai.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ---- Light / dark switch ----

/**
 * Which palette the app is drawn with. Light is the default. The choice is kept in
 * SharedPreferences so it survives restarts (and isn't wiped when the user logs out, unlike the
 * session store).
 */
object ThemeState {
    var isDark by mutableStateOf(false)
        private set

    private const val PREFS = "appearance"
    private const val KEY_DARK = "dark_mode"

    /** Called from MainActivity.onCreate, before the first frame, so the saved theme shows with no flash. */
    fun load(context: Context) {
        isDark = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DARK, false)
    }

    /**
     * Saves the new theme, then rebuilds the screen from scratch so every screen (including ones
     * further back, such as Sign Up behind Welcome) is drawn in it. This is the same thing Android
     * does for its own dark-mode switch. The current screen, typed text and the signed-in session
     * are kept across the rebuild.
     */
    fun toggle(context: Context) {
        val dark = !isDark
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DARK, dark).commit()
        isDark = dark
        context.findActivity()?.recreate()
    }


    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

private fun pick(dark: Color, light: Color): Color = if (ThemeState.isDark) dark else light

// ---- Palette taken from the prototype (dark), with a matching light version ----
val Bg get() = pick(Color(0xFF07060D), Color(0xFFF6F4FB))
val BgElevated get() = pick(Color(0xFF0E0C18), Color(0xFFFFFFFF))
val CardBg get() = pick(Color(0xFF110F1D), Color(0xFFFFFFFF))
val CardBorder get() = pick(Color(0xFF2B2640), Color(0xFFE3DEF0))
val FieldBg get() = pick(Color(0xFF12101E), Color(0xFFFFFFFF))
val FieldBorder get() = pick(Color(0xFF2E2846), Color(0xFFD9D2EA))

val Purple = Color(0xFF8B5CF6)
val PurpleDeep = Color(0xFF6D28D9)
val PurpleLight get() = pick(Color(0xFFB57BFF), Color(0xFF7C3AED))
val PurpleText get() = pick(Color(0xFFC084FC), Color(0xFF7E22CE))

// Status colours: bright on dark, deeper on light so text stays readable on white.
val Green get() = pick(Color(0xFF4ADE80), Color(0xFF16A34A))
val Red get() = pick(Color(0xFFFB5A78), Color(0xFFE11D48))
val Yellow get() = pick(Color(0xFFFACC15), Color(0xFFCA8A04))
val Blue get() = pick(Color(0xFF38BDF8), Color(0xFF0284C7))

val TextPrimary get() = pick(Color(0xFFF5F3FF), Color(0xFF1A1630))
val TextSecondary get() = pick(Color(0xFFA7A3BC), Color(0xFF5E5A73))
val TextMuted get() = pick(Color(0xFF7C7893), Color(0xFF8A869D))
/** Slightly softer than TextPrimary (greetings, tile labels). */
val TextSoft get() = pick(Color(0xFFD9D5EA), Color(0xFF3B3652))

// Component surfaces that used to be hard-coded hex values.
val HeaderButtonBg get() = pick(Color(0xE615131F), Color(0xE6FFFFFF))
val IconTileBg get() = pick(Color(0xFF1F1835), Color(0xFFF1EBFF))
val IconTileBorder get() = pick(Color(0xFF34295A), Color(0xFFDCD0FA))
val SearchFieldBg get() = pick(Color(0xFF100E1C), Color(0xFFFFFFFF))
val PreviewBg get() = pick(Color(0xFF0A0912), Color(0xFFFFFFFF))
val PreviewBorder get() = pick(Color(0xFF1C1929), Color(0xFFE3DEF0))
/** Background behind small structure drawings. Light theme uses white with dark bonds. */
val ThumbBg get() = pick(Color(0xFF0B0A14), Color(0xFFFFFFFF))
val TrackBg get() = pick(Color(0xFF26223A), Color(0xFFE9E4F5))
val PagerDotIdle get() = pick(Color(0xFF2E2A40), Color(0xFFD6CFEA))
val SocialBorder get() = pick(Color(0xFF3A3354), Color(0xFFD4CDE8))
val FieldIcon get() = pick(Color(0xFFC4B5FD), Color(0xFF7C3AED))
val FieldIconMuted get() = pick(Color(0xFF8B85A8), Color(0xFF8A849F))
val DisabledButton get() = pick(Color(0xFF3A2D5C), Color(0xFFC9BCF2))
val PurpleTint get() = pick(Color(0xFF15102A), Color(0xFFF3EEFF))

val TotalCardGradient
    get() = Brush.linearGradient(
        if (ThemeState.isDark) listOf(Color(0xFF1C1530), Color(0xFF0F0D1A)) else listOf(Color(0xFFF1EAFF), Color(0xFFFFFFFF)),
    )

/** The "New Prediction" banner stays a saturated purple in both themes, with white text. */
val BannerGradient
    get() = Brush.horizontalGradient(
        if (ThemeState.isDark) listOf(Color(0xFF3B1F78), Color(0xFF2A1458), Color(0xFF4C1D95))
        else listOf(Color(0xFF7C3AED), Color(0xFF6D28D9), Color(0xFF8B5CF6)),
    )

val PrimaryGradient = Brush.horizontalGradient(listOf(Color(0xFF9B5CF6), Color(0xFF6D28D9)))

@Composable
fun InhibitTheme(content: @Composable () -> Unit) {
    val scheme = if (ThemeState.isDark) {
        darkColorScheme(
            primary = Purple, onPrimary = Color.White, secondary = PurpleLight,
            background = Bg, onBackground = TextPrimary, surface = CardBg, onSurface = TextPrimary,
            surfaceVariant = CardBg, onSurfaceVariant = TextSecondary, outline = CardBorder, error = Red,
        )
    } else {
        lightColorScheme(
            primary = PurpleDeep, onPrimary = Color.White, secondary = PurpleLight,
            background = Bg, onBackground = TextPrimary, surface = CardBg, onSurface = TextPrimary,
            surfaceVariant = CardBg, onSurfaceVariant = TextSecondary, outline = CardBorder, error = Red,
        )
    }
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
