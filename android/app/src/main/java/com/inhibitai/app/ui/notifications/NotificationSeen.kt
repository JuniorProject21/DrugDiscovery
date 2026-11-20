package com.inhibitai.app.ui.notifications

import android.content.Context
import java.time.LocalDateTime

/**
 * When the user last opened the notification list, so the bell can show how many arrived since.
 * Kept in SharedPreferences, like the theme choice, so it survives a restart and isn't wiped on
 * sign-out along with the session.
 */
object NotificationSeen {

    private const val PREFS = "notifications"
    private const val KEY_LAST_SEEN = "last_seen"

    fun load(context: Context): LocalDateTime {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST_SEEN, null)
        // Never looked: everything counts as new.
        return stored?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: LocalDateTime.MIN
    }

    fun save(context: Context, at: LocalDateTime) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_SEEN, at.toString()).apply()
    }
}
