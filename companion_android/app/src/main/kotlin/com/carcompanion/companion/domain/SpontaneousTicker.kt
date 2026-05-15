package com.carcompanion.companion.domain

import com.carcompanion.companion.data.SoulDefinition
import com.carcompanion.companion.data.SpontaneousEntry
import kotlin.random.Random

/**
 * Round 4 — the "idle thought" generator.
 *
 * On a jittered 60-180s timer (modulated by personality + quirks), the service
 * calls [pick] to ask the ticker for a spontaneous reaction. The ticker:
 *   - filters [SoulDefinition.spontaneousPool] by current hypothesis + emotion
 *     zone (entries with `null` / "any" are universal)
 *   - weighted-random picks among the survivors
 *
 * Returns null when no entry matches — caller does nothing then.
 */
class SpontaneousTicker(
    private val random: Random = Random.Default,
) {

    fun nextDelayMs(
        traits: com.carcompanion.companion.data.CoreTraits,
        quirks: Set<Quirk>,
        currentEmotion: Emotion = Emotion.NEUTRAL,
    ): Long {
        val mul = spontaneousFrequencyMultiplier(traits, quirks)
        // Happy / excited souls chatter more — Kuro likes to share when she's
        // in a good mood. Scales the existing personality-multiplier.
        val emotionBoost = when (currentEmotion) {
            Emotion.HAPPY, Emotion.EXCITED -> EMOTION_TALK_BOOST
            Emotion.SAD, Emotion.ANGRY     -> EMOTION_QUIET_DAMPEN
            else                            -> 1.0f
        }
        // Base 60-180s jitter; faster characters tick more often.
        val baseMs = random.nextLong(60_000L, 180_001L)
        return (baseMs / mul / emotionBoost).toLong().coerceAtLeast(20_000L)
    }

    companion object {
        /** Multiplier on the delay calculator when HAPPY / EXCITED (> 1 = faster). */
        const val EMOTION_TALK_BOOST = 1.6f
        /** Multiplier when SAD / ANGRY (< 1 = slower / quieter). */
        const val EMOTION_QUIET_DAMPEN = 0.7f
    }

    fun pick(
        definition: SoulDefinition,
        hypothesis: Hypothesis,
        zone: Emotion,
    ): SpontaneousEntry? {
        val pool = definition.spontaneousPool
        if (pool.isEmpty()) return null

        val filtered = pool.filter {
            (it.hypothesis == null || it.hypothesis.equals("any", true) || it.hypothesis.equals(hypothesis.name, true)) &&
                (it.emotionZone == null || it.emotionZone.equals(zone.name, true))
        }
        if (filtered.isEmpty()) return null

        val totalW = filtered.sumOf { it.weight.toDouble() }
        if (totalW <= 0.0) return filtered.first()
        val needle = random.nextDouble() * totalW
        var acc = 0.0
        for (entry in filtered) {
            acc += entry.weight
            if (acc >= needle) return entry
        }
        return filtered.last()
    }
}
