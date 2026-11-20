package com.inhibitai.app

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.toArgb
import com.inhibitai.app.navigation.InhibitApp
import com.inhibitai.app.ui.notifications.Notifications
import com.inhibitai.app.ui.theme.Bg
import com.inhibitai.app.ui.theme.InhibitTheme
import com.inhibitai.app.ui.theme.ThemeState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Read the saved theme (light by default). Toggling it rebuilds this Activity, so this
        // runs again and every screen starts out in the chosen theme.
        ThemeState.load(this)
        // Must exist before the first notification is posted (batch finished, active hit).
        Notifications.createChannel(this)
        val dark = ThemeState.isDark

        // Light icons on the dark theme, dark icons on the light theme.
        val bars = if (dark) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        // Window colour behind the app, seen briefly during screen transitions.
        window.setBackgroundDrawable(ColorDrawable(Bg.toArgb()))

        setContent {
            InhibitTheme {
                InhibitApp()
            }
        }
    }
}
