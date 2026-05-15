package com.carcompanion.companion.domain

/**
 * Layer-3 in the event-tier model (§4.1 of SOUL_EVENT_REDESIGN.md).
 *
 * Tracks **sustained states** rather than single moments, so the soul
 * can react once to "ทางลูกรังตลอดเลย" instead of barking on every
 * pothole. The tracker watches a few signals at once and emits
 * START / TICK / END events whose priority lives in P3_PATTERN_EPISODE
 * — see [EventPriority] — so each episode start will suppress the
 * underlying atomic chatter via [ReactionPolicy] for the next 10 s.
 *
 * ── Episodes shipped in Phase 3 ──────────────────────────────────────
 *
 * | Episode          | START condition                              | TICK         | END                            |
 * |------------------|----------------------------------------------|--------------|--------------------------------|
 * | ROUGH_ROAD       | ≥5 IMU bumps in last 30 s                    | every 60 s   | no bump for 60 s               |
 * | SUSTAINED_BRAKE  | brakePedal=true continuously for 5 s         | every 30 s   | brake released                 |
 * | STUCK_TRAFFIC    | avg speed < 10 km/h over 3 min, engine on    | every 90 s   | avg speed > 15 km/h over 60 s  |
 *
 * Future episodes (HIGHWAY_CRUISE, NIGHT_DRIVING, etc.) add new
 * `update*` helpers and entries in `Episode`. The window deques are
 * cheap so growing the list is fine.
 *
 * The class is **driven by [consume]**, called from the service tick
 * once per OBD status update. It is **not** a coroutine source on its
 * own — staying passive keeps testability high (the unit tests just
 * feed events with a fake clock).
 */
