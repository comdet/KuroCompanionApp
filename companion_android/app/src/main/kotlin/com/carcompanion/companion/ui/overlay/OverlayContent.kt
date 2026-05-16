package com.carcompanion.companion.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.carcompanion.companion.network.ObdConnection
import com.carcompanion.companion.service.CarCompanionService
import com.carcompanion.companion.ui.theme.CompanionTheme

/**
 * Compose content for the floating overlay window.
 *
 * Layout (small, glanceable):
 *   ┌─────────────────────────────────┐
 *   │ 😴   - vol +   [mute]   [×]     │
 *   │ └─── drag handle ────┘          │
 *   └─────────────────────────────────┘
 *
 * The mood emoji is the **drag handle** — touching it and moving drags the
 * whole window. Buttons (vol/mute/close) consume their own taps so they don't
 * accidentally trigger drag. Drag from button areas does NOT move the window
 * (so user can tap a button without nudging position).
 *
 * The mute button is a smart toggle:
 *   - muted   → shows VolumeUp icon (tap = restore)
 *   - unmuted → shows VolumeOff icon (tap = mute)
 *
 * Mood emoji is driven by the soul engine's current emotion zone.
 */
/**
 * Composite readiness signal shown as a tiny coloured dot in the
 * overlay. Drives glance-level "is everything connected?" feedback —
 * details are still on the Soul Debug screen.
 *
 *   ALL_OK   green   — robot CCP responding AND OBD bridge attached
 *   PARTIAL  amber   — one of the two is up, the other isn't
 *   OFFLINE  red     — neither is up (service just started, no OBD,
 *                      robot unreachable, …)
 */
enum class SystemStatus { ALL_OK, PARTIAL, OFFLINE }

@Composable
fun OverlayContent(
    moodEmoji: String,
    currentVolume: Int,
    muted: Boolean,
    systemStatus: SystemStatus,
    onVolumeDown: () -> Unit,
    onVolumeUp: () -> Unit,
    onMuteToggle: () -> Unit,
    onDebug: () -> Unit,
    onClose: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit = { _, _ -> },
) {
    CompanionTheme {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Status dot — glance-level "is the stack alive?".
                // Sits next to the mood emoji so it shares the drag area
                // visually without interfering with drag pointer routing.
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            color = systemStatus.tint(),
                            shape = CircleShape,
                        ),
                )

                // Drag handle: capture pointer drags here and forward the
                // raw pixel delta to OverlayService → WindowManager. We use
                // pointerInput on Compose side (not setOnTouchListener on the
                // host View) because Compose consumes the DOWN event before
                // the View-level listener can see the MOVE stream — which is
                // exactly why the previous drag implementation never fired.
                Text(
                    text = moodEmoji,
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                onDrag(dragAmount.x, dragAmount.y)
                            }
                        },
                )

                FilledIconButton(
                    onClick = onVolumeDown,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = "vol down")
                }

                Text(
                    text = currentVolume.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    // 38 dp fits "100" on a single line at titleMedium.
                    // Earlier 28 dp wrapped to two rows on max volume.
                    modifier = Modifier.width(38.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.Center,
                )

                FilledIconButton(
                    onClick = onVolumeUp,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "vol up")
                }

                // Mute toggle: icon flips between off (= currently audible)
                // and up (= currently muted, tap to restore).
                FilledIconButton(
                    onClick = onMuteToggle,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (muted)
                            MaterialTheme.colorScheme.tertiaryContainer
                        else
                            MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    modifier = Modifier.size(40.dp),
                ) {
                    if (muted) {
                        Icon(
                            Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "restore audio",
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = "mute",
                        )
                    }
                }

                // Debug — opens MainActivity (manual mode, won't auto-finish
                // back into overlay) so user can hit Soul/Sensors/Cfg.
                FilledIconButton(
                    onClick = onDebug,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(Icons.Filled.BugReport, contentDescription = "debug")
                }

                // Real clickable close button (was a non-interactive Text
                // before — Compose treated it as static decoration so taps
                // never reached onClose).
                Box(
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(32.dp)
                        .background(
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                            shape = CircleShape,
                        )
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "✕",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * Live-data wrapper read directly from service singletons.
 * Pulled out so OverlayContent itself stays state-less and testable.
 */
@Composable
fun OverlayContentLive(
    onVolumeDown: () -> Unit,
    onVolumeUp: () -> Unit,
    onMuteToggle: () -> Unit,
    onDebug: () -> Unit,
    onClose: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit = { _, _ -> },
) {
    val volume by CarCompanionService.overlayVolume.collectAsState()
    val mood by CarCompanionService.overlayMood.collectAsState()
    val robotOnline by CarCompanionService.robotOnline.collectAsState()
    val obdConn by CarCompanionService.obdConnection.collectAsState()
    val status = when {
        robotOnline && obdConn == ObdConnection.CLIENT_CONNECTED -> SystemStatus.ALL_OK
        robotOnline || obdConn == ObdConnection.CLIENT_CONNECTED -> SystemStatus.PARTIAL
        else -> SystemStatus.OFFLINE
    }
    OverlayContent(
        moodEmoji = mood,
        currentVolume = volume,
        muted = volume == 0,
        systemStatus = status,
        onVolumeDown = onVolumeDown,
        onVolumeUp = onVolumeUp,
        onMuteToggle = onMuteToggle,
        onDebug = onDebug,
        onClose = onClose,
        onDrag = onDrag,
    )
}

private fun SystemStatus.tint(): Color = when (this) {
    SystemStatus.ALL_OK  -> Color(0xFF4CAF50)   // material green 500
    SystemStatus.PARTIAL -> Color(0xFFFFB300)   // amber 600
    SystemStatus.OFFLINE -> Color(0xFFE53935)   // red 600
}
