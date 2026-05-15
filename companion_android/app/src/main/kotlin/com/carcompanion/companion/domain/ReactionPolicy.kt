package com.carcompanion.companion.domain

/**
 * The "speech traffic cop" that sits between the soul's reaction picker
 * and [BroadcastEngine]. Before the redesign, every event that survived
 * cooldown produced audible output, which created two failure modes:
 *
 *  1. **Chatter** during dense event streams (rough roads, stop-and-go
 *     traffic, parking lots) where 6+ reactions per minute drowned each
 *     other out.
 *  2. **Trampling** of important reactions — a P1 "you forgot the
 *     parking brake!" could end up queued behind three "อ๊ะ!" bumps.
 *
 * [ReactionPolicy.decide] takes both concerns into account:
 *
 *  - **Priority bypass.** P1 critical safety always broadcasts; budget
 *    and suppression don't apply.
 *  - **Tier suppression.** When a P1 / P2 / P3 fires it sets a recent-
 *    high-tier timestamp; P4 (atomic) and P5 (spontaneous) reactions
 *    arriving within [suppressionWindowMs] are silenced. The atomic
 *    component reactions of an ongoing pattern/episode disappear, which
 *    is exactly what the redesign §4.6 wanted.
 *  - **Speech budget.** A sliding 60 s window counts allowed broadcasts.
 *    Caller passes a snapshot-aware budget (high stress → quieter; high
 *    bond + positive valence → chattier). When the cap is hit, anything
 *    P2-P5 drops silently. P1 still bypasses.
 *
 * State held: a small deque of broadcast timestamps and one volatile
 * "last high-tier fired at" marker. Both reset on [reset], called from
 * service teardown.
 */
class ReactionPolicy(
    /**
     * Per-minute cap on reactions. Looked up each [decide] call so the
     * caller can recompute based on the current [SoulSnapshot] without
     * threading state through the policy object.
     */
    private val budgetPerMinute: () -> Int = { DEFAULT_BUDGET_PER_MINUTE },
    private val suppressionWindowMs: Long = DEFAULT_SUPPRESSION_WINDOW_MS,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Outcome of a policy decision. Caller broadcasts only on [ALLOW];
     * the DROP variants exist so the service can log *why* a reaction
     * was silenced (helpful when tuning thresholds).
     */
    enum class Decision { ALLOW, DROP_BUDGET, DROP_SUPPRESSED }

    /** Ring of broadcast timestamps in the last 60 s. */
    private val sentTimestamps: ArrayDeque<Long> = ArrayDeque()

    /**
     * Wall-clock of the most recent P1-P3 broadcast. Used to suppress
     * P4-P5 within [suppressionWindowMs]. 0 = never fired this session.
     */
    @Volatile private var highTierLastFiredMs: Long = 0L

    /** Snapshots for debug UI. */
    val recentBroadcastCount: Int get() = sentTimestamps.size
    val lastHighTierFiredMs: Long get() = highTierLastFiredMs

    fun decide(event: SemanticEvent): Decision {
        val priority = EventPriority.priorityOf(event)
        val nowMs = now()
        evictOld(nowMs)

        // 1. P1 critical safety always lands. We still record it for
        // the suppression / budget book so subsequent atomics behave.
        if (priority == EventPriority.P1_CRITICAL_SAFETY) {
            recordBroadcast(nowMs, priority)
            return Decision.ALLOW
        }

        // 2. Suppression: P4/P5 drop when a high-tier is still warm.
        if (priority == EventPriority.P4_ATOMIC ||
            priority == EventPriority.P5_SPONTANEOUS
        ) {
            if (highTierLastFiredMs > 0L &&
                nowMs - highTierLastFiredMs < suppressionWindowMs
            ) return Decision.DROP_SUPPRESSED
        }

        // 3. Budget: 60-second sliding cap.
        if (sentTimestamps.size >= budgetPerMinute()) {
            return Decision.DROP_BUDGET
        }

        recordBroadcast(nowMs, priority)
        return Decision.ALLOW
    }

    /** Hard reset (service teardown). */
    fun reset() {
        sentTimestamps.clear()
        highTierLastFiredMs = 0L
    }

    private fun recordBroadcast(nowMs: Long, priority: EventPriority) {
        sentTimestamps.addLast(nowMs)
        if (priority.isHighTier) highTierLastFiredMs = nowMs
    }

    private fun evictOld(nowMs: Long) {
        val cutoff = nowMs - WINDOW_MS
        while (sentTimestamps.isNotEmpty() && sentTimestamps.first() < cutoff) {
            sentTimestamps.removeFirst()
        }
    }

    companion object {
        const val DEFAULT_BUDGET_PER_MINUTE = 6
        const val DEFAULT_SUPPRESSION_WINDOW_MS = 10_000L
        const val WINDOW_MS = 60_000L

        /**
         * Snapshot-aware budget computation. High stress → quieter (the
         * soul shouldn't pile on when the driver is already tense). High
         * bond + positive valence → chattier (Kuro is comfortable
         * speaking up around an engaged driver).
         */
        fun budgetFor(snapshot: SoulSnapshot): Int = when {
            snapshot.stress > 60f -> 4
            snapshot.bond > 70f && snapshot.valence > 0.3f -> 8
            else -> DEFAULT_BUDGET_PER_MINUTE
        }
    }
}
