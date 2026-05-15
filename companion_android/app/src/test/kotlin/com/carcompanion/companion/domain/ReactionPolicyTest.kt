package com.carcompanion.companion.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ReactionPolicyTest {

    /** Mutable clock so the tests can advance time deterministically. */
    private class FakeClock(var nowMs: Long = 1_000_000L) {
        fun get(): Long = nowMs
        fun advance(ms: Long) { nowMs += ms }
    }

    private fun atomicEvent(name: String = "CAN_DOOR_OPEN"): SemanticEvent =
        SemanticEvent(name = name)

    private fun spontaneousEvent(): SemanticEvent =
        SemanticEvent(name = "IDLE_HAPPY")

    @Test
    fun atomic_allowedWithinBudget() {
        val clock = FakeClock()
        val policy = ReactionPolicy(budgetPerMinute = { 6 }, now = clock::get)

        // First 6 atomic events should ALL be allowed.
        repeat(6) {
            assertEquals(
                "broadcast #${it + 1} should be ALLOWED",
                ReactionPolicy.Decision.ALLOW,
                policy.decide(atomicEvent("ATOMIC_TEST")),
            )
            clock.advance(1_000)
        }
    }

    @Test
    fun atomic_dropsAfterBudgetExhausted() {
        val clock = FakeClock()
        val policy = ReactionPolicy(budgetPerMinute = { 3 }, now = clock::get)

        // Fill the budget with low-priority atomics that share suppression
        // semantics; use CAN_LOCKED so suppression isn't triggered.
        repeat(3) {
            assertEquals(ReactionPolicy.Decision.ALLOW,
                policy.decide(atomicEvent("ATOMIC_TEST")))
            clock.advance(500)
        }
        // Fourth call within the same 60s window → DROP_BUDGET.
        assertEquals(
            ReactionPolicy.Decision.DROP_BUDGET,
            policy.decide(atomicEvent("ATOMIC_TEST")),
        )
    }

    @Test
    fun budget_resetsAfterWindow() {
        val clock = FakeClock()
        val policy = ReactionPolicy(budgetPerMinute = { 2 }, now = clock::get)

        // Fill budget at t=0.
        assertEquals(ReactionPolicy.Decision.ALLOW, policy.decide(atomicEvent("ATOMIC_TEST")))
        assertEquals(ReactionPolicy.Decision.ALLOW, policy.decide(atomicEvent("ATOMIC_TEST")))
        assertEquals(ReactionPolicy.Decision.DROP_BUDGET,
            policy.decide(atomicEvent("ATOMIC_TEST")))

        // Slide past the 60 s window — the two earlier broadcasts age out.
        clock.advance(61_000)
        assertEquals(
            "after window, budget should be replenished",
            ReactionPolicy.Decision.ALLOW,
            policy.decide(atomicEvent("ATOMIC_TEST")),
        )
    }

    @Test
    fun p1_safetyBypassesBudget() {
        val clock = FakeClock()
        val policy = ReactionPolicy(budgetPerMinute = { 1 }, now = clock::get)

        // Use up the entire budget with a non-safety event.
        policy.decide(atomicEvent("ATOMIC_TEST"))
        assertEquals(ReactionPolicy.Decision.DROP_BUDGET,
            policy.decide(atomicEvent("ATOMIC_TEST")))

        // P1 critical should still land.
        assertEquals(
            "P1 critical (overheat) should bypass budget",
            ReactionPolicy.Decision.ALLOW,
            policy.decide(SemanticEvent(name = "CAN_OVERHEATING")),
        )
    }

    @Test
    fun p1_severityPromotedBrakeBypasses() {
        val clock = FakeClock()
        val policy = ReactionPolicy(budgetPerMinute = { 1 }, now = clock::get)

        // Use up the budget.
        policy.decide(atomicEvent("ATOMIC_TEST"))
        assertEquals(ReactionPolicy.Decision.DROP_BUDGET,
            policy.decide(atomicEvent("ATOMIC_TEST")))

        // A SEVERE harsh brake should be promoted to P1 and bypass.
        val panicBrake = SemanticEvent(
            name = "CAN_HARSH_BRAKE",
            severity = Severity.SEVERE,
        )
        assertEquals(
            ReactionPolicy.Decision.ALLOW,
            policy.decide(panicBrake),
        )

        // …but a MILD harsh brake stays P4 atomic. With the panic brake (P1)
        // having just set the high-tier marker, the next P4 is suppressed
        // *before* the budget check fires — DROP_SUPPRESSED wins.
        val gentleBrake = SemanticEvent(
            name = "CAN_HARSH_BRAKE",
            severity = Severity.MILD,
        )
        assertEquals(
            ReactionPolicy.Decision.DROP_SUPPRESSED,
            policy.decide(gentleBrake),
        )
    }

    @Test
    fun suppression_silencesAtomicsAfterHighTier() {
        val clock = FakeClock()
        val policy = ReactionPolicy(
            budgetPerMinute = { 100 },                 // huge so it's not the limiter
            suppressionWindowMs = 10_000L,
            now = clock::get,
        )

        // A P2 lifecycle event fires…
        assertEquals(
            ReactionPolicy.Decision.ALLOW,
            policy.decide(SemanticEvent(name = "WAKE_ROBOT")),
        )
        // …then an atomic 5 s later → suppressed.
        clock.advance(5_000)
        assertEquals(
            ReactionPolicy.Decision.DROP_SUPPRESSED,
            policy.decide(atomicEvent("ATOMIC_TEST")),
        )
        // …and a spontaneous 8 s after the WAKE → also suppressed.
        clock.advance(3_000)
        assertEquals(
            ReactionPolicy.Decision.DROP_SUPPRESSED,
            policy.decide(spontaneousEvent()),
        )
        // After the window, atomics flow again.
        clock.advance(3_000)   // total 11 s after WAKE
        assertEquals(
            ReactionPolicy.Decision.ALLOW,
            policy.decide(atomicEvent("ATOMIC_TEST")),
        )
    }

    @Test
    fun suppression_doesNotBlockHigherTiers() {
        val clock = FakeClock()
        val policy = ReactionPolicy(
            budgetPerMinute = { 100 },
            suppressionWindowMs = 10_000L,
            now = clock::get,
        )

        // P3 episode fires…
        assertEquals(
            ReactionPolicy.Decision.ALLOW,
            policy.decide(SemanticEvent(name = "ROUGH_ROAD_START")),
        )
        clock.advance(2_000)
        // P1 critical must still go through.
        assertEquals(
            ReactionPolicy.Decision.ALLOW,
            policy.decide(SemanticEvent(name = "CAN_OVERHEATING")),
        )
        // P2 lifecycle also fine (only P4/P5 are gated).
        assertEquals(
            ReactionPolicy.Decision.ALLOW,
            policy.decide(SemanticEvent(name = "CAN_ENGINE_STOP")),
        )
    }

    @Test
    fun reset_clearsBudgetAndSuppression() {
        val clock = FakeClock()
        val policy = ReactionPolicy(
            budgetPerMinute = { 2 },
            suppressionWindowMs = 10_000L,
            now = clock::get,
        )
        // Trip suppression by firing a P2 lifecycle event.
        policy.decide(SemanticEvent(name = "WAKE_ROBOT"))
        // Confirm suppression is now active — next P4 atomic drops.
        assertEquals(
            ReactionPolicy.Decision.DROP_SUPPRESSED,
            policy.decide(atomicEvent("ATOMIC_TEST")),
        )

        policy.reset()

        // Fresh state — atomic right after reset should ALLOW.
        assertEquals(
            ReactionPolicy.Decision.ALLOW,
            policy.decide(atomicEvent("ATOMIC_TEST")),
        )
    }
}
