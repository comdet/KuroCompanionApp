package com.carcompanion.companion.ui.debug

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import com.carcompanion.companion.data.CoreTraits
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Hexagonal radar of the six [CoreTraits].
 * Axes (clockwise from top): Openness · Optimism · Sociability ·
 *                            Sensitivity · Energy · Patience
 */
@Composable
fun PersonalityRadar(
    traits: CoreTraits,
    modifier: Modifier = Modifier,
) {
    val outline = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val stroke = MaterialTheme.colorScheme.primary

    val values = listOf(
        "Opn" to traits.openness,
        "Opt" to traits.optimism,
        "Soc" to traits.sociability,
        "Sen" to traits.sensitivity,
        "Ene" to traits.energyBaseline,
        "Pat" to traits.patience,
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = min(size.width, size.height) / 2f * 0.78f

            // Concentric rings (0.25 .. 1.0)
            for (level in listOf(0.25f, 0.5f, 0.75f, 1f)) {
                val ringPath = Path()
                for (i in 0 until 6) {
                    val angle = angleFor(i)
                    val r = radius * level
                    val x = cx + cos(angle) * r
                    val y = cy + sin(angle) * r
                    if (i == 0) ringPath.moveTo(x, y) else ringPath.lineTo(x, y)
                }
                ringPath.close()
                drawPath(
                    ringPath,
                    color = outline.copy(alpha = if (level == 1f) 0.6f else 0.25f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = if (level == 1f) 1.5f else 1f),
                )
            }

            // Spokes
            for (i in 0 until 6) {
                val angle = angleFor(i)
                drawLine(
                    color = outline.copy(alpha = 0.3f),
                    start = Offset(cx, cy),
                    end = Offset(cx + cos(angle) * radius, cy + sin(angle) * radius),
                    strokeWidth = 0.8f,
                )
            }

            // Filled polygon for trait values
            val polyPath = Path()
            for ((i, pair) in values.withIndex()) {
                val (_, value) = pair
                val angle = angleFor(i)
                val r = radius * value.coerceIn(0f, 1f)
                val x = cx + cos(angle) * r
                val y = cy + sin(angle) * r
                if (i == 0) polyPath.moveTo(x, y) else polyPath.lineTo(x, y)
            }
            polyPath.close()
            drawPath(polyPath, color = fill)
            drawPath(
                polyPath, color = stroke,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.2f),
            )

            // Vertex dots
            for ((i, pair) in values.withIndex()) {
                val (_, value) = pair
                val angle = angleFor(i)
                val r = radius * value.coerceIn(0f, 1f)
                drawCircle(stroke, radius = 4f, center = Offset(cx + cos(angle) * r, cy + sin(angle) * r))
            }
        }

        // Trait labels at each axis (placed slightly outside the ring)
        // Note: positioned with arithmetic — kept text small to fit a 200dp box.
        Text("Opn", style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.TopCenter))
        Text("Opt", style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.TopEnd))
        Text("Soc", style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomEnd))
        Text("Ene", style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomCenter))
        Text("Pat", style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomStart))
        Text("Sen", style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.TopStart))
    }
}

/** Angle (radians) for the i-th vertex of a 6-sided radar starting at top. */
private fun angleFor(i: Int): Float =
    (-Math.PI / 2 + i * (2 * Math.PI / 6)).toFloat()
