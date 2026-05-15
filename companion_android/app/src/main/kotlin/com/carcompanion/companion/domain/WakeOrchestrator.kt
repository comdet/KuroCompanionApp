package com.carcompanion.companion.domain

/**
 * Decides when to fire `WAKE_ROBOT` — the single audible "I'm here"
 * reaction that should land in the brief window where:
 *   - the driver is in the car
 *   - the engine is on
 *   - the robot has confirmed it's actually reachable
 *   - the car hasn't started rolling yet
 *
 * Without this gate, `CAN_UNLOCKED` / `CAN_ENGINE_START` would each try
 * to play a welcome line, but both fire before the ESP32 has finished
 * booting and joining the LAN, so the audio goes to a black hole.
 *
 * ── Inputs ────────────────────────────────────────────────────────────
 *   - [RobotPresence.isOnline]   → set by every successful CCP exchange
 *   - [TripPhaseAnalyzer.phase]  → PRE_DRIVE only counts; once we move,
 *                                  the wake window has closed
 *
 * ── Edge rules ────────────────────────────────────────────────────────
 *   - Fires **at most once per ignition cycle.**
 *   - Engine restart within [ignitionContinuationMs] of the previous stop
 *     does **not** rearm the flag — that scenario is "driver killed the
 *     engine then started again 30 s later," which is the same ignition
 *     from the soul's POV.
 *   - Presence flapping (online → offline → online again) during PRE_DRIVE
 *     does **not** re-trigger because the flag stays set once fired.
 *   - If the car starts moving before the robot ever comes online, wake
 *     is missed by design — fall back to `CAN_FIRST_DRIVE` for audible
 *     greeting on the road.
 */
class WakeOrchestrator(
    private val presence: RobotPresence,
    private val tripPhase: TripPhaseAnalyzer,
    private val ignitionContinuationMs: Long = DEFAULT_IGNITION_CONTINUATION_MS,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile private var wakeFiredThisIgnition: Boolean = false
    @Volatile private var lastEngineStopMs: Long = 0L

    val hasFiredThisIgnition: Boolean get() = wakeFiredThisIgnition

    /**
     * Process upstream events (we care about CAN_ENGINE_START / STOP
     * for ignition bookkeeping) and return any synthetic wake events
     * the soul pipeline should consume next.
     *
     * Returned event names: `WAKE_ROBOT`.
     */
    fun consume(events: List<SemanticEvent>): List<SemanticEvent> {
        bookkeep(events)
        return checkWake()
    }

    /**
     * Re-check the wake condition without new events. Call this when
     * [RobotPresence.isOnline] just flipped to true so the orchestrator
     * can react to a presence-driven transition (e.g. ESP32 finished
     * booting after the engine was already on).
     */
    fun poll(): List<SemanticEvent> = checkWake()

    /** Service teardown — clears all per-ignition state. */
    fun reset() {
        wakeFiredThisIgnition = false
        lastEngineStopMs = 0L
    }

    private fun bookkeep(events: List<SemanticEvent>) {
        val nowMs = now()
        for (e in events) when (e.name) {
            "CAN_ENGINE_START" -> {
                val gap = if (lastEngineStopMs > 0L) nowMs - lastEngineStopMs
                          else Long.MAX_VALUE
                if (gap >= ignitionContinuationMs) {
                    // Distinct ignition cycle — re-arm the wake flag.
                    wakeFiredThisIgnition = false
                }
                // else: brief restart, keep flag (same ignition session)
            }
            "CAN_ENGINE_STOP" -> {
                lastEngineStopMs = nowMs
            }
        }
    }

    private fun checkWake(): List<SemanticEvent> {
        if (wakeFiredThisIgnition) return emptyList()
        if (tripPhase.phase.value != TripPhaseAnalyzer.Phase.PRE_DRIVE) return emptyList()
        if (!presence.isOnline.value) return emptyList()
        wakeFiredThisIgnition = true
        return listOf(SemanticEvent(name = "WAKE_ROBOT"))
    }

    companion object {
        /**
         * Engine off→on within this window is treated as the same
         * ignition — e.g. driver killed the engine to ask passenger
         * something then started again.
         */
        const val DEFAULT_IGNITION_CONTINUATION_MS = 60_000L
    }
}
