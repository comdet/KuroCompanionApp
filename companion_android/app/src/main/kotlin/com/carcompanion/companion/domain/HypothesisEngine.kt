package com.carcompanion.companion.domain

import com.carcompanion.companion.data.ObdMessage
import java.util.Calendar

/**
 * Layer 2.5 — long-context narrative classifier.
 *
 * Consumes ObdStatus snapshots into a 5-min rolling window, computes a small
 * feature set (avg speed, harsh-brake rate, idle ratio, engine runtime,
 * time-of-day), then resolves a [Hypothesis] via a deterministic lookup table.
 *
 * To avoid flapping when the driver is on the boundary between e.g.
 * CRUISING / RUSHING, classification uses a **2-tick hysteresis**: a new
 * candidate must classify the same way twice in a row before it replaces
 * the active hypothesis.
 *
 * Stateless from the service's POV — feed() updates the window, classify()
 * recomputes; both are safe to call from the same coroutine.
 */
class HypothesisEngine(
    private val windowMs: Long = 5 * 60 * 1000L,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val hourOfDay: () -> Int = {
        Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    },
) {
    private data class Snap(
        val tMs: Long,
        val speed: Int,
        val brakePedal: Boolean,
        val engineRunning: Boolean,
    )

    private val window = ArrayDeque<Snap>()
    private var active: Hypothesis = Hypothesis.RELAXED
    private var candidate: Hypothesis = Hypothesis.RELAXED
    private var candidateRepeats = 0

    /** Push a new snapshot. Window self-prunes to [windowMs]. */
    fun feed(status: ObdMessage.Status) {
        val tMs = now()
        val s = Snap(
            tMs = tMs,
            speed = status.speed ?: 0,
            brakePedal = status.brakePedal == true,
            engineRunning = status.engineRunning ?: ((status.rpm ?: 0) > 0),
        )
        window.addLast(s)
        while (window.isNotEmpty() && tMs - window.first().tMs > windowMs) {
            window.removeFirst()
        }
    }

    /** Currently active hypothesis (use this in the picker). */
    fun current(): Hypothesis = active

    /**
     * Recompute classification using the latest window. Applies hysteresis
     * before updating [current]. Returns the post-hysteresis result so the
     * caller can react.
     */
    fun classify(): Hypothesis {
        if (window.isEmpty()) return active
        val f = computeFeatures()
        val proposal = classifyByFeatures(f)

        if (proposal == candidate) {
            candidateRepeats++
        } else {
            candidate = proposal
            candidateRepeats = 1
        }

        if (candidateRepeats >= 2 && candidate != active) {
            active = candidate
        }
        return active
    }

    private fun computeFeatures(): Features {
        val speeds = window.map { it.speed }
        val avg = speeds.average().toFloat()
        val mx = speeds.maxOrNull() ?: 0
        val idleCount = speeds.count { it == 0 }
        val idleRatio = idleCount.toFloat() / speeds.size.coerceAtLeast(1)
        val brakeRises = countBrakeRisingEdges()
        val firstEngineRunning = window.firstOrNull { it.engineRunning }?.tMs
        val runtimeSec = if (firstEngineRunning != null) {
            (window.last().tMs - firstEngineRunning) / 1000L
        } else 0L
        val windowMinutes = (window.last().tMs - window.first().tMs) / 60_000f
        val brakeRate = if (windowMinutes > 0.1f) brakeRises / windowMinutes else 0f
        return Features(
            avgSpeed = avg,
            maxSpeed = mx,
            harshBrakeRate = brakeRate,
            idleRatio = idleRatio,
            engineRuntimeSec = runtimeSec,
            hour = hourOfDay(),
        )
    }

    private fun countBrakeRisingEdges(): Int {
        var count = 0
        var prev = false
        for (s in window) {
            if (!prev && s.brakePedal) count++
            prev = s.brakePedal
        }
        return count
    }

    private fun classifyByFeatures(f: Features): Hypothesis {
        val isNight = f.hour in nightHours
        return when {
            f.harshBrakeRate > 1.0f -> Hypothesis.FRUSTRATED
            f.engineRuntimeSec > 3600 && f.avgSpeed < 50 && isNight -> Hypothesis.TIRED
            f.avgSpeed > 80 && f.harshBrakeRate > 0.5f && f.engineRuntimeSec > 60 -> Hypothesis.RUSHING
            f.avgSpeed < 15 && f.idleRatio > 0.4f -> Hypothesis.STUCK_TRAFFIC
            f.avgSpeed in 40f..100f && f.harshBrakeRate < 0.2f && f.idleRatio < 0.1f -> Hypothesis.CRUISING
            else -> Hypothesis.RELAXED
        }
    }

    private data class Features(
        val avgSpeed: Float,
        val maxSpeed: Int,
        val harshBrakeRate: Float,
        val idleRatio: Float,
        val engineRuntimeSec: Long,
        val hour: Int,
    )

    companion object {
        private val nightHours = setOf(22, 23, 0, 1, 2, 3, 4, 5)
    }
}
