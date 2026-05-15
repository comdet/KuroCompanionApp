package com.carcompanion.companion.domain

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.sqrt

/**
 * Layer 1.5 — physical jolt detection from the **phone's** accelerometer.
 *
 * The car-side IMU isn't reachable yet (the firmware ESP32-S3 has QMI8658 on
 * the dashboard robot, not the OBD bridge), so the phone IMU is the next-best
 * thing — it travels with the car and sees road bumps from the driver's seat.
 *
 * Algorithm:
 *   1. Subscribe to `TYPE_LINEAR_ACCELERATION` (gravity-removed, ~50 Hz).
 *   2. Magnitude = √(x² + y² + z²) m/s².
 *   3. When magnitude > [bumpThresholdMps2] AND it's been at least
 *      [cooldownMs] since the last detected bump → emit `CAN_IMU_BUMP`.
 *
 * Threshold tuning notes (verified-in-session is impossible — we don't have
 * the phone on the dashboard yet):
 *   - 8 m/s² catches gentle speed bumps but also enthusiastic phone gestures.
 *   - 12 m/s² is what most "shake detection" libraries use; works for cars
 *     hitting potholes at city speeds.
 *   - 15+ m/s² catches only serious road events (highway potholes, hitting
 *     curbs). We default to 12 — adjust after road-testing.
 *
 * Cooldown stops a single bump from re-firing repeatedly while the chassis
 * is still oscillating.
 */
class ImuAnalyzer(
    context: Context,
    private val onBump: (magnitude: Float) -> Unit,
    /** Optional throttled live reading (~4 Hz) for debug UI. */
    private val onLiveSample: ((magnitude: Float) -> Unit)? = null,
    private val bumpThresholdMps2: Float = 12f,
    private val cooldownMs: Long = 2_000L,
    private val now: () -> Long = { System.currentTimeMillis() },
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val linearAccel: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    @Volatile private var lastBumpMs: Long = 0L
    @Volatile private var lastSampleMs: Long = 0L
    @Volatile private var registered: Boolean = false

    /** Most recent peak that triggered a bump. 0 until the first bump fires. */
    @Volatile var lastBumpMagnitude: Float = 0f
        private set
    /** Timestamp of last bump (epoch ms). 0 until the first bump fires. */
    @Volatile var lastBumpEpochMs: Long = 0L
        private set
    /** True if the sensor exists and we managed to subscribe. */
    val isActive: Boolean get() = registered

    /** Returns true if the sensor was actually subscribed (false = no hardware). */
    fun start(): Boolean {
        if (registered) return true
        val sensor = linearAccel ?: run {
            Log.w(TAG, "No TYPE_LINEAR_ACCELERATION sensor; IMU bumps disabled")
            return false
        }
        val ok = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        registered = ok
        if (ok) Log.d(TAG, "IMU bump detector armed (threshold=${bumpThresholdMps2} m/s²)")
        return ok
    }

    fun stop() {
        if (!registered) return
        sensorManager.unregisterListener(this)
        registered = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        if (e.sensor.type != Sensor.TYPE_LINEAR_ACCELERATION) return
        val x = e.values[0]
        val y = e.values[1]
        val z = e.values[2]
        val mag = sqrt(x * x + y * y + z * z)
        val nowMs = now()

        // Throttle the live-debug callback to ~4 Hz so we don't flood the
        // Compose recomposer with 50 Hz sensor samples.
        if (onLiveSample != null && nowMs - lastSampleMs >= 250L) {
            lastSampleMs = nowMs
            onLiveSample.invoke(mag)
        }

        if (mag < bumpThresholdMps2) return
        if (nowMs - lastBumpMs < cooldownMs) return
        lastBumpMs = nowMs
        lastBumpEpochMs = nowMs
        lastBumpMagnitude = mag
        onBump(mag)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val TAG = "ImuAnalyzer"
    }
}
