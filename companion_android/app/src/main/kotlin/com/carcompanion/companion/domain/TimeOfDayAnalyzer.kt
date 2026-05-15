package com.carcompanion.companion.domain

import java.util.Calendar

/**
 * Layer 2 helper — emits a one-shot SemanticEvent each time the wall-clock
 * crosses into a new time-of-day bucket. Driven by the service's per-minute
 * tick (cheap; doesn't need a sensor).
 *
 * Buckets (chosen to match [LikesContext.timeOfDay] and Kuro's `night_owl`
 * quirk window from her character bible):
 *   TIME_EARLY_MORNING   05:00 – 06:59
 *   TIME_MORNING         07:00 – 10:59
 *   TIME_AFTERNOON       11:00 – 16:59
 *   TIME_EVENING         17:00 – 21:59
 *   TIME_LATE_NIGHT      22:00 – 04:59
 *
 * First call after construction emits the current bucket so a reaction can
 * trigger at service startup if appropriate.
 */
class TimeOfDayAnalyzer(
    initialBucket: String? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * Persisted across service lifecycles by [AnalyzerStateRepository]. When
     * seeded from disk, the very next [tick] won't emit the same bucket the
     * service already announced before its previous shutdown. If the
     * current bucket differs (e.g. the user opened the app the morning
     * after closing it the previous night), the transition fires once.
     */
    private var lastBucket: String? = initialBucket

    /** Snapshot for debug UI. */
    val currentBucket: String? get() = lastBucket

    /** Call from a periodic tick (≥1× per minute). Returns synthetic events. */
    fun tick(): List<SemanticEvent> {
        val cal = Calendar.getInstance().apply { timeInMillis = now() }
        val bucket = bucketFor(cal.get(Calendar.HOUR_OF_DAY))
        if (bucket == lastBucket) return emptyList()
        lastBucket = bucket
        return listOf(SemanticEvent(name = bucket))
    }

    private fun bucketFor(hour: Int): String = when (hour) {
        in 5..6   -> "TIME_EARLY_MORNING"
        in 7..10  -> "TIME_MORNING"
        in 11..16 -> "TIME_AFTERNOON"
        in 17..21 -> "TIME_EVENING"
        else      -> "TIME_LATE_NIGHT"     // 22, 23, 0–4
    }
}
