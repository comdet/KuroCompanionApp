package com.carcompanion.companion.domain

/**
 * How "big" an atomic event is. The same `CAN_HARSH_BRAKE` should not
 * trigger the same reaction whether the driver tapped the pedal firmly
 * at 35 km/h or stood on it to avoid a collision — yet pre-Severity that
 * was exactly the case.
 *
 * Severity affects the soul pipeline in three places:
 *
 *  1. [com.carcompanion.companion.domain.CharacterEngine.applyReaction]
 *     scales the [EVENT_STATE_DELTAS] entry by [multiplier]. A
 *     CRITICAL brake hits stress / energy twice as hard as the MILD
 *     baseline.
 *
 *  2. Reaction picker (Round 2 — TODO) can nudge weight on
 *     PANIC / EXCITED zone reactions for higher severities, so the
 *     intuitive "stronger event → stronger persona" surfaces in the
 *     content too.
 *
 *  3. Future: severity becomes a content-pool selector
 *     (`audio/Kuro/CAN_HARSH_BRAKE_CRITICAL/`) once we generate severity-
 *     specific lines.
 *
 * Buckets are *open-ended* — the highest threshold catches everything
 * above it — so a 2.5g brake still classifies as CRITICAL.
 */
enum class Severity {
    MILD, MODERATE, SEVERE, CRITICAL;

    /**
     * Scaling factor applied to [EventStateDelta] fields. Centred on
     * MILD = 1.0 so existing tuning is preserved when severity is
     * unknown or omitted.
     */
    fun multiplier(): Float = when (this) {
        MILD     -> 1.0f
        MODERATE -> 1.2f
        SEVERE   -> 1.5f
        CRITICAL -> 2.0f
    }

    companion object {
        /**
         * Brake severity from the speed lost in the sample window in km/h.
         * Without IMU deceleration we approximate via Δspeed; calibrate
         * once we have road data.
         */
        fun forBrakeDelta(speedDeltaKmh: Int): Severity = when {
            speedDeltaKmh >= 40 -> CRITICAL
            speedDeltaKmh >= 25 -> SEVERE
            speedDeltaKmh >= 10 -> MODERATE
            else                -> MILD
        }

        /** IMU bump severity from peak acceleration magnitude (m/s²). */
        fun forBumpMagnitude(magnitude: Float): Severity = when {
            magnitude >= 15f -> CRITICAL
            magnitude >= 10f -> SEVERE
            magnitude >= 6f  -> MODERATE
            else             -> MILD
        }

        /** High-speed severity from current speed (km/h). */
        fun forHighSpeed(speedKmh: Int): Severity = when {
            speedKmh >= 160 -> CRITICAL
            speedKmh >= 140 -> SEVERE
            speedKmh >= 120 -> MODERATE
            else            -> MILD
        }

        /** Throttle aggressiveness from instantaneous throttle %. */
        fun forThrottlePercent(throttle: Float): Severity = when {
            throttle >= 98f -> CRITICAL
            throttle >= 92f -> SEVERE
            throttle >= 85f -> MODERATE
            else            -> MILD
        }

        /** Coolant overheat severity from temperature (°C). */
        fun forOverheatTemp(tempC: Int): Severity = when {
            tempC >= 115 -> CRITICAL
            tempC >= 110 -> SEVERE
            tempC >= 105 -> MODERATE
            else         -> MILD
        }
    }
}
