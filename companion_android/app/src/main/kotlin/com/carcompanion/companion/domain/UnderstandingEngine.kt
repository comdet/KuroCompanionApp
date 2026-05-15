package com.carcompanion.companion.domain

import com.carcompanion.companion.data.ObdMessage
import java.util.Calendar

/**
 * Layer 2 — Understanding Engine.
 *
 * Watches successive [ObdMessage.Status] snapshots and fires semantic events
 * that Layer 3 (CharacterEngine) cares about.
 *
 * Edges + latches:
 *   - "rising-edge" events (door opened, lights turned on) fire once per
 *     transition.
 *   - "sustained-condition" events (door ajar while driving, light forgotten,
 *     parking unsafe, turn signal stuck, idle too long) use a latch that fires
 *     once when the unsafe condition starts and re-arms when it clears.
 *
 * Event names mirror SOUL_LOGIC.json's `event_logic[].event`, so the
 * downstream mapping in CharacterEngine can stay a direct string lookup.
 */
class UnderstandingEngine(
    private val now: () -> Long = { System.currentTimeMillis() },
    private val hourOfDay: () -> Int = { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) },
) {
    private var previous: ObdMessage.Status? = null

    // Latches for once-per-occurrence safety warnings
    private var doorAjarWarned = false
    private var trunkOpenWarned = false
    private var headlightForgottenWarned = false
    private var highBeamInCityWarned = false
    private var speedingInCityWarned = false
    private var idleTooLongWarned = false

    // Timestamp-based "started at" trackers for sustained conditions
    private var turnSignalActiveSinceMs: Long = 0L
    private var turnSignalStuckWarned = false
    private var parkingUnsafeSinceMs: Long = 0L
    private var parkingUnsafeWarned = false
    private var idleSinceMs: Long = 0L

    /**
     * Returns a list of [SemanticEvent]s derived from the transition
     * `previous -> current`. Stateless from the caller's perspective.
     */
    fun consume(current: ObdMessage.Status): List<SemanticEvent> {
        val prev = previous
        previous = current
        if (prev == null) return emptyList()

        val events = mutableListOf<SemanticEvent>()
        val nowMs = now()
        val curSpeed = current.speed ?: 0
        val prevSpeed = prev.speed ?: 0
        val curEngine = current.engineRunning ?: ((current.rpm ?: 0) > 0)
        val prevEngine = prev.engineRunning ?: ((prev.rpm ?: 0) > 0)

        // ── Body — doors / trunk ────────────────────────────────────────
        if (anyDoorOpened(prev, current)) {
            events += SemanticEvent(name = "CAN_DOOR_OPEN")
        }
        // Car door ajar while driving (excludes trunk — trunk has its own event
        // because flying contents are a different kind of bad).
        val carDoorOpen = carDoorsOpen(current)
        if (carDoorOpen && curSpeed > 10 && !doorAjarWarned) {
            events += SemanticEvent(name = "CAN_DOOR_AJAR_DRIVING", value = curSpeed.toFloat())
            doorAjarWarned = true
        }
        if (!carDoorOpen || curSpeed <= 5) doorAjarWarned = false

        // Trunk open while driving — separate warning, harsher because of
        // contents at risk of falling out.
        val trunkOpen = current.doors?.trunk == true
        if (trunkOpen && curSpeed > 10 && !trunkOpenWarned) {
            events += SemanticEvent(name = "CAN_TRUNK_OPEN_DRIVING", value = curSpeed.toFloat())
            trunkOpenWarned = true
        }
        if (!trunkOpen || curSpeed <= 5) trunkOpenWarned = false

        // Lock state flip
        if (prev.locked != null && current.locked != null && prev.locked != current.locked) {
            events += SemanticEvent(
                name = if (current.locked) "CAN_LOCKED" else "CAN_UNLOCKED",
            )
        }

        // Harsh brake — rising edge while speed > 30. Severity from the
        // speed delta against the previous sample: a 40+ km/h drop in
        // one tick is panic-stop territory; <10 is just a firm tap.
        val prevBrake = prev.brakePedal == true
        val curBrake = current.brakePedal == true
        if (!prevBrake && curBrake && curSpeed > 30) {
            val speedDrop = (prevSpeed - curSpeed).coerceAtLeast(0)
            events += SemanticEvent(
                name = "CAN_HARSH_BRAKE",
                value = curSpeed.toFloat(),
                severity = Severity.forBrakeDelta(speedDrop),
            )
        }

        // Handbrake forgotten — driving with parking brake engaged
        val prevHandbrake = prev.handbrake == true
        val curHandbrake = current.handbrake == true
        if (curHandbrake && curSpeed > 5 && (!prevHandbrake || prevSpeed <= 5)) {
            events += SemanticEvent(name = "CAN_HANDBRAKE_FORGOTTEN", value = curSpeed.toFloat())
        }

        // ── Speed ────────────────────────────────────────────────────────
        if (prevSpeed <= 110 && curSpeed > 110) {
            events += SemanticEvent(
                name = "CAN_HIGH_SPEED",
                value = curSpeed.toFloat(),
                severity = Severity.forHighSpeed(curSpeed),
            )
        }
        if (prevSpeed > 0 && curSpeed == 0) events += SemanticEvent("CAN_STOPPED")
        if (prevSpeed == 0 && curSpeed > 0) events += SemanticEvent("CAN_STARTED_MOVING")

        // Speeding in city — speed > 80 in firmware state=DRIVING (pre-highway
        // city portion) or REARM. LOCKED_CRUISING is generally highway, so
        // we don't warn there.
        val inCity = current.state in CITY_STATES
        if (inCity && curSpeed > 80 && !speedingInCityWarned) {
            events += SemanticEvent(name = "CAN_SPEEDING_IN_CITY", value = curSpeed.toFloat())
            speedingInCityWarned = true
        }
        if (!inCity || curSpeed <= 70) speedingInCityWarned = false

        // ── Engine ───────────────────────────────────────────────────────
        if (!prevEngine && curEngine) events += SemanticEvent("CAN_ENGINE_START")
        if (prevEngine && !curEngine) events += SemanticEvent("CAN_ENGINE_STOP")

        // Aggressive throttle — crossing 80% from below
        val prevThrottle = prev.throttle ?: 0f
        val curThrottle = current.throttle ?: 0f
        if (prevThrottle <= 80f && curThrottle > 80f) {
            events += SemanticEvent(
                name = "CAN_AGGRESSIVE_THROTTLE",
                value = curThrottle,
                severity = Severity.forThrottlePercent(curThrottle),
            )
        }

        // Idle too long — engine on but stationary for over 5 minutes
        val isIdle = curEngine && curSpeed == 0
        if (isIdle) {
            if (idleSinceMs == 0L) idleSinceMs = nowMs
            val idleSec = (nowMs - idleSinceMs) / 1000
            if (idleSec >= 300 && !idleTooLongWarned) {
                events += SemanticEvent(name = "CAN_IDLE_TOO_LONG", value = idleSec.toFloat())
                idleTooLongWarned = true
            }
        } else {
            idleSinceMs = 0L
            idleTooLongWarned = false
        }

        // ── Health ───────────────────────────────────────────────────────
        val prevCoolant = prev.coolant ?: 0
        val curCoolant = current.coolant ?: 0
        if (prevCoolant <= 100 && curCoolant > 100) {
            events += SemanticEvent(
                name = "CAN_OVERHEATING",
                value = curCoolant.toFloat(),
                severity = Severity.forOverheatTemp(curCoolant),
            )
        }
        // Warm-up complete — cold start crosses 70°C for the first time
        if (prevCoolant < 70 && curCoolant >= 70 && curEngine) {
            events += SemanticEvent(name = "CAN_WARM_UP_COMPLETE", value = curCoolant.toFloat())
        }

        val prevBattery = prev.battery ?: 14f
        val curBattery = current.battery ?: 14f
        val battThreshold = if (curEngine) 12.0f else 11.8f
        if (prevBattery > battThreshold && curBattery <= battThreshold) {
            events += SemanticEvent(name = "CAN_BATTERY_LOW", value = curBattery)
        }

        if (prev.mil != true && current.mil == true) {
            events += SemanticEvent(name = "CAN_MIL_ON")
        }

        // ── Lights ───────────────────────────────────────────────────────
        val prevHeadOn = (prev.lights?.headlightRaw ?: 0) and 0x80 != 0
        val curHeadOn = (current.lights?.headlightRaw ?: 0) and 0x80 != 0
        if (!prevHeadOn && curHeadOn) events += SemanticEvent("CAN_HEADLIGHT_ON")
        if (prevHeadOn && !curHeadOn) events += SemanticEvent("CAN_HEADLIGHT_OFF")

        // Headlight forgotten — driving at night with no headlights on
        val hour = hourOfDay()
        val isNight = hour >= 19 || hour <= 5
        val drivingAtNight = isNight && curEngine && curSpeed > 5
        if (drivingAtNight && !curHeadOn && !headlightForgottenWarned) {
            events += SemanticEvent(name = "CAN_HEADLIGHT_FORGOTTEN", value = hour.toFloat())
            headlightForgottenWarned = true
        }
        if (curHeadOn || !isNight || curSpeed <= 5) headlightForgottenWarned = false

        // High beam in city / at low speed — dazzling oncoming traffic
        val curHighBeam = current.lights?.highBeam == true
        if (curHighBeam && curSpeed in 1..60 && inCity && !highBeamInCityWarned) {
            events += SemanticEvent(name = "CAN_HIGH_BEAM_IN_CITY", value = curSpeed.toFloat())
            highBeamInCityWarned = true
        }
        if (!curHighBeam || curSpeed > 70 || !inCity) highBeamInCityWarned = false

        // Turn signal stuck — left/right on for over 30 s without state change
        val anyTurnNow = (current.lights?.turnLeft == true) || (current.lights?.turnRight == true)
        if (anyTurnNow) {
            if (turnSignalActiveSinceMs == 0L) turnSignalActiveSinceMs = nowMs
            val activeSec = (nowMs - turnSignalActiveSinceMs) / 1000
            if (activeSec >= 30 && !turnSignalStuckWarned) {
                events += SemanticEvent(name = "CAN_TURN_SIGNAL_STUCK", value = activeSec.toFloat())
                turnSignalStuckWarned = true
            }
        } else {
            turnSignalActiveSinceMs = 0L
            turnSignalStuckWarned = false
        }

        // ── Parking ──────────────────────────────────────────────────────
        // Engine on, fully stopped, parking brake released — risk of rolling.
        val parkingUnsafe = curEngine && curSpeed == 0 && !curHandbrake
        if (parkingUnsafe) {
            if (parkingUnsafeSinceMs == 0L) parkingUnsafeSinceMs = nowMs
            val unsafeSec = (nowMs - parkingUnsafeSinceMs) / 1000
            if (unsafeSec >= 30 && !parkingUnsafeWarned) {
                events += SemanticEvent(name = "CAN_PARKING_UNSAFE", value = unsafeSec.toFloat())
                parkingUnsafeWarned = true
            }
        } else {
            parkingUnsafeSinceMs = 0L
            parkingUnsafeWarned = false
        }

        // ── Firmware state-machine transitions ──────────────────────────
        if (prev.state != current.state) {
            events += SemanticEvent(
                name = "CAN_STATE_CHANGE",
                detail = "${prev.state}>${current.state}",
            )
        }

        return events
    }

    private fun anyDoorOpened(prev: ObdMessage.Status, cur: ObdMessage.Status): Boolean {
        val p = prev.doors ?: return false
        val c = cur.doors ?: return false
        return rising(p.driver, c.driver) ||
            rising(p.passenger, c.passenger) ||
            rising(p.rearLeft, c.rearLeft) ||
            rising(p.rearRight, c.rearRight) ||
            rising(p.trunk, c.trunk)
    }

    /** Any car door (driver / passenger / rear*) currently open — trunk excluded. */
    private fun carDoorsOpen(cur: ObdMessage.Status): Boolean {
        val d = cur.doors ?: return false
        return d.driver == true || d.passenger == true ||
            d.rearLeft == true || d.rearRight == true
    }

    private fun rising(prev: Boolean?, cur: Boolean?): Boolean =
        prev == false && cur == true

    companion object {
        /** Firmware states that imply the car is in an urban environment. */
        private val CITY_STATES = setOf("DRIVING", "REARM", "LOCKED_STOPPED")
    }
}

/**
 * A semantic event the rest of the system reacts to.
 * `name` matches SOUL_LOGIC.json's `event_logic[].event`.
 *
 * [severity] is set by the producer (UnderstandingEngine for OBD-derived
 * events, ImuAnalyzer for bumps) based on the underlying magnitude.
 * Default MILD lets older event sites that don't know about severity
 * keep working — they just lose the magnitude-based scaling, which is
 * exactly the old behaviour.
 */
data class SemanticEvent(
    val name: String,
    val value: Float? = null,
    val detail: String? = null,
    val severity: Severity = Severity.MILD,
)
