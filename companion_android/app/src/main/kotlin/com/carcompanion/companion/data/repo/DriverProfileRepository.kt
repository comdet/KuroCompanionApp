package com.carcompanion.companion.data.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.carcompanion.companion.data.DriverProfile
import com.carcompanion.companion.data.SoulJson
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString

/**
 * Persists the active [DriverProfile] as a JSON blob inside DataStore.
 * Single-profile for now (key = "current"); list-of-profiles arrives with
 * Round 5b multi-driver detection.
 */
private val Context.driverStore by preferencesDataStore(name = "companion_drivers")

class DriverProfileRepository(private val context: Context) {

    private val key = stringPreferencesKey("current")

    suspend fun load(): DriverProfile {
        val raw = context.driverStore.data.first()[key] ?: return DriverProfile()
        return runCatching { SoulJson.decodeFromString<DriverProfile>(raw) }
            .getOrDefault(DriverProfile())
    }

    suspend fun save(profile: DriverProfile) {
        val encoded = SoulJson.encodeToString(profile)
        context.driverStore.edit { it[key] = encoded }
    }
}
