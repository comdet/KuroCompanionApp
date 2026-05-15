package com.carcompanion.companion.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the ESP32-S3 robot is currently reachable over CCP.
 *
 * Source of truth for "robot online" used by [WakeOrchestrator] to decide
 * whether reactions like WAKE_ROBOT can fire. Without this gate the soul
 * would emit welcome audio while the robot is still booting and the
 * speakers are silent.
 *
 * ── How it gets updated ──────────────────────────────────────────────────
 *   markSeen()  — called from every CCP success path (BroadcastEngine after
 *                 a successful `ccpClient.send`, plus the explicit ping
 *                 poller in CarCompanionService during PRE_DRIVE).
 *   tickStaleness() — called from the service's per-second tick; flips
 *                 isOnline back to false when no traffic has been observed
 *                 for [staleAfterMs] (default 15 s).
 *
 * Both [markSeen] and [tickStaleness] are safe to call from multiple
 * coroutines — the StateFlow handles concurrent emits.
 */
class RobotPresence(
    private val staleAfterMs: Long = DEFAULT_STALE_AFTER_MS,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    @Volatile private var lastSeenMs: Long = 0L

    /** ms since the most recent successful CCP exchange. Long.MAX_VALUE if never seen. */
    val ageMs: Long
        get() = if (lastSeenMs == 0L) Long.MAX_VALUE else now() - lastSeenMs

    /**
     * Record that the robot just answered. Call this from any CCP
     * success path (send, ping, getStatus). Flips [isOnline] to true if
     * it wasn't already.
     */
    fun markSeen() {
        lastSeenMs = now()
        if (!_isOnline.value) _isOnline.value = true
    }

    /**
     * Re-check whether the last-seen sample is still within the live
     * window. Returns true if [isOnline] just flipped to false (caller
     * may want to log the transition).
     *
     * Idempotent — repeated calls after staleness has been reported do
     * nothing.
     */
    fun tickStaleness(): Boolean {
        if (!_isOnline.value) return false
        if (lastSeenMs == 0L) return false
        return if (now() - lastSeenMs > staleAfterMs) {
            _isOnline.value = false
            true
        } else false
    }

    /** Force back to offline (e.g. on service teardown). */
    fun reset() {
        lastSeenMs = 0L
        _isOnline.value = false
    }

    companion object {
        const val DEFAULT_STALE_AFTER_MS = 15_000L
    }
}
