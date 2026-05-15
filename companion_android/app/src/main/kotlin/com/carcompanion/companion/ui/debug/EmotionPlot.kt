package com.carcompanion.companion.ui.debug

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.carcompanion.companion.domain.Emotion

/**
 * 2D scatter showing the character's current (valence, arousal) point plus the
 * named emotion zones as translucent backgrounds — a circumplex-style mood map.
 *
 *           arousal +1
 *               │
 *      ANGRY ───┼─── EXCITED
 *               │
 *  -1 ──────────●─────────── +1   valence
 *               │
 *       SAD  ───┼─── SLEEPY
 *               │
 *           arousal -1
 */
@Composable
fun EmotionPlot(
    valence: Float,
    arousal: Float,
    emotion: Emotion,
    modifier: Modifier = Modifier,
) {
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val dotColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f

            // Zone backgrounds — quadrant tints (HAPPY / EXCITED / ANGRY / SAD / SLEEPY / BORED)
            val zones = listOf(
                ZoneSpec("EXCITED", v = 0.2f to 1f,  a = 0.5f to 1f,  tint = Color(0x33FFC107)),
                ZoneSpec("HAPPY",   v = 0.3f to 1f,  a = -0.3f to 0.5f, tint = Color(0x3380D8FF)),
                ZoneSpec("ANGRY",   v = -1f to -0.3f, a = 0.3f to 1f,  tint = Color(0x33E91E63)),
                ZoneSpec("SAD",     v = -1f to -0.3f, a = -0.5f to 0.2f, tint = Color(0x33304FFE)),
                ZoneSpec("SLEEPY",  v = -0.2f to 0.5f, a = -1f to -0.4f, tint = Color(0x335E35B1)),
                ZoneSpec("BORED",   v = -0.4f to 0.2f, a = -0.4f to 0.1f, tint = Color(0x33455A64)),
            )
            zones.forEach { z ->
                val x0 = mapValence(z.v.first, w)
                val x1 = mapValence(z.v.second, w)
                val y0 = mapArousal(z.a.second, h)
                val y1 = mapArousal(z.a.first, h)
                drawRect(
                    color = z.tint,
                    topLeft = Offset(x0, y0),
                    size = Size(x1 - x0, y1 - y0),
                )
            }

            // Axes (cross)
            drawLine(gridColor, Offset(0f, cy), Offset(w, cy), strokeWidth = 1.5f)
            drawLine(gridColor, Offset(cx, 0f), Offset(cx, h), strokeWidth = 1.5f)
            // Bounds
            drawRect(gridColor, topLeft = Offset.Zero, size = Size(w, h),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f))

            // Current point
            val px = mapValence(valence.coerceIn(-1f, 1f), w)
            val py = mapArousal(arousal.coerceIn(-1f, 1f), h)
            // outer halo
            drawCircle(dotColor.copy(alpha = 0.3f), radius = 14f, center = Offset(px, py))
            // dot
            drawCircle(dotColor, radius = 7f, center = Offset(px, py))
            // crosshair
            drawLine(dotColor.copy(alpha = 0.5f),
                Offset(px - 14f, py), Offset(px + 14f, py),
                strokeWidth = 1.5f, cap = StrokeCap.Round)
            drawLine(dotColor.copy(alpha = 0.5f),
                Offset(px, py - 14f), Offset(px, py + 14f),
                strokeWidth = 1.5f, cap = StrokeCap.Round)
        }

        // Big emoji at the centre for at-a-glance reading
        Text(
            emotion.emoji,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.align(Alignment.TopEnd).width(40.dp).height(40.dp),
        )

        // Axis labels (positioned at extremes, outside the dot)
        Text("arousal+", style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.TopCenter))
        Text("arousal-", style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomCenter))
        Text("- val", style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.CenterStart))
        Text("+ val", style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.CenterEnd))
    }
}

private fun mapValence(v: Float, width: Float): Float =
    ((v + 1f) / 2f) * width

private fun mapArousal(a: Float, height: Float): Float =
    (1f - (a + 1f) / 2f) * height

private data class ZoneSpec(
    val name: String,
    val v: Pair<Float, Float>,
    val a: Pair<Float, Float>,
    val tint: Color,
)
