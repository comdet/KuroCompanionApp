package com.carcompanion.companion.network

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * In-app port of `tools/find_robot.py`.
 *
 * Scans the local /24 for TCP :8080, sends a CCP PING with the
 * 4-byte length prefix [wifi_task.cpp] expects, and reports anything
 * that responds with the firmware's "CP {...json...}" status line.
 */
class RobotScanner(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    data class Found(val ip: String, val status: String)

    /**
     * Run a scan over the device's local /24. Returns matches in scan-completion
     * order (not address order).
     */
    suspend fun scan(
        subnetBase: String? = null,
        port: Int = CompanionCcpClient.DEFAULT_PORT,
        concurrency: Int = 32,
        timeoutMs: Int = 400,
    ): List<Found> = coroutineScope {
        val base = subnetBase ?: detectSubnetBase()
        if (base.isNullOrBlank()) {
            Log.w(TAG, "Could not auto-detect subnet")
            return@coroutineScope emptyList()
        }
        Log.i(TAG, "Scanning ${base}.0/24:${port}")

        val sem = Semaphore(concurrency)
        val targets = (1..254).map { "${base}.$it" }
        targets.map { ip ->
            async(dispatcher) {
                sem.withPermit { probe(ip, port, timeoutMs) }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun probe(ip: String, port: Int, timeoutMs: Int): Found? =
        withContext(dispatcher) {
            try {
                Socket().use { sock ->
                    sock.connect(InetSocketAddress(ip, port), timeoutMs)
                    sock.soTimeout = timeoutMs

                    val packet = CcpPacket.build(listOf(CcpItem.ping()))
                    val len = packet.size
                    val lenPrefix = byteArrayOf(
                        (len and 0xFF).toByte(),
                        ((len ushr 8) and 0xFF).toByte(),
                        ((len ushr 16) and 0xFF).toByte(),
                        ((len ushr 24) and 0xFF).toByte(),
                    )
                    sock.getOutputStream().apply {
                        write(lenPrefix)
                        write(packet)
                        flush()
                    }

                    val resp = sock.getInputStream().bufferedReader().readText().trim()
                    if (resp.startsWith("CP ")) Found(ip, resp) else null
                }
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Open a UDP socket to a public IP — never sends, just resolves our source
     * address — then derive the /24 base (first three octets).
     */
    private fun detectSubnetBase(): String? = try {
        DatagramSocket().use { sock ->
            sock.connect(InetAddress.getByName("8.8.8.8"), 80)
            val ip = sock.localAddress?.hostAddress
            ip?.substringBeforeLast('.')
        }
    } catch (e: Exception) {
        Log.w(TAG, "detectSubnetBase failed: ${e.message}")
        null
    }

    companion object {
        private const val TAG = "RobotScanner"
    }
}
