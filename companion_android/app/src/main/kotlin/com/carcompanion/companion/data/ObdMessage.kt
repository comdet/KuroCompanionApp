package com.carcompanion.companion.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Messages the ESP32-C3 OBD firmware sends over TCP, newline-delimited.
 * Schema mirrors `HUD_PROTOCOL.md` in nissan-almera-doorlock-reverse-engineering/.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed class ObdMessage {

    @Serializable
    @SerialName("hello")
    data class Hello(
        val fw: String,
        val car: String = "",
        val uptime: Long = 0,
    ) : ObdMessage()

    @Serializable
    @SerialName("status")
    data class Status(
        val ts: Long? = null,
        val state: String,
        val lowpower: Boolean = false,

        val rpm: Int? = null,
        val speed: Int? = null,
        val throttle: Float? = null,
        val coolant: Int? = null,
        val ambient: Int? = null,
        val battery: Float? = null,
        val mil: Boolean? = null,
        @SerialName("dtc_count") val dtcCount: Int? = null,

        val gear: String? = null,
        val handbrake: Boolean? = null,
        @SerialName("brake_pedal") val brakePedal: Boolean? = null,
        @SerialName("engine_running") val engineRunning: Boolean? = null,

        val locked: Boolean? = null,
        val doors: Doors? = null,
        val lights: Lights? = null,
        val wifi: WifiInfo? = null,
    ) : ObdMessage()

    @Serializable
    @SerialName("ack")
    data class Ack(
        val cmd: String,
        val ok: Boolean,
    ) : ObdMessage()

    @Serializable
    @SerialName("fast")
    data class Fast(
        val ts: Long? = null,
        val rpm: Int? = null,
        val speed: Int? = null,
        val throttle: Float? = null,
    ) : ObdMessage()
}

@Serializable
data class Doors(
    val driver: Boolean? = null,
    val passenger: Boolean? = null,
    @SerialName("rear_left") val rearLeft: Boolean? = null,
    @SerialName("rear_right") val rearRight: Boolean? = null,
    val trunk: Boolean? = null,
)

@Serializable
data class Lights(
    val parking: Boolean? = null,
    @SerialName("high_beam") val highBeam: Boolean? = null,
    @SerialName("turn_left") val turnLeft: Boolean? = null,
    @SerialName("turn_right") val turnRight: Boolean? = null,
    @SerialName("headlight_raw") val headlightRaw: Int? = null,
)

@Serializable
data class WifiInfo(
    val rssi: Int? = null,
    val ip: String? = null,
)

/** JSON config configured for ObdMessage's polymorphic shape. */
val ObdJson: Json = Json {
    classDiscriminator = "type"
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}
