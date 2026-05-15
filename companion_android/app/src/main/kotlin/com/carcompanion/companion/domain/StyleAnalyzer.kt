package com.carcompanion.companion.domain

/**
 * Layer 2 helper — looks across a *stream* of [SemanticEvent]s rather than
 * single-snapshot transitions. Catches behavioural patterns that only show
 * up over time:
 *
 *  - `DRIVING_AGGRESSIVE`  — ≥3 harsh events in any 5-minute window
 *  - `DRIVING_SMOOTH_30M`  — 30+ minutes without a harsh event since the last
 *                            one or the engine start
 *
 * Resets when CAN_ENGINE_START / CAN_ENGINE_STOP fire. The analyzer is fed
 * after [UnderstandingEngine.consume] so it already knows about
 * CAN_HARSH_BRAKE / CAN_AGGRESSIVE_THROTTLE / CAN_SPEEDING_IN_CITY.
 */
class StyleAnalyzer(
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val harshTimestamps = ArrayDeque<Long>()
    private var lastHarshOrStartMs: Long = 0L
    private var smoothAnnounced = false
    private var aggressiveAnnounced = false

    /**
     * Process a slice of upstream events. Returns any synthetic events
     * (DRIVING_*) that this batch caused to fire.
     */
    fun consume(events: List<SemanticEvent>): List<SemanticEvent> {
        val nowMs = now()
        val out = mutableListOf<SemanticEvent>()

        for (e in events) when (e.name) {
            in HARSH_NAMES -> {
                harshTimestamps.addLast(nowMs)
                lastHarshOrStartMs = nowMs
                smoothAnnounced = false
            }
            "CAN_ENGINE_START" -> {
                harshTimestamps.clear()
                lastHarshOrStartMs = nowMs
                smoothAnnounced = false
                aggressiveAnnounced = false
            }
            "CAN_ENGINE_STOP" -> {
                harshTimestamps.clear()
                lastHarshOrStartMs = 0L
                smoothAnnounced = false
                aggressiveAnnounced = false
            }
        }

        // Evict harsh events older than 5 minutes
        while (harshTimestamps.isNotEmpty() &&
            nowMs - harshTimestamps.first() > AGGRESSIVE_WINDOW_MS
        ) harshTimestamps.removeFirst()

        // Aggressive pattern — fires once until window empties
        if (harshTimestamps.size >= AGGRESSIVE_THRESHOLD && !aggressiveAnnounced) {
            out += SemanticEvent(
                name = "DRIVING_AGGRESSIVE",
                value = harshTimestamps.size.toFloat(),
            )
            aggressiveAnnounced = true
        }
        if (harshTimestamps.isEmpty()) aggressiveAnnounced = false

        // Smooth — 30 min since last harsh / engine start, fires once
        if (lastHarshOrStartMs > 0 &&
            nowMs - lastHarshOrStartMs >= SMOOTH_WINDOW_MS &&
            !smoothAnnounced
        ) {
            out += SemanticEvent(name = "DRIVING_SMOOTH_30M")
            smoothAnnounced = true
        }

        return out
    }

    companion object {
        private val HARSH_NAMES = setOf(
            "CAN_HARSH_BRAKE",
            "CAN_AGGRESSIVE_THROTTLE",
            "CAN_SPEEDING_IN_CITY",
        )
        private const val AGGRESSIVE_WINDOW_MS = 5 * 60 * 1000L
        private const val AGGRESSIVE_THRESHOLD = 3
        private const val SMOOTH_WINDOW_MS = 30 * 60 * 1000L
    }
}
