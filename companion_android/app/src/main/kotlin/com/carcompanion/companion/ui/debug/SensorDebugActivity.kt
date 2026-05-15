package com.carcompanion.companion.ui.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.carcompanion.companion.service.CarCompanionService
import com.carcompanion.companion.service.NavNotification
import com.carcompanion.companion.ui.theme.CompanionTheme

/**
 * Standalone debug screen for **non-OBD** event sources: location, weather,
 * time-of-day, IMU accelerometer, navigation notifications. Lives separately
 * from the Soul Debug screen so the soul state view stays focused on the
 * persona (emotion / personality / stats / reactions) without sensor noise.
 *
 * Reached from the main screen's "Sensors" button.
 */
class SensorDebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompanionTheme {
                Scaffold { padding ->
                    SensorDebugScreen(modifier = Modifier.padding(padding).fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun SensorDebugScreen(modifier: Modifier = Modifier) {
    val location by CarCompanionService.locationLatLon.collectAsState()
    val env by CarCompanionService.envSnapshot.collectAsState()
    val imu by CarCompanionService.imuSnapshot.collectAsState()
    val navNotifs by CarCompanionService.navNotifications.collectAsState()
    val now = System.currentTimeMillis()
    val scroll = rememberScrollState()

    Column(
        modifier = modifier
            .verticalScroll(scroll)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Sensors & Environment", style = MaterialTheme.typography.titleLarge)
        EnvironmentRow(location = location, env = env)
        ImuRow(imu = imu, nowMs = now)
        NavNotificationsCard(notifications = navNotifs, nowMs = now)
    }
}

@Composable
private fun EnvironmentRow(
    location: com.carcompanion.companion.data.repo.LatLon?,
    env: com.carcompanion.companion.service.EnvSnapshot,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val hasLocationPerm = androidx.core.content.ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val locText = when {
        location != null -> "📍 %.4f, %.4f".format(location.lat, location.lon)
        !hasLocationPerm -> "📍 ⚠ no location permission — tap Grant"
        else -> "📍 waiting for fix (Bangkok fallback)"
    }
    val weatherText = run {
        val bucket = env.weatherBucket?.removePrefix("WEATHER_")?.lowercase()
        val temp = env.tempC?.let { " · %.1f°C".format(it) }.orEmpty()
        val code = env.wmoCode?.takeIf { it >= 0 }?.let { " · wmo $it" }.orEmpty()
        when {
            bucket != null -> "🌤 $bucket$temp$code"
            else -> "🌤 (waiting for first poll)"
        }
    }
    val timeText = env.timeBucket?.removePrefix("TIME_")?.lowercase()?.let { "⏰ $it" }
        ?: "⏰ (waiting)"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                locText,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1.2f),
            )
            Text(weatherText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.5f))
            Text(timeText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.8f))
        }
    }
}

@Composable
private fun ImuRow(
    imu: com.carcompanion.companion.service.ImuSnapshot,
    nowMs: Long,
) {
    val barRange = 15f
    val fraction = (imu.liveMag / barRange).coerceIn(0f, 1f)
    val barColor = when {
        imu.liveMag >= 12f -> MaterialTheme.colorScheme.error
        imu.liveMag >= 6f  -> MaterialTheme.colorScheme.tertiary
        else               -> MaterialTheme.colorScheme.primary
    }
    val lastBumpText = if (imu.lastBumpEpochMs == 0L) "no bump yet"
    else {
        val ago = (nowMs - imu.lastBumpEpochMs) / 1000L
        val agoStr = when {
            ago < 60 -> "${ago}s ago"
            ago < 3600 -> "${ago / 60}m ago"
            else -> "${ago / 3600}h ago"
        }
        "last %.1f m/s² · %s".format(imu.lastBumpMag, agoStr)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("📱 acc", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(56.dp))
                Canvas(modifier = Modifier.height(14.dp).weight(1f)) {
                    val w = size.width; val h = size.height
                    drawRect(color = Color.Gray.copy(alpha = 0.2f), size = Size(w, h))
                    drawRect(color = barColor, size = Size(w * fraction, h))
                }
                Text(
                    "%.2f m/s²".format(imu.liveMag),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.width(96.dp),
                )
            }
            Text(
                lastBumpText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NavNotificationsCard(notifications: List<NavNotification>, nowMs: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                "🗺  Nav notifications (raw — last ${notifications.size})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (notifications.isEmpty()) {
                Text(
                    "Open Google Maps + start navigation. " +
                        "If nothing shows up, grant notification access from the main screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                notifications.asReversed().take(5).forEach { n -> NavNotificationEntry(n, nowMs) }
            }
        }
    }
}

@Composable
private fun NavNotificationEntry(n: NavNotification, nowMs: Long) {
    val ageSec = ((nowMs - n.postedAt) / 1000L).coerceAtLeast(0L)
    val ageStr = when {
        ageSec < 60 -> "${ageSec}s"
        ageSec < 3600 -> "${ageSec / 60}m"
        else -> "${ageSec / 3600}h"
    }
    val tag = if (n.removed) "✕" else "•"
    val source = n.pkg.substringAfterLast('.').take(8)
    val parts = buildList {
        if (n.title.isNotBlank())   add("T: ${n.title}")
        if (n.text.isNotBlank())    add("X: ${n.text}")
        if (n.bigText.isNotBlank() && n.bigText != n.text) add("B: ${n.bigText}")
        if (n.subText.isNotBlank()) add("S: ${n.subText}")
    }
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            "$tag $source · $ageStr ago",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (parts.isEmpty()) {
            Text(
                "    (no text fields)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        } else {
            parts.forEach { p ->
                Text(
                    "    $p",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}
