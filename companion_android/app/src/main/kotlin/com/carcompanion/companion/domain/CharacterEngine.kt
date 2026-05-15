package com.carcompanion.companion.domain

import android.util.Log
import com.carcompanion.companion.data.EventLogic
import com.carcompanion.companion.data.Reaction
import com.carcompanion.companion.data.SoulDefinition
import com.carcompanion.companion.data.SoulLogic
import com.carcompanion.companion.data.effectiveCoreTraits

/**
 * Layer 3 — Character Engine.
 *
 * Round 1 wiring:
 *  - 2D state (valence / arousal) instead of scalar mood
 *  - personality multipliers via [PersonalityFactor]
 *  - cooldown trimmed by patience
 *  - reaction pick still linear (Round 2 will swap in weighted picker)
 *  - legacy v1 schema (`mood_delta`, `traits`) still parses; auto-mapped to v2
 */
class CharacterEngine(
    val definition: SoulDefinition,
    val logic: SoulLogic,
    private val picker: ReactionPicker = ReactionPicker(),
) {
    val traits = definition.effectiveCoreTraits()
    val quirks: Set<Quirk> = Quirk.fromTagsOrEmpty(definition.quirks)
    val state = SoulState(definition)
    val moodlets = MoodletStack()

    /** Last picker decision — exposed so the service can log it for visibility. */
    @Volatile var lastDecision: ReactionPicker.Decision? = null
        private set

    /**
     * Process one [SemanticEvent]. Returns the picked [Reaction] (if any).
     *
     * Round 2: delegates to [ReactionPicker] (weighted random + emotion-zone
     * boost + 5% surprise + anti-repeat).
     */
    fun handle(
        event: SemanticEvent,
        currentHypothesis: String = "any",
        nowMs: Long = System.currentTimeMillis(),
    ): Reaction? {
        val eventLogic = logic.eventLogic.firstOrNull { it.event == event.name } ?: return null
        if (!shouldFire(event, eventLogic)) return null

        val cdMul = PersonalityFactor.cooldownMultiplier(traits)
        val effectiveCooldown = (eventLogic.cooldown * cdMul).toInt().coerceAtLeast(0)
        if (!state.consumeCooldown(event.name, effectiveCooldown, nowMs)) {
            Log.d(TAG, "Event ${event.name} suppressed by cooldown (eff=${effectiveCooldown}s)")
            return null
        }

        val zone = currentEmotion(nowMs)
        val boosted = boostReactionsByQuirks(eventLogic.reactions, event.name)
        val decision = picker.pick(
            reactions = boosted,
            currentZone = zone,
            currentHypothesis = currentHypothesis,
            recentGifs = state.recentReactionGifs,
        ) ?: return null

        lastDecision = decision
        applyReaction(decision.reaction, event)
        decision.reaction.gif?.let { state.rememberReactionGif(it) }
        return decision.reaction
    }

    /**
     * Periodic drift. Called every 30s by the service.
     *
     *  - valence drifts back toward 0 (faster recovery for optimists)
     *  - arousal drifts back toward the personality baseline
     *  - needs decay per SOUL_DEFINITION.needs_decay_rates (30s slice)
     *  - bond decay damped by sociability
     *  - stress recovers passively
     */
    fun decay(engineRuntimeSec: Long = 0L) {
        val rates = definition.needsDecayRates
        val sliceMin = 30f / 60f
        val sliceHour = 30f / 3600f
        val sliceDay = 30f / 86400f

        // Valence drift toward 0
        if (state.valence != 0f) {
            val sign = if (state.valence > 0f) -1f else +1f
            val magnitude = if (state.valence < 0f) 0.002f * (1f + traits.optimism) else 0.001f
            state.adjustValence(sign * magnitude)
        }

        // Arousal drift toward (baseline + circadian quirk shift)
        val baseShift = PersonalityFactor.baselineArousalShift(traits)
        val circadianShift = QuirkEffects.circadianArousalShift(quirks)
        val arousalTarget = (baseShift + circadianShift).coerceIn(-1f, 1f)
        val arousalGap = arousalTarget - state.arousal
        state.adjustArousal(arousalGap * 0.01f)

        // Needs
        state.adjustHunger(rates.hungerPerMin * sliceMin)
        state.adjustEnergy(-rates.energyPerMin * sliceMin)
        state.adjustCuriosity(rates.curiosityPerMin * sliceMin)
        state.adjustVitality(-rates.vitalityPerHour * sliceHour)

        val bondDamp = PersonalityFactor.bondDecayDamper(traits)
        state.adjustBond(-rates.bondPerDay * sliceDay * bondDamp)

        state.adjustStress(-rates.stressRecoveryPerMin * sliceMin)

        // Long-drive quirks. Per-hour rates → scale by sliceHour.
        //
        // NOTE: `foodieValenceDrop` is intentionally NOT called here. It
        // used to fire every 30 s while hunger > 60, which created a
        // valence death spiral (hungry → grumpy → no eating events fire
        // because no trip → grumpier → …). The Quirks.kt function is
        // kept available for a future moodlet-based reincarnation that
        // would express "hangry" as a *temporary* moodlet with natural
        // decay rather than a permanent decay-tick drag.
        state.adjustVitality(
            QuirkEffects.longDriveVitalityRatePerHour(quirks, engineRuntimeSec) * sliceHour
        )
        state.adjustStress(
            QuirkEffects.longDriveStressRatePerHour(quirks, engineRuntimeSec) * sliceHour
        )
    }

    /** Emotion derived from state + active moodlets at the given moment. */
    fun currentEmotion(nowMs: Long = System.currentTimeMillis()): Emotion {
        val s = state.snapshot()
        val vEff = (s.valence + moodlets.valenceShift(nowMs)).coerceIn(-1f, 1f)
        val aEff = (s.arousal + moodlets.arousalShift(nowMs)).coerceIn(-1f, 1f)
        val shifted = s.copy(valence = vEff, arousal = aEff)
        return deriveEmotion(shifted, definition)
    }

    /** Spawn moodlets keyed off events / hypothesis transitions. */
    fun trySpawnMoodlet(triggerKey: String, nowMs: Long = System.currentTimeMillis()) {
        val def = definition.moodletPool.firstOrNull { it.trigger == triggerKey } ?: return
        moodlets.spawn(def, source = triggerKey, nowMs = nowMs)
    }

    /** Open-ended moodlets (durationSec = 0) need an explicit clear. */
    fun clearMoodlet(id: String) {
        moodlets.remove(id)
    }

    private fun shouldFire(event: SemanticEvent, logic: EventLogic): Boolean {
        val threshold = logic.threshold ?: return true
        val v = event.value ?: return true
        return v >= threshold
    }

    /** Apply quirk weight boost to the reaction list before picker sees it. */
    private fun boostReactionsByQuirks(reactions: List<Reaction>, eventName: String): List<Reaction> {
        val boost = QuirkEffects.reactionWeightBoost(quirks, eventName)
        if (boost == 1f) return reactions
        return reactions.map { it.copy(weight = it.weight * boost) }
    }

    private fun applyReaction(reaction: Reaction, event: SemanticEvent) {
        val baseV = reaction.effectiveValence()
        val baseA = reaction.effectiveArousal()
        val sev = event.severity.multiplier()

        // Tier 1 personality + Tier 2 quirk amplifier
        val vMul = PersonalityFactor.valenceMultiplier(traits, baseV) *
            QuirkEffects.amplitudeMultiplier(quirks)
        var aMul = PersonalityFactor.arousalMultiplier(traits) *
            QuirkEffects.amplitudeMultiplier(quirks)

        // Speed-event quirks adjust valence further
        var vFactored = baseV * vMul
        if (event.name == "CAN_HIGH_SPEED") {
            vFactored *= QuirkEffects.speedEventValenceFactor(quirks)
        }
        // Scary-event arousal spike
        aMul *= QuirkEffects.scaryArousalFactor(quirks, event.name)

        // Severity scales the visible mood swing too — a CRITICAL event
        // hits valence/arousal as hard as the state-delta table below.
        state.adjustValence(vFactored * sev)
        state.adjustArousal(baseA * aMul * sev)

        // Non-valence state deltas — table-driven so the full lifecycle of
        // each state variable is auditable in one place (see EventStateDeltas.kt).
        // Severity scales the entry uniformly: a CRITICAL brake hits the soul
        // twice as hard as the MILD baseline (multiplier table on Severity).
        EVENT_STATE_DELTAS[event.name]?.let { d ->
            val sev = event.severity.multiplier()
            if (d.energy != 0f)    state.adjustEnergy(d.energy * sev)
            if (d.hunger != 0f)    state.adjustHunger(d.hunger * sev)
            if (d.curiosity != 0f) state.adjustCuriosity(d.curiosity * sev)
            if (d.bond != 0f)      state.adjustBond(d.bond * sev)
            if (d.stress != 0f)    state.adjustStress(d.stress * sev)
            if (d.vitality != 0f)  state.adjustVitality(d.vitality * sev)
        }

        // NOTE: removed the "FOODIE imagines food on door-open" auto-eat
        // (-30 hunger) — opening a door isn't reliable evidence of eating,
        // so this fired ~3× per trip on a daily driver and tanked the
        // hunger model. Hunger now resets only on TRIP_START_OF_DAY /
        // RETURN_AFTER_ABSENCE. The "foodie_door_open_hungry" moodlet
        // below still spawns so the foodie quirk has a visible signature.

        // Moodlet triggers tied to specific events
        when (event.name) {
            "CAN_DOOR_OPEN" -> {
                trySpawnMoodlet("CAN_DOOR_OPEN")
                if (Quirk.FOODIE in quirks && state.hunger > 70f) {
                    trySpawnMoodlet("foodie_door_open_hungry")
                }
            }
            "CAN_ENGINE_START" -> trySpawnMoodlet("CAN_ENGINE_START")
            "CAN_IMU_BUMP" -> trySpawnMoodlet("CAN_IMU_BUMP")
        }
    }

    companion object {
        private const val TAG = "CharacterEngine"
    }
}
