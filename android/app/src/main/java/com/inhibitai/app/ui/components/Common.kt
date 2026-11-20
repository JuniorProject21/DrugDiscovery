package com.inhibitai.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inhibitai.app.data.Level
import com.inhibitai.app.data.PredictionStatus
import com.inhibitai.app.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin

// ---------- spacing helpers ----------
@Composable fun VSpace(h: Dp) = Spacer(Modifier.height(h))
@Composable fun HSpace(w: Dp) = Spacer(Modifier.width(w))

/**
 * True when the phone's font size is turned up (Settings > Display > Font size). A row of fixed
 * tiles stacks into a column at that point, instead of squeezing its numbers until they clip.
 */
@Composable
fun largeText(threshold: Float = 1.3f): Boolean = LocalConfiguration.current.fontScale >= threshold

// ---------- colour helpers ----------
fun statusColor(status: PredictionStatus): Color = when (status) {
    PredictionStatus.ACTIVE -> Green
    PredictionStatus.INACTIVE -> Red
    PredictionStatus.UNCERTAIN -> Yellow
}

fun statusIcon(status: PredictionStatus): ImageVector = when (status) {
    PredictionStatus.ACTIVE -> Icons.Outlined.CheckCircle
    PredictionStatus.INACTIVE -> Icons.Outlined.Cancel
    PredictionStatus.UNCERTAIN -> Icons.AutoMirrored.Outlined.HelpOutline
}

/** For absorption / distribution / metabolism: higher is better. */
fun admeColor(level: Level): Color = when (level) {
    Level.HIGH, Level.GOOD -> Green
    Level.MODERATE -> Yellow
    Level.LOW -> Red
}

/** For toxicity: lower is better. */
fun toxicityColor(level: Level): Color = when (level) {
    Level.LOW -> Green
    Level.MODERATE, Level.GOOD -> Yellow
    Level.HIGH -> Red
}

// ---------- brand ----------
@Composable
fun BrandLogo(modifier: Modifier = Modifier, color: Color = PurpleLight) {
    Canvas(modifier) {
        val s = size.minDimension
        val r = s * 0.15f
        val sw = s * 0.035f
        val a = Offset(0.40f * s, 0.30f * s)
        val centers = listOf(a, Offset(a.x + 0.866f * r, a.y + 1.5f * r), Offset(a.x, a.y + 3f * r))
        fun vertex(c: Offset, k: Int): Offset {
            val ang = Math.toRadians(90.0 + 60.0 * k)
            return Offset(c.x + r * cos(ang).toFloat(), c.y - r * sin(ang).toFloat())
        }
        centers.forEach { c ->
            val path = Path().apply {
                val v0 = vertex(c, 0)
                moveTo(v0.x, v0.y)
                for (k in 1..5) {
                    val v = vertex(c, k)
                    lineTo(v.x, v.y)
                }
                close()
            }
            drawPath(path, color, style = Stroke(width = sw, join = StrokeJoin.Round))
            val p1 = Offset(c.x - r * 0.5f, c.y - r * 0.2f)
            drawLine(color, p1, Offset(c.x - r * 0.5f, c.y + r * 0.3f), sw * 0.8f, StrokeCap.Round)
            drawLine(color, p1, Offset(c.x - r * 0.15f, c.y - r * 0.45f), sw * 0.8f, StrokeCap.Round)
        }
        listOf(0 to 0, 0 to 1, 0 to 5, 2 to 2, 1 to 4).forEach { (ci, k) ->
            val c = centers[ci]
            val v = vertex(c, k)
            val dir = (v - c) / r
            val end = v + dir * (r * 0.55f)
            val dotR = r * 0.2f
            drawLine(color, v, end, sw, StrokeCap.Round)
            drawCircle(color, dotR, end + dir * dotR, style = Stroke(sw))
        }
    }
}

@Composable
fun BrandTitle(titleSize: TextUnit = 30.sp, subtitleSize: TextUnit = 15.sp) {
    Column {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = TextPrimary)) { append("Inhibit") }
                withStyle(SpanStyle(color = PurpleLight)) { append("AI") }
            },
            fontSize = titleSize,
            fontWeight = FontWeight.SemiBold,
        )
        Text("PARP-1 Predictor", fontSize = subtitleSize, color = TextSecondary)
    }
}

