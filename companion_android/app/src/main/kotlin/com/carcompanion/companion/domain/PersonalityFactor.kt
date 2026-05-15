package com.carcompanion.companion.domain

import com.carcompanion.companion.data.CoreTraits

/**
 * Tier 1 — translates the six core traits into multipliers the engines apply
 * when modifying valence / arousal / cooldown.
 *
 * Convention: a multiplier of 1.0 means "no change vs neutral persona".
 */
object PersonalityFactor {

    /**
     * Multiplier for valence_delta.
     *
     * Optimism amplifies positive events / dampens negative ones (asymmetric).
     * Sensitivity (Neuroticism) amplifies |delta| in both directions.
     *
     *   optimism=0.8, positive  → ×1.15
     *   optimism=0.8, negative  → ×0.85
     *   sensitivity=0.9         → ×1.20
     */
    fun valenceMultiplier(traits: CoreTraits, baseDelta: Float): Float {
        val optimismShift = traits.optimism - 0.5f
        val sensitivityShift = traits.sensitivity - 0.5f
        val optimismFactor = if (baseDelta >= 0f) {
            1f + 0.5f * optimismShift
        } else {
            1f - 0.5f * optimismShift
        }
        val sensitivityFactor = 1f + 0.5f * sensitivityShift
        return (optimismFactor * sensitivityFactor).coerceAtLeast(0.1f)
    }

    /**
     * Multiplier for arousal_delta. Sensitive personas spike arousal harder.
     *
     *   sensitivity=0.9 → ×1.20
     *   sensitivity=0.1 → ×0.80
     */
    fun arousalMultiplier(traits: CoreTraits): Float {
        val shift = traits.sensitivity - 0.5f
        return (1f + 0.5f * shift).coerceAtLeast(0.1f)
    }

    /**
     * Cooldown multiplier — lower patience trims the cooldown so the character
     * reacts more often. Clamped so cooldown can't collapse to zero.
     *
     *   patience=0.4 → ×0.4 (faster reactions)
     *   patience=0.9 → ×0.9 (calm, takes its time)
     */
    fun cooldownMultiplier(traits: CoreTraits): Float =
        traits.patience.coerceIn(0.3f, 1.5f)

    /**
     * Default-arousal anchor — Active personas idle slightly excited, Lazy
     * personas idle slightly calm. Used as the target during arousal drift.
     *
     *   energy_baseline=0.7 → +0.08 baseline
     *   energy_baseline=0.3 → -0.08 baseline
     */
    fun baselineArousalShift(traits: CoreTraits): Float =
        (traits.energyBaseline - 0.5f) * 0.4f

    /**
     * Sociability scales bond decay — outgoing characters lose bond more slowly.
     * Returns the multiplier to apply against the configured daily decay.
     */
    fun bondDecayDamper(traits: CoreTraits): Float =
        (1f - traits.sociability * 0.7f).coerceIn(0.2f, 1f)

    /**
     * Spontaneous emit frequency multiplier (used by SpontaneousTicker in Round 4).
     * Sociable + curious characters tick more often.
     */
    fun spontaneousFrequencyMultiplier(traits: CoreTraits): Float {
        val curious = traits.openness
        val social = traits.sociability
        return (0.6f + 0.4f * curious + 0.4f * social).coerceIn(0.5f, 2.0f)
    }
}
