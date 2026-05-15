package com.carcompanion.companion.domain

import com.carcompanion.companion.data.Reaction
import kotlin.random.Random

/**
 * Picks one of several candidate reactions in a way that **feels alive**.
 *
 * Algorithm:
 *   1. Filter by hypothesis  — drop reactions whose `hypothesis` doesn't match
 *      the current one (a reaction with `hypothesis == null` or "any" is universal).
 *   2. Compute weights per reaction:
 *        base weight (from JSON)
 *        × 1.5 if `emotion_zone` matches the character's current zone
 *        × 1.5 if hypothesis is an *exact* match (not "any")
 *        × 0.3 if its gif is in `recentGifs` (anti-repeat)
 *   3. With probability [surpriseChance] (default 5%) pick uniformly at random
 *      from the filtered set instead of using weights — the "out of nowhere"
 *      reaction.
 *   4. Otherwise sample by cumulative weight.
 *
 * The picker is stateless aside from the supplied [random] seed — easy to test.
 */
class ReactionPicker(
    private val random: Random = Random.Default,
    private val surpriseChance: Float = 0.05f,
) {

    data class Decision(
        val reaction: Reaction,
        val weight: Float,
        val wasSurprise: Boolean,
    )

    fun pick(
        reactions: List<Reaction>,
        currentZone: Emotion,
        currentHypothesis: String,         // "any" placeholder in Round 2; real hypothesis in Round 3
        recentGifs: Collection<String>,
    ): Decision? {
        if (reactions.isEmpty()) return null

        val filtered = reactions.filter { matchesHypothesis(it.hypothesis, currentHypothesis) }
        if (filtered.isEmpty()) return null

        // Surprise path: ignore weights entirely
        if (random.nextFloat() < surpriseChance) {
            val pick = filtered.random(random)
            return Decision(reaction = pick, weight = 0f, wasSurprise = true)
        }

        val weighted = filtered.map { r -> r to weightOf(r, currentZone, currentHypothesis, recentGifs) }
        val totalW = weighted.sumOf { it.second.toDouble() }
        if (totalW <= 0.0) {
            val pick = filtered.first()
            return Decision(reaction = pick, weight = 0f, wasSurprise = false)
        }

        val needle = random.nextDouble() * totalW
        var acc = 0.0
        for ((r, w) in weighted) {
            acc += w
            if (acc >= needle) {
                return Decision(reaction = r, weight = w, wasSurprise = false)
            }
        }
        // Defensive fallback
        return weighted.last().let { Decision(it.first, it.second, false) }
    }

    private fun weightOf(
        r: Reaction,
        zone: Emotion,
        hypothesis: String,
        recentGifs: Collection<String>,
    ): Float {
        var w = r.weight.coerceAtLeast(0f)

        // Emotion zone boost
        val zoneMatch = r.emotionZone?.equals(zone.name, ignoreCase = true) == true
        if (zoneMatch) w *= 1.5f

        // Exact hypothesis match (not "any")
        val hypoExact = r.hypothesis != null &&
            !r.hypothesis.equals("any", ignoreCase = true) &&
            r.hypothesis.equals(hypothesis, ignoreCase = true)
        if (hypoExact) w *= 1.5f

        // Anti-repeat
        if (r.gif != null && r.gif in recentGifs) w *= 0.3f

        return w
    }

    private fun matchesHypothesis(reactionHypo: String?, current: String): Boolean {
        if (reactionHypo == null) return true
        if (reactionHypo.equals("any", ignoreCase = true)) return true
        return reactionHypo.equals(current, ignoreCase = true)
    }
}
