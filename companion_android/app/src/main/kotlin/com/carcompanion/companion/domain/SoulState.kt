package com.carcompanion.companion.domain

import com.carcompanion.companion.data.CoreTraits
import com.carcompanion.companion.data.SoulDefinition
import com.carcompanion.companion.data.effectiveCoreTraits

/**
 * Runtime "inner life" of the companion.
 *
 * Round 1 — 2D state model:
 *   - valence  ∈ [-1, +1]   negative ↔ positive
 *   - arousal  ∈ [-1, +1]   calm ↔ excited
 *   - needs    ∈ [0, 100]   hunger / energy / curiosity
 *   - relationship ∈ [0, 100]  bond / vitality / stress
 *   - mood     derived (0-100)  for UI display only
 *
 * Cooldown per event tracked in [lastEventAtMs]. Moodlet stack (Tier 3) lives
 * separately in [MoodletStack] (Round 4).
 */
class SoulState(initial: SoulDefinition? = null) {
    private val traits: CoreTraits = initial?.effectiveCoreTraits() ?: CoreTraits()

    var valence: Float = 0f
        private set

    /** Initial arousal sits at the persona's energy baseline (-0.2 lazy .. +0.2 active). */
    var arousal: Float = (traits.energyBaseline - 0.5f) * 0.4f
        private set

    var hunger: Float = 0f
        private set
    var energy: Float = 80f
        private set
    var curiosity: Float = 50f
        private set
    var bond: Float = 50f
        private set
    var vitality: Float = 100f
        private set
    var stress: Float = 0f
        private set

    /** Derived overall mood 0-100 (for UI; not used by reaction picker). */
    val mood: Int
        get() = ((valence + 1f) * 50f + (energy - 50f) * 0.3f - stress * 0.2f)
            .coerceIn(0f, 100f).toInt()

    private val lastEventAtMs = HashMap<String, Long>()

    /** Rolling tail of the last ≤5 gif filenames played, for anti-repeat. */
    private val recentReactionGifsBacking = ArrayDeque<String>()
    val recentReactionGifs: List<String>
        get() = recentReactionGifsBacking.toList()

    fun rememberReactionGif(gif: String) {
        recentReactionGifsBacking.addLast(gif)
        while (recentReactionGifsBacking.size > MAX_RECENT_GIFS) {
            recentReactionGifsBacking.removeFirst()
        }
    }

    fun consumeCooldown(event: String, cooldownSec: Int, nowMs: Long): Boolean {
        val last = lastEventAtMs[event] ?: 0L
        if (nowMs - last < cooldownSec * 1000L) return false
        lastEventAtMs[event] = nowMs
        return true
    }

    fun adjustValence(delta: Float) {
        valence = (valence + delta).coerceIn(-1f, 1f)
    }

    fun adjustArousal(delta: Float) {
        arousal = (arousal + delta).coerceIn(-1f, 1f)
    }

    fun adjustHunger(delta: Float) { hunger = (hunger + delta).coerceIn(0f, 100f) }
    fun adjustEnergy(delta: Float) { energy = (energy + delta).coerceIn(0f, 100f) }
    fun adjustCuriosity(delta: Float) { curiosity = (curiosity + delta).coerceIn(0f, 100f) }
    fun adjustBond(delta: Float) { bond = (bond + delta).coerceIn(0f, 100f) }
    fun adjustVitality(delta: Float) { vitality = (vitality + delta).coerceIn(0f, 100f) }
    fun adjustStress(delta: Float) { stress = (stress + delta).coerceIn(0f, 100f) }

    /** Force absolute values — used by SoulPersistence restore only. */
    internal fun restoreAbsolute(
        valence: Float, arousal: Float,
        hunger: Float, energy: Float, curiosity: Float,
        bond: Float, vitality: Float, stress: Float,
    ) {
        this.valence = valence.coerceIn(-1f, 1f)
        this.arousal = arousal.coerceIn(-1f, 1f)
        this.hunger = hunger.coerceIn(0f, 100f)
        this.energy = energy.coerceIn(0f, 100f)
        this.curiosity = curiosity.coerceIn(0f, 100f)
        this.bond = bond.coerceIn(0f, 100f)
        this.vitality = vitality.coerceIn(0f, 100f)
        this.stress = stress.coerceIn(0f, 100f)
    }

    companion object {
        private const val MAX_RECENT_GIFS = 5
    }

    fun snapshot() = SoulSnapshot(
        valence = valence,
        arousal = arousal,
        hunger = hunger,
        energy = energy,
        curiosity = curiosity,
        bond = bond,
        vitality = vitality,
        stress = stress,
        mood = mood,
    )
}

data class SoulSnapshot(
    val valence: Float,
    val arousal: Float,
    val hunger: Float,
    val energy: Float,
    val curiosity: Float,
    val bond: Float,
    val vitality: Float,
    val stress: Float,
    val mood: Int,
)

/** Coarse-grained emotional categories driving the overlay mood emoji. */
enum class Emotion(val emoji: String) {
    HAPPY("😄"),
    EXCITED("🤩"),
    NEUTRAL("🙂"),
    SLEEPY("😴"),
    BORED("😑"),
    ANGRY("😠"),
    SAD("😢"),
    HUNGRY("🍙"),
}

/**
 * Derive [Emotion] from a 2D (valence, arousal) point, using the regions defined
 * in SOUL_DEFINITION.emotion_thresholds. Hunger >= 80 overrides everything.
 */
fun deriveEmotion(snapshot: SoulSnapshot, definition: SoulDefinition? = null): Emotion {
    if (snapshot.hunger >= 80f) return Emotion.HUNGRY

    val regions = definition?.emotionThresholds
    if (!regions.isNullOrEmpty()) {
        for ((name, region) in regions) {
            val vLo = region.valence.getOrNull(0) ?: continue
            val vHi = region.valence.getOrNull(1) ?: continue
            val aLo = region.arousal.getOrNull(0) ?: continue
            val aHi = region.arousal.getOrNull(1) ?: continue
            if (snapshot.valence in vLo..vHi && snapshot.arousal in aLo..aHi) {
                val matched = runCatching { Emotion.valueOf(name) }.getOrNull()
                if (matched != null) return matched
            }
        }
    }

    // Fallback heuristic if JSON regions absent or no match
    val v = snapshot.valence
    val a = snapshot.arousal
    return when {
        v >= 0.2f && a >= 0.5f -> Emotion.EXCITED
        v >= 0.3f -> Emotion.HAPPY
        v <= -0.3f && a >= 0.3f -> Emotion.ANGRY
        v <= -0.3f -> Emotion.SAD
        a <= -0.4f -> Emotion.SLEEPY
        a <= 0.1f && v in -0.4f..0.2f -> Emotion.BORED
        else -> Emotion.NEUTRAL
    }
}
