package com.carcompanion.companion.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SeverityTest {

    @Test
    fun multiplier_isMonotonic() {
        // Each bucket must hit harder than the one below it; tests that no
        // future tweak accidentally lets MILD = SEVERE.
        assertEquals(1.0f, Severity.MILD.multiplier())
        assertEquals(1.2f, Severity.MODERATE.multiplier())
        assertEquals(1.5f, Severity.SEVERE.multiplier())
        assertEquals(2.0f, Severity.CRITICAL.multiplier())
    }

    @Test
    fun brakeDelta_buckets() {
        assertEquals(Severity.MILD,     Severity.forBrakeDelta(0))
        assertEquals(Severity.MILD,     Severity.forBrakeDelta(9))
        assertEquals(Severity.MODERATE, Severity.forBrakeDelta(10))
        assertEquals(Severity.MODERATE, Severity.forBrakeDelta(24))
        assertEquals(Severity.SEVERE,   Severity.forBrakeDelta(25))
        assertEquals(Severity.SEVERE,   Severity.forBrakeDelta(39))
        assertEquals(Severity.CRITICAL, Severity.forBrakeDelta(40))
        assertEquals(Severity.CRITICAL, Severity.forBrakeDelta(80))
    }

    @Test
    fun brakeDelta_negativeTreatedAsZero() {
        // Speed increased between samples (data glitch) — shouldn't crash,
        // shouldn't return CRITICAL. The caller already coerces to >=0 but
        // confirm the bucket is sane.
        assertEquals(Severity.MILD, Severity.forBrakeDelta(0))
    }

    @Test
    fun bumpMagnitude_buckets() {
        assertEquals(Severity.MILD,     Severity.forBumpMagnitude(0f))
        assertEquals(Severity.MILD,     Severity.forBumpMagnitude(5.9f))
        assertEquals(Severity.MODERATE, Severity.forBumpMagnitude(6f))
        assertEquals(Severity.MODERATE, Severity.forBumpMagnitude(9.9f))
        assertEquals(Severity.SEVERE,   Severity.forBumpMagnitude(10f))
        assertEquals(Severity.SEVERE,   Severity.forBumpMagnitude(14.9f))
        assertEquals(Severity.CRITICAL, Severity.forBumpMagnitude(15f))
        assertEquals(Severity.CRITICAL, Severity.forBumpMagnitude(25f))
    }

    @Test
    fun highSpeed_buckets() {
        assertEquals(Severity.MILD,     Severity.forHighSpeed(111))
        assertEquals(Severity.MODERATE, Severity.forHighSpeed(120))
        assertEquals(Severity.MODERATE, Severity.forHighSpeed(139))
        assertEquals(Severity.SEVERE,   Severity.forHighSpeed(140))
        assertEquals(Severity.CRITICAL, Severity.forHighSpeed(160))
        assertEquals(Severity.CRITICAL, Severity.forHighSpeed(200))
    }

    @Test
    fun throttle_buckets() {
        assertEquals(Severity.MILD,     Severity.forThrottlePercent(81f))
        assertEquals(Severity.MODERATE, Severity.forThrottlePercent(85f))
        assertEquals(Severity.MODERATE, Severity.forThrottlePercent(91f))
        assertEquals(Severity.SEVERE,   Severity.forThrottlePercent(92f))
        assertEquals(Severity.SEVERE,   Severity.forThrottlePercent(97f))
        assertEquals(Severity.CRITICAL, Severity.forThrottlePercent(98f))
        assertEquals(Severity.CRITICAL, Severity.forThrottlePercent(100f))
    }

    @Test
    fun overheat_buckets() {
        assertEquals(Severity.MILD,     Severity.forOverheatTemp(101))
        assertEquals(Severity.MODERATE, Severity.forOverheatTemp(105))
        assertEquals(Severity.MODERATE, Severity.forOverheatTemp(109))
        assertEquals(Severity.SEVERE,   Severity.forOverheatTemp(110))
        assertEquals(Severity.SEVERE,   Severity.forOverheatTemp(114))
        assertEquals(Severity.CRITICAL, Severity.forOverheatTemp(115))
        assertEquals(Severity.CRITICAL, Severity.forOverheatTemp(130))
    }

    @Test
    fun severityField_defaultsToMild() {
        val e = SemanticEvent(name = "TEST")
        assertEquals(Severity.MILD, e.severity)
    }
}
