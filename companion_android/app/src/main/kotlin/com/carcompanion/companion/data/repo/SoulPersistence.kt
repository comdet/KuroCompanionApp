package com.carcompanion.companion.data.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.carcompanion.companion.domain.SoulSnapshot
import kotlinx.coroutines.flow.first

/**
 * Stores the last known [SoulSnapshot] so personality persists across boots.
 * Separate datastore file from connection config so personality wipe doesn't
 * lose IP settings (and vice versa).
 *
 * Schema v2: all stats are Float ([-1,+1] for valence/arousal, [0,100] for needs).
 * `mood` is derived at runtime so it is not persisted.
 */
private val Context.soulStore by preferencesDataStore(name = "companion_soul")

class SoulPersistence(private val context: Context) {

    private object Keys {
        val VALENCE = floatPreferencesKey("valence")
        val AROUSAL = floatPreferencesKey("arousal")
        val HUNGER = floatPreferencesKey("hunger")
        val ENERGY = floatPreferencesKey("energy")
        val CURIOSITY = floatPreferencesKey("curiosity")
        val BOND = floatPreferencesKey("bond")
        val VITALITY = floatPreferencesKey("vitality")
        val STRESS = floatPreferencesKey("stress")
    }

    suspend fun load(): SoulSnapshot? {
        val prefs = context.soulStore.data.first()
        val valence = prefs[Keys.VALENCE] ?: return null
        return SoulSnapshot(
            valence = valence,
            arousal = prefs[Keys.AROUSAL] ?: 0f,
            hunger = prefs[Keys.HUNGER] ?: 0f,
            energy = prefs[Keys.ENERGY] ?: 80f,
            curiosity = prefs[Keys.CURIOSITY] ?: 50f,
            bond = prefs[Keys.BOND] ?: 50f,
            vitality = prefs[Keys.VITALITY] ?: 100f,
            stress = prefs[Keys.STRESS] ?: 0f,
            mood = 50,  // derived, will be recomputed
        )
    }

    suspend fun save(snapshot: SoulSnapshot) {
        context.soulStore.edit { prefs ->
            prefs[Keys.VALENCE] = snapshot.valence
            prefs[Keys.AROUSAL] = snapshot.arousal
            prefs[Keys.HUNGER] = snapshot.hunger
            prefs[Keys.ENERGY] = snapshot.energy
            prefs[Keys.CURIOSITY] = snapshot.curiosity
            prefs[Keys.BOND] = snapshot.bond
            prefs[Keys.VITALITY] = snapshot.vitality
            prefs[Keys.STRESS] = snapshot.stress
        }
    }
}
