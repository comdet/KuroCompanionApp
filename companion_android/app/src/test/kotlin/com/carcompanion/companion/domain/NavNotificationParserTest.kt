package com.carcompanion.companion.domain

import com.carcompanion.companion.service.NavNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavNotificationParserTest {

    private class FakeClock(var nowMs: Long = 1_000_000L) {
        fun get(): Long = nowMs
        fun advance(ms: Long) { nowMs += ms }
    }

    private fun maps(
        title: String = "1.2 กม.",
        text: String = "มุ่งสู่ ถนนตัวอย่าง",
        sub: String = "15 นาที · 8.8 กม. · จะมาถึง 13:03 น.",
        removed: Boolean = false,
    ): NavNotification = NavNotification(
        pkg = "com.google.android.apps.maps",
        title = title,
        text = text,
        bigText = "",
        subText = sub,
        postedAt = 0L,
        removed = removed,
    )

    @Test
    fun navStart_firesOnceAtSessionBegin() {
        val parser = NavNotificationParser()
        val out1 = parser.consume(maps(sub = "15 นาที · 8.8 กม."))
        assertTrue(out1.any { it.name == "NAV_START" })

        val out2 = parser.consume(maps(sub = "14 นาที · 8 กม."))
        assertTrue("NAV_START should fire once",
            out2.none { it.name == "NAV_START" })
    }

    @Test
    fun nearDestination_firesWhenEtaCrossesBelow2Min() {
        val parser = NavNotificationParser()
        // Start at 15 min — no near-destination yet.
        val r1 = parser.consume(maps(sub = "15 นาที · 8 กม."))
        assertTrue(r1.none { it.name == "NAV_NEAR_DESTINATION" })

        // 5 min — still not near.
        val r2 = parser.consume(maps(sub = "5 นาที · 1.5 กม."))
        assertTrue(r2.none { it.name == "NAV_NEAR_DESTINATION" })

        // 2 min — exactly at threshold, should fire.
        val r3 = parser.consume(maps(sub = "2 นาที · 500 ม."))
        assertTrue("near-destination fires at ETA ≤ 2",
            r3.any { it.name == "NAV_NEAR_DESTINATION" })
    }

    @Test
    fun nearDestination_firesOncePerSession() {
        val parser = NavNotificationParser()
        parser.consume(maps(sub = "15 นาที · 8 กม."))

        // First crossing → fire.
        val r1 = parser.consume(maps(sub = "1 นาที · 200 ม."))
        assertTrue(r1.any { it.name == "NAV_NEAR_DESTINATION" })

        // ETA bounces around — should not re-fire (avoid spam on
        // Google Maps re-estimates).
        val r2 = parser.consume(maps(sub = "2 นาที · 300 ม."))
        assertTrue(r2.none { it.name == "NAV_NEAR_DESTINATION" })

        val r3 = parser.consume(maps(sub = "1 นาที · 100 ม."))
        assertTrue(r3.none { it.name == "NAV_NEAR_DESTINATION" })
    }

    @Test
    fun nearDestination_resetsAfterSessionEnd() {
        val parser = NavNotificationParser()
        parser.consume(maps(sub = "15 นาที · 8 กม."))
        parser.consume(maps(sub = "1 นาที · 200 ม."))   // fire

        // Session ends.
        val arrived = parser.consume(maps(removed = true))
        assertTrue(arrived.any { it.name == "NAV_ARRIVED" })

        // New session — near-destination should fire again.
        parser.consume(maps(sub = "10 นาที · 5 กม."))
        val r = parser.consume(maps(sub = "1 นาที · 200 ม."))
        assertTrue("new session should allow another NAV_NEAR_DESTINATION",
            r.any { it.name == "NAV_NEAR_DESTINATION" })
    }

    @Test
    fun arrived_firesOnRemovalAndClearsSession() {
        val parser = NavNotificationParser()
        parser.consume(maps())
        val r = parser.consume(maps(removed = true))
        assertTrue(r.any { it.name == "NAV_ARRIVED" })
    }

    @Test
    fun turnSoon_firesUnderDistanceThreshold() {
        val clock = FakeClock()
        val parser = NavNotificationParser(now = clock::get)
        parser.consume(maps(title = "1.2 กม."))
        val r = parser.consume(maps(title = "150 ม."))
        assertTrue(r.any { it.name == "NAV_TURN_SOON" })
    }
}
