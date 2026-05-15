package com.carcompanion.companion.ui.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.carcompanion.companion.data.DriverProfile
import com.carcompanion.companion.domain.Moodlet
import com.carcompanion.companion.domain.Quirk
import com.carcompanion.companion.service.ReactionLogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Horizontal value bar — label · filled rect · numeric value.
 * Use for energy / bond / etc.
 */
@Composable
fun StatBar(
    label: String,
    value: Float,
    maxValue: Float = 100f,
    color: Color = MaterialTheme.colorScheme.primary,
    valueText: String? = null,
    modifier: Modifier = Modifier,
) {
    val pct = (value / maxValue).coerceIn(0f, 1f)
    Row(modifier = modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(70.dp))
        Box(
            modifier = Modifier.weight(1f).height(10.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(pct)
                    .height(10.dp)
                    .background(color),
            )
        }
        Text(
            valueText ?: value.toInt().toString(),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(start = 8.dp).width(48.dp),
        )
    }
}

/** Stack of moodlets with remaining time. */
@Composable
fun MoodletList(moodlets: List<Moodlet>, nowMs: Long, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text("Moodlets", style = MaterialTheme.typography.titleSmall)
        if (moodlets.isEmpty()) {
            Text("(none active)", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        for (m in moodlets) {
            val left = if (m.expiresAtMs == Long.MAX_VALUE) "open"
                else "${((m.expiresAtMs - nowMs) / 1000).coerceAtLeast(0)}s"
            Row(modifier = Modifier.padding(vertical = 1.dp)) {
                Text(m.id, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(
                    "v%+.2f a%+.2f · %s".format(m.valenceShift, m.arousalShift, left),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}

/** Driver profile summary with three bond bars + driving style. */
@Composable
fun DriverPanel(driver: DriverProfile, modifier: Modifier = Modifier) {
    val mins = driver.totalDrivetimeSec / 60
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Driver: ${driver.nickname ?: driver.id} · trips ${driver.totalTrips} · ${mins}min total",
            style = MaterialTheme.typography.titleSmall,
        )
        StatBar("Bond", driver.bond, color = MaterialTheme.colorScheme.primary)
        StatBar("Trust", driver.trust, color = MaterialTheme.colorScheme.secondary)
        StatBar("Famili", driver.familiarity, color = MaterialTheme.colorScheme.tertiary)
        val s = driver.drivingStyle
        Text(
            "Style: %.0f km/h · %.2f brakes/min · night %.0f%% · ~%dmin trips"
                .format(s.avgSpeed, s.harshBrakeRatio, s.nightDriveRatio * 100f, s.avgTripDurationSec / 60),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

/** Active-quirks chip row. */
@Composable
fun QuirkChips(quirks: Set<Quirk>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (q in quirks) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(q.tag, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (quirks.isEmpty()) {
            Text("(no quirks)", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Scrollable reaction history with timestamp + event + gif + weight. */
@Composable
fun ReactionTimeline(entries: List<ReactionLogEntry>, modifier: Modifier = Modifier) {
    val sdf = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Column(modifier = modifier) {
        Text("Recent reactions", style = MaterialTheme.typography.titleSmall)
        if (entries.isEmpty()) {
            Text("(none yet)", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(entries.asReversed()) { e ->
                val tag = if (e.wasSurprise) "[surprise]" else "w=%.1f".format(e.weight)
                Text(
                    "${sdf.format(Date(e.ts))}  ${e.event}  → ${e.gif ?: "—"}  $tag  (${e.emotion}/${e.hypothesis.label})",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}

