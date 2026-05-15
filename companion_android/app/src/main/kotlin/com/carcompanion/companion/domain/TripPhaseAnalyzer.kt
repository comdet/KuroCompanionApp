package com.carcompanion.companion.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Layer-2 helper that classifies the **current segment of a trip
 * lifecycle** as one of seven phases. The other analyzers in this
 * package are stateful but event-shaped; this one is a state machine
 * whose value is observed continuously (by [WakeOrchestrator], the
 * ping poller in the service, and any future reaction policy that
 * cares about "where are we in the journey").
 *
 * ── Phases ────────────────────────────────────────────────────────────
 *
 *   IDLE_NO_TRIP   — Car at rest. Nobody around.
 *   ENTERING       — Driver opened a door but the engine hasn't started.
 *                    Robot is still offline (no 12 V), so reactions
 *                    here are silent state-deltas only.
 *   PRE_DRIVE      — Engine on, car stationary. Wake window.
 *   DRIVING        — Sustained speed > 5 km/h. Most reactions live here.
 *   ARRIVING       — Navigation reports near the destination.
 *   POST_DRIVE     — Engine just stopped, doors may still be cycling.
 *   LEFT           — Engine off + locked + 60 s of quiet. Cabin empty.
 *                    Robot will follow shortly (USB power dropped on
 *                    engine off, but firmware has a debounce).
 *
 * ── Transitions ──────────────────────────────────────────────────────
 *
 *   IDLE_NO_TRIP / LEFT ──CAN_DOOR_OPEN──▶ ENTERING
 *   ENTERING ──CAN_ENGINE_START──▶ PRE_DRIVE
 *   ENTERING ──door closed + 60 s without engine──▶ IDLE_NO_TRIP
 *     (driver opened door to grab something, never started the car)
 *
 *   any ──CAN_ENGINE_START──▶ PRE_DRIVE
 *     (defensive fallback if the service started mid-ignition)
 *
 *   PRE_DRIVE ──speed > 5 km/h for 3 s──▶ DRIVING
 *     (also emits CAN_FIRST_DRIVE exactly once per ignition)
 *
 *   DRIVING ──NAV_NEAR_DESTINATION──▶ ARRIVING
 *
 *   PRE_DRIVE | DRIVING | ARRIVING ──CAN_ENGINE_STOP──▶ POST_DRIVE
 *
 *   POST_DRIVE ──door closed + locked + 60 s──▶ LEFT
 *
 *   LEFT ──5 min──▶ IDLE_NO_TRIP
 *
 *   POST_DRIVE | LEFT ──CAN_ENGINE_START──▶ PRE_DRIVE (new ignition)
 *
 * The "sustained" threshold and window avoid false-closing the wake gate
 * when the driver inches forward at a parking gate. The timeouts on
 * ENTERING / POST_DRIVE / LEFT are checked on every [consume] call —
 * the analyzer doesn't run its own coroutine, so the caller must tick it
 * at least every few seconds for the time-based transitions to land.
 */
