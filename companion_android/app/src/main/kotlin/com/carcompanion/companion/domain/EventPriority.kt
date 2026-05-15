package com.carcompanion.companion.domain

/**
 * Priority tier each event broadcast falls into.
 *
 * Higher priorities (P1, P2, P3) can pre-empt and suppress lower
 * priorities for a short window — so a "shake the tooth out, careful!"
 * (P1) doesn't get drowned out by chatty atomic reactions, and a
 * pattern-level "ทางลูกรังตลอดเลย" (P3) silences the per-bump "อ๊ะ" (P4).
 *
 * The lower the ordinal value, the higher the priority (P1 = 0, P5 = 4).
 */
enum class EventPriority {
    /** Immediate safety / damage risk — overheat, MIL, severe brake, low battery. */
    P1_CRITICAL_SAFETY,

    /** Lifecycle / trip boundary — wake, first-drive, engine on/off. */
    P2_LIFECYCLE,

    /** Pattern or episode — composite or sustained-state event. */
    P3_PATTERN_EPISODE,

    /** Default atomic event — single OBD edge, single bump, etc. */
    P4_ATOMIC,

    /** Spontaneous idle thought — lowest priority, always droppable. */
    P5_SPONTANEOUS;

    /** True iff this tier should pre-empt P4/P5 for the suppression window. */
    val isHighTier: Boolean
        get() = ordinal <= P3_PATTERN_EPISODE.ordinal

    companion object {
        /**
         * Lookup the priority of an event. Most cases are pure event-name;
         * a few (notably HARSH_BRAKE) promote to P1 when severity hits
         * SEVERE or CRITICAL.
         */
        fun priorityOf(event: SemanticEvent): EventPriority {
            // Severity-based promotion: a panic brake or a high-magnitude
            // bump becomes a safety event even though the underlying name
            // is just an atomic.
            if (event.name in SEVERITY_PROMOTED &&
                event.severity >= Severity.SEVERE
            ) return P1_CRITICAL_SAFETY

            return NAME_PRIORITY[event.name]
                ?: when {
                    event.name.startsWith("IDLE_")    -> P5_SPONTANEOUS
                    event.name.startsWith("ROUGH_")   -> P3_PATTERN_EPISODE
                    event.name.startsWith("STUCK_")   -> P3_PATTERN_EPISODE
                    event.name.startsWith("SUSTAIN")  -> P3_PATTERN_EPISODE
                    event.name.startsWith("UNSAFE_")  -> P3_PATTERN_EPISODE
                    event.name.startsWith("MILESTONE_") -> P2_LIFECYCLE
                    else -> P4_ATOMIC
                }
        }

        /** Events that get promoted to P1 only at SEVERE / CRITICAL severity. */
        private val SEVERITY_PROMOTED = setOf(
            "CAN_HARSH_BRAKE",
            "CAN_IMU_BUMP",
            "CAN_HIGH_SPEED",
        )

        /**
         * Explicit priority for events that don't follow a prefix pattern.
         * Anything not in this map (and not matching the prefix rules
         * below) defaults to [P4_ATOMIC].
         */
        private val NAME_PRIORITY: Map<String, EventPriority> = mapOf(
            // P1 — safety / damage risk
            "CAN_OVERHEATING"         to P1_CRITICAL_SAFETY,
            "CAN_HANDBRAKE_FORGOTTEN" to P1_CRITICAL_SAFETY,
            "CAN_DOOR_AJAR_DRIVING"   to P1_CRITICAL_SAFETY,
            "CAN_TRUNK_OPEN_DRIVING"  to P1_CRITICAL_SAFETY,
            "CAN_BATTERY_LOW"         to P1_CRITICAL_SAFETY,
            "CAN_MIL_ON"              to P1_CRITICAL_SAFETY,
            "CAN_PARKING_UNSAFE"      to P1_CRITICAL_SAFETY,
            "EMERGENCY_BRAKE"         to P1_CRITICAL_SAFETY,

            // P2 — lifecycle / trip boundary
            "WAKE_ROBOT"              to P2_LIFECYCLE,
            "CAN_FIRST_DRIVE"         to P2_LIFECYCLE,
            "CAN_ENGINE_START"        to P2_LIFECYCLE,
            "CAN_ENGINE_STOP"         to P2_LIFECYCLE,
            "CAN_UNLOCKED"            to P2_LIFECYCLE,
            "CAN_LOCKED"              to P2_LIFECYCLE,
            "RETURN_AFTER_ABSENCE"    to P2_LIFECYCLE,
            "TRIP_START_OF_DAY"       to P2_LIFECYCLE,
            "TRIP_LATE_RETURN"        to P2_LIFECYCLE,
            "TRIP_LONG_60M"           to P2_LIFECYCLE,
            "TRIP_LONG_120M"          to P2_LIFECYCLE,
            "TRIP_SHORT"              to P2_LIFECYCLE,
            "NAV_START"               to P2_LIFECYCLE,
            "NAV_ARRIVED"             to P2_LIFECYCLE,
            "NAV_NEAR_DESTINATION"    to P2_LIFECYCLE,
            "TRIP_ARRIVING"           to P2_LIFECYCLE,

            // P3 — pattern / episode lands here via prefix matching below;
            // none yet name-matched.
        )
    }
}
