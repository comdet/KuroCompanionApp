package com.carcompanion.companion.network

import java.io.ByteArrayOutputStream

/**
 * Companion Control Protocol — binary packet sent over TCP to ESP32-S3 (:8080).
 *
 * Spec (verified in esp32_companion/src/ccp.cpp):
 *
 *     Header: 'C' 'P' [version:u8] [nItems:u8]
 *     Item:   [type:u8] [dataLen:u32 LE] [data:dataLen bytes]
 *
 * The firmware replies with one of:
 *   - "OK"               — normal command(s) accepted
 *   - "ERR header"       — header too short
 *   - "CP {...json...}"  — fast-path PING/GET_STATUS response
 * and then closes the socket. One TCP connection per packet.
 */
object CcpPacket {
    const val VERSION: Byte = 0x01

    // Item type bytes (must match esp32_companion/src/globals.h §CCP Protocol)
    const val TYPE_GIF: Byte = 0x01
    const val TYPE_WAV: Byte = 0x02
    const val TYPE_VOLUME: Byte = 0x10
    const val TYPE_BRIGHTNESS: Byte = 0x11
    const val TYPE_DELAY: Byte = 0x12
    const val TYPE_GIF_LOOP: Byte = 0x13
    const val TYPE_PRIORITY: Byte = 0x14
    const val TYPE_WIFI_SSID: Byte = 0x20
    const val TYPE_WIFI_PASS: Byte = 0x21
    const val TYPE_DEFAULT_VOL: Byte = 0x22
    const val TYPE_DEFAULT_BRI: Byte = 0x23
    const val TYPE_IDLE2_CHANCE: Byte = 0x24
    const val TYPE_PING: Byte = 0xF0.toByte()
    const val TYPE_REBOOT: Byte = 0xF1.toByte()
    const val TYPE_GET_STATUS: Byte = 0xF2.toByte()

    fun build(items: List<CcpItem>): ByteArray {
        require(items.size in 1..255) { "nItems must fit in u8 (got ${items.size})" }
        val out = ByteArrayOutputStream()
        out.write('C'.code)
        out.write('P'.code)
        out.write(VERSION.toInt())
        out.write(items.size and 0xFF)
        for (item in items) {
            out.write(item.type.toInt() and 0xFF)
            // dataLen as little-endian u32
            val len = item.data.size
            out.write(len and 0xFF)
            out.write((len ushr 8) and 0xFF)
            out.write((len ushr 16) and 0xFF)
            out.write((len ushr 24) and 0xFF)
            out.write(item.data)
        }
        return out.toByteArray()
    }
}

/** A single typed payload inside a CCP packet. */
data class CcpItem(val type: Byte, val data: ByteArray) {
    override fun equals(other: Any?) =
        other is CcpItem && type == other.type && data.contentEquals(other.data)
    override fun hashCode() = 31 * type.toInt() + data.contentHashCode()

    companion object {
        fun volume(level: Int): CcpItem {
            require(level in 0..100) { "volume must be 0..100" }
            return CcpItem(CcpPacket.TYPE_VOLUME, byteArrayOf(level.toByte()))
        }

        fun brightness(level: Int): CcpItem {
            require(level in 0..255) { "brightness must be 0..255" }
            return CcpItem(CcpPacket.TYPE_BRIGHTNESS, byteArrayOf(level.toByte()))
        }

        fun delay(ms: Int): CcpItem {
            require(ms in 0..65535) { "delay must be 0..65535 (u16 LE)" }
            return CcpItem(
                CcpPacket.TYPE_DELAY,
                byteArrayOf((ms and 0xFF).toByte(), ((ms ushr 8) and 0xFF).toByte()),
            )
        }

        fun gifLoop(loops: Int): CcpItem {
            require(loops in 0..255) { "loop count must be 0..255 (u8)" }
            return CcpItem(CcpPacket.TYPE_GIF_LOOP, byteArrayOf(loops.toByte()))
        }

        fun priority(interrupt: Boolean): CcpItem =
            CcpItem(CcpPacket.TYPE_PRIORITY, byteArrayOf(if (interrupt) 1 else 0))

        fun ping(): CcpItem = CcpItem(CcpPacket.TYPE_PING, ByteArray(1))
        fun getStatus(): CcpItem = CcpItem(CcpPacket.TYPE_GET_STATUS, ByteArray(1))
        fun reboot(): CcpItem = CcpItem(CcpPacket.TYPE_REBOOT, ByteArray(1))

        fun gif(data: ByteArray): CcpItem = CcpItem(CcpPacket.TYPE_GIF, data)
        fun wav(data: ByteArray): CcpItem = CcpItem(CcpPacket.TYPE_WAV, data)

        // ── NVS-persisted config items (survive reboot) ─────────────────
        //
        // The firmware (esp32_companion/src/ccp.cpp §CCP_WIFI_* / DEFAULT_*)
        // writes these into Preferences keys: "ssid", "pass", "def_vol",
        // "def_bri", "idle2_pct". Volume / brightness DEFAULT_* are also
        // applied live so the user sees the new value immediately.

        /** WiFi SSID — raw UTF-8 bytes, max 63 chars (firmware buffer is 64). */
        fun wifiSsid(ssid: String): CcpItem {
            val bytes = ssid.toByteArray(Charsets.UTF_8)
            require(bytes.size in 1..63) { "SSID must be 1..63 UTF-8 bytes" }
            return CcpItem(CcpPacket.TYPE_WIFI_SSID, bytes)
        }

        /** WiFi password — raw UTF-8 bytes, max 63 chars. Empty = open network. */
        fun wifiPass(pass: String): CcpItem {
            val bytes = pass.toByteArray(Charsets.UTF_8)
            require(bytes.size in 0..63) { "WiFi password must be 0..63 UTF-8 bytes" }
            return CcpItem(CcpPacket.TYPE_WIFI_PASS, bytes)
        }

        /** Default power-on volume (persisted + applied live). */
        fun defaultVolume(level: Int): CcpItem {
            require(level in 0..100) { "default volume must be 0..100" }
            return CcpItem(CcpPacket.TYPE_DEFAULT_VOL, byteArrayOf(level.toByte()))
        }

        /** Default power-on brightness (persisted + applied live). */
        fun defaultBrightness(level: Int): CcpItem {
            require(level in 0..255) { "default brightness must be 0..255" }
            return CcpItem(CcpPacket.TYPE_DEFAULT_BRI, byteArrayOf(level.toByte()))
        }

        /** Probability % the firmware swaps in an `idles/` GIF between idle loops. */
        fun idle2Chance(percent: Int): CcpItem {
            require(percent in 0..100) { "idle2 chance must be 0..100" }
            return CcpItem(CcpPacket.TYPE_IDLE2_CHANCE, byteArrayOf(percent.toByte()))
        }
    }
}
