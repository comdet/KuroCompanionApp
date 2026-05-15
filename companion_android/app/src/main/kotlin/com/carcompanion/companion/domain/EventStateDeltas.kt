package com.carcompanion.companion.domain

/**
 * Event → non-valence state deltas. Single source of truth for how each
 * SemanticEvent nudges hunger / energy / curiosity / bond / stress /
 * vitality. Valence and arousal stay reaction-driven (per SOUL_LOGIC.json's
 * `valence_delta` / `arousal_delta`); this table covers everything else.
 *
 * ── Why this lives here ──────────────────────────────────────────────────
 * Pre-refactor, [CharacterEngine.applyReaction] had hand-coded
 * `when (event.name) { … }` blocks that mutated state. That was:
 *   - Asymmetric — every state needed a recovery path but only a handful of
 *     events were wired (e.g. nothing ever lowered hunger → emotion locked
 *     to HUNGRY after ~6.7 h)
 *   - Hard to audit — you had to grep all of CharacterEngine to know what
 *     event affected what state
 *   - Hard to tune per-persona — couldn't override without editing Kotlin
 *
 * By centralising into a Map<String, EventStateDelta>, every state has its
 * full lifecycle visible in one place, and a future persona JSON override
 * is straightforward.
 *
 * ── Sign convention ──────────────────────────────────────────────────────
 *   energy:    +ve = wakes up    / -ve = drains
 *   hunger:    +ve = gets hungry / -ve = sated (food / "I ate")
 *   curiosity: +ve = wants to explore / -ve = satisfied (new sight)
 *   bond:      +ve = stronger / -ve = weaker
 *   stress:    +ve = tense / -ve = calmer
 *   vitality:  +ve = restored / -ve = worn down
 *
 * All values are in the same units as the [SoulState] field they target
 * (0..100 scale).
 *
 * ── Tuning principle ─────────────────────────────────────────────────────
 * Every passive decay needs at least one event-driven counter. Audit:
 *
 *   STATE         | decay/min  | recovery events
 *   --------------|------------|--------------------------------------------
 *   hunger        | +0.2 (up)  | RETURN_AFTER_ABSENCE, TRIP_START_OF_DAY,
 *                 |            | (FOODIE+DOOR_OPEN when hunger>60 — special)
 *   energy        | -1.0 (dn)  | CAN_ENGINE_START, CAN_UNLOCKED,
 *                 |            | RETURN_AFTER_ABSENCE, TRIP_START_OF_DAY
 *   curiosity     | +0.5 (up)  | NAV_START, TRIP_START_OF_DAY, TRIP_LONG_*
 *                 |            | (also -5 per spontaneous in service tick)
 *   bond          | -1/day     | DOOR_OPEN, UNLOCKED, NAV_ARRIVED,
 *                 |            | DRIVING_SMOOTH_30M, TRIP_LONG_*, MILESTONE_*
 *   stress        | -0.5 (dn)  | event spikes only (HARSH_BRAKE, IMU_BUMP …)
 *   vitality      | -0.16/hr   | CAN_ENGINE_STOP, RETURN_AFTER_ABSENCE
 */
data class EventStateDelta(
    val energy: Float = 0f,
    val hunger: Float = 0f,
    val curiosity: Float = 0f,
    val bond: Float = 0f,
    val stress: Float = 0f,
    val vitality: Float = 0f,
)

/**
 * Lookup table. Keep entries ordered by event family for readability.
 * Missing event = no state delta (valence/arousal still apply via reaction).
 */
