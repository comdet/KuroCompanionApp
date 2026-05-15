package com.carcompanion.companion.network

import android.util.Log
import com.carcompanion.companion.data.ObdJson
import com.carcompanion.companion.data.ObdMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * Listens on a TCP port for the ESP32-C3 OBD firmware (HUD_PROTOCOL.md).
 *
 * The ESP32 is the **client** — it joins the Android hotspot and opens a TCP socket
 * to the gateway IP (Android phone). Here we accept that connection and stream
 * newline-delimited JSON in both directions.
 */
class ObdServer(
    private val port: Int = DEFAULT_PORT,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        private const val TAG = "ObdServer"
        const val DEFAULT_PORT = 35000
    }

    private val _connection = MutableStateFlow(ObdConnection.IDLE)
    val connection: StateFlow<ObdConnection> = _connection.asStateFlow()

    private val _hello = MutableStateFlow<ObdMessage.Hello?>(null)
    val hello: StateFlow<ObdMessage.Hello?> = _hello.asStateFlow()

    private val _status = MutableStateFlow<ObdMessage.Status?>(null)
    val status: StateFlow<ObdMessage.Status?> = _status.asStateFlow()

    private val _acks = MutableSharedFlow<ObdMessage.Ack>(extraBufferCapacity = 16)
    val acks: SharedFlow<ObdMessage.Ack> = _acks.asSharedFlow()

    @Volatile private var clientSocket: Socket? = null

    suspend fun run(): Unit = coroutineScope {
        withContext(dispatcher) {
            var server: ServerSocket? = null
            try {
                server = ServerSocket(port).also {
                    it.reuseAddress = true
                }
                Log.i(TAG, "Listening on :$port")
                _connection.value = ObdConnection.LISTENING
                while (isActive) {
                    val socket = try {
                        server.accept()
                    } catch (e: SocketException) {
                        if (!isActive) break
                        Log.w(TAG, "accept() failed: ${e.message}")
                        continue
                    }
                    Log.i(TAG, "ESP32 connected from ${socket.inetAddress?.hostAddress}")
                    clientSocket = socket
                    _connection.value = ObdConnection.CLIENT_CONNECTED
                    launch { handleClient(socket) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.e(TAG, "Server error", e)
            } finally {
                server?.runCatching { close() }
                clientSocket?.runCatching { close() }
                _connection.value = ObdConnection.IDLE
            }
        }
    }

    /**
     * Send a command line (e.g. `{"cmd":"lock"}`) back to the connected ESP32.
     * Returns false if no client is connected or the write fails.
     */
    suspend fun sendCommand(line: String): Boolean = withContext(dispatcher) {
        val sock = clientSocket ?: return@withContext false
        try {
            val out = sock.getOutputStream()
            out.write((line.trimEnd('\n') + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
            true
        } catch (e: IOException) {
            Log.w(TAG, "sendCommand failed: ${e.message}")
            false
        }
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { sock ->
            val reader: BufferedReader = sock.getInputStream().bufferedReader(Charsets.UTF_8)
            try {
                while (true) {
                    val line = withContext(dispatcher) { reader.readLine() } ?: break
                    if (line.isBlank()) continue
                    parseAndDispatch(line)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "Client read error: ${e.message}")
            } finally {
                if (clientSocket === sock) {
                    clientSocket = null
                    _connection.value = ObdConnection.LISTENING
                }
                Log.i(TAG, "ESP32 disconnected")
            }
        }
    }

    private suspend fun parseAndDispatch(line: String) {
        val msg = try {
            ObdJson.decodeFromString(ObdMessage.serializer(), line)
        } catch (e: Exception) {
            Log.w(TAG, "Bad JSON: ${line.take(80)} — ${e.message}")
            return
        }
        when (msg) {
            is ObdMessage.Hello -> _hello.value = msg
            is ObdMessage.Status -> _status.value = msg
            is ObdMessage.Ack -> _acks.emit(msg)
            is ObdMessage.Fast -> Unit  // reserved by spec; ignore for now
        }
    }
}

enum class ObdConnection { IDLE, LISTENING, CLIENT_CONNECTED }
