package com.inhibitai.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/** A glossy sphere in the decorative background. x/y are fractions of the box, depth 0..1 (1 = closest). */
data class SceneNode(val x: Float, val y: Float, val r: Dp, val depth: Float)
data class MoleculeScene(val nodes: List<SceneNode>, val edges: List<Pair<Int, Int>>)

/** Used behind the header on Home, Predict, Insights and Login. */
val HeaderScene = MoleculeScene(
    nodes = listOf(
        SceneNode(0.12f, 0.10f, 11.dp, 0.35f),
        SceneNode(0.38f, 0.18f, 9.dp, 0.35f),
        SceneNode(0.22f, 0.40f, 12.dp, 0.35f),
        SceneNode(0.50f, 0.50f, 10.dp, 0.35f),
        SceneNode(0.02f, 0.62f, 10.dp, 0.30f),
        SceneNode(0.66f, 0.30f, 24.dp, 1f),
        SceneNode(0.90f, 0.50f, 30.dp, 1f),
        SceneNode(0.60f, 0.80f, 30.dp, 1f),
        SceneNode(1.05f, 0.30f, 22.dp, 0.8f),
        SceneNode(0.98f, 0.95f, 20.dp, 0.5f),
    ),
    edges = listOf(0 to 1, 1 to 2, 2 to 3, 2 to 4, 1 to 5, 5 to 6, 6 to 7, 6 to 8, 7 to 9),
)

/** Large hero scene for the onboarding screen. */
val OnboardingScene = MoleculeScene(
    nodes = listOf(
        SceneNode(0.08f, 0.45f, 16.dp, 0.35f),
        SceneNode(0.22f, 0.33f, 13.dp, 0.30f),
        SceneNode(0.12f, 0.72f, 18.dp, 0.35f),
        SceneNode(0.32f, 0.62f, 15.dp, 0.30f),
        SceneNode(0.70f, 0.08f, 13.dp, 0.30f),
        SceneNode(0.86f, 0.18f, 11.dp, 0.30f),
        SceneNode(0.95f, 0.04f, 10.dp, 0.25f),
        SceneNode(0.62f, 0.30f, 13.dp, 0.30f),
        SceneNode(0.66f, 0.74f, 48.dp, 1f),
        SceneNode(0.92f, 0.42f, 44.dp, 1f),
        SceneNode(1.00f, 0.72f, 42.dp, 0.9f),
    ),
    edges = listOf(0 to 1, 0 to 2, 2 to 3, 4 to 5, 5 to 6, 4 to 7, 8 to 10, 9 to 10),
)

@Composable
fun MoleculeBackdrop(scene: MoleculeScene, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        fun pos(n: SceneNode) = Offset(n.x * size.width, n.y * size.height)

        drawCircle(
            Brush.radialGradient(
                listOf(Color(0xFF7C3AED).copy(alpha = 0.18f), Color.Transparent),
                center = Offset(size.width * 0.75f, size.height * 0.5f),
                radius = size.width * 0.9f,
            ),
            radius = size.width * 0.9f,
            center = Offset(size.width * 0.75f, size.height * 0.5f),
        )

        scene.edges
            .sortedBy { (a, b) -> min(scene.nodes[a].depth, scene.nodes[b].depth) }
            .forEach { (a, b) ->
                val na = scene.nodes[a]
                val nb = scene.nodes[b]
                val depth = min(na.depth, nb.depth)
                drawLine(
                    color = Color(0xFF7C4DDB).copy(alpha = 0.85f * depth),
                    start = pos(na),
                    end = pos(nb),
                    strokeWidth = min(na.r.toPx(), nb.r.toPx()) * 0.42f,
                    cap = StrokeCap.Round,
                )
            }

        scene.nodes.sortedBy { it.depth }.forEach { drawSphere(pos(it), it.r.toPx(), it.depth) }
    }
}

private fun DrawScope.drawSphere(c: Offset, r: Float, alpha: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color(0xFFB794F6), Color(0xFF6D28D9), Color(0xFF2E1065)),
            center = c + Offset(-r * 0.35f, -r * 0.4f),
            radius = r * 1.45f,
        ),
        radius = r,
        center = c,
        alpha = alpha,
    )
    drawCircle(Color(0xFFC4B5FD).copy(alpha = 0.25f * alpha), r, c, style = Stroke(1.dp.toPx()))
    drawCircle(Color.White.copy(alpha = 0.35f * alpha), r * 0.18f, c + Offset(-r * 0.4f, -r * 0.45f))
}
