package com.inhibitai.app.ui.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.inhibitai.app.R
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger

/** What a notification is about; the bell screen colours and sorts them by this. */
enum class NotificationKind { ACTIVE_HIT, BATCH, SYSTEM }

/** One entry in the app's notification list. */
data class AppNotification(
    val id: String,
    val kind: NotificationKind,
    val title: String,
    val body: String,
    val at: LocalDateTime,
    /** A prediction to open when the row is tapped, when there is one. */
    val compoundId: String? = null,
)

/**
 * System notifications, for the things worth telling the user about while they are elsewhere:
 * a batch of several hundred compounds finishing, and an active hit worth looking at.
 *
 * Everything here is local to the device. The app has no push service, and a screening run is
 * exactly the kind of wait where leaving the app is the point.
 */
object Notifications {

    const val CHANNEL_RESULTS = "results"
    private val nextId = AtomicInteger(1000)

    /** Must exist before the first notification is posted (Android 8+). */
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_RESULTS, "Prediction results", NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "Batch screening runs that have finished, and active hits." }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /** True when the user has allowed notifications (always true before Android 13). */
    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Posts one notification. Does nothing when the user hasn't allowed them. */
    fun show(context: Context, title: String, body: String) {
        if (!allowed(context)) return
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(nextId.incrementAndGet(), notification) }
    }
}
