package com.carcompanion.companion.domain

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Layer 2 helper — pulls current weather from OpenMeteo (free, no API key)
 * and emits `WEATHER_*` SemanticEvents on bucket transitions.
 *
 * API: https://api.open-meteo.com/v1/forecast
 *   ?latitude=<>&longitude=<>
 *   &current=temperature_2m,weather_code,precipitation
 *
 * weather_code follows WMO 4677:
 *   0          → clear / SUNNY
 *   1-3        → mostly clear → cloudy
 *   45,48      → fog
 *   51-67      → drizzle / rain (any intensity) → RAINY
 *   71-77      → snow → SNOWY
 *   80-82      → rain showers → RAINY
 *   85-86      → snow showers → SNOWY
 *   95-99      → thunderstorm → THUNDERSTORM
 *
 * Temperature buckets (matches LikesContext.kt thresholds):
 *   ≥ 32°C → HOT
 *   ≤ 18°C → COLD
 *   else   → MILD
 *
 * Emit policy — only on transitions (e.g. WEATHER_RAINY fires once when the
 * sky turns wet, not every poll). Reaction picker handles cooldown.
 */
class WeatherAnalyzer(
    initialWeatherBucket: String? = null,
    initialTempBucket: String? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    // Seeded from [AnalyzerStateRepository] so we don't re-fire WEATHER_*
    // events that the soul already reacted to before a restart.
    private var lastWeatherBucket: String? = initialWeatherBucket
    private var lastTempBucket: String? = initialTempBucket
    private var lastFetchMs: Long = 0L
    @Volatile private var lastTempC: Float? = null
    @Volatile private var lastCode: Int? = null

    /** Snapshot for the Cfg debug card / status line. */
    val currentTempC: Float? get() = lastTempC
    val currentWmoCode: Int? get() = lastCode
    val currentWeatherBucket: String? get() = lastWeatherBucket
    val currentTempBucket: String? get() = lastTempBucket
    val lastFetchEpochMs: Long get() = lastFetchMs

    /**
     * Fetch + classify. Returns the events that fired since the previous
     * fetch (empty if buckets unchanged or network failed). Caller should
     * gate by [shouldFetch].
     */
    suspend fun fetch(latitude: Double, longitude: Double): List<SemanticEvent> =
        withContext(Dispatchers.IO) {
            val raw = httpGet(buildUrl(latitude, longitude)) ?: return@withContext emptyList()
            val parsed = parse(raw) ?: return@withContext emptyList()
            lastFetchMs = now()
            lastTempC = parsed.tempC
            lastCode = parsed.code

            val weatherBucket = weatherBucketFor(parsed.code, parsed.precipitation)
            val tempBucket = tempBucketFor(parsed.tempC)

            val out = mutableListOf<SemanticEvent>()
            if (weatherBucket != lastWeatherBucket) {
                lastWeatherBucket = weatherBucket
                out += SemanticEvent(name = weatherBucket, value = parsed.code.toFloat())
            }
            if (tempBucket != lastTempBucket) {
                lastTempBucket = tempBucket
                out += SemanticEvent(name = tempBucket, value = parsed.tempC)
            }
            out
        }

    /** Should we fetch now? Caller throttles polls to once per [intervalMs]. */
    fun shouldFetch(intervalMs: Long = DEFAULT_POLL_MS): Boolean =
        now() - lastFetchMs >= intervalMs

    private fun buildUrl(lat: Double, lon: Double): String =
        "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code,precipitation"

    private data class Parsed(val tempC: Float, val code: Int, val precipitation: Float)

    private fun parse(json: String): Parsed? = try {
        val cur = JSONObject(json).optJSONObject("current") ?: return null
        Parsed(
            tempC = cur.optDouble("temperature_2m", Double.NaN).toFloat(),
            code = cur.optInt("weather_code", -1),
            precipitation = cur.optDouble("precipitation", 0.0).toFloat(),
        ).takeIf { !it.tempC.isNaN() && it.code >= 0 }
    } catch (e: Exception) {
        Log.w(TAG, "weather parse fail: ${e.message}")
        null
    }

    private fun httpGet(url: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 4000
            requestMethod = "GET"
        }
        conn.use { c ->
            if (c.responseCode != 200) {
                Log.w(TAG, "weather HTTP ${c.responseCode}")
                return@use null
            }
            c.inputStream.bufferedReader().readText()
        }
    } catch (e: Exception) {
        Log.w(TAG, "weather GET fail: ${e.message}")
        null
    }

    private fun weatherBucketFor(code: Int, precipitation: Float): String = when (code) {
        in 95..99 -> "WEATHER_THUNDERSTORM"
        in 51..67, in 80..82 -> "WEATHER_RAINY"
        in 71..77, in 85..86 -> "WEATHER_SNOWY"
        0, 1 -> "WEATHER_SUNNY"
        2, 3 -> "WEATHER_CLOUDY"
        45, 48 -> "WEATHER_FOG"
        else -> if (precipitation > 0f) "WEATHER_RAINY" else "WEATHER_CLOUDY"
    }

    private fun tempBucketFor(tempC: Float): String = when {
        tempC >= 32f -> "WEATHER_HOT"
        tempC <= 18f -> "WEATHER_COLD"
        else         -> "WEATHER_MILD"
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T = try {
        block(this)
    } finally {
        disconnect()
    }

    companion object {
        private const val TAG = "WeatherAnalyzer"
        const val DEFAULT_POLL_MS = 10 * 60 * 1000L   // 10 min
    }
}
