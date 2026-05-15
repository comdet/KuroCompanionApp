package com.carcompanion.companion.domain

import com.carcompanion.companion.data.MoodletDef
import com.carcompanion.companion.data.ObdMessage
import com.carcompanion.companion.data.SoulDefinition
import java.util.Calendar

/**
 * Tier 4 — Likes / Dislikes.
 *
 * Resolves the current "context bundle" from OBD state + hypothesis + clock,
 * then spawns short moodlets when the character enters a context they like
 * or dislike. Re-entering the same context within [reSpawnGapMs] is a no-op,
 * so we don't pile on duplicate moodlets every snapshot.
 *
 * Context keys are namespaced strings (e.g. `time_of_day:evening`,
 * `traffic:jam`) so SOUL_DEFINITION.likes can list them directly.
 *
 * Sources implemented today:
 *   - `time_of_day:*`   from clock
 *   - `traffic:*`       from active [Hypothesis]
 *
 * Sources stubbed (no source wired yet, ignored if listed in likes/dislikes):
 *   - `music_genre:*`   — needs Media metadata
 *   - `weather:*`       — needs Weather API
 *   - `route_type:*`    — needs Maps / GPS
 *   - `road_quality:*`  — needs IMU
 */
class LikesContext(
    private val reSpawnGapMs: Long = 5 * 60 * 1000L,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val hourOfDay: () -> Int = {
        Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    },
    private val dayOfWeekRaw: () -> Int = {
        Calendar.getInstance().get(Calendar.DAY_OF_WEEK)  // 1=Sun ... 7=Sat
    },
) {
    private val recentSpawns = HashMap<String, Long>()
    private var lastContexts: Set<String> = emptySet()

    /** Compute the set of currently active context keys. */
    fun resolveContexts(
        status: ObdMessage.Status?,
        hypothesis: Hypothesis,
    ): Set<String> {
        val out = mutableSetOf<String>()
        val hour = hourOfDay()
        val day = dayOfWeekRaw()  // 1=Sun, 2=Mon, ... 7=Sat

        out += timeOfDay(hour)
        out += "day_of_week:${dayName(day)}"

        val isWeekend = day == Calendar.SATURDAY || day == Calendar.SUNDAY
        val isWeekday = !isWeekend
        if (isWeekend) out += "temporal:weekend"

        // Rush hour — weekday commuter windows
        if (isWeekday && (hour in 7..8 || hour in 17..18)) {
            out += "temporal:rush_hour"
        }
        // Monday-morning blues + Friday-evening relief — specific weekday
        // anchors persona traits often hook into.
        if (day == Calendar.MONDAY && hour in 7..9) out += "temporal:monday_morning"
        if (day == Calendar.FRIDAY && hour in 17..19) out += "temporal:friday_evening"
        when (hypothesis) {
            Hypothesis.STUCK_TRAFFIC -> out += "traffic:jam"
            Hypothesis.CRUISING -> out += "traffic:flowing"
            Hypothesis.RUSHING -> out += "traffic:light"
            else -> Unit
        }
        // Cabin temperature buckets (OBD `ambient` is outside air; we proxy
        // the cabin from it until we have an inside-temp source).
        val ambient = status?.ambient
        if (ambient != null) {
            out += when {
                ambient >= 32 -> "weather:hot"
                ambient <= 18 -> "weather:cold"
                else -> "weather:comfortable"
            }
        }
        // Light state context — headlight on means the driver thinks it's dark.
        // (We can also infer "should be dark" from the clock, but the firmware's
        //  headlight switch is the most accurate "driver perception" signal.)
        val head = status?.lights?.headlightRaw ?: 0
        if (head and 0x80 != 0) out += "light:headlight_on" else out += "light:headlight_off"
        return out
    }

    /**
     * Compare current context set vs the previous one + persona likes/dislikes.
     * For each newly-entered like/dislike, return the moodlet defs to spawn.
     */
    fun moodletsForContextChange(
        contexts: Set<String>,
        definition: SoulDefinition,
    ): List<Pair<String, MoodletDef>> {
        val nowMs = now()
        val entered = contexts - lastContexts
        lastContexts = contexts

        val likes = definition.likes.toSet()
        val dislikes = definition.dislikes.toSet()

        val out = mutableListOf<Pair<String, MoodletDef>>()
        for (ctx in entered) {
            val moodletId: String
            val v: Float
            val a: Float
            when {
                ctx in likes -> {
                    moodletId = "liked_$ctx"
                    v = +0.15f
                    a = 0f
                }
                ctx in dislikes -> {
                    moodletId = "disliked_$ctx"
                    v = -0.15f
                    a = 0f
                }
                else -> continue
            }
            val recent = recentSpawns[moodletId] ?: 0L
            if (nowMs - recent < reSpawnGapMs) continue
            recentSpawns[moodletId] = nowMs
            out += moodletId to MoodletDef(
                id = moodletId, trigger = ctx,
                valence = v, arousal = a, durationSec = 600,
            )
        }
        return out
    }

    private fun timeOfDay(h: Int): String = when (h) {
        in 5..6 -> "time_of_day:early_morning"
        in 7..10 -> "time_of_day:morning"
        in 11..14 -> "time_of_day:afternoon"
        in 15..16 -> "time_of_day:afternoon"
        in 17..19 -> "time_of_day:evening"
        in 20..21 -> "time_of_day:evening"
        in 22..23 -> "time_of_day:night"
        in 0..4 -> "time_of_day:night"
        else -> "time_of_day:midday"
    }

    private fun dayName(d: Int): String = when (d) {
        Calendar.SUNDAY -> "sunday"
        Calendar.MONDAY -> "monday"
        Calendar.TUESDAY -> "tuesday"
        Calendar.WEDNESDAY -> "wednesday"
        Calendar.THURSDAY -> "thursday"
        Calendar.FRIDAY -> "friday"
        Calendar.SATURDAY -> "saturday"
        else -> "unknown"
    }
}