val EVENT_STATE_DELTAS: Map<String, EventStateDelta> = mapOf(
    // ── Engine + movement ────────────────────────────────────────────────
    "CAN_ENGINE_START"      to EventStateDelta(energy = +15f),
    "CAN_ENGINE_STOP"       to EventStateDelta(energy = -10f, vitality = +5f),
    "CAN_STARTED_MOVING"    to EventStateDelta(energy = +5f),
    "CAN_STOPPED"           to EventStateDelta(energy = -1f),
    "CAN_HIGH_SPEED"        to EventStateDelta(energy = +5f, stress = +1f),
    // Wake & first-drive — gated by [WakeOrchestrator] /
    // [TripPhaseAnalyzer]. The state deltas are additive on top of
    // CAN_ENGINE_START / CAN_STARTED_MOVING; they represent the soul
    // *registering* that the driver and robot are now in sync.
    "WAKE_ROBOT"            to EventStateDelta(bond = +5f, energy = +10f),
    "CAN_FIRST_DRIVE"       to EventStateDelta(energy = +5f, curiosity = +5f),

    // ── Door + lock cycle ────────────────────────────────────────────────
    "CAN_DOOR_OPEN"         to EventStateDelta(bond = +2f),
    "CAN_UNLOCKED"          to EventStateDelta(bond = +1f, energy = +5f),
    "CAN_LOCKED"            to EventStateDelta(),    // neutral
    "CAN_DOOR_AJAR_DRIVING" to EventStateDelta(stress = +5f),
    "CAN_TRUNK_OPEN_DRIVING" to EventStateDelta(stress = +5f),

    // ── Trip lifecycle (the main recovery path for chronic drains) ───────
    "TRIP_START_OF_DAY"     to EventStateDelta(
        hunger = -30f, energy = +20f, curiosity = -20f, vitality = +10f,
    ),
    "RETURN_AFTER_ABSENCE"  to EventStateDelta(
        hunger = -40f, energy = +20f, vitality = +15f, bond = +3f,
    ),
    "TRIP_LONG_60M"         to EventStateDelta(
        bond = +5f, energy = -10f, curiosity = -15f,
    ),
    "TRIP_LONG_120M"        to EventStateDelta(
        bond = +10f, vitality = -10f, energy = -15f,
    ),
    "TRIP_SHORT"            to EventStateDelta(curiosity = -5f),
    "TRIP_LATE_RETURN"      to EventStateDelta(energy = -10f, stress = +3f),

    // ── Navigation ───────────────────────────────────────────────────────
    "NAV_START"             to EventStateDelta(curiosity = -25f, energy = +3f),
    "NAV_ARRIVED"           to EventStateDelta(bond = +2f, energy = -5f, curiosity = -10f),
    // Anticipation beat — fires when we cross ETA ≤ 2 min, before the
    // actual NAV_ARRIVED. Drives TripPhaseAnalyzer DRIVING → ARRIVING.
    "TRIP_ARRIVING"         to EventStateDelta(curiosity = +5f, energy = +3f),

    // ── Music (Phase 6 input pillar — MediaSessionManager) ───────────────
    // Music is companionship in the car — playing → bond + energy bump,
    // stopping → mild bond drop, new song → curiosity poke.
    "MUSIC_STARTED"         to EventStateDelta(bond = +1f, energy = +3f, curiosity = +3f),
    "MUSIC_STOPPED"         to EventStateDelta(bond = -1f),
    "MUSIC_SONG_CHANGED"    to EventStateDelta(curiosity = +2f),

    // TIME_TICK — periodic in-drive heartbeat (every 15 min). Tiny
    // curiosity bump so a long uneventful trip still produces internal
    // change. Silent (no audio/gif mapping) — name ends in _TICK so
    // ReactionPolicy bypasses it.
    "TIME_TICK"             to EventStateDelta(curiosity = +2f),

    // ── Driving style feedback ───────────────────────────────────────────
    "DRIVING_SMOOTH_30M"    to EventStateDelta(bond = +5f, stress = -10f, vitality = +3f),
    "DRIVING_AGGRESSIVE"    to EventStateDelta(stress = +10f, bond = -2f),
    "CAN_HARSH_BRAKE"       to EventStateDelta(stress = +5f, bond = -1f),
    "CAN_AGGRESSIVE_THROTTLE" to EventStateDelta(stress = +3f),
    "CAN_IMU_BUMP"          to EventStateDelta(stress = +5f),

    // ── Patterns (Phase 4 composite events) ──────────────────────────────
    // UNSAFE_OVERTAKE = aggressive throttle without turn signal — bond
    // drops, stress climbs, soul is going to scold (ANGRY zone reaction).
    "UNSAFE_OVERTAKE"       to EventStateDelta(stress = +8f, bond = -3f),
    // EMERGENCY_BRAKE = severe brake with no aggressive precursor — Kuro
    // is *frightened*, not angry. Sympathy rather than scold. Vitality
    // drops because adrenaline cost, bond stays neutral.
    "EMERGENCY_BRAKE"       to EventStateDelta(stress = +15f, vitality = -2f),

    // ── Episodes (P3 sustained-state events) ─────────────────────────────
    // START = acknowledgement, TICK = "still here" minor wear,
    // END = the relief/closure beat once the episode passes.
    "ROUGH_ROAD_START"      to EventStateDelta(stress = +5f, energy = -3f),
    "ROUGH_ROAD_TICK"       to EventStateDelta(stress = +1f, vitality = -1f),
    "ROUGH_ROAD_END"        to EventStateDelta(stress = -3f, vitality = -2f),
    "SUSTAINED_BRAKE_START" to EventStateDelta(stress = +3f),
    "SUSTAINED_BRAKE_TICK"  to EventStateDelta(stress = +1f),
    "SUSTAINED_BRAKE_END"   to EventStateDelta(stress = -2f),
    "STUCK_TRAFFIC_START"   to EventStateDelta(stress = +2f, bond = +1f),
    "STUCK_TRAFFIC_TICK"    to EventStateDelta(bond = +1f),
    "STUCK_TRAFFIC_END"     to EventStateDelta(stress = -3f, bond = +3f),

    // ── Safety nags ──────────────────────────────────────────────────────
    "CAN_HEADLIGHT_FORGOTTEN" to EventStateDelta(stress = +2f),
    "CAN_TURN_SIGNAL_STUCK"   to EventStateDelta(stress = +1f),
    "CAN_SPEEDING_IN_CITY"    to EventStateDelta(stress = +5f, bond = -1f),
    "CAN_HANDBRAKE_FORGOTTEN" to EventStateDelta(stress = +5f),
    "CAN_PARKING_UNSAFE"      to EventStateDelta(stress = +3f),
    "CAN_BATTERY_LOW"         to EventStateDelta(vitality = -10f, stress = +3f),
    "CAN_MIL_ON"              to EventStateDelta(vitality = -15f, stress = +5f),
    "CAN_OVERHEATING"         to EventStateDelta(vitality = -20f, stress = +10f),

    // ── Milestones (achievement = bond + vitality boost) ─────────────────
    "MILESTONE_BOND_50"       to EventStateDelta(bond = +5f, vitality = +5f),
    "MILESTONE_BOND_100"      to EventStateDelta(bond = +10f, vitality = +10f),
    "MILESTONE_TRUST_80"      to EventStateDelta(bond = +5f, stress = -5f),
    "MILESTONE_TRIPS_10"      to EventStateDelta(bond = +2f, vitality = +3f),
    "MILESTONE_TRIPS_100"     to EventStateDelta(bond = +5f, vitality = +5f),
    "MILESTONE_TRIPS_1000"    to EventStateDelta(bond = +10f, vitality = +10f),
    "MILESTONE_DRIVETIME_10H" to EventStateDelta(bond = +3f, vitality = +5f),
    "MILESTONE_DRIVETIME_100H" to EventStateDelta(bond = +8f, vitality = +10f),
)
