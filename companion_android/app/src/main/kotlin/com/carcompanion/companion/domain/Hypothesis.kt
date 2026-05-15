package com.carcompanion.companion.domain

/**
 * The character's running guess about what the driver is doing.
 *
 * Computed by [HypothesisEngine] from a 5-minute rolling window of OBD status
 * snapshots. Reactions can filter on this string (`hypothesis: "RUSHING"`) so
 * the same event picks different responses depending on long-term context.
 */
enum class Hypothesis(val label: String, val emoji: String) {
    RUSHING("rushing", "🏃"),
    TIRED("tired", "😪"),
    CRUISING("cruising", "🛣️"),
    STUCK_TRAFFIC("stuck", "🚥"),
    FRUSTRATED("frustrated", "💢"),
    RELAXED("relaxed", "😌"),
}
