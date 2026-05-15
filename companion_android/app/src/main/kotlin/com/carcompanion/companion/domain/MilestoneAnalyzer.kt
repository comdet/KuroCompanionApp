package com.carcompanion.companion.domain

import com.carcompanion.companion.data.DriverProfile

/**
 * Layer 2 helper for "you reached X" moments — emits synthetic events when
 * persistent stats cross meaningful thresholds.
 *
 * Bond / trips / drivetime are checked against [DriverProfile] snapshots
 * (so they fire once per crossing across reboots).
 *
 * Internal soul stats (valence swings, stress max-out, curiosity boil-over)
 * are checked against an in-memory previous snapshot — these are about
 * the *moment*, not the lifetime.
 */
class MilestoneAnalyzer(
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private var prevValence: Float = 0f
    private var prevValenceAtMs: Long = 0L
    private var stressMaxAnnounced = false
    private var boredOutAnnounced = false
    private var lastReactionAtMs: Long = 0L

    /**
     * Compare an old and a new [DriverProfile] and report any crossed
     * persistent milestones.
     */
    fun checkPersistent(old: DriverProfile, new: DriverProfile): List<SemanticEvent> {
        val out = mutableListOf<SemanticEvent>()

        if (old.bond < 50f && new.bond >= 50f) out += SemanticEvent("MILESTONE_BOND_50")
        if (old.bond < 100f && new.bond >= 100f) out += SemanticEvent("MILESTONE_BOND_100")
        if (old.trust < 80f && new.trust >= 80f) out += SemanticEvent("MILESTONE_TRUST_80")

        if (old.totalTrips < 10 && new.totalTrips >= 10) out += SemanticEvent("MILESTONE_TRIPS_10")
        if (old.totalTrips < 100 && new.totalTrips >= 100) out += SemanticEvent("MILESTONE_TRIPS_100")
        if (old.totalTrips < 1000 && new.totalTrips >= 1000) out += SemanticEvent("MILESTONE_TRIPS_1000")

        val hours10 = 10 * 3600L
        val hours100 = 100 * 3600L
        if (old.totalDrivetimeSec < hours10 && new.totalDrivetimeSec >= hours10) {
            out += SemanticEvent("MILESTONE_DRIVETIME_10H")
        }
        if (old.totalDrivetimeSec < hours100 && new.totalDrivetimeSec >= hours100) {
            out += SemanticEvent("MILESTONE_DRIVETIME_100H")
        }
        return out
    }

    /**
     * Check the *transient* soul snapshot for thresholds. Called from the
     * service every time [SoulState] is published.
     *
     * @param hadReactionThisTick true if [CharacterEngine.handle] picked a
     *                            reaction this tick — used by BORED_OUT to
     *                            reset the "no recent stimulation" clock.
     */
    fun checkInternal(
        snap: SoulSnapshot,
        hadReactionThisTick: Boolean,
    ): List<SemanticEvent> {
        val out = mutableListOf<SemanticEvent>()
        val nowMs = now()
        if (hadReactionThisTick) lastReactionAtMs = nowMs

        // Valence swing — |Δ valence| > 0.5 within 60 s
        if (prevValenceAtMs > 0 && (nowMs - prevValenceAtMs) <= 60_000L) {
            val dv = kotlin.math.abs(snap.valence - prevValence)
            if (dv > 0.5f) {
                out += SemanticEvent(name = "MOOD_SWING", value = dv)
                prevValenceAtMs = nowMs   // avoid double-firing
            }
        }
        if (prevValenceAtMs == 0L || nowMs - prevValenceAtMs > 60_000L) {
            prevValence = snap.valence
            prevValenceAtMs = nowMs
        }

        // Stress max-out
        if (snap.stress >= 90f && !stressMaxAnnounced) {
            out += SemanticEvent(name = "STRESS_MAX", value = snap.stress)
            stressMaxAnnounced = true
        }
        if (snap.stress < 70f) stressMaxAnnounced = false

        // Bored out — high curiosity + no reaction for 5 minutes
        val noReactionSec = if (lastReactionAtMs == 0L) 0L else (nowMs - lastReactionAtMs) / 1000
        if (snap.curiosity >= 90f && noReactionSec >= 300 && !boredOutAnnounced) {
            out += SemanticEvent(name = "BORED_OUT", value = snap.curiosity)
            boredOutAnnounced = true
        }
        if (snap.curiosity < 70f || hadReactionThisTick) boredOutAnnounced = false

        return out
    }
}
