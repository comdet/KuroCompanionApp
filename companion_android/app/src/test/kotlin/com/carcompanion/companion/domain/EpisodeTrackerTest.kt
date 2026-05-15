package com.carcompanion.companion.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeTrackerTest {

    private class FakeClock(var nowMs: Long = 1_000_000L) {
        fun get(): Long = nowMs
        fun advance(ms: Long) { nowMs += ms }
    }

    private fun bumpEvent() = SemanticEvent(name = "CAN_IMU_BUMP")

    private fun feed(
        tracker: EpisodeTracker,
        bumps: Int = 0,
        speed: Int = 0,
        brake: Boolean = false,
        engineOn: Boolean = true,
    ): List<SemanticEvent> = tracker.consume(
        events = List(bumps) { bumpEvent() },
        speedKmh = speed,
        brakePedal = brake,
        engineOn = engineOn,
    )

    // ── ROUGH_ROAD ─────────────────────────────────────────────────────

    @Test
    fun roughRoad_startsAfterFiveBumps() {
        val clock = FakeClock()
        val tracker = EpisodeTracker(now = clock::get)

        // 4 bumps spread over 8s — below threshold, no episode.
        for (i in 0..3) {
            val out = feed(tracker, bumps = 1)
            assertTrue("Should not start before threshold",
                out.none { it.name == "ROUGH_ROAD_START" })
            clock.advance(2_000)
        }
        // 5th bump within the 30s window triggers START.
        val out = feed(tracker, bumps = 1)
        assertTrue(out.any { it.name == "ROUGH_ROAD_START" })
        assertTrue(tracker.isActive(EpisodeTracker.Episode.ROUGH_ROAD))
    }

    @Test
    fun roughRoad_doesNotStartIfBumpsSpacedOutsideWindow() {
        val clock = FakeClock()
        val tracker = EpisodeTracker(now = clock::get)

        // 5 bumps but spread over 50s — only the last 1-2 land inside the
        // 30s window so START shouldn't fire.
        repeat(5) {
            val out = feed(tracker, bumps = 1)
            assertTrue("No start mid-stream", out.none { it.name == "ROUGH_ROAD_START" })
            clock.advance(10_000)
        }
        assertTrue(!tracker.isActive(EpisodeTracker.Episode.ROUGH_ROAD))
    }

    @Test
    fun roughRoad_emitsTickWhileActive() {
        val clock = FakeClock()
        val tracker = EpisodeTracker(now = clock::get)

        // Trigger START with 5 quick bumps.
        repeat(5) {
            feed(tracker, bumps = 1)
            clock.advance(1_000)
        }
        assertTrue(tracker.isActive(EpisodeTracker.Episode.ROUGH_ROAD))

        // Continue bumping every 5s — TICK should not fire before 60s, then
        // fire on the first call after that window elapses.
        clock.advance(30_000)   // total since start ~35s
        val midOut = feed(tracker, bumps = 1)
        assertTrue("no tick yet at ~35s",
            midOut.none { it.name == "ROUGH_ROAD_TICK" })

        clock.advance(30_000)   // total ~65s since start
        val tickOut = feed(tracker, bumps = 1)
        assertTrue("tick fires past 60s",
            tickOut.any { it.name == "ROUGH_ROAD_TICK" })
    }

    @Test
    fun roughRoad_endsAfterQuietWindow() {
        val clock = FakeClock()
        val tracker = EpisodeTracker(now = clock::get)

        // START
        repeat(5) { feed(tracker, bumps = 1); clock.advance(1_000) }
        assertTrue(tracker.isActive(EpisodeTracker.Episode.ROUGH_ROAD))

        // No bumps for 70s — should END.
        clock.advance(70_000)
        val out = feed(tracker, bumps = 0)
        assertTrue(out.any { it.name == "ROUGH_ROAD_END" })
        assertTrue(!tracker.isActive(EpisodeTracker.Episode.ROUGH_ROAD))
    }

    // ── SUSTAINED_BRAKE ────────────────────────────────────────────────

    @Test
    fun sustainedBrake_startsAfter5sHeld() {
        val clock = FakeClock()
        val tracker = EpisodeTracker(now = clock::get)

        // Brake pressed for 3s → still no start.
        feed(tracker, brake = true)
        clock.advance(3_000)
        val midOut = feed(tracker, brake = true)
        assertTrue(midOut.none { it.name == "SUSTAINED_BRAKE_START" })

        // Another 2.5s → past the 5s threshold.
        clock.advance(2_500)
        val out = feed(tracker, brake = true)
        assertTrue(out.any { it.name == "SUSTAINED_BRAKE_START" })
        assertTrue(tracker.isActive(EpisodeTracker.Episode.SUSTAINED_BRAKE))
    }

    @Test
    fun sustainedBrake_endsOnRelease() {
        val clock = FakeClock()
        val tracker = EpisodeTracker(now = clock::get)

        // START
        feed(tracker, brake = true)
        clock.advance(6_000)
        feed(tracker, brake = true)
        assertTrue(tracker.isActive(EpisodeTracker.Episode.SUSTAINED_BRAKE))

        // Release brake.
        val out = feed(tracker, brake = false)
        assertTrue(out.any { it.name == "SUSTAINED_BRAKE_END" })
        assertTrue(!tracker.isActive(EpisodeTracker.Episode.SUSTAINED_BRAKE))
    }

    @Test
    fun sustainedBrake_resetsTimerWhenReleasedEarly() {
        val clock = FakeClock()
        val tracker = EpisodeTracker(now = clock::get)

        // Press for 3s, release, then press for 3s more — should NOT count
        // as 6s sustained (the press streak reset on release).
        feed(tracker, brake = true)
        clock.advance(3_000)
        feed(tracker, brake = false)   // release at 3s
        clock.advance(1_000)
        feed(tracker, brake = true)    // new press
        clock.advance(3_000)
        val out = feed(tracker, brake = true)
        assertTrue("Brake-released-then-re-pressed should not trip START",
            out.none { it.name == "SUSTAINED_BRAKE_START" })
    }

    // ── STUCK_TRAFFIC ──────────────────────────────────────────────────

    @Test
    fun stuckTraffic_startsAfterLowSpeedWindow() {
        val clock = FakeClock()
        val tracker = EpisodeTracker(now = clock::get)

        // First drive properly so the "were we moving?" guard is satisfied.
        repeat(6) {
            feed(tracker, speed = 35, engineOn = true)
            clock.advance(10_000)
        }
        // Now crawl at 8 km/h for ~3.5 min — should START.
        var seenStart = false
        repeat(22) {
            val out = feed(tracker, speed = 8, engineOn = true)
            if (out.any { it.name == "STUCK_TRAFFIC_START" }) seenStart = true
            clock.advance(10_000)
        }
        assertTrue("Should start once 3min window of slow speed is full",
            seenStart)
        assertTrue(tracker.isActive(EpisodeTracker.Episode.STUCK_TRAFFIC))
    }

    @Test
    fun stuckTraffic_doesNotStartFromFreshParkedWarmup() {
        val clock = FakeClock()
        val tracker = EpisodeTracker(now = clock::get)

        // Engine on, never moved (warming up in driveway) — should NOT
        // trigger STUCK_TRAFFIC even after 3+ min.
        repeat(25) {
            val out = feed(tracker, speed = 0, engineOn = true)
            assertTrue("warm-up != stuck",
                out.none { it.name == "STUCK_TRAFFIC_START" })
            clock.advance(10_000)
        }
        assertTrue(!tracker.isActive(EpisodeTracker.Episode.STUCK_TRAFFIC))
    }

    @Test
    fun stuckTraffic_doesNotStartWithEngineOff() {
        val clock = FakeClock()
        val tracker = EpisodeTracker(now = clock::get)

        repeat(25) {
            val out = feed(tracker, speed = 0, engineOn = false)
            assertTrue("Parked != traffic",
                out.none { it.name == "STUCK_TRAFFIC_START" })
            clock.advance(10_000)
        }
        assertTrue(!tracker.isActive(EpisodeTracker.Episode.STUCK_TRAFFIC))
    }

    @Test
    fun stuckTraffic_endsAfterPickup() {
        val clock = FakeClock()
        val tracker = EpisodeTracker(now = clock::get)

        // Prime "we were moving" then trigger STUCK_TRAFFIC START.
        repeat(6) { feed(tracker, speed = 35, engineOn = true); clock.advance(10_000) }
        repeat(22) { feed(tracker, speed = 8, engineOn = true); clock.advance(10_000) }
        assertTrue(tracker.isActive(EpisodeTracker.Episode.STUCK_TRAFFIC))

        // Now cruise at 30 km/h for 70s — END condition met.
        var seenEnd = false
        repeat(8) {
            val out = feed(tracker, speed = 30, engineOn = true)
            if (out.any { it.name == "STUCK_TRAFFIC_END" }) seenEnd = true
            clock.advance(10_000)
        }
        assertTrue("Should end once 60s of >15 km/h average lands", seenEnd)
    }

    @Test
    fun stuckTraffic_endsImmediatelyOnEngineOff() {
        val clock = FakeClock()
        val tracker = EpisodeTracker(now = clock::get)

        // Prime "we were moving" then trigger STUCK_TRAFFIC START.
        repeat(6) { feed(tracker, speed = 35, engineOn = true); clock.advance(10_000) }
        repeat(22) { feed(tracker, speed = 8, engineOn = true); clock.advance(10_000) }
        assertTrue(tracker.isActive(EpisodeTracker.Episode.STUCK_TRAFFIC))

        // Engine off → instant END (we parked, no longer "traffic").
        val out = feed(tracker, speed = 0, engineOn = false)
        assertTrue(out.any { it.name == "STUCK_TRAFFIC_END" })
        assertTrue(!tracker.isActive(EpisodeTracker.Episode.STUCK_TRAFFIC))
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    @Test
    fun reset_clearsAllEpisodes() {
        val clock = FakeClock()
        val tracker = EpisodeTracker(now = clock::get)

        // Get all three active.
        repeat(5) { feed(tracker, bumps = 1, brake = true); clock.advance(1_000) }
        clock.advance(6_000)
        feed(tracker, brake = true)
        repeat(21) {
            feed(tracker, speed = 8, brake = true, engineOn = true)
            clock.advance(10_000)
        }
        assertTrue(tracker.isActive(EpisodeTracker.Episode.SUSTAINED_BRAKE))

        tracker.reset()
        for (ep in EpisodeTracker.Episode.values()) {
            assertTrue("Episode $ep should be inactive after reset",
                !tracker.isActive(ep))
        }
    }
}
