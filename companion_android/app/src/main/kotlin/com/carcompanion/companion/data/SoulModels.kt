package com.carcompanion.companion.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * SOUL_DEFINITION.json v2 — persona "shape" of the companion.
 *
 * Schema supports the 5-tier personality model (see workspace/SOUL_ENGINE_DESIGN.md):
 *   Tier 1: core_traits (6 continuous traits)
 *   Tier 2: quirks (boolean tag list)
 *   Tier 3: moodlet_pool (temporary buff definitions)
 *   Tier 4: likes / dislikes
 *   Tier 5: persisted separately as DriverProfile
 *
 * Backward-compat: also parses v1 `traits` block if present. Engine prefers
 * core_traits when both exist.
 */
@Serializable
data class SoulDefinition(
    val version: String = "1.0",
    val persona: String = "Kuro",
    @SerialName("core_traits") val coreTraits: CoreTraits = CoreTraits(),
    val quirks: List<String> = emptyList(),
    @SerialName("needs_decay_rates") val needsDecayRates: NeedsDecayRates = NeedsDecayRates(),
    @SerialName("emotion_thresholds") val emotionThresholds: Map<String, EmotionRegion> = emptyMap(),
    @SerialName("moodlet_pool") val moodletPool: List<MoodletDef> = emptyList(),
    @SerialName("spontaneous_pool") val spontaneousPool: List<SpontaneousEntry> = emptyList(),
    val likes: List<String> = emptyList(),
    val dislikes: List<String> = emptyList(),

    // Legacy v1 traits — auto-mapped to coreTraits if coreTraits is at defaults
    val traits: TraitsV1? = null,
    @SerialName("stats_decay_rates") val statsDecayRatesV1: StatsDecayRatesV1? = null,
)

/**
 * Tier 1 — 6 continuous personality axes (Big Five-inspired).
 */
@Serializable
data class CoreTraits(
    val openness: Float = 0.5f,
    val optimism: Float = 0.5f,
    val sociability: Float = 0.5f,
    val sensitivity: Float = 0.5f,
    @SerialName("energy_baseline") val energyBaseline: Float = 0.5f,
    val patience: Float = 0.5f,
)

@Serializable
data class TraitsV1(
    val optimism: Float = 0.5f,
    val patience: Float = 0.5f,
    val bravery: Float = 0.5f,
    val sociability: Float = 0.5f,
)

@Serializable
data class StatsDecayRatesV1(
    val vitality: Float = 0.05f,
    @SerialName("stress_recovery") val stressRecovery: Float = 0.1f,
    @SerialName("bond_decay") val bondDecay: Float = 0.02f,
)

@Serializable
data class NeedsDecayRates(
    @SerialName("hunger_per_min") val hungerPerMin: Float = 0.2f,
    @SerialName("energy_per_min") val energyPerMin: Float = 1f,
    @SerialName("curiosity_per_min") val curiosityPerMin: Float = 2f,
    @SerialName("vitality_per_hour") val vitalityPerHour: Float = 0.16f,
    @SerialName("bond_per_day") val bondPerDay: Float = 1f,
    @SerialName("stress_recovery_per_min") val stressRecoveryPerMin: Float = 0.5f,
)

@Serializable
data class EmotionRegion(
    val valence: List<Float> = listOf(-1f, 1f),
    val arousal: List<Float> = listOf(-1f, 1f),
)

/**
 * Tier 3 — moodlet definition (template for instances created at runtime).
 */
@Serializable
data class MoodletDef(
    val id: String,
    val trigger: String,
    val valence: Float = 0f,
    val arousal: Float = 0f,
    @SerialName("duration_sec") val durationSec: Long = 0,
)

@Serializable
data class SpontaneousEntry(
    val hypothesis: String? = null,
    @SerialName("emotion_zone") val emotionZone: String? = null,
    val weight: Float = 1f,
    val gif: String? = null,
    val audio: String? = null,
)

/**
 * SOUL_LOGIC.json v2 — event-driven reactions.
 */
@Serializable
data class SoulLogic(
    val personality: String = "Kuro",
    val version: String = "1.0",
    @SerialName("mood_system") val moodSystem: MoodSystem = MoodSystem(),  // legacy v1
    @SerialName("event_logic") val eventLogic: List<EventLogic> = emptyList(),
)

@Serializable
data class MoodSystem(
    @SerialName("base_mood") val baseMood: Int = 50,
    @SerialName("decay_rate") val decayRate: Float = 0.1f,
    @SerialName("max_mood") val maxMood: Int = 100,
    @SerialName("min_mood") val minMood: Int = 0,
)

@Serializable
data class EventLogic(
    val event: String,
    val cooldown: Int = 0,
    val threshold: Float? = null,
    val reactions: List<Reaction> = emptyList(),
)

@Serializable
data class Reaction(
    val weight: Float = 1f,
    val hypothesis: String? = null,
    @SerialName("emotion_zone") val emotionZone: String? = null,
    @SerialName("valence_delta") val valenceDelta: Float? = null,
    @SerialName("arousal_delta") val arousalDelta: Float? = null,
    // legacy v1 — Reaction.condition + mood_delta (scalar 0-100 → -1..1)
    val condition: String = "all",
    @SerialName("mood_delta") val moodDeltaLegacy: Int? = null,
    val gif: String? = null,
    val audio: String? = null,
    @SerialName("led_matrix") val ledMatrix: String? = null,
) {
    /** Resolve valence delta with fallback to legacy `mood_delta` (mapped /100 → -1..+1). */
    fun effectiveValence(): Float = valenceDelta ?: (moodDeltaLegacy?.toFloat()?.div(100f) ?: 0f)
    fun effectiveArousal(): Float = arousalDelta ?: 0f
}

/**
 * EVENTS.json — high-level category catalog.
 */
@Serializable
data class EventsCatalog(
    val project: String = "",
    val version: String = "1.0",
    val events: List<EventCategory> = emptyList(),
)

@Serializable
data class EventCategory(
    val id: Int,
    val category: String,
    val description: String = "",
    @SerialName("default_gif") val defaultGif: String? = null,
)

/**
 * MAPS_LOGIC.json — navigation triggers (kept for future Maps integration).
 */
@Serializable
data class MapsLogic(
    @SerialName("navigation_events") val navigationEvents: List<NavEvent> = emptyList(),
)

@Serializable
data class NavEvent(
    val instruction: String? = null,
    @SerialName("trigger_distance_m") val triggerDistanceM: Int? = null,
    @SerialName("esp32_action") val esp32Action: String? = null,
    @SerialName("led_action") val ledAction: String? = null,
    @SerialName("traffic_status") val trafficStatus: String? = null,
)

/** Lenient JSON config — assets may evolve faster than the data classes. */
val SoulJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

/**
 * Effective core traits — prefers v2 [SoulDefinition.coreTraits], falls back to v1 traits
 * when coreTraits looks default and a v1 traits block exists.
 */
fun SoulDefinition.effectiveCoreTraits(): CoreTraits {
    val ct = coreTraits
    val looksDefault = ct.openness == 0.5f && ct.optimism == 0.5f &&
        ct.sociability == 0.5f && ct.sensitivity == 0.5f &&
        ct.energyBaseline == 0.5f && ct.patience == 0.5f
    val v1 = traits
    return if (looksDefault && v1 != null) {
        CoreTraits(
            openness = 0.5f,                           // v1 had no openness
            optimism = v1.optimism,
            sociability = v1.sociability,
            sensitivity = 1f - v1.bravery,             // brave = low sensitivity
            energyBaseline = 0.5f,
            patience = v1.patience,
        )
    } else ct
}
