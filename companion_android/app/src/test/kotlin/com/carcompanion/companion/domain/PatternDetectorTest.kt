package com.carcompanion.companion.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternDetectorTest {

    private class FakeClock(var nowMs: Long = 1_000_000L) {
        fun get(): Long = nowMs
        fun advance(ms: Long) { nowMs += ms }
    }

    private fun throttle() = SemanticEvent(name = "CAN_AGGRESSIVE_THROTTLE")
    private fun harshBrake(severity: Severity = Severity.SEVERE) =
        SemanticEvent(name = "CAN_HARSH_BRAKE", severity = severity)
    private fun highSpeed() = SemanticEvent(name = "CAN_HIGH_SPEED")

    // ── UNSAFE_OVERTAKE ────────────────────────────────────────────────

    @Test
    fun overtake_firesWhenSpeedGainsWithoutSignal() {
        val clock = FakeClock()
        val det = PatternDetector(now = clock::get)

        // Throttle spike at 60 km/h, no turn signal.
        val r1 = det.consume(
            events = listOf(throttle()),
            speedKmh = 60,
            turnLeft = false,
            turnRight = false,
        )
        assertTrue(r1.emitted.isEmpty())   // pending, not fired yet
        assertTrue(det.hasPendingOvertake())

        // 5s later, speed climbed to 85 (gain = 25) → should fire.
        clock.advance(5_000)
        val r2 = det.consume(
            events = emptyList(),
            speedKmh = 85,
            turnLeft = false,
            turnRight = false,
        )
        assertTrue(r2.emitted.any { it.name == "UNSAFE_OVERTAKE" })
        assertTrue(!det.hasPendingOvertake())
    }

    @Test
    fun overtake_suppressedByTurnSignal() {
        val clock = FakeClock()
        val det = PatternDetector(now = clock::get)

        // Throttle spike WITH right turn signal already on.
        det.consume(
            events = listOf(throttle()),
            speedKmh = 60,
            turnLeft = false,
            turnRight = true,
        )
        clock.advance(5_000)
        val r = det.consume(
            events = emptyList(),
            speedKmh = 90,
            turnLeft = false,
            turnRight = true,
        )
        assertTrue("Signalled overtake should not fire UNSAFE_OVERTAKE",
            r.emitted.none { it.name == "UNSAFE_OVERTAKE" })
    }

    @Test
    fun overtake_dropsAfterWindow() {
        val clock = FakeClock()
        val det = PatternDetector(now = clock::get)

        det.consume(
            events = listOf(throttle()),
            speedKmh = 60,
            turnLeft = false,
            turnRight = false,
        )
        // 12s later, beyond the 10s window → drop pending even if speed gained.
        clock.advance(12_000)
        val r = det.consume(
            events = emptyList(),
            speedKmh = 90,
            turnLeft = false,
            turnRight = false,
        )
        assertTrue("Pending should be dropped after window",
            r.emitted.none { it.name == "UNSAFE_OVERTAKE" })
        assertTrue(!det.hasPendingOvertake())
    }

    @Test
    fun overtake_secondSpikeResetsBaseline() {
        val clock = FakeClock()
        val det = PatternDetector(now = clock::get)

        // First spike at 60.
        det.consume(listOf(throttle()), 60, false, false)
        clock.advance(3_000)
        // Second spike at 70 — baseline resets to 70, prior gain irrelevant.
        det.consume(listOf(throttle()), 70, false, false)
        clock.advance(2_000)
        // Speed 80 = gain 10 from the new baseline (not 20 from the first).
        val r = det.consume(emptyList(), 80, false, false)
        assertTrue("Insufficient gain from refreshed baseline",
            r.emitted.none { it.name == "UNSAFE_OVERTAKE" })
    }

    // ── EMERGENCY_BRAKE ────────────────────────────────────────────────

    @Test
    fun emergencyBrake_firesForSevereBrakeWithoutAggressiveContext() {
        val clock = FakeClock()
        val det = PatternDetector(now = clock::get)

        // Cruising peacefully — no aggressive throttle/high speed in buffer.
        det.consume(emptyList(), 50, false, false)
        clock.advance(5_000)

        // Sudden severe brake (pedestrian darted out).
        val r = det.consume(listOf(harshBrake(Severity.SEVERE)), 30, false, false)
        assertTrue(r.emitted.any { it.name == "EMERGENCY_BRAKE" })
        assertTrue("HARSH_BRAKE should be suppressed in favour of EMERGENCY_BRAKE",
            "CAN_HARSH_BRAKE" in r.suppressed)
    }

    @Test
    fun emergencyBrake_suppressedByRecentAggressiveThrottle() {
        val clock = FakeClock()
        val det = PatternDetector(now = clock::get)

        // Aggressive throttle 10s ago — counts as aggressive context.
        det.consume(listOf(throttle()), 80, false, false)
        clock.advance(10_000)
        val r = det.consume(listOf(harshBrake(Severity.SEVERE)), 40, false, false)
        assertTrue("Brake after aggressive throttle = scold, not sympathy",
            r.emitted.none { it.name == "EMERGENCY_BRAKE" })
        assertTrue("Raw HARSH_BRAKE should pass through unchanged",
            r.suppressed.isEmpty())
    }

    @Test
    fun emergencyBrake_suppressedByRecentHighSpeed() {
        val clock = FakeClock()
        val det = PatternDetector(now = clock::get)

        det.consume(listOf(highSpeed()), 130, false, false)
        clock.advance(20_000)
        val r = det.consume(listOf(harshBrake(Severity.SEVERE)), 50, false, false)
        assertTrue(r.emitted.none { it.name == "EMERGENCY_BRAKE" })
    }

    @Test
    fun emergencyBrake_doesNotFireForMildBrake() {
        val clock = FakeClock()
        val det = PatternDetector(now = clock::get)

        det.consume(emptyList(), 50, false, false)
        val r = det.consume(listOf(harshBrake(Severity.MILD)), 30, false, false)
        assertTrue("Only SEVERE/CRITICAL brakes promote to emergency",
            r.emitted.none { it.name == "EMERGENCY_BRAKE" })
        assertTrue(r.suppressed.isEmpty())
    }

    @Test
    fun emergencyBrake_suppressedByRecentOvertake() {
        val clock = FakeClock()
        val det = PatternDetector(now = clock::get)

        // Trigger UNSAFE_OVERTAKE first.
        det.consume(listOf(throttle()), 60, false, false)
        clock.advance(3_000)
        val r1 = det.consume(emptyList(), 85, false, false)
        assertTrue(r1.emitted.any { it.name == "UNSAFE_OVERTAKE" })

        // Then a severe brake 5s later. UNSAFE_OVERTAKE counts as aggressive
        // context, so EMERGENCY_BRAKE should NOT fire.
        clock.advance(5_000)
        val r2 = det.consume(listOf(harshBrake(Severity.SEVERE)), 40, false, false)
        assertTrue(r2.emitted.none { it.name == "EMERGENCY_BRAKE" })
    }

    @Test
    fun emergencyBrake_oldAggressiveContextAgesOut() {
        val clock = FakeClock()
        val det = PatternDetector(now = clock::get)

        det.consume(listOf(throttle()), 80, false, false)
        // Wait past the 30s context window.
        clock.advance(35_000)
        val r = det.consume(listOf(harshBrake(Severity.SEVERE)), 40, false, false)
        assertTrue("After context expires, brake again becomes 'emergency'",
            r.emitted.any { it.name == "EMERGENCY_BRAKE" })
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    @Test
    fun reset_clearsPendingAndBuffers() {
        val clock = FakeClock()
        val det = PatternDetector(now = clock::get)

        det.consume(listOf(throttle()), 60, false, false)
        assertTrue(det.hasPendingOvertake())

        det.reset()
        assertTrue(!det.hasPendingOvertake())

        // Severe brake right after reset → no aggressive context anywhere
        // (buffer is empty), so EMERGENCY_BRAKE fires cleanly.
        val r = det.consume(listOf(harshBrake(Severity.SEVERE)), 40, false, false)
        assertTrue(r.emitted.any { it.name == "EMERGENCY_BRAKE" })
    }
}