/**
 * What the bell in the header shows and does. Provided once by the navigation host so that every
 * screen's header has it without threading a callback through each one.
 */
data class BellState(val unread: Int = 0, val open: () -> Unit = {})

val LocalBell = staticCompositionLocalOf { BellState() }

@Composable
fun AppHeader(modifier: Modifier = Modifier, onBellClick: (() -> Unit)? = null) {
    val bell = LocalBell.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BrandLogo(Modifier.size(58.dp))
        HSpace(10.dp)
        BrandTitle()
        Spacer(Modifier.weight(1f))
        ThemeToggleButton()
        HSpace(10.dp)
        NotificationBell(onBellClick ?: bell.open, bell.unread)
    }
}

/**
 * Switches between the light and dark palettes. Sized and styled like [NotificationBell] so the
 * two sit together in the header, and used on the signed-out screens too.
 */
@Composable
fun ThemeToggleButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dark = ThemeState.isDark
    Box(
        modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(HeaderButtonBg)
            .border(1.dp, CardBorder, CircleShape)
            .clickable { ThemeState.toggle(context) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (dark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
            if (dark) "Switch to the light theme" else "Switch to the dark theme",
            tint = TextPrimary,
            modifier = Modifier.size(26.dp),
        )
    }
}

@Composable
fun NotificationBell(onClick: () -> Unit, unread: Int = 0) {
    val spoken = when {
        unread == 0 -> "Notifications"
        unread == 1 -> "Notifications, 1 unread"
        else -> "Notifications, $unread unread"
    }
    Box(Modifier.size(54.dp).clearAndSetSemantics { contentDescription = spoken; role = Role.Button }) {
        Box(
            Modifier
                .matchParentSize()
                .clip(CircleShape)
                .background(HeaderButtonBg)
                .border(1.dp, CardBorder, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Notifications, null, tint = TextPrimary, modifier = Modifier.size(26.dp))
        }
        if (unread > 0) {
            // A count up to 9, then "9+", so the badge stays inside the bell.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Red)
                    .border(2.dp, HeaderButtonBg, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (unread > 9) "9+" else "$unread",
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1,
                )
            }
        }
    }
}

/**
 * A strip of equal-width tiles, which becomes one tile per line when the phone's font size is
 * turned up. Side by side, a tile only gets a fraction of the width and its label clips.
 */
@Composable
fun AdaptiveCells(
    cells: List<@Composable (Modifier) -> Unit>,
    spacing: Dp = 14.dp,
    stacked: Boolean = largeText(),
) {
    if (stacked) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spacing)) {
            cells.forEach { cell -> cell(Modifier.fillMaxWidth()) }
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
            cells.forEach { cell -> cell(Modifier.weight(1f)) }
        }
    }
}

// ---------- surfaces ----------
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    background: Brush = SolidColor(CardBg),
    borderColor: Color = CardBorder,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    /** What a screen reader says the tap will do, e.g. "Open this prediction". */
    clickLabel: String? = null,
    longClickLabel: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier.combinedClickable(
                        onClickLabel = clickLabel,
                        onLongClickLabel = longClickLabel,
                        onLongClick = onLongClick,
                        onClick = onClick,
                    )
                }
            ),
        content = content,
    )
}

/** Shown when the backend can't be reached or a refresh failed. */
@Composable
fun ErrorBanner(message: String, retrying: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    GlassCard(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        background = Brush.linearGradient(listOf(Red.copy(alpha = 0.10f), CardBg)),
        borderColor = Red.copy(alpha = 0.45f),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CloudOff, null, tint = Red, modifier = Modifier.size(26.dp))
            HSpace(12.dp)
            Text(message, color = TextPrimary, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
            HSpace(8.dp)
            if (retrying) {
                CircularProgressIndicator(color = PurpleLight, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            } else {
                Text(
                    "Retry", color = PurpleLight, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onRetry).padding(8.dp),
                )
            }
        }
    }
}

/**
 * Says that the results on screen were read from the device because the server can't be reached.
 * Unlike [ErrorBanner] this isn't a failure: there is something to look at, just not a live copy.
 */
