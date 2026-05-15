package com.carcompanion.companion.data.repo

import android.content.Context
import android.util.Log
import com.carcompanion.companion.data.EventsCatalog
import com.carcompanion.companion.data.MapsLogic
import com.carcompanion.companion.data.SoulDefinition
import com.carcompanion.companion.data.SoulJson
import com.carcompanion.companion.data.SoulLogic
import java.io.IOException

/**
 * Loads the static persona JSONs bundled in `app/src/main/assets/`.
 * Loaded once at service startup; downstream engines hold the parsed objects.
 */
class SoulRepository(private val context: Context) {
    private val tag = "SoulRepository"

    /**
     * Load a persona from `assets/personas/{name}.json`. Falls back to the
     * legacy single-file SOUL_DEFINITION.json if the persona file isn't
     * found, and finally to a hard-coded default.
     */
    fun loadDefinition(personaName: String = "kuro"): SoulDefinition {
        val safe = personaName.lowercase().filter { it.isLetterOrDigit() || it == '_' }
        val asPersona = readAsset("personas/$safe.json")
        val raw = asPersona ?: readAsset("SOUL_DEFINITION.json")
            ?: return SoulDefinition()
        return runCatching { SoulJson.decodeFromString<SoulDefinition>(raw) }
            .getOrElse { e ->
                Log.e(tag, "Persona '$safe' parse failed; using defaults", e)
                SoulDefinition()
            }
    }

    fun loadLogic(): SoulLogic = readAsset("SOUL_LOGIC.json")?.let {
        runCatching { SoulJson.decodeFromString<SoulLogic>(it) }
            .getOrElse { e -> Log.e(tag, "SOUL_LOGIC parse failed", e); SoulLogic() }
    } ?: SoulLogic()

    fun loadEvents(): EventsCatalog = readAsset("EVENTS.json")?.let {
        runCatching { SoulJson.decodeFromString<EventsCatalog>(it) }
            .getOrElse { e -> Log.e(tag, "EVENTS parse failed", e); EventsCatalog() }
    } ?: EventsCatalog()

    fun loadMaps(): MapsLogic = readAsset("MAPS_LOGIC.json")?.let {
        runCatching { SoulJson.decodeFromString<MapsLogic>(it) }
            .getOrElse { e -> Log.e(tag, "MAPS_LOGIC parse failed", e); MapsLogic() }
    } ?: MapsLogic()

    private fun readAsset(name: String): String? = try {
        context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (e: IOException) {
        Log.e(tag, "Asset $name missing", e)
        null
    }
}
