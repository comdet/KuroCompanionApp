package com.carcompanion.companion.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Tier 5 — per-driver memory.
 *
 * Single "default" profile for Round 5a; multi-driver fingerprinting comes
 * later (Round 5b). Persisted via [DriverProfileRepository] as a JSON blob
 * inside DataStore so we don't fight Preferences serializers.
 */
@Serializable
data class DriverProfile(
    val id: String = "default",
    val nickname: String? = null,
    @SerialName("first_seen_ms") val firstSeenMs: Long = System.currentTimeMillis(),
    @SerialName("last_seen_ms") val lastSeenMs: Long = System.currentTimeMillis(),
    @SerialName("last_engine_stop_ms") val lastEngineStopMs: Long = 0L,
    @SerialName("total_drivetime_sec") val totalDrivetimeSec: Long = 0,
    @SerialName("total_trips") val totalTrips: Int = 0,
    val bond: Float = 0f,           // 0-100
    val trust: Float = 50f,         // 0-100
    val familiarity: Float = 0f,    // 0-100
    @SerialName("driving_style") val drivingStyle: DrivingStyle = DrivingStyle(),
    @SerialName("last_impressive_event_at") val lastImpressiveEventAt: Long = 0,
)

@Serializable
data class DrivingStyle(
    @SerialName("avg_speed") val avgSpeed: Float = 0f,
    @SerialName("harsh_brake_ratio") val harshBrakeRatio: Float = 0f,
    @SerialName("night_drive_ratio") val nightDriveRatio: Float = 0f,
    @SerialName("avg_trip_duration_sec") val avgTripDurationSec: Long = 0,
)
