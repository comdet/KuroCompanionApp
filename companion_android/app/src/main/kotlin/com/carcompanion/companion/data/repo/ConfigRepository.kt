package com.carcompanion.companion.data.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Top-level connection + behaviour config, persisted via androidx.datastore.preferences.
 *
 * One file: `companion_config.preferences_pb`.
 */
private val Context.dataStore by preferencesDataStore(name = "companion_config")

data class AppConfig(
    val robotHost: String = DEFAULT_ROBOT_HOST,
    val robotPort: Int = DEFAULT_ROBOT_PORT,
    val obdPort: Int = DEFAULT_OBD_PORT,
    val autoStart: Boolean = true,
    val personaName: String = DEFAULT_PERSONA,
) {
    companion object {
        const val DEFAULT_ROBOT_HOST = "192.168.4.1"
        const val DEFAULT_ROBOT_PORT = 8080
        const val DEFAULT_OBD_PORT = 35000
        const val DEFAULT_PERSONA = "kuro"
    }
}

class ConfigRepository(private val context: Context) {

    private object Keys {
        val ROBOT_HOST = stringPreferencesKey("robot_host")
        val ROBOT_PORT = intPreferencesKey("robot_port")
        val OBD_PORT = intPreferencesKey("obd_port")
        val AUTO_START = booleanPreferencesKey("auto_start")
        val PERSONA = stringPreferencesKey("persona_name")
    }

    val configFlow: Flow<AppConfig> = context.dataStore.data.map { prefs ->
        AppConfig(
            robotHost = prefs[Keys.ROBOT_HOST] ?: AppConfig.DEFAULT_ROBOT_HOST,
            robotPort = prefs[Keys.ROBOT_PORT] ?: AppConfig.DEFAULT_ROBOT_PORT,
            obdPort = prefs[Keys.OBD_PORT] ?: AppConfig.DEFAULT_OBD_PORT,
            autoStart = prefs[Keys.AUTO_START] ?: true,
            personaName = prefs[Keys.PERSONA] ?: AppConfig.DEFAULT_PERSONA,
        )
    }

    suspend fun current(): AppConfig = configFlow.first()

    suspend fun update(transform: (AppConfig) -> AppConfig) {
        val updated = transform(current())
        context.dataStore.edit { prefs ->
            prefs[Keys.ROBOT_HOST] = updated.robotHost
            prefs[Keys.ROBOT_PORT] = updated.robotPort
            prefs[Keys.OBD_PORT] = updated.obdPort
            prefs[Keys.AUTO_START] = updated.autoStart
            prefs[Keys.PERSONA] = updated.personaName
        }
    }
}
