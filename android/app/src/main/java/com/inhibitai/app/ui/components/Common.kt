package com.inhibitai.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
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

@Composable
fun AppHeader(modifier: Modifier = Modifier, onBellClick: () -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BrandLogo(Modifier.size(58.dp))
        HSpace(10.dp)
        BrandTitle()
        Spacer(Modifier.weight(1f))
        NotificationBell(onBellClick)
    }
}

@Composable
fun NotificationBell(onClick: () -> Unit) {
    Box(Modifier.size(54.dp)) {
        Box(
            Modifier
                .matchParentSize()
                .clip(CircleShape)
                .background(Color(0xFF15131F).copy(alpha = 0.9f))
                .border(1.dp, CardBorder, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Notifications, "Notifications", tint = TextPrimary, modifier = Modifier.size(26.dp))
        }
    }
}

// ---------- surfaces ----------
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    background: Brush = SolidColor(CardBg),
    borderColor: Color = CardBorder,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
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
            .background(Color(0xFF1F1835))
            .border(1.dp, Color(0xFF34295A), shape),
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
        background = Brush.linearGradient(listOf(Color(0xFF1C1530), Color(0xFF0F0D1A))),
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
            Icon(statusIcon(status), null, tint = color, modifier = Modifier.size(50.dp))
            HSpace(12.dp)
            Column(Modifier.weight(1f)) {
                Text("$count", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text(
                    status.label, fontSize = 17.sp,
                    color = if (status == PredictionStatus.ACTIVE) TextPrimary.copy(alpha = 0.85f) else color.copy(alpha = 0.9f),
                )
            }
            Chevron(size = 24.dp)
        }
    }
}

@Composable
fun NewPredictionBanner(onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassCard(
        modifier.fillMaxWidth(),
        background = Brush.horizontalGradient(listOf(Color(0xFF3B1F78), Color(0xFF2A1458), Color(0xFF4C1D95))),
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
                Text("New Prediction", fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
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
                Text(label, fontSize = 14.sp, color = Color(0xFFD4D0E4), maxLines = 2)
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
            .height(height)
            .shadow(18.dp, shape, ambientColor = Purple, spotColor = Purple)
            .clip(shape)
            .background(if (enabled) PrimaryGradient else SolidColor(Color(0xFF3A2D5C)))
            .border(1.dp, Color(0x55E9D5FF), shape)
            .clickable(enabled = enabled && !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp, modifier = Modifier.size(26.dp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (leadingIcon != null) {
                    Icon(leadingIcon, null, tint = Color.White, modifier = Modifier.size(26.dp))
                    HSpace(12.dp)
                }
                Text(text, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = Color.White)
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
            .height(height)
            .clip(shape)
            .border(1.5.dp, PurpleLight.copy(alpha = 0.8f), shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 20.sp, fontWeight = FontWeight.Medium, color = PurpleLight)
    }
}

@Composable
fun OutlineActionButton(icon: ImageVector, text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier
            .height(64.dp)
            .clip(shape)
            .border(1.dp, PurpleLight.copy(alpha = 0.6f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = PurpleLight, modifier = Modifier.size(22.dp))
        HSpace(8.dp)
        Text(text, fontSize = 16.sp, color = PurpleLight, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------- row overflow menu ----------
@Composable
fun CompoundMenu(isSaved: Boolean, onView: () -> Unit, onToggleSave: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.MoreVert, "More", tint = TextSecondary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(CardBg)) {
            DropdownMenuItem(text = { Text("View details", color = TextPrimary) }, onClick = { open = false; onView() })
            DropdownMenuItem(
                text = { Text(if (isSaved) "Remove from saved" else "Save result", color = TextPrimary) },
                onClick = { open = false; onToggleSave() },
            )
            DropdownMenuItem(text = { Text("Delete", color = Red) }, onClick = { open = false; onDelete() })
        }
    }
}
