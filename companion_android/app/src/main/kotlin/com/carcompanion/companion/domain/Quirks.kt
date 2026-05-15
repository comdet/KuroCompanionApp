package com.carcompanion.companion.domain

import com.carcompanion.companion.data.CoreTraits
import java.util.Calendar

/**
 * Tier 2 — Quirks.
 *
 * Boolean tags loaded from `SOUL_DEFINITION.quirks`. Each is a small hard-coded
 * behavioural rule that hooks into specific engine points. Mutually-exclusive
 * pairs are documented in workspace/SOUL_ENGINE_DESIGN.md §3.4.
 */
enum class Quirk(val tag: String) {
    FOODIE("foodie"),
    SPEED_LOVER("speed_lover"),
    CAUTIOUS("cautious"),
    DRAMA_QUEEN("drama_queen"),
    STOIC("stoic"),
    NIGHT_OWL("night_owl"),
    MORNING_PERSON("morning_person"),
    WANDERER("wanderer"),
    HOMEBODY("homebody"),
    ;

    companion object {
        fun fromTagsOrEmpty(tags: List<String>): Set<Quirk> {
            val map = entries.associateBy { it.tag }
            return tags.mapNotNull { map[it.lowercase()] }.toSet()
        }
    }
}

/**
 * Quirk effect functions. Each takes the current state + traits and reports
 * a multiplier or boolean used by the engines. Side-effect-free.
 */
object QuirkEffects {

    /** Drama queen amplifies, stoic dampens. Returns multiplier for |delta|. */
    fun amplitudeMultiplier(quirks: Set<Quirk>): Float {
        var m = 1f
        if (Quirk.DRAMA_QUEEN in quirks) m *= 2f
        if (Quirk.STOIC in quirks) m *= 0.5f
        return m
    }

    /** Speed-event valence boost (1.0 = no change). */
    fun speedEventValenceFactor(quirks: Set<Quirk>): Float = when {
        Quirk.SPEED_LOVER in quirks -> 1.5f
        Quirk.CAUTIOUS in quirks -> 0.5f
        else -> 1f
    }

    /** Reaction-weight multiplier — speed_lover boosts CAN_HIGH_SPEED entries. */
    fun reactionWeightBoost(quirks: Set<Quirk>, eventName: String): Float {
        if (Quirk.SPEED_LOVER in quirks && eventName == "CAN_HIGH_SPEED") return 3f
        if (Quirk.CAUTIOUS in quirks && eventName in scaryEvents) return 2f
        return 1f
    }

    /** Arousal multiplier on scary events. */
    fun scaryArousalFactor(quirks: Set<Quirk>, eventName: String): Float {
        if (Quirk.CAUTIOUS in quirks && eventName in scaryEvents) return 1.5f
        return 1f
    }

    /** Hunger penalty multiplier (foodie suffers more when hungry). */
    fun foodieValenceDrop(quirks: Set<Quirk>, hunger: Float): Float {
        if (Quirk.FOODIE !in quirks) return 0f
        if (hunger < 60f) return 0f
        // Linear -0.05 .. -0.10 valence drop applied during decay tick
        val intensity = ((hunger - 60f) / 40f).coerceIn(0f, 1f)
        return -0.05f * (1f + intensity)
    }

    /** Arousal modulator for circadian quirks at current hour. */
    fun circadianArousalShift(
        quirks: Set<Quirk>,
        hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
    ): Float {
        val night = hour in nightHours
        val morning = hour in morningHours
        var shift = 0f
        if (Quirk.NIGHT_OWL in quirks) {
            if (night) shift += 0.1f
            if (morning) shift -= 0.1f
        }
        if (Quirk.MORNING_PERSON in quirks) {
            if (morning) shift += 0.1f
            if (night) shift -= 0.1f
        }
        return shift
    }

    /**
     * Vitality rate per hour while the engine has been running > 1 h.
     *
     * Pre-refactor this returned a flat ±0.5 *per decay tick* (30 s) — so a
     * wanderer's vitality bonus came out at +60/hr and pegged the cap inside
     * 2 minutes of qualifying drive. Now the caller multiplies by the tick's
     * fraction-of-an-hour (`sliceHour`) so the headline number actually
     * matches what hits the state.
     */
    fun longDriveVitalityRatePerHour(quirks: Set<Quirk>, engineRuntimeSec: Long): Float {
        if (engineRuntimeSec < 3600) return 0f
        if (Quirk.WANDERER in quirks) return +5f   // gentle restore
        if (Quirk.HOMEBODY in quirks) return -5f   // gentle drain
        return 0f
    }

    /**
     * Stress accumulation rate per hour during long drives. Homebody only.
     * Same per-tick-vs-per-hour fix as [longDriveVitalityRatePerHour]:
     * the pre-refactor +2 per tick = +240/hr was wildly out of scale.
     */
    fun longDriveStressRatePerHour(quirks: Set<Quirk>, engineRuntimeSec: Long): Float {
        if (engineRuntimeSec < 3600) return 0f
        if (Quirk.HOMEBODY in quirks) return +20f
        return 0f
    }

    val scaryEvents = setOf("CAN_HARSH_BRAKE", "CAN_IMU_BUMP", "CAN_HIGH_SPEED")
    private val nightHours = setOf(22, 23, 0, 1, 2)
    private val morningHours = setOf(6, 7, 8, 9, 10)
}

/** Helper for `traits ⊕ quirks` derived spontaneous frequency. */
fun spontaneousFrequencyMultiplier(traits: CoreTraits, quirks: Set<Quirk>): Float {
    var m = PersonalityFactor.spontaneousFrequencyMultiplier(traits)
    if (Quirk.DRAMA_QUEEN in quirks) m *= 1.3f
    if (Quirk.STOIC in quirks) m *= 0.7f
    return m.coerceIn(0.4f, 2.5f)
}
