package com.carcompanion.companion.data.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persists the latest bucket each transition-firing analyzer last emitted.
 *
 * Background: the analyzers themselves (TimeOfDayAnalyzer, WeatherAnalyzer)
 * keep "last bucket" state in-memory so they only emit on transitions.
 * That worked while the service ran 24/7, but the foreground service can
 * be stopped at any point (user pauses it, OOM, reboot before BootReceiver
 * fires). After a restart the analyzer instance is fresh → `lastBucket =
 * null` → it fires the current bucket as if it were brand new, producing a
 * duplicate reaction the user just heard before the restart.
 *
 * Storing the last bucket in DataStore and seeding analyzers from it on
 * service start fixes both directions of the bug:
 *   - **No duplicate** if the same bucket is current after restart.
 *   - **Catches missed transition** if the bucket boundary was crossed
 *     while the service was offline (e.g. app closed at 21:00 in EVENING,
 *     reopened at 23:30 in LATE_NIGHT → fires LATE_NIGHT once).
 */
private val Context.analyzerStateStore by preferencesDataStore(name = "analyzer_state")

class AnalyzerStateRepository(private val context: Context) {

    private object Keys {
        val TIME_BUCKET    = stringPreferencesKey("time_bucket")
        val WEATHER_BUCKET = stringPreferencesKey("weather_bucket")
        val TEMP_BUCKET    = stringPreferencesKey("temp_bucket")
    }

    /** Loaded once at service start; analyzer instances seeded from this. */
    suspend fun load(): AnalyzerStateSnapshot {
        val prefs = context.analyzerStateStore.data.first()
        return AnalyzerStateSnapshot(
            timeBucket    = prefs[Keys.TIME_BUCKET],
            weatherBucket = prefs[Keys.WEATHER_BUCKET],
            tempBucket    = prefs[Keys.TEMP_BUCKET],
        )
    }

    /** Called after each analyzer emits — keeps DataStore in sync. */
    suspend fun saveTimeBucket(name: String) =
        context.analyzerStateStore.edit { it[Keys.TIME_BUCKET] = name }

    suspend fun saveWeatherBucket(name: String) =
        context.analyzerStateStore.edit { it[Keys.WEATHER_BUCKET] = name }

    suspend fun saveTempBucket(name: String) =
        context.analyzerStateStore.edit { it[Keys.TEMP_BUCKET] = name }
}

data class AnalyzerStateSnapshot(
    val timeBucket: String?,
    val weatherBucket: String?,
    val tempBucket: String?,
)