class EpisodeTracker(
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    enum class Episode { ROUGH_ROAD, SUSTAINED_BRAKE, STUCK_TRAFFIC }

    private data class State(
        var active: Boolean = false,
        var startedAtMs: Long = 0L,
        var lastTickEmittedMs: Long = 0L,
    )

    private val states: Map<Episode, State> =
        Episode.values().associateWith { State() }

    // ── Rolling windows for the start/end detectors ─────────────────────

    /** IMU bump timestamps, evicted past 30 s. */
    private val recentBumps: ArrayDeque<Long> = ArrayDeque()

    /** Speed samples (time, kmh), evicted past 5 min — the longest window. */
    private val recentSpeeds: ArrayDeque<Pair<Long, Int>> = ArrayDeque()

    /** When brake pedal first went down in the current press; 0 = released. */
    @Volatile private var brakePressedSinceMs: Long = 0L

    /** Snapshots for debug UI. */
    fun isActive(episode: Episode): Boolean = states.getValue(episode).active
    fun ageSec(episode: Episode): Long {
        val s = states.getValue(episode)
        return if (s.active) (now() - s.startedAtMs) / 1000 else 0L
    }

    /**
     * Feed one OBD slice. Returns synthetic events to push through the
     * normal soul pipeline (CharacterEngine, ReactionPolicy, broadcast).
     *
     * @param events    upstream atomic events (we look at CAN_IMU_BUMP)
     * @param speedKmh  current OBD speed (0 if unknown)
     * @param brakePedal current brake pedal state
     * @param engineOn  whether the engine is running — STUCK_TRAFFIC
     *                  ignores stationary samples when engine is off
     *                  (those are "parked", not "traffic")
     */
    fun consume(
        events: List<SemanticEvent>,
        speedKmh: Int,
        brakePedal: Boolean,
        engineOn: Boolean,
    ): List<SemanticEvent> {
        val nowMs = now()
        val out = mutableListOf<SemanticEvent>()

        // Update signal windows.
        for (e in events) if (e.name == "CAN_IMU_BUMP") {
            recentBumps.addLast(nowMs)
        }
        recentSpeeds.addLast(nowMs to speedKmh)
        evictOld(nowMs)

        out += updateRoughRoad(nowMs)
        out += updateSustainedBrake(nowMs, brakePedal)
        out += updateStuckTraffic(nowMs, engineOn)

        return out
    }

    /** Service teardown — clears all episode state. */
    fun reset() {
        states.values.forEach {
            it.active = false
            it.startedAtMs = 0L
            it.lastTickEmittedMs = 0L
        }
        recentBumps.clear()
        recentSpeeds.clear()
        brakePressedSinceMs = 0L
    }

    // ── ROUGH_ROAD ──────────────────────────────────────────────────────

    private fun updateRoughRoad(nowMs: Long): List<SemanticEvent> {
        val s = states.getValue(Episode.ROUGH_ROAD)
        val out = mutableListOf<SemanticEvent>()

        if (!s.active) {
            if (recentBumps.size >= ROUGH_ROAD_BUMP_THRESHOLD) {
                s.active = true
                s.startedAtMs = nowMs
                s.lastTickEmittedMs = nowMs
                out += SemanticEvent(
                    name = "ROUGH_ROAD_START",
                    value = recentBumps.size.toFloat(),
                )
            }
            return out
        }

        // While active, the latest bump time is the most recent entry in
        // the (evicted) window. If the window is empty for [END_QUIET_MS],
        // we're past the section.
        val lastBumpMs = recentBumps.lastOrNull() ?: 0L
        val quietForMs = nowMs - lastBumpMs

        if (lastBumpMs == 0L || quietForMs >= ROUGH_ROAD_END_QUIET_MS) {
            s.active = false
            out += SemanticEvent(
                name = "ROUGH_ROAD_END",
                value = ((nowMs - s.startedAtMs) / 1000f),
            )
        } else if (nowMs - s.lastTickEmittedMs >= ROUGH_ROAD_TICK_MS) {
            s.lastTickEmittedMs = nowMs
            out += SemanticEvent(
                name = "ROUGH_ROAD_TICK",
                value = ((nowMs - s.startedAtMs) / 1000f),
            )
        }
        return out
    }

    // ── SUSTAINED_BRAKE ─────────────────────────────────────────────────

    private fun updateSustainedBrake(
        nowMs: Long,
        brakePedal: Boolean,
    ): List<SemanticEvent> {
        val s = states.getValue(Episode.SUSTAINED_BRAKE)
        val out = mutableListOf<SemanticEvent>()

        if (brakePedal) {
            if (brakePressedSinceMs == 0L) brakePressedSinceMs = nowMs
            val pressedFor = nowMs - brakePressedSinceMs

            if (!s.active && pressedFor >= SUSTAINED_BRAKE_START_MS) {
                s.active = true
                s.startedAtMs = brakePressedSinceMs
                s.lastTickEmittedMs = nowMs
                out += SemanticEvent(
                    name = "SUSTAINED_BRAKE_START",
                    value = pressedFor / 1000f,
                )
            } else if (s.active &&
                nowMs - s.lastTickEmittedMs >= SUSTAINED_BRAKE_TICK_MS
            ) {
                s.lastTickEmittedMs = nowMs
                out += SemanticEvent(
                    name = "SUSTAINED_BRAKE_TICK",
                    value = pressedFor / 1000f,
                )
            }
        } else {
            // Brake released.
            brakePressedSinceMs = 0L
            if (s.active) {
                s.active = false
                out += SemanticEvent(
                    name = "SUSTAINED_BRAKE_END",
                    value = ((nowMs - s.startedAtMs) / 1000f),
                )
            }
        }
        return out
    }

    // ── STUCK_TRAFFIC ───────────────────────────────────────────────────

    private fun updateStuckTraffic(
        nowMs: Long,
        engineOn: Boolean,
    ): List<SemanticEvent> {
        val s = states.getValue(Episode.STUCK_TRAFFIC)
        val out = mutableListOf<SemanticEvent>()

        if (!engineOn) {
            // Parked, not traffic — end any active episode immediately.
            if (s.active) {
                s.active = false
                out += SemanticEvent(
                    name = "STUCK_TRAFFIC_END",
                    value = ((nowMs - s.startedAtMs) / 1000f),
                )
            }
            return out
        }

        if (!s.active) {
            val startAvg = avgSpeedWithin(nowMs, STUCK_TRAFFIC_START_WINDOW_MS)
                ?: return out
            // Distinguish "stuck in traffic" from "engine running while parked"
            // by requiring we *were* moving in the recent past. Without this
            // a fresh ignition cycle with a 3-min warm-up would trigger
            // STUCK_TRAFFIC immediately.
            val recentMax = recentSpeeds.maxOfOrNull { it.second } ?: 0
            val haveMoved = recentMax >= STUCK_TRAFFIC_PRECONDITION_KMH
            // Also require enough history — early session has only a few
            // samples and could trip on a false low average.
            val window = recentSpeeds.firstOrNull()?.let { nowMs - it.first } ?: 0L
            val historyEnough = window >= STUCK_TRAFFIC_START_WINDOW_MS / 2

            if (haveMoved && historyEnough &&
                startAvg < STUCK_TRAFFIC_START_THRESHOLD_KMH
            ) {
                s.active = true
                s.startedAtMs = nowMs
                s.lastTickEmittedMs = nowMs
                out += SemanticEvent(
                    name = "STUCK_TRAFFIC_START",
                    value = startAvg,
                )
            }
        } else {
            val endAvg = avgSpeedWithin(nowMs, STUCK_TRAFFIC_END_WINDOW_MS)
            if (endAvg != null && endAvg > STUCK_TRAFFIC_END_THRESHOLD_KMH) {
                s.active = false
                out += SemanticEvent(
                    name = "STUCK_TRAFFIC_END",
                    value = ((nowMs - s.startedAtMs) / 1000f),
                )
            } else if (nowMs - s.lastTickEmittedMs >= STUCK_TRAFFIC_TICK_MS) {
                s.lastTickEmittedMs = nowMs
                out += SemanticEvent(
                    name = "STUCK_TRAFFIC_TICK",
                    value = ((nowMs - s.startedAtMs) / 1000f),
                )
            }
        }
        return out
    }

    /**
     * Mean speed across samples whose timestamp falls within
     * `[nowMs - windowMs, nowMs]`. Returns null if there are no samples
     * (so the caller doesn't trip on an "avg=0" early in the session).
     */
    private fun avgSpeedWithin(nowMs: Long, windowMs: Long): Float? {
        val cutoff = nowMs - windowMs
        var sum = 0L
        var count = 0
        // ArrayDeque doesn't support reverse-iteration cheaply; we accept
        // the linear scan because the window is bounded by [evictOld].
        for ((t, v) in recentSpeeds) if (t >= cutoff) {
            sum += v
            count++
        }
        return if (count == 0) null else sum.toFloat() / count
    }

    private fun evictOld(nowMs: Long) {
        val bumpCutoff = nowMs - ROUGH_ROAD_WINDOW_MS
        while (recentBumps.isNotEmpty() && recentBumps.first() < bumpCutoff) {
            recentBumps.removeFirst()
        }
        val speedCutoff = nowMs - SPEED_HISTORY_MS
        while (recentSpeeds.isNotEmpty() && recentSpeeds.first().first < speedCutoff) {
            recentSpeeds.removeFirst()
        }
    }

    companion object {
        // ROUGH_ROAD
        const val ROUGH_ROAD_BUMP_THRESHOLD = 5
        const val ROUGH_ROAD_WINDOW_MS = 30_000L
        const val ROUGH_ROAD_END_QUIET_MS = 60_000L
        const val ROUGH_ROAD_TICK_MS = 60_000L

        // SUSTAINED_BRAKE
        const val SUSTAINED_BRAKE_START_MS = 5_000L
        const val SUSTAINED_BRAKE_TICK_MS = 30_000L

        // STUCK_TRAFFIC
        const val STUCK_TRAFFIC_START_WINDOW_MS = 3 * 60_000L
        const val STUCK_TRAFFIC_END_WINDOW_MS = 60_000L
        const val STUCK_TRAFFIC_START_THRESHOLD_KMH = 10f
        const val STUCK_TRAFFIC_END_THRESHOLD_KMH = 15f
        const val STUCK_TRAFFIC_TICK_MS = 90_000L
        /** "We were actually driving" precondition — see [updateStuckTraffic]. */
        const val STUCK_TRAFFIC_PRECONDITION_KMH = 20

        /** Max retention for the speed deque (matches longest window above). */
        private const val SPEED_HISTORY_MS = 5 * 60_000L
    }
}