@Composable
fun OfflineBanner(message: String, retrying: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    GlassCard(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        background = Brush.linearGradient(listOf(Blue.copy(alpha = 0.10f), CardBg)),
        borderColor = Blue.copy(alpha = 0.45f),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CloudOff, null, tint = Blue, modifier = Modifier.size(26.dp))
            HSpace(12.dp)
            Text(message, color = TextPrimary, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
            HSpace(8.dp)
            if (retrying) {
                CircularProgressIndicator(color = PurpleLight, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            } else {
                Text(
                    "Retry", color = PurpleLight, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onRetry).padding(8.dp),
                )
            }
        }
    }
}

/** One of a short row of mutually exclusive choices, e.g. "Email" / "Phone". */
@Composable
fun SegmentedChoice(
    options: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(FieldBg)
            .border(1.dp, FieldBorder, shape)
            .padding(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val isSelected = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(shape)
                    .background(if (isSelected) PrimaryGradient else SolidColor(Color.Transparent))
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, fontSize = 17.sp, maxLines = 2, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) Color.White else TextSecondary,
                )
            }
        }
    }
}

/** A selectable pill used for filters and sort choices. */
@Composable
fun ChoicePill(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(if (selected) PrimaryGradient else SolidColor(Color.Transparent))
            .border(1.dp, if (selected) Color.Transparent else CardBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { this.selected = selected; role = Role.Tab },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 15.sp, color = if (selected) Color.White else TextPrimary, maxLines = 1)
    }
}

@Composable
fun StatusPill(status: PredictionStatus, modifier: Modifier = Modifier, compact: Boolean = false) {
    val color = statusColor(status)
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.45f), shape)
            .padding(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 7.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(if (compact) 10.dp else 11.dp).clip(CircleShape).background(color))
        HSpace(8.dp)
        Text(status.label, color = color, fontSize = if (compact) 15.sp else 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun FlaskTile(modifier: Modifier = Modifier, iconSize: Dp = 48.dp, corner: Dp = 24.dp) {
    val shape = RoundedCornerShape(corner)
    Box(
        modifier
            .shadow(16.dp, shape, ambientColor = Purple, spotColor = Purple)
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF9F67FF), Color(0xFF5B21B6))))
            .border(1.dp, Color(0x80D8B4FE), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Science, null, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun IconTile(icon: ImageVector, modifier: Modifier = Modifier, tint: Color = PurpleLight, iconSize: Dp = 26.dp) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .clip(shape)
            .background(IconTileBg)
            .border(1.dp, IconTileBorder, shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun Chevron(tint: Color = TextSecondary, size: Dp = 28.dp) {
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = tint, modifier = Modifier.size(size))
}

// ---------- dashboard cards ----------
@Composable
fun TotalPredictedCard(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassCard(
        modifier.fillMaxWidth(),
        background = TotalCardGradient,
        borderColor = Purple.copy(alpha = 0.45f),
        onClick = onClick,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            FlaskTile(Modifier.size(96.dp))
            HSpace(20.dp)
            Column(Modifier.weight(1f)) {
                Text("$count", fontSize = 56.sp, lineHeight = 60.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                Text("Compounds Predicted", fontSize = 18.sp, color = PurpleText, fontWeight = FontWeight.Medium)
            }
            Box(Modifier.width(1.dp).height(80.dp).background(CardBorder))
            HSpace(12.dp)
            Chevron(size = 30.dp)
        }
    }
}

@Composable
fun MiniStatCard(count: Int, status: PredictionStatus, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = statusColor(status)
    GlassCard(
        modifier,
        background = Brush.linearGradient(listOf(color.copy(alpha = 0.10f), CardBg)),
        borderColor = color.copy(alpha = 0.30f),
        onClick = onClick,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Sized so that the longest label ("Inactive") fits on one line on a phone-width tile.
            Icon(statusIcon(status), null, tint = color, modifier = Modifier.size(44.dp))
            HSpace(10.dp)
            Column(Modifier.weight(1f)) {
                Text("$count", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text(
                    status.label, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (status == PredictionStatus.ACTIVE) TextPrimary.copy(alpha = 0.85f) else color.copy(alpha = 0.9f),
                )
            }
            Chevron(size = 20.dp)
        }
    }
}

@Composable
fun NewPredictionBanner(onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassCard(
        modifier.fillMaxWidth(),
        background = BannerGradient,
        borderColor = Purple.copy(alpha = 0.6f),
        onClick = onClick,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val tileShape = RoundedCornerShape(16.dp)
            Box(
                Modifier
                    .size(60.dp)
                    .clip(tileShape)
                    .background(Color(0x66A78BFA))
                    .border(1.dp, Color(0x55E9D5FF), tileShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
            HSpace(16.dp)
            Column(Modifier.weight(1f)) {
                Text("New Prediction", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                VSpace(4.dp)
                Text("Enter a SMILES string and predict activity", fontSize = 14.sp, color = Color(0xFFD8CCF5))
            }
            HSpace(10.dp)
            Box(
                Modifier.size(54.dp).clip(CircleShape).background(PurpleText),
                contentAlignment = Alignment.Center,
            ) {
                Chevron(tint = Color(0xFF2E1065), size = 32.dp)
            }
        }
    }
}

@Composable
fun InfoTile(icon: ImageVector, label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassCard(modifier, onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, Modifier.size(52.dp))
            HSpace(12.dp)
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 14.sp, color = TextSecondary, maxLines = 2)
                Text(value, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            }
            Chevron(size = 24.dp)
        }
    }
}

