package com.carcompanion.companion.domain

import com.carcompanion.companion.data.MoodletDef

/**
 * Tier 3 — Moodlets.
 *
 * A moodlet is a *temporary* valence/arousal shift on top of [SoulState]. It
 * decays after its duration or is replaced (with amplification) if the same id
 * fires again before expiry.
 *
 * Engines never write to mood directly — they spawn moodlets, which contribute
 * to the **effective** valence/arousal exposed by [MoodletStack].
 */
data class Moodlet(
    val id: String,
    val source: String,
    val valenceShift: Float,
    val arousalShift: Float,
    val spawnedAtMs: Long,
    val expiresAtMs: Long,    // [Long.MAX_VALUE] means "open-ended, evict on external signal"
) {
    fun isExpired(nowMs: Long): Boolean = nowMs >= expiresAtMs
}

class MoodletStack(private val maxActive: Int = 5) {
    private val list = ArrayDeque<Moodlet>()

    /** Active (non-expired) moodlets, oldest first. */
    fun active(nowMs: Long = System.currentTimeMillis()): List<Moodlet> {
        evict(nowMs)
        return list.toList()
    }

    /**
     * Adds a moodlet. If `id` already exists, the timer resets and the
     * valence/arousal magnitudes get a ×1.2 amplification (capped at ×1.5).
     * Stack auto-trims to [maxActive] (oldest dropped).
     */
    fun spawn(def: MoodletDef, source: String, nowMs: Long = System.currentTimeMillis()): Moodlet {
        evict(nowMs)
        val durationMs = if (def.durationSec <= 0L) Long.MAX_VALUE else def.durationSec * 1000L
        val expiresAt = if (durationMs == Long.MAX_VALUE) Long.MAX_VALUE else nowMs + durationMs

        val existing = list.firstOrNull { it.id == def.id }
        val moodlet = if (existing != null) {
            list.remove(existing)
            // Refresh timer + amplify by 20% (bounded to ≤ 1.5× the base def magnitude).
            val ampV = stackAmplify(existing.valenceShift, def.valence)
            val ampA = stackAmplify(existing.arousalShift, def.arousal)
            Moodlet(
                id = def.id, source = source,
                valenceShift = ampV, arousalShift = ampA,
                spawnedAtMs = nowMs, expiresAtMs = expiresAt,
            )
        } else {
            Moodlet(
                id = def.id, source = source,
                valenceShift = def.valence, arousalShift = def.arousal,
                spawnedAtMs = nowMs, expiresAtMs = expiresAt,
            )
        }

        list.addLast(moodlet)
        while (list.size > maxActive) list.removeFirst()
        return moodlet
    }

    private fun stackAmplify(existing: Float, base: Float): Float {
        if (base == 0f) return existing
        val target = (existing + base * 0.2f)
        val capMag = kotlin.math.abs(base) * 1.5f
        return target.coerceIn(-capMag, capMag).coerceIn(-1f, 1f)
    }

    /** Explicit removal — used for open-ended moodlets when their condition clears. */
    fun remove(id: String) {
        list.removeAll { it.id == id }
    }

    fun valenceShift(nowMs: Long = System.currentTimeMillis()): Float {
        evict(nowMs)
        return list.sumOf { it.valenceShift.toDouble() }.toFloat()
    }

    fun arousalShift(nowMs: Long = System.currentTimeMillis()): Float {
        evict(nowMs)
        return list.sumOf { it.arousalShift.toDouble() }.toFloat()
    }

    private fun evict(nowMs: Long) {
        list.removeAll { it.isExpired(nowMs) }
    }
}
