package com.carcompanion.companion.domain

/**
 * Layer 2 helper for trip-level events that depend on engine-on duration
 * and the gap since the last engine stop:
 *
 *  - TRIP_START_OF_DAY    : engine start after ≥ 6 hours off
 *  - RETURN_AFTER_ABSENCE : engine start after ≥ 24 hours off (rarer/stronger)
 *  - TRIP_LONG_60M        : engine has been on for 60 minutes
 *  - TRIP_LONG_120M       : 120 minutes
 *  - TRIP_SHORT           : engine stopped within 2 minutes of starting
 *  - TRIP_LATE_RETURN     : engine stopped after 22:00
 *
 * Stateful between events but otherwise side-effect free.
 */
class TripLifecycleAnalyzer(
    private val now: () -> Long = { System.currentTimeMillis() },
    private val hourOfDay: () -> Int = {
        java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    },
) {
    private var engineStartMs: Long = 0L
    private var long60Announced = false
    private var long120Announced = false

    /**
     * Feed a slice of upstream events plus the persisted `lastEngineStopMs`
     * (or 0 if no record). Returns trip-level events to inject downstream.
     */
    fun consume(events: List<SemanticEvent>, lastEngineStopMs: Long): List<SemanticEvent> {
        val out = mutableListOf<SemanticEvent>()
        val nowMs = now()

        for (e in events) when (e.name) {
            "CAN_ENGINE_START" -> {
                engineStartMs = nowMs
                long60Announced = false
                long120Announced = false

                val gapMs = if (lastEngineStopMs > 0) nowMs - lastEngineStopMs else 0L
                val gapHours = gapMs / 3_600_000L
                when {
                    gapHours >= 24 -> out += SemanticEvent(
                        name = "RETURN_AFTER_ABSENCE",
                        value = gapHours.toFloat(),
                    )
                    gapHours >= 6 -> out += SemanticEvent(
                        name = "TRIP_START_OF_DAY",
                        value = gapHours.toFloat(),
                    )
                }
            }

            "CAN_ENGINE_STOP" -> {
                val runtimeSec = if (engineStartMs > 0) (nowMs - engineStartMs) / 1000 else 0
                if (runtimeSec in 1..119) {
                    out += SemanticEvent(name = "TRIP_SHORT", value = runtimeSec.toFloat())
                }
                if (hourOfDay() >= 22) {
                    out += SemanticEvent(name = "TRIP_LATE_RETURN", value = hourOfDay().toFloat())
                }
                engineStartMs = 0L
                long60Announced = false
                long120Announced = false
            }
        }

        // Continuous trip-duration milestones
        if (engineStartMs > 0) {
            val runtimeSec = (nowMs - engineStartMs) / 1000
            if (runtimeSec >= 3600 && !long60Announced) {
                out += SemanticEvent(name = "TRIP_LONG_60M", value = (runtimeSec / 60).toFloat())
                long60Announced = true
            }
            if (runtimeSec >= 7200 && !long120Announced) {
                out += SemanticEvent(name = "TRIP_LONG_120M", value = (runtimeSec / 60).toFloat())
                long120Announced = true
            }
        }

        return out
    }
}