class TripPhaseAnalyzer(
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    enum class Phase {
        IDLE_NO_TRIP,
        ENTERING,
        PRE_DRIVE,
        DRIVING,
        ARRIVING,
        POST_DRIVE,
        LEFT,
    }

    private val _phase = MutableStateFlow(Phase.IDLE_NO_TRIP)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private var sustainedAboveSinceMs: Long = 0L
    private var firstDriveEmitted: Boolean = false
    private var phaseEnteredAtMs: Long = 0L

    /** Snapshot for debug UI / Soul Debug panel. */
    val isPreDrive: Boolean get() = _phase.value == Phase.PRE_DRIVE

    /**
     * Drive the machine one tick. Pass the events from upstream
     * analyzers (we look at engine, door, and NAV events), the current
     * OBD speed, plus a snapshot of door and lock state. Returns
     * synthetic events the soul pipeline should also see —
     * `CAN_FIRST_DRIVE` on pre→driving, `TRIP_ARRIVING` on
     * driving→arriving.
     */
    fun consume(
        events: List<SemanticEvent>,
        speedKmh: Int,
        doorOpen: Boolean = false,
        locked: Boolean = false,
    ): List<SemanticEvent> {
        val out = mutableListOf<SemanticEvent>()
        val nowMs = now()

        // ── Event-driven transitions ─────────────────────────────────
        for (e in events) when (e.name) {
            "CAN_DOOR_OPEN" -> {
                if (_phase.value == Phase.IDLE_NO_TRIP || _phase.value == Phase.LEFT) {
                    transition(Phase.ENTERING, nowMs)
                }
            }
            "CAN_ENGINE_START" -> {
                transition(Phase.PRE_DRIVE, nowMs)
                sustainedAboveSinceMs = 0L
                firstDriveEmitted = false
            }
            "CAN_ENGINE_STOP" -> {
                transition(Phase.POST_DRIVE, nowMs)
                sustainedAboveSinceMs = 0L
            }
            "NAV_NEAR_DESTINATION" -> {
                if (_phase.value == Phase.DRIVING) {
                    transition(Phase.ARRIVING, nowMs)
                    out += SemanticEvent(name = "TRIP_ARRIVING", value = e.value)
                }
            }
        }

        // ── Speed-driven: PRE_DRIVE → DRIVING ────────────────────────
        if (_phase.value == Phase.PRE_DRIVE) {
            if (speedKmh > MOVE_THRESHOLD_KMH) {
                if (sustainedAboveSinceMs == 0L) {
                    sustainedAboveSinceMs = nowMs
                } else if (!firstDriveEmitted &&
                    nowMs - sustainedAboveSinceMs >= SUSTAIN_MS
                ) {
                    transition(Phase.DRIVING, nowMs)
                    firstDriveEmitted = true
                    out += SemanticEvent(
                        name = "CAN_FIRST_DRIVE",
                        value = speedKmh.toFloat(),
                    )
                }
            } else {
                // Inched forward then stopped — don't count the streak.
                sustainedAboveSinceMs = 0L
            }
        }

        // ── Time-driven transitions ──────────────────────────────────
        val elapsed = nowMs - phaseEnteredAtMs
        when (_phase.value) {
            Phase.ENTERING -> {
                if (!doorOpen && elapsed >= ENTERING_TIMEOUT_MS) {
                    transition(Phase.IDLE_NO_TRIP, nowMs)
                }
            }
            Phase.POST_DRIVE -> {
                if (!doorOpen && locked && elapsed >= POST_DRIVE_TO_LEFT_MS) {
                    transition(Phase.LEFT, nowMs)
                }
            }
            Phase.LEFT -> {
                if (elapsed >= LEFT_TO_IDLE_MS) {
                    transition(Phase.IDLE_NO_TRIP, nowMs)
                }
            }
            else -> Unit
        }

        return out
    }

    private fun transition(newPhase: Phase, nowMs: Long) {
        if (_phase.value == newPhase) return
        _phase.value = newPhase
        phaseEnteredAtMs = nowMs
    }

    /** Hard reset (service teardown). */
    fun reset() {
        _phase.value = Phase.IDLE_NO_TRIP
        sustainedAboveSinceMs = 0L
        firstDriveEmitted = false
        phaseEnteredAtMs = 0L
    }

    companion object {
        /** Sustained speed needed to declare DRIVING. */
        const val MOVE_THRESHOLD_KMH = 5

        /** How long [MOVE_THRESHOLD_KMH] must hold before we commit. */
        const val SUSTAIN_MS = 3_000L

        /** ENTERING reverts to IDLE if no engine start by this point. */
        const val ENTERING_TIMEOUT_MS = 60_000L

        /** POST_DRIVE promotes to LEFT after this much closed-and-locked time. */
        const val POST_DRIVE_TO_LEFT_MS = 60_000L

        /** LEFT decays back to IDLE_NO_TRIP after this much quiet. */
        const val LEFT_TO_IDLE_MS = 5 * 60_000L
    }
}