@Composable
fun SectionHeader(title: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, modifier = Modifier.weight(1f))
        if (actionLabel != null) {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(actionLabel, fontSize = 16.sp, color = PurpleLight)
                Chevron(tint = PurpleLight, size = 22.dp)
            }
        }
    }
}

// ---------- buttons ----------
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    loading: Boolean = false,
    enabled: Boolean = true,
    height: Dp = 60.dp,
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .shadow(18.dp, shape, ambientColor = Purple, spotColor = Purple)
            .clip(shape)
            .background(if (enabled) PrimaryGradient else SolidColor(DisabledButton))
            .border(1.dp, Color(0x55E9D5FF), shape)
            .clickable(enabled = enabled && !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp, modifier = Modifier.size(26.dp))
        } else {
            Row(
                Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leadingIcon != null) {
                    Icon(leadingIcon, null, tint = Color.White, modifier = Modifier.size(26.dp))
                    HSpace(12.dp)
                }
                Text(
                    text, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }
            if (trailingIcon != null) {
                Icon(
                    trailingIcon, null, tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 24.dp).size(26.dp),
                )
            }
        }
    }
}

@Composable
fun OutlinePillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, height: Dp = 60.dp) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .clip(shape)
            .border(1.5.dp, PurpleLight.copy(alpha = 0.8f), shape)
            .clickable(onClick = onClick, role = Role.Button),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = PurpleLight,
            textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
        )
    }
}

@Composable
fun OutlineActionButton(icon: ImageVector, text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier
            .heightIn(min = 64.dp)
            .clip(shape)
            .border(1.dp, PurpleLight.copy(alpha = 0.6f), shape)
            .clickable(onClick = onClick, role = Role.Button)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = PurpleLight, modifier = Modifier.size(22.dp))
        HSpace(8.dp)
        Text(text, fontSize = 16.sp, color = PurpleLight, maxLines = 2, textAlign = TextAlign.Center)
    }
}

// ---------- row overflow menu ----------
@Composable
fun CompoundMenu(
    isSaved: Boolean,
    onView: () -> Unit,
    onToggleSave: () -> Unit,
    onDelete: () -> Unit,
    label: String = "More actions",
    /** Shows a "Select" item, so multi-select is reachable without knowing about the long press. */
    onSelect: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.MoreVert, label, tint = TextSecondary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(CardBg)) {
            DropdownMenuItem(text = { Text("View details", color = TextPrimary) }, onClick = { open = false; onView() })
            DropdownMenuItem(
                text = { Text(if (isSaved) "Remove from saved" else "Save result", color = TextPrimary) },
                onClick = { open = false; onToggleSave() },
            )
            if (onSelect != null) {
                DropdownMenuItem(
                    text = { Text("Select", color = TextPrimary) },
                    onClick = { open = false; onSelect() },
                )
            }
            DropdownMenuItem(text = { Text("Delete", color = Red) }, onClick = { open = false; onDelete() })
        }
    }
}
