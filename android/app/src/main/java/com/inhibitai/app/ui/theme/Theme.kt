package com.inhibitai.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ---- Palette taken from the prototype ----
val Bg = Color(0xFF07060D)
val BgElevated = Color(0xFF0E0C18)
val CardBg = Color(0xFF110F1D)
val CardBorder = Color(0xFF2B2640)
val FieldBg = Color(0xFF12101E)

val Purple = Color(0xFF8B5CF6)
val PurpleDeep = Color(0xFF6D28D9)
val PurpleLight = Color(0xFFB57BFF)
val PurpleText = Color(0xFFC084FC)

val Green = Color(0xFF4ADE80)
val Red = Color(0xFFFB5A78)
val Yellow = Color(0xFFFACC15)
val Blue = Color(0xFF38BDF8)

val TextPrimary = Color(0xFFF5F3FF)
val TextSecondary = Color(0xFFA7A3BC)
val TextMuted = Color(0xFF7C7893)

val PrimaryGradient = Brush.horizontalGradient(listOf(Color(0xFF9B5CF6), Color(0xFF6D28D9)))

@Composable
fun InhibitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Purple,
            onPrimary = Color.White,
            secondary = PurpleLight,
            background = Bg,
            onBackground = TextPrimary,
            surface = CardBg,
            onSurface = TextPrimary,
            surfaceVariant = CardBg,
            onSurfaceVariant = TextSecondary,
            outline = CardBorder,
            error = Red,
        ),
        typography = Typography(),
        content = content,
    )
}
