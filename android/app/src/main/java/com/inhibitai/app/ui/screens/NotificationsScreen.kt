package com.inhibitai.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inhibitai.app.ui.InhibitViewModel
import com.inhibitai.app.ui.components.*
import com.inhibitai.app.ui.notifications.AppNotification
import com.inhibitai.app.ui.notifications.NotificationKind
import com.inhibitai.app.ui.theme.*
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the bell opens: active hits from the history and the things that happened while the app
 * was running (a screening run finishing, a run that failed).
 *
 * Opening the list is what marks it read, so the badge on the bell only ever counts things the
 * user hasn't looked at.
 */
@Composable
fun NotificationsScreen(
    vm: InhibitViewModel,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenCompound: (String) -> Unit,
) {
    val notifications = vm.notifications
    LaunchedEffect(Unit) { vm.markNotificationsSeen() }

    LazyColumn(
        Modifier.fillMaxSize().background(Bg).padding(contentPadding),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Back", tint = TextPrimary,
                        modifier = Modifier.size(36.dp))
                }
                Text(
                    "Notifications", fontSize = 23.sp, fontWeight = FontWeight.Medium, color = TextPrimary,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(48.dp))
            }
        }
        if (notifications.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(top = 80.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    IconTile(Icons.Outlined.NotificationsNone, Modifier.size(72.dp), iconSize = 34.dp)
                    VSpace(16.dp)
                    Text("Nothing yet", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                    VSpace(6.dp)
                    Text(
                        "Active hits and finished screening runs show up here.",
                        fontSize = 15.sp, lineHeight = 21.sp, color = TextSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        items(notifications, key = { it.id }) { notification ->
            NotificationCard(notification) {
                notification.compoundId?.let(onOpenCompound)
            }
        }
    }
}

@Composable
private fun NotificationCard(notification: AppNotification, onOpen: () -> Unit) {
    val (icon, colour) = when (notification.kind) {
        NotificationKind.ACTIVE_HIT -> Icons.Outlined.CheckCircle to Green
        NotificationKind.BATCH -> Icons.Outlined.UploadFile to PurpleLight
        NotificationKind.SYSTEM -> Icons.Outlined.WarningAmber to Yellow
    }
    GlassCard(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        onClick = if (notification.compoundId != null) onOpen else null,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(colour.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = colour, modifier = Modifier.size(24.dp))
            }
            HSpace(14.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    notification.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                VSpace(3.dp)
                Text(notification.body, fontSize = 14.sp, lineHeight = 20.sp, color = TextSecondary)
                VSpace(6.dp)
                Text(ago(notification.at), fontSize = 12.sp, color = TextMuted)
            }
            if (notification.compoundId != null) {
                HSpace(6.dp)
                Chevron(size = 22.dp)
            }
        }
    }
}

/** "Just now", "12 min ago", "3 h ago", then the date. */
private fun ago(at: LocalDateTime): String {
    val minutes = Duration.between(at, LocalDateTime.now()).toMinutes()
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> at.format(DateTimeFormatter.ofPattern("MMM d, hh:mm a", Locale.US))
    }
}
