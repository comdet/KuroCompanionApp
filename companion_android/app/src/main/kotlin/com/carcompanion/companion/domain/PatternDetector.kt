package com.carcompanion.companion.domain

/**
 * Layer-3 in the event-tier model (§4.1 of SOUL_EVENT_REDESIGN.md) —
 * the **composite** rung. Where [EpisodeTracker] turns sustained signals
 * into episodes, PatternDetector turns short event sequences into a
 * single semantic action.
 *
 * The two patterns shipped in Phase 4:
 *
 * ── UNSAFE_OVERTAKE ───────────────────────────────────────────────────
 * The driver hammered the throttle, gained speed quickly, but never
 * engaged a turn signal at the moment of the spike. Components today:
 *
 *   1. `CAN_AGGRESSIVE_THROTTLE` arrives → record `pendingOvertake` with
 *      speed and turn-signal snapshot at that instant.
 *   2. Subsequent `consume` calls compare current speed against
 *      `speedAtSpike`. A gain of ≥ [OVERTAKE_SPEED_GAIN_KMH] within
 *      [OVERTAKE_WINDOW_MS] **and** no turn signal at the spike emits
 *      `UNSAFE_OVERTAKE`.
 *   3. Either the window times out or the pattern fires; pending clears.
 *
 * Limitations: we don't have lane position. We approximate "completed
 * overtake" by speed magnitude alone — a fast climb can also be a
 * highway merge, but those usually have the turn signal *on*, which
 * suppresses the pattern.
 *
 * ── EMERGENCY_BRAKE ───────────────────────────────────────────────────
 * A severe brake without preceding aggressive driving is most likely a
 * "swerved to avoid something" event, not a "stop being aggressive"
 * scold. Detection:
 *
 *   1. `CAN_HARSH_BRAKE` arrives at SEVERE or CRITICAL severity.
 *   2. Scan the last [EMERGENCY_CONTEXT_WINDOW_MS] of events for
 *      `CAN_AGGRESSIVE_THROTTLE`, `CAN_HIGH_SPEED`, or `UNSAFE_OVERTAKE`.
 *   3. If **none** are present → emit `EMERGENCY_BRAKE` and tell the
 *      caller to suppress the originating `CAN_HARSH_BRAKE` so Kuro
 *      doesn't scold and sympathise at the same time.
 *
 * Both detectors are pure logic — call [reset] on service teardown.
 */
