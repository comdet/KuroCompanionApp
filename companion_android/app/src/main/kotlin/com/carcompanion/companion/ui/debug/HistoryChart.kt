package com.carcompanion.companion.ui.debug

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.carcompanion.companion.service.StateHistoryPoint

/**
 * Two-line history chart for valence (blue) and arousal (orange) over the
 * service's rolling buffer (~5 minutes when sampling at ~1Hz).
 *
 * Drawn with Canvas rather than Vico for two reasons: (a) the buffer is
 * already a dense in-memory list — no need for a charting framework's
 * model adapter, (b) we want each axis line to share a single y-grid
 * locked to [-1, +1] which Vico's stock cartesian chart can do but with
 * more ceremony than it's worth here.
 */
@Composable
fun HistoryChart(
    points: List<StateHistoryPoint>,
    modifier: Modifier = Modifier,
) {
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val valenceColor = MaterialTheme.colorScheme.primary
    val arousalColor = MaterialTheme.colorScheme.tertiary

    Column(modifier = modifier) {
        Text("Valence/Arousal over time", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LegendDot(valenceColor)
            Text("valence", style = MaterialTheme.typography.labelSmall)
            LegendDot(arousalColor)
            Text("arousal", style = MaterialTheme.typography.labelSmall)
            Text(" · ${points.size} samples",
                style = MaterialTheme.typography.labelSmall)
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                .padding(4.dp),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                // zero-line + bounds
                drawLine(grid, Offset(0f, h / 2f), Offset(w, h / 2f), strokeWidth = 1f)
                drawLine(grid.copy(alpha = 0.2f), Offset(0f, h * 0.25f), Offset(w, h * 0.25f), strokeWidth = 0.7f)
                drawLine(grid.copy(alpha = 0.2f), Offset(0f, h * 0.75f), Offset(w, h * 0.75f), strokeWidth = 0.7f)

                if (points.size < 2) return@Canvas
                val step = w / (points.size - 1).toFloat()

                fun yFor(v: Float) = (1f - (v + 1f) / 2f) * h

                val vPath = Path()
                val aPath = Path()
                for ((i, p) in points.withIndex()) {
                    val x = step * i
                    val vy = yFor(p.valence)
                    val ay = yFor(p.arousal)
                    if (i == 0) {
                        vPath.moveTo(x, vy); aPath.moveTo(x, ay)
                    } else {
                        vPath.lineTo(x, vy); aPath.lineTo(x, ay)
                    }
                }
                drawPath(vPath, valenceColor,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
                drawPath(aPath, arousalColor,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))

                // Current marker
                val last = points.last()
                val lx = step * (points.size - 1)
                drawCircle(valenceColor, radius = 4f, center = Offset(lx, yFor(last.valence)))
                drawCircle(arousalColor, radius = 4f, center = Offset(lx, yFor(last.arousal)))
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color) {
    Box(modifier = Modifier
        .size(10.dp)
        .clip(RoundedCornerShape(50))
        .background(color))
}
