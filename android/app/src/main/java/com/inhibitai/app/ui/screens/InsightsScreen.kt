package com.inhibitai.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inhibitai.app.data.DayBar
import com.inhibitai.app.data.PredictionStatus
import com.inhibitai.app.ui.InhibitViewModel
import com.inhibitai.app.ui.components.*
import com.inhibitai.app.ui.theme.*
import java.util.Locale

enum class InsightRange(val label: String, val days: Long?) {
    ALL("All Time", null), MONTH("This Month", 30), WEEK("This Week", 7)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InsightsScreen(
    vm: InhibitViewModel,
    contentPadding: PaddingValues,
    onOpenHistory: (String) -> Unit,
) {
    val context = LocalContext.current
    var range by rememberSaveable { mutableStateOf(InsightRange.ALL) }
    val subset = range.days?.let { vm.within(it) } ?: vm.compounds.toList()
    val active = subset.count { it.status == PredictionStatus.ACTIVE }
    val inactive = subset.count { it.status == PredictionStatus.INACTIVE }
    val uncertain = subset.count { it.status == PredictionStatus.UNCERTAIN }

    Box(Modifier.fillMaxSize().background(Bg).padding(contentPadding)) {
        MoleculeBackdrop(HeaderScene, Modifier.align(Alignment.TopEnd).size(250.dp, 430.dp))
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            VSpace(12.dp)
            AppHeader(onBellClick = { Toast.makeText(context, "No new notifications", Toast.LENGTH_SHORT).show() })
            VSpace(30.dp)
            Text("Insights", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = PurpleLight)
            Text(
                "Your drug discovery analytics\nat a glance.",
                fontSize = 19.sp, lineHeight = 26.sp, color = TextSecondary,
            )
            VSpace(26.dp)
            TotalPredictedCard(vm.total, onClick = { onOpenHistory("all") })
            VSpace(14.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MiniStatCard(vm.activeCount, PredictionStatus.ACTIVE, { onOpenHistory("active") }, Modifier.weight(1f))
                MiniStatCard(vm.inactiveCount, PredictionStatus.INACTIVE, { onOpenHistory("inactive") }, Modifier.weight(1f))
            }
            VSpace(14.dp)

            // ---- distribution ----
            GlassCard(
                Modifier.fillMaxWidth(),
                background = Brush.linearGradient(listOf(Color(0xFF15102A), CardBg)),
                borderColor = Purple.copy(alpha = 0.40f),
            ) {
                Column(Modifier.padding(18.dp)) {
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            "Prediction Distribution", fontSize = 19.sp, fontWeight = FontWeight.Medium,
                            color = TextPrimary, modifier = Modifier.align(Alignment.CenterVertically),
                        )
                        RangeSelector(range) { range = it }
                    }
                    VSpace(18.dp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DonutChart(active, inactive, uncertain, Modifier.size(170.dp))
                        HSpace(26.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(if (uncertain > 0) 12.dp else 22.dp)) {
                            LegendEntry(Purple, active, "Active", pct(active, subset.size))
                            LegendEntry(Red, inactive, "Inactive", pct(inactive, subset.size))
                            if (uncertain > 0) LegendEntry(Yellow, uncertain, "Uncertain", pct(uncertain, subset.size))
                        }
                    }
                }
            }
            VSpace(14.dp)

            // ---- weekly chart ----
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    SectionHeader("Last 7 Days", "View All") { onOpenHistory("all") }
                    VSpace(8.dp)
                    val week = vm.lastSevenDays()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        LegendDot(Purple, "Active")
                        HSpace(18.dp)
                        LegendDot(Red, "Inactive")
                        if (week.any { it.uncertain > 0 }) {
                            HSpace(18.dp)
                            LegendDot(Yellow, "Uncertain")
                        }
                    }
                    VSpace(10.dp)
                    WeeklyBarChart(week, Modifier.fillMaxWidth().height(190.dp))
                }
            }
            VSpace(14.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                InfoTile(Icons.Outlined.BarChart, "Average Confidence", "${vm.averageConfidence}%", {}, Modifier.weight(1f))
                InfoTile(Icons.Outlined.BookmarkBorder, "Saved Compounds", "${vm.savedCount}", { onOpenHistory("saved") }, Modifier.weight(1f))
            }
            VSpace(24.dp)
        }
    }
}

