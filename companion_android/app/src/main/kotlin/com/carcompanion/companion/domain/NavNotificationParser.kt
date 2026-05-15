package com.carcompanion.companion.domain

import com.carcompanion.companion.service.NavNotification

/**
 * Layer 2 helper — turns raw notification text from Google Maps (Thai locale)
 * into [SemanticEvent]s the soul engine can react to.
 *
 * ── Notification shape (verified 2026-05-14 on user's device) ─────────────
 *   title  = next maneuver distance, e.g. "0 ม." | "110 ม." | "1.2 กม."
 *   text   = next street, e.g. "มุ่งสู่ ถนนโยธาธิการ ขก. 2112"
 *   sub    = trip summary, e.g. "15 นาที · 8.8 กม. · จะมาถึง 13:03 น."
 *
 * Different Maps versions / locales emit different strings; the parser
 * matches what we logged on this device. English-locale support and "เลี้ยว
 * ซ้าย" / accident keywords can be added once we see them in the wild.
 *
 * ── Emitted events ───────────────────────────────────────────────────────
 *   NAV_START             — first nav notification of a session
 *   NAV_TURN_SOON         — distance to next maneuver crosses below 200 m
 *                           (with 30 s cooldown — Maps re-posts every ~8 s)
 *   NAV_ETA_UPDATE        — ETA in minutes changes by ≥ 2 min (avoids spam)
 *   NAV_NEAR_DESTINATION  — ETA crosses below [nearDestinationEtaMin]
 *                           minutes for the first time in this session.
 *                           Drives [TripPhaseAnalyzer] DRIVING → ARRIVING.
 *   NAV_ARRIVED           — notification removed (nav session ended)
 *
 * The parser is stateful; one instance per service lifecycle.
 */
class NavNotificationParser(
    private val now: () -> Long = { System.currentTimeMillis() },
    private val turnSoonThresholdM: Int = 200,
    private val turnSoonCooldownMs: Long = 30_000L,
    private val etaMinDeltaMin: Int = 2,
    private val nearDestinationEtaMin: Int = 2,
) {
    private var navActive = false
    private var lastTurnSoonMs = 0L
    private var lastEtaMin: Int? = null
    private var nearDestEmittedThisSession = false

    /** Process one captured notification; returns synthetic events to dispatch. */
    fun consume(n: NavNotification): List<SemanticEvent> {
        if (n.pkg !in NAV_PACKAGES) return emptyList()

        // Removal = session ended → arrive (or cancel).
        if (n.removed) {
            if (!navActive) return emptyList()
            navActive = false
            lastEtaMin = null
            nearDestEmittedThisSession = false
            return listOf(SemanticEvent("NAV_ARRIVED"))
        }

        val out = mutableListOf<SemanticEvent>()

        if (!navActive) {
            navActive = true
            out += SemanticEvent("NAV_START", detail = n.text.takeIf { it.isNotBlank() })
        }

        // Distance to next maneuver — from the title field.
        parseDistanceMeters(n.title)?.let { distM ->
            if (distM in 0..turnSoonThresholdM) {
                val nowMs = now()
                if (nowMs - lastTurnSoonMs >= turnSoonCooldownMs) {
                    lastTurnSoonMs = nowMs
                    out += SemanticEvent(
                        name = "NAV_TURN_SOON",
                        value = distM.toFloat(),
                        detail = n.text.takeIf { it.isNotBlank() },
                    )
                }
            }
        }

        // ETA delta — from the sub field.
        parseEtaMinutes(n.subText)?.let { etaMin ->
            val prev = lastEtaMin
            val significant = prev == null ||
                kotlin.math.abs(etaMin - prev) >= etaMinDeltaMin
            if (significant) {
                lastEtaMin = etaMin
                out += SemanticEvent("NAV_ETA_UPDATE", value = etaMin.toFloat())
            }
            // Rising edge into "near destination" — fire once per nav
            // session so TripPhaseAnalyzer can flip DRIVING → ARRIVING.
            if (!nearDestEmittedThisSession &&
                etaMin <= nearDestinationEtaMin
            ) {
                nearDestEmittedThisSession = true
                out += SemanticEvent(
                    "NAV_NEAR_DESTINATION",
                    value = etaMin.toFloat(),
                )
            }
        }

        return out
    }

    /**
     * "0 ม." → 0, "110 ม." → 110, "1.2 กม." → 1200, "1 km" → 1000.
     * Returns null if the string doesn't look like a distance.
     */
    internal fun parseDistanceMeters(title: String): Int? {
        val m = DIST_REGEX.find(title.trim()) ?: return null
        val v = m.groupValues[1].toDoubleOrNull() ?: return null
        val unit = m.groupValues[2]
        val meters = when (unit) {
            "กม.", "กม", "km" -> v * 1000.0
            else              -> v   // ม. / m / unitless
        }
        return meters.toInt()
    }

    /**
     * "15 นาที · 8.8 กม. · จะมาถึง 13:03 น." → 15
     * "12 min · 5 km · 14:30" → 12
     */
    internal fun parseEtaMinutes(subText: String): Int? {
        return ETA_REGEX_TH.find(subText)?.groupValues?.get(1)?.toIntOrNull()
            ?: ETA_REGEX_EN.find(subText)?.groupValues?.get(1)?.toIntOrNull()
    }

    companion object {
        val NAV_PACKAGES: Set<String> = setOf(
            "com.google.android.apps.maps",
            "com.waze",
        )
        // Captures number + unit; unit-less treated as meters.
        private val DIST_REGEX = Regex("""^(\d+(?:[.,]\d+)?)\s*(ม\.?|กม\.?|m|km)?""")
        private val ETA_REGEX_TH = Regex("""(\d+)\s*นาที""")
        private val ETA_REGEX_EN = Regex("""(\d+)\s*min""")
    }
}
