package com.carcompanion.companion.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TripPhaseAnalyzerTest {

    private class FakeClock(var nowMs: Long = 1_000_000L) {
        fun get(): Long = nowMs
        fun advance(ms: Long) { nowMs += ms }
    }

    private fun ev(name: String) = SemanticEvent(name = name)
    private fun emit(
        a: TripPhaseAnalyzer,
        eventName: String? = null,
        speed: Int = 0,
        door: Boolean = false,
        locked: Boolean = false,
    ): List<SemanticEvent> = a.consume(
        events = eventName?.let { listOf(ev(it)) } ?: emptyList(),
        speedKmh = speed,
        doorOpen = door,
        locked = locked,
    )

    @Test
    fun starts_inIdleNoTrip() {
        val a = TripPhaseAnalyzer()
        assertEquals(TripPhaseAnalyzer.Phase.IDLE_NO_TRIP, a.phase.value)
    }

    // ── ENTERING ───────────────────────────────────────────────────────

    @Test
    fun idle_doorOpen_enters() {
        val a = TripPhaseAnalyzer()
        emit(a, eventName = "CAN_DOOR_OPEN", door = true)
        assertEquals(TripPhaseAnalyzer.Phase.ENTERING, a.phase.value)
    }

    @Test
    fun entering_engineStart_movesToPreDrive() {
        val a = TripPhaseAnalyzer()
        emit(a, eventName = "CAN_DOOR_OPEN", door = true)
        emit(a, eventName = "CAN_ENGINE_START")
        assertEquals(TripPhaseAnalyzer.Phase.PRE_DRIVE, a.phase.value)
    }

    @Test
    fun entering_timeoutWithDoorClosed_revertsToIdle() {
        val clock = FakeClock()
        val a = TripPhaseAnalyzer(now = clock::get)
        emit(a, eventName = "CAN_DOOR_OPEN", door = true)
        // Door closed; nothing happens for 70 s.
        clock.advance(70_000)
        emit(a, eventName = null, door = false)
        assertEquals(TripPhaseAnalyzer.Phase.IDLE_NO_TRIP, a.phase.value)
    }

    @Test
    fun entering_doorStillOpen_doesNotTimeOut() {
        val clock = FakeClock()
        val a = TripPhaseAnalyzer(now = clock::get)
        emit(a, eventName = "CAN_DOOR_OPEN", door = true)
        clock.advance(70_000)
        // Door still open (driver loading something) — stay in ENTERING.
        emit(a, eventName = null, door = true)
        assertEquals(TripPhaseAnalyzer.Phase.ENTERING, a.phase.value)
    }

    // ── PRE_DRIVE → DRIVING ────────────────────────────────────────────

    @Test
    fun preDrive_sustainedSpeed_promotesAndEmitsFirstDrive() {
        val clock = FakeClock()
        val a = TripPhaseAnalyzer(now = clock::get)
        emit(a, eventName = "CAN_ENGINE_START")
        emit(a, eventName = null, speed = 10)
        clock.advance(3_500)
        val out = emit(a, eventName = null, speed = 10)
        assertTrue(out.any { it.name == "CAN_FIRST_DRIVE" })
        assertEquals(TripPhaseAnalyzer.Phase.DRIVING, a.phase.value)
    }

    @Test
    fun preDrive_inchForwardStop_doesNotPromote() {
        val clock = FakeClock()
        val a = TripPhaseAnalyzer(now = clock::get)
        emit(a, eventName = "CAN_ENGINE_START")
        emit(a, eventName = null, speed = 10)
        clock.advance(1_000)
        emit(a, eventName = null, speed = 0)   // briefly stopped
        clock.advance(3_000)
        // Speed back to >5 but the streak was broken at the stop.
        emit(a, eventName = null, speed = 10)
        assertEquals(TripPhaseAnalyzer.Phase.PRE_DRIVE, a.phase.value)
    }

    // ── DRIVING → ARRIVING ─────────────────────────────────────────────

    @Test
    fun driving_navNearDestination_movesToArrivingAndEmits() {
        val clock = FakeClock()
        val a = TripPhaseAnalyzer(now = clock::get)
        emit(a, eventName = "CAN_ENGINE_START")
        emit(a, eventName = null, speed = 20)
        clock.advance(4_000)
        emit(a, eventName = null, speed = 30)
        assertEquals(TripPhaseAnalyzer.Phase.DRIVING, a.phase.value)

        val out = emit(a, eventName = "NAV_NEAR_DESTINATION", speed = 30)
        assertEquals(TripPhaseAnalyzer.Phase.ARRIVING, a.phase.value)
        assertTrue(out.any { it.name == "TRIP_ARRIVING" })
    }

    @Test
    fun preDrive_navNearDestination_isIgnored() {
        // Without DRIVING context the NAV signal shouldn't promote phases.
        val a = TripPhaseAnalyzer()
        emit(a, eventName = "CAN_ENGINE_START")
        val out = emit(a, eventName = "NAV_NEAR_DESTINATION")
        assertEquals(TripPhaseAnalyzer.Phase.PRE_DRIVE, a.phase.value)
        assertTrue(out.none { it.name == "TRIP_ARRIVING" })
    }

    // ── ARRIVING → POST_DRIVE ──────────────────────────────────────────

    @Test
    fun arriving_engineStop_movesToPostDrive() {
        val clock = FakeClock()
        val a = TripPhaseAnalyzer(now = clock::get)
        emit(a, eventName = "CAN_ENGINE_START")
        emit(a, eventName = null, speed = 20)
        clock.advance(4_000)
        emit(a, eventName = null, speed = 30)
        emit(a, eventName = "NAV_NEAR_DESTINATION", speed = 30)
        assertEquals(TripPhaseAnalyzer.Phase.ARRIVING, a.phase.value)

        emit(a, eventName = "CAN_ENGINE_STOP")
        assertEquals(TripPhaseAnalyzer.Phase.POST_DRIVE, a.phase.value)
    }

    // ── POST_DRIVE → LEFT ──────────────────────────────────────────────

    @Test
    fun postDrive_lockedClosedQuiet_promotesToLeft() {
        val clock = FakeClock()
        val a = TripPhaseAnalyzer(now = clock::get)
        emit(a, eventName = "CAN_ENGINE_STOP")
        assertEquals(TripPhaseAnalyzer.Phase.POST_DRIVE, a.phase.value)

        // Locked, door closed, 70 s pass.
        clock.advance(70_000)
        emit(a, eventName = null, door = false, locked = true)
        assertEquals(TripPhaseAnalyzer.Phase.LEFT, a.phase.value)
    }

    @Test
    fun postDrive_doorOpen_doesNotPromote() {
        val clock = FakeClock()
        val a = TripPhaseAnalyzer(now = clock::get)
        emit(a, eventName = "CAN_ENGINE_STOP")
        clock.advance(70_000)
        // Door still open — still in POST_DRIVE.
        emit(a, eventName = null, door = true, locked = false)
        assertEquals(TripPhaseAnalyzer.Phase.POST_DRIVE, a.phase.value)
    }

    @Test
    fun postDrive_unlocked_doesNotPromote() {
        val clock = FakeClock()
        val a = TripPhaseAnalyzer(now = clock::get)
        emit(a, eventName = "CAN_ENGINE_STOP")
        clock.advance(70_000)
        emit(a, eventName = null, door = false, locked = false)
        assertEquals(TripPhaseAnalyzer.Phase.POST_DRIVE, a.phase.value)
    }

    // ── LEFT → IDLE / re-entry ─────────────────────────────────────────

    @Test
    fun left_decaysToIdleAfterFiveMin() {
        val clock = FakeClock()
        val a = TripPhaseAnalyzer(now = clock::get)
        emit(a, eventName = "CAN_ENGINE_STOP")
        clock.advance(70_000)
        emit(a, eventName = null, door = false, locked = true)
        assertEquals(TripPhaseAnalyzer.Phase.LEFT, a.phase.value)

        clock.advance(5 * 60_000 + 1_000)
        emit(a, eventName = null, door = false, locked = true)
        assertEquals(TripPhaseAnalyzer.Phase.IDLE_NO_TRIP, a.phase.value)
    }

    @Test
    fun left_doorOpen_movesBackToEntering() {
        val clock = FakeClock()
        val a = TripPhaseAnalyzer(now = clock::get)
        emit(a, eventName = "CAN_ENGINE_STOP")
        clock.advance(70_000)
        emit(a, eventName = null, door = false, locked = true)
        assertEquals(TripPhaseAnalyzer.Phase.LEFT, a.phase.value)

        emit(a, eventName = "CAN_DOOR_OPEN", door = true)
        assertEquals(TripPhaseAnalyzer.Phase.ENTERING, a.phase.value)
    }

    // ── Resilience ────────────────────────────────────────────────────

    @Test
    fun engineStart_fromAnyPhase_dropsToPreDrive() {
        // Service may start mid-ignition; the analyzer should accept
        // CAN_ENGINE_START as a hard reset to PRE_DRIVE.
        val a = TripPhaseAnalyzer()
        emit(a, eventName = "CAN_DOOR_OPEN", door = true)   // ENTERING
        emit(a, eventName = "CAN_ENGINE_START")             // → PRE_DRIVE
        assertEquals(TripPhaseAnalyzer.Phase.PRE_DRIVE, a.phase.value)
    }

    @Test
    fun reset_returnsToIdle() {
        val a = TripPhaseAnalyzer()
        emit(a, eventName = "CAN_DOOR_OPEN", door = true)
        assertEquals(TripPhaseAnalyzer.Phase.ENTERING, a.phase.value)
        a.reset()
        assertEquals(TripPhaseAnalyzer.Phase.IDLE_NO_TRIP, a.phase.value)
    }
}
