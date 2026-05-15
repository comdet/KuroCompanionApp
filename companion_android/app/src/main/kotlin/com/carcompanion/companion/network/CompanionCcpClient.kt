package com.carcompanion.companion.network

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Send CCP packets to the ESP32-S3 Companion firmware (:8080).
 *
 * Firmware closes the socket after every command (see esp32_companion/src/ccp.cpp:
 * `client.print("OK"); client.stop();`), so each call opens a fresh connection.
 */
class CompanionCcpClient(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        internal const val TAG = "CompanionCcp"
        const val DEFAULT_PORT = 8080
        private const val CONNECT_TIMEOUT_MS = 3000
        private const val READ_TIMEOUT_MS = 3000
    }

    /**
     * Send one or more CCP items in a single packet.
     * Returns the response line the firmware wrote before closing.
     *
     * Throws [IOException] when the socket cannot connect or the write fails;
     * caller decides retry / surface-to-user.
     */
    suspend fun send(
        host: String,
        port: Int = DEFAULT_PORT,
        items: List<CcpItem>,
    ): String = withContext(dispatcher) {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS

            val packet = CcpPacket.build(items)
            // wifi_task.cpp reads a 4-byte little-endian payload-size header
            // first, *then* the payload (which may be raw GIF/WAV or a CCP
            // packet starting with 'C''P'). Without this prefix the firmware
            // misreads our 'C''P' as the size and rejects the connection.
            val len = packet.size
            val lenPrefix = byteArrayOf(
                (len and 0xFF).toByte(),
                ((len ushr 8) and 0xFF).toByte(),
                ((len ushr 16) and 0xFF).toByte(),
                ((len ushr 24) and 0xFF).toByte(),
            )
            socket.getOutputStream().apply {
                write(lenPrefix)
                write(packet)
                flush()
            }

            // Read whatever the firmware sent before it closed the socket.
            val resp = try {
                socket.getInputStream().bufferedReader(Charsets.UTF_8).readText()
            } catch (e: IOException) {
                Log.w(TAG, "Response read partial: ${e.message}")
                ""
            }
            resp.trim()
        }
    }

    // ── Runtime (volatile until reboot) ──────────────────────────────────

    suspend fun setVolume(host: String, level: Int, port: Int = DEFAULT_PORT): String =
        send(host, port, listOf(CcpItem.volume(level)))

    suspend fun setBrightness(host: String, level: Int, port: Int = DEFAULT_PORT): String =
        send(host, port, listOf(CcpItem.brightness(level)))

    // ── NVS-persisted config (survives reboot, also applied live) ───────

    suspend fun setDefaultVolume(host: String, level: Int, port: Int = DEFAULT_PORT): String =
        send(host, port, listOf(CcpItem.defaultVolume(level)))

    suspend fun setDefaultBrightness(host: String, level: Int, port: Int = DEFAULT_PORT): String =
        send(host, port, listOf(CcpItem.defaultBrightness(level)))

    suspend fun setIdle2Chance(host: String, percent: Int, port: Int = DEFAULT_PORT): String =
        send(host, port, listOf(CcpItem.idle2Chance(percent)))

    /**
     * Write WiFi credentials to NVS. Pass `andReboot=true` to chain a reboot
     * item — the firmware applies STA reconfig on next boot.
     */
    suspend fun setWifiConfig(
        host: String,
        ssid: String,
        pass: String,
        andReboot: Boolean = false,
        port: Int = DEFAULT_PORT,
    ): String {
        val items = buildList {
            add(CcpItem.wifiSsid(ssid))
            add(CcpItem.wifiPass(pass))
            if (andReboot) add(CcpItem.reboot())
        }
        return send(host, port, items)
    }

    // ── Lifecycle / introspection ────────────────────────────────────────

    suspend fun ping(host: String, port: Int = DEFAULT_PORT): String =
        send(host, port, listOf(CcpItem.ping()))

    suspend fun getStatus(host: String, port: Int = DEFAULT_PORT): String =
        send(host, port, listOf(CcpItem.getStatus()))

    suspend fun reboot(host: String, port: Int = DEFAULT_PORT): String =
        send(host, port, listOf(CcpItem.reboot()))
}

/**
 * Parsed view of the firmware's PING / GET_STATUS JSON reply.
 *
 * Wire format (from firmware ping handler, see esp32_companion/src/ccp.cpp):
 *   CP {"psram":<int>,"rssi":<int>,"vol":<0-100>,"bri":<0-255>,
 *       "idle2":<0-100>,"state":"<idle|playing|...>",
 *       "batt_mv":<int>,"batt_pct":<0-100>,"usb":<bool>,
 *       "charging":<bool>,"sys_mv":<int>}
 *
 * Any field may be absent on older firmware — all defaults to safe sentinels.
 */
data class RobotStatus(
    val psramFree: Int = 0,
    val rssi: Int = 0,
    val volume: Int = 0,
    val brightness: Int = 0,
    val idle2Chance: Int = 0,
    val state: String = "?",
    val batteryMv: Int = 0,
    val batteryPct: Int = 0,
    val usbPresent: Boolean = false,
    val charging: Boolean = false,
    val systemMv: Int = 0,
) {
    companion object {
        /**
         * Parse a raw firmware response. Strips the leading "CP " prefix the
         * firmware emits for fast-path replies. Returns null if not parseable.
         */
        fun parse(raw: String): RobotStatus? {
            val trimmed = raw.trim().removePrefix("CP").trim()
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
            return try {
                val o = org.json.JSONObject(trimmed)
                RobotStatus(
                    psramFree   = o.optInt("psram", 0),
                    rssi        = o.optInt("rssi", 0),
                    volume      = o.optInt("vol", 0),
                    brightness  = o.optInt("bri", 0),
                    idle2Chance = o.optInt("idle2", 0),
                    state       = o.optString("state", "?"),
                    batteryMv   = o.optInt("batt_mv", 0),
                    batteryPct  = o.optInt("batt_pct", 0),
                    usbPresent  = o.optBoolean("usb", false),
                    charging    = o.optBoolean("charging", false),
                    systemMv    = o.optInt("sys_mv", 0),
                )
            } catch (e: org.json.JSONException) {
                Log.w(CompanionCcpClient.TAG, "RobotStatus parse failed: ${e.message}")
                null
            }
        }
    }
}