private fun pct(part: Int, total: Int): String =
    if (total == 0) "(0%)" else String.format(Locale.US, "(%.1f%%)", part * 100f / total)

@Composable
private fun RangeSelector(selected: InsightRange, onSelect: (InsightRange) -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(Modifier.clip(shape).border(1.dp, CardBorder, shape).padding(3.dp)) {
        InsightRange.entries.forEach { r ->
            val sel = r == selected
            Box(
                Modifier
                    .clip(shape)
                    .background(if (sel) PrimaryGradient else SolidColor(Color.Transparent))
                    .clickable { onSelect(r) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(
                    r.label, fontSize = 13.sp,
                    color = if (sel) Color.White else TextSecondary,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun DonutChart(active: Int, inactive: Int, uncertain: Int, modifier: Modifier = Modifier) {
    val total = active + inactive + uncertain
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 26.dp.toPx()
            val tl = Offset(w / 2, w / 2)
            val sz = Size(size.width - w, size.height - w)
            if (total == 0) {
                drawArc(CardBorder, 0f, 360f, false, tl, sz, style = Stroke(w))
            } else {
                var start = -90f
                listOf(Purple to active, Red to inactive, Yellow to uncertain).forEach { (color, count) ->
                    val sweep = 360f * count / total
                    if (sweep > 0f) drawArc(color, start, sweep, false, tl, sz, style = Stroke(w, cap = StrokeCap.Butt))
                    start += sweep
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$total", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text("Total", fontSize = 16.sp, color = TextSecondary)
        }
    }
}

@Composable
private fun LegendEntry(color: Color, count: Int, label: String, percent: String) {
    Row {
        Box(Modifier.padding(top = 6.dp).size(18.dp).clip(CircleShape).background(color))
        HSpace(14.dp)
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$count", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                HSpace(10.dp)
                Text(label, fontSize = 18.sp, color = TextSecondary)
            }
            Text(percent, fontSize = 17.sp, color = TextSecondary)
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        HSpace(8.dp)
        Text(label, fontSize = 14.sp, color = TextSecondary)
    }
}

@Composable
private fun WeeklyBarChart(data: List<DayBar>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(color = TextSecondary, fontSize = 12.sp)
    val dayStyle = TextStyle(color = TextSecondary, fontSize = 13.sp)
    Canvas(modifier) {
        val axisW = 30.dp.toPx()
        val bottomH = 26.dp.toPx()
        val top = 8.dp.toPx()
        val base = size.height - bottomH
        val plotH = base - top
        // Four gridline steps, each a whole number, tall enough for the busiest day.
        val step = maxOf(1, (data.maxOfOrNull { it.total } ?: 0).let { (it + 3) / 4 })
        val maxV = step * 4f
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))

        for (i in 0..4) {
            val v = i * step
            val y = base - plotH * (v / maxV)
            drawLine(CardBorder, Offset(axisW, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = if (i == 0) null else dash)
            val t = measurer.measure(AnnotatedString("$v"), axisStyle)
            drawText(t, topLeft = Offset(axisW - t.size.width - 8.dp.toPx(), y - t.size.height / 2f))
        }

        val slot = (size.width - axisW) / data.size
        val barW = slot * 0.52f
        val r = 4.dp.toPx()
        data.forEachIndexed { i, d ->
            val x = axisW + slot * i + (slot - barW) / 2f
            val activeH = plotH * (d.active / maxV)
            val decidedH = plotH * ((d.active + d.inactive) / maxV)
            val totalH = plotH * (d.total / maxV)
            if (d.uncertain > 0) drawRoundRect(Yellow, Offset(x, base - totalH), Size(barW, totalH), CornerRadius(r, r))
            if (d.inactive > 0) {
                drawRoundRect(Red, Offset(x, base - decidedH), Size(barW, decidedH), CornerRadius(r, r))
            }
            if (d.active > 0) {
                drawRect(
                    Brush.verticalGradient(listOf(Color(0xFFA06BFF), Color(0xFF7C3AED)), startY = base - activeH, endY = base),
                    Offset(x, base - activeH), Size(barW, activeH),
                )
            }
            val t = measurer.measure(AnnotatedString(d.day), dayStyle)
            drawText(t, topLeft = Offset(x + barW / 2f - t.size.width / 2f, base + 6.dp.toPx()))
        }
    }
}