class PatternDetector(
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * What [consume] returns. `emitted` events should join the soul
     * event stream; names in `suppressed` should be filtered out of the
     * raw stream so they don't double up with their composite version.
     */
    data class Result(
        val emitted: List<SemanticEvent>,
        val suppressed: Set<String>,
    ) {
        companion object {
            val EMPTY = Result(emptyList(), emptySet())
        }
    }

    // ── Rolling state ────────────────────────────────────────────────

    private val recentEvents: ArrayDeque<Pair<Long, String>> = ArrayDeque()
    private val recentSpeeds: ArrayDeque<Pair<Long, Int>> = ArrayDeque()

    private data class PendingOvertake(
        val spikedAt: Long,
        val speedAtSpike: Int,
        val hadSignalAtSpike: Boolean,
    )
    @Volatile private var pendingOvertake: PendingOvertake? = null

    /** Snapshot for debug UI. */
    fun hasPendingOvertake(): Boolean = pendingOvertake != null

    fun consume(
        events: List<SemanticEvent>,
        speedKmh: Int,
        turnLeft: Boolean,
        turnRight: Boolean,
    ): Result {
        val nowMs = now()
        recentSpeeds.addLast(nowMs to speedKmh)
        for (e in events) recentEvents.addLast(nowMs to e.name)
        evictOld(nowMs)

        val emitted = mutableListOf<SemanticEvent>()
        val suppressed = mutableSetOf<String>()

        // Order matters: an UNSAFE_OVERTAKE detected this tick should be
        // visible to the EMERGENCY_BRAKE context scan.
        detectOvertake(nowMs, events, speedKmh, turnLeft || turnRight)?.let { ev ->
            emitted += ev
            recentEvents.addLast(nowMs to ev.name)
        }

        detectEmergencyBrake(nowMs, events)?.let { ev ->
            emitted += ev
            suppressed += "CAN_HARSH_BRAKE"
        }

        if (emitted.isEmpty() && suppressed.isEmpty()) return Result.EMPTY
        return Result(emitted, suppressed)
    }

    fun reset() {
        recentEvents.clear()
        recentSpeeds.clear()
        pendingOvertake = null
    }

    private fun detectOvertake(
        nowMs: Long,
        events: List<SemanticEvent>,
        speedKmh: Int,
        anySignalActive: Boolean,
    ): SemanticEvent? {
        // 1. New throttle spike opens a window (overwrites any previous
        //    pending — a second spike resets the comparison baseline).
        if (events.any { it.name == "CAN_AGGRESSIVE_THROTTLE" }) {
            pendingOvertake = PendingOvertake(
                spikedAt = nowMs,
                speedAtSpike = speedKmh,
                hadSignalAtSpike = anySignalActive,
            )
            return null
        }

        // 2. With no pending window, nothing to evaluate.
        val p = pendingOvertake ?: return null

        // 3. Window expired without a qualifying speed gain — drop it.
        if (nowMs - p.spikedAt > OVERTAKE_WINDOW_MS) {
            pendingOvertake = null
            return null
        }

        // 4. Match: speed gained enough AND turn signal was absent at the
        //    spike. Fire pattern once and clear pending so we don't
        //    re-fire on the next tick at higher speed.
        val speedGain = speedKmh - p.speedAtSpike
        if (speedGain >= OVERTAKE_SPEED_GAIN_KMH && !p.hadSignalAtSpike) {
            pendingOvertake = null
            return SemanticEvent(
                name = "UNSAFE_OVERTAKE",
                value = speedGain.toFloat(),
            )
        }
        return null
    }

    private fun detectEmergencyBrake(
        nowMs: Long,
        events: List<SemanticEvent>,
    ): SemanticEvent? {
        val severeBrake = events.firstOrNull {
            it.name == "CAN_HARSH_BRAKE" && it.severity >= Severity.SEVERE
        } ?: return null

        // Scan recent events (which already include this tick's UNSAFE_OVERTAKE
        // if it just fired) for aggressive context.
        val cutoff = nowMs - EMERGENCY_CONTEXT_WINDOW_MS
        val aggressive = recentEvents.any { (t, name) ->
            t >= cutoff && name in AGGRESSIVE_CONTEXT_NAMES
        }
        if (aggressive) return null

        return SemanticEvent(
            name = "EMERGENCY_BRAKE",
            value = severeBrake.value,
            severity = severeBrake.severity,
        )
    }

    private fun evictOld(nowMs: Long) {
        val eventCutoff = nowMs - EVENT_HISTORY_MS
        while (recentEvents.isNotEmpty() && recentEvents.first().first < eventCutoff) {
            recentEvents.removeFirst()
        }
        val speedCutoff = nowMs - SPEED_HISTORY_MS
        while (recentSpeeds.isNotEmpty() && recentSpeeds.first().first < speedCutoff) {
            recentSpeeds.removeFirst()
        }
    }

    companion object {
        const val OVERTAKE_WINDOW_MS = 10_000L
        const val OVERTAKE_SPEED_GAIN_KMH = 20

        const val EMERGENCY_CONTEXT_WINDOW_MS = 30_000L

        private const val EVENT_HISTORY_MS = EMERGENCY_CONTEXT_WINDOW_MS
        private const val SPEED_HISTORY_MS = 15_000L

        /** Recent presence of any of these suppresses EMERGENCY_BRAKE. */
        private val AGGRESSIVE_CONTEXT_NAMES = setOf(
            "CAN_AGGRESSIVE_THROTTLE",
            "CAN_HIGH_SPEED",
            "UNSAFE_OVERTAKE",
        )
    }
}
