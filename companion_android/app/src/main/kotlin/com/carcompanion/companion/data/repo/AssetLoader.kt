package com.carcompanion.companion.data.repo

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Reads GIF / WAV byte streams from the persona asset pack.
 *
 * Lookup order:
 *   1. External app-files dir — populated by [AssetDownloader] pulling
 *      the ZIP from GitHub Releases:
 *        <externalFilesDir>/assets/kuro/audio/<event>/<id>.wav
 *        <externalFilesDir>/assets/kuro/gif/<folder>/<name>.gif
 *   2. Internal files dir — legacy dev path written by setup_assets.ps1
 *      via `adb push → /data/local/tmp → run-as cp`:
 *        /data/data/<pkg>/files/audio/Kuro/<event>/<id>.wav
 *        /data/data/<pkg>/files/gif/Kuro/<folder>/<name>.gif
 *
 * As of the asset-pack split, the APK no longer bundles audio/gif at
 * all — a fresh install with no downloaded pack returns null for every
 * pickRandom call, which the broadcast layer treats as "silent".
 * Personas / SOUL_LOGIC JSON stay inside the APK because they're tiny.
 */
class AssetLoader(private val context: Context) {

    private val filesBase: File = context.filesDir
    private val audioRoot: File = File(filesBase, AUDIO_PERSONA_DIR)
    private val gifRoot: File = File(filesBase, GIF_PERSONA_DIR)
    private val externalAssetsRoot: File =
        context.getExternalFilesDir(EXT_DIR_NAME)
            ?: File(filesBase, EXT_DIR_NAME).apply { mkdirs() }
    private val externalAudioRoot: File =
        File(externalAssetsRoot, "$ACTIVE_PERSONA/audio")
    private val externalGifRoot: File =
        File(externalAssetsRoot, "$ACTIVE_PERSONA/gif")

    // ── New API: event-aware pool selection (matches content_pipeline structure)

    /**
     * Pick a random WAV from the given event's pool.
     *
     * Resolution order: external pack → internal legacy → null.
     */
    fun pickRandomWav(event: String): ByteArray? {
        // 1. Downloaded pack (production path)
        File(externalAudioRoot, event).listFiles { f -> f.isFile && f.name.endsWith(".wav", true) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return readSafe(it.random(), "WAV ext $event") }
        // 2. Internal storage (dev override via setup_assets.ps1)
        File(audioRoot, event).listFiles { f -> f.isFile && f.name.endsWith(".wav", true) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return readSafe(it.random(), "WAV int $event") }
        return null
    }

    /** Pick a random GIF from the given pack folder. External → internal → null. */
    fun pickRandomGif(folder: String): ByteArray? {
        File(externalGifRoot, folder).listFiles { f -> f.isFile && f.name.endsWith(".gif", true) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return readSafe(it.random(), "GIF ext $folder") }
        File(gifRoot, folder).listFiles { f -> f.isFile && f.name.endsWith(".gif", true) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return readSafe(it.random(), "GIF int $folder") }
        return null
    }

    /**
     * Random (wav, gif) pair for an event using the built-in map.
     * Returns (null, null) if event not in map (caller should fall back).
     */
    fun pickRandomPair(event: String): Pair<ByteArray?, ByteArray?> {
        val mapping = EVENT_TO_ASSETS[event] ?: return null to null
        val wav = mapping.audioDir?.let { pickRandomWav(it) }
        val gif = mapping.gifFolder?.let { pickRandomGif(it) }
        return wav to gif
    }

    /** Returns true if this event has any asset pool wired (audio or gif). */
    fun hasPool(event: String): Boolean {
        val m = EVENT_TO_ASSETS[event] ?: return false
        return m.audioDir != null || m.gifFolder != null
    }

    /** Count of WAVs available for an event. External first, then internal legacy. */
    fun countWavs(event: String): Int {
        val ext = File(externalAudioRoot, event).listFiles { f -> f.name.endsWith(".wav", true) }?.size ?: 0
        if (ext > 0) return ext
        return File(audioRoot, event).listFiles { f -> f.name.endsWith(".wav", true) }?.size ?: 0
    }

    /** Count of GIFs available for a folder. External first, then internal legacy. */
    fun countGifs(folder: String): Int {
        val ext = File(externalGifRoot, folder).listFiles { f -> f.name.endsWith(".gif", true) }?.size ?: 0
        if (ext > 0) return ext
        return File(gifRoot, folder).listFiles { f -> f.name.endsWith(".gif", true) }?.size ?: 0
    }

    /** Inventory snapshot for debug UI / health check. */
    fun inventory(): Map<String, AssetInventoryEntry> = buildMap {
        EVENT_TO_ASSETS.forEach { (event, mapping) ->
            put(event, AssetInventoryEntry(
                event = event,
                folder = mapping.gifFolder,
                wavCount = mapping.audioDir?.let { countWavs(it) } ?: 0,
                gifCount = mapping.gifFolder?.let { countGifs(it) } ?: 0,
            ))
        }
    }

    // ── Legacy API: direct file name lookup (manual-test dropdowns)

    fun loadGif(name: String): ByteArray? {
        findRecursive(externalGifRoot, name)?.let { return readSafe(it, "GIF ext") }
        return readInternalOrAsset(
            internalSearch = { findRecursive(gifRoot, name) },
            assetPath = "gif_crushed/$name",
            kind = "GIF",
        )
    }

    fun loadWav(name: String): ByteArray? {
        findRecursive(externalAudioRoot, name)?.let { return readSafe(it, "WAV ext") }
        return readInternalOrAsset(
            internalSearch = { findRecursive(audioRoot, name) },
            assetPath = "wav/$name",
            kind = "WAV",
        )
    }

    /** Flat list of every GIF name (across all pack folders). Used by Cfg manual-test dropdown. */
    fun listGifs(): List<String> {
        val ext = if (externalGifRoot.isDirectory) externalGifRoot.walkTopDown()
            .filter { it.isFile && it.extension.equals("gif", true) }
            .map { it.name }.distinct().sorted().toList() else emptyList()
        if (ext.isNotEmpty()) return ext
        val internal = if (gifRoot.isDirectory) gifRoot.walkTopDown()
            .filter { it.isFile && it.extension.equals("gif", true) }
            .map { it.name }.distinct().sorted().toList() else emptyList()
        return internal.takeIf { it.isNotEmpty() } ?: listBundled("gif_crushed", ".gif")
    }

    /** Flat list of every WAV name (across all event folders). Used by Cfg manual-test dropdown. */
    fun listWavs(): List<String> {
        val ext = if (externalAudioRoot.isDirectory) externalAudioRoot.walkTopDown()
            .filter { it.isFile && it.extension.equals("wav", true) }
            .map { it.name }.distinct().sorted().toList() else emptyList()
        if (ext.isNotEmpty()) return ext
        val internal = if (audioRoot.isDirectory) audioRoot.walkTopDown()
            .filter { it.isFile && it.extension.equals("wav", true) }
            .map { it.name }.distinct().sorted().toList() else emptyList()
        return internal.takeIf { it.isNotEmpty() } ?: listBundled("wav", ".wav")
    }

    // ── Internal helpers

    private fun findRecursive(root: File, name: String): File? =
        if (!root.isDirectory) null
        else root.walkTopDown().firstOrNull { it.isFile && it.name == name }

    private fun readSafe(file: File, label: String): ByteArray? = try {
        file.readBytes()
    } catch (e: IOException) {
        Log.w(TAG, "$label read ${file.name} failed: ${e.message}")
        null
    }

    private fun readInternalOrAsset(
        internalSearch: () -> File?,
        assetPath: String,
        kind: String,
    ): ByteArray? {
        internalSearch()?.let { f ->
            return readSafe(f, kind)
        }
        return readBundled(assetPath, kind)
    }

    private fun listBundled(dir: String, extension: String): List<String> = try {
        context.assets.list(dir)
            ?.filter { it.endsWith(extension, ignoreCase = true) }
            ?.sorted()
            ?: emptyList()
    } catch (e: IOException) {
        Log.w(TAG, "bundled list $dir failed: ${e.message}")
        emptyList()
    }

    private fun readBundled(path: String, kind: String): ByteArray? = try {
        context.assets.open(path).use { it.readBytes() }
    } catch (e: IOException) {
        Log.d(TAG, "$kind asset missing: $path")
        null
    }

    companion object {
        private const val TAG = "AssetLoader"

        private const val AUDIO_PERSONA_DIR = "audio/Kuro"
        private const val GIF_PERSONA_DIR = "gif/Kuro"
        /** Subdir under context.getExternalFilesDir() — matches AssetStore. */
        private const val EXT_DIR_NAME = "assets"
        /** Single active persona for now. Multi-persona = future Phase 7. */
        const val ACTIVE_PERSONA = "kuro"

        /**
         * Master map: soul event ID → asset folders.
         *
         * Event IDs match either:
         *   - SOUL_LOGIC event names (CAN_DOOR_OPEN, CAN_HARSH_BRAKE, …)
         *   - Synthetic spontaneous keys ("IDLE_" + emotion zone)
         *
         * Note `audioDir` ≠ `gifFolder`:
         *   - audioDir mirrors `content_pipeline/content/Kuro/<event>.json` naming
         *     (some used moodlet-trigger names like `harsh_brake_spam`)
         *   - gifFolder mirrors `gif_packs/Kuro/<FOLDER>/` (semantic emotion groups)
         *
         * null on either field = skip that asset entirely.
         */
        val EVENT_TO_ASSETS: Map<String, EventAssetMap> = mapOf(
            // ── Wake-up gated reactions ──────────────────────────────────
            // WAKE_ROBOT is the new audible welcome — only fires after
            // [WakeOrchestrator] confirms the robot is reachable AND the
            // car hasn't started rolling. It reuses the existing
            // CAN_UNLOCKED voice pool because those lines are the closest
            // to "I'm awake, hi" that we have.
            "WAKE_ROBOT"        to EventAssetMap("CAN_UNLOCKED", "WELCOME"),
            // CAN_FIRST_DRIVE is the new "off we go" reaction — fires on
            // the first sustained-speed transition after engine start.
            // Reuses CAN_ENGINE_START's pool (those lines are about
            // starting a trip, not about turning the key).
            "CAN_FIRST_DRIVE"   to EventAssetMap("CAN_ENGINE_START", "READY"),

            // ── Event-driven with full audio+gif pools ────────────────────
            "CAN_DOOR_OPEN"     to EventAssetMap("CAN_DOOR_OPEN", "WELCOME"),
            // CAN_ENGINE_START fires BEFORE the ESP32 has finished booting,
            // so playing audio here lands in a black hole. We keep the
            // event firing (state-delta still hits via EventStateDeltas)
            // but skip the broadcast. The audible "I'm here" is now
            // gated through WAKE_ROBOT above.
            "CAN_ENGINE_START"  to EventAssetMap(null, null),
            "CAN_IMU_BUMP"      to EventAssetMap("CAN_IMU_BUMP", "STARTLED"),
            "CAN_HARSH_BRAKE"   to EventAssetMap("harsh_brake_spam", "STARTLED"),
            "CAN_MIL_ON"        to EventAssetMap("CAN_MIL_ON", "WORRIED"),
            "CAN_OVERHEATING"   to EventAssetMap("CAN_OVERHEATING", "PANIC"),

            // ── Tier 1-5 fresh-gen audio: full audio+gif pools ───────────
            "CAN_STARTED_MOVING"        to EventAssetMap("CAN_STARTED_MOVING", "READY"),
            "CAN_STOPPED"               to EventAssetMap("CAN_STOPPED", "BORED"),
            "CAN_LOCKED"                to EventAssetMap("CAN_LOCKED", "HAPPY"),
            // CAN_UNLOCKED fires during pre-boot (driver just pressed the
            // remote unlock). Silent for the same reason as
            // CAN_ENGINE_START — see WAKE_ROBOT above for the audible
            // replacement. State-delta (bond+1, energy+5) still applies.
            "CAN_UNLOCKED"              to EventAssetMap(null, null),
            "CAN_HEADLIGHT_ON"          to EventAssetMap("CAN_HEADLIGHT_ON", "HAPPY"),
            "CAN_HEADLIGHT_OFF"         to EventAssetMap("CAN_HEADLIGHT_OFF", "HAPPY"),
            "DRIVING_AGGRESSIVE"        to EventAssetMap("DRIVING_AGGRESSIVE", "EXCITED"),
            "DRIVING_SMOOTH_30M"        to EventAssetMap("DRIVING_SMOOTH_30M", "HAPPY"),
            "CAN_DOOR_AJAR_DRIVING"     to EventAssetMap("CAN_DOOR_AJAR_DRIVING", "STARTLED"),
            "CAN_TRUNK_OPEN_DRIVING"    to EventAssetMap("CAN_TRUNK_OPEN_DRIVING", "STARTLED"),
            "CAN_HANDBRAKE_FORGOTTEN"   to EventAssetMap("CAN_HANDBRAKE_FORGOTTEN", "STARTLED"),
            "CAN_PARKING_UNSAFE"        to EventAssetMap("CAN_PARKING_UNSAFE", "STARTLED"),
            "CAN_HEADLIGHT_FORGOTTEN"   to EventAssetMap("CAN_HEADLIGHT_FORGOTTEN", "WORRIED"),
            "CAN_SPEEDING_IN_CITY"      to EventAssetMap("CAN_SPEEDING_IN_CITY", "WORRIED"),
            "CAN_BATTERY_LOW"           to EventAssetMap("CAN_BATTERY_LOW", "WORRIED"),
            "CAN_TURN_SIGNAL_STUCK"     to EventAssetMap("CAN_TURN_SIGNAL_STUCK", "ANGRY"),
            // TRIP_START_OF_DAY + RETURN_AFTER_ABSENCE both fire as side
            // effects of CAN_ENGINE_START, well before the ESP32 has
            // booted. State delta still hits (hunger reset, energy boost,
            // bond bump for RETURN_AFTER_ABSENCE). Audio for "long
            // absence" is deferred — see SOUL_EVENT_REDESIGN.md for the
            // pre-wake buffer concept we'd need to play these audibly.
            "TRIP_START_OF_DAY"         to EventAssetMap(null, null),
            "RETURN_AFTER_ABSENCE"      to EventAssetMap(null, null),
            "MILESTONE_BOND_50"         to EventAssetMap("MILESTONE_BOND_50", "HAPPY"),
            "MILESTONE_BOND_100"        to EventAssetMap("MILESTONE_BOND_100", "EXCITED"),
            "MILESTONE_TRUST_80"        to EventAssetMap("MILESTONE_TRUST_80", "HAPPY"),
            "MILESTONE_TRIPS_10"        to EventAssetMap("MILESTONE_TRIPS_10", "HAPPY"),
            "MILESTONE_TRIPS_100"       to EventAssetMap("MILESTONE_TRIPS_100", "EXCITED"),
            "MILESTONE_TRIPS_1000"      to EventAssetMap("MILESTONE_TRIPS_1000", "EXCITED"),
            "MILESTONE_DRIVETIME_10H"   to EventAssetMap("MILESTONE_DRIVETIME_10H", "HAPPY"),
            "MILESTONE_DRIVETIME_100H"  to EventAssetMap("MILESTONE_DRIVETIME_100H", "EXCITED"),

            // ── Pattern (P3 composites — Phase 4) — GIF-only, audio TBD ──
            // UNSAFE_OVERTAKE = scold tone, ANGRY zone.
            // EMERGENCY_BRAKE = frightened tone, PANIC zone. Both reuse
            // existing gif pools until purpose-written content lands.
            "UNSAFE_OVERTAKE"           to EventAssetMap(null, "ANGRY"),
            "EMERGENCY_BRAKE"           to EventAssetMap(null, "PANIC"),

            // ── Episode (P3) — GIF-only for now, audio content TBD ───────
            // START events get an attention-grabbing gif; TICK is silent
            // (state-delta only, see EventStateDeltas); END plays a relief
            // beat tied to the natural HAPPY/calm zones.
            "ROUGH_ROAD_START"          to EventAssetMap(null, "STARTLED"),
            "ROUGH_ROAD_TICK"           to EventAssetMap(null, null),
            "ROUGH_ROAD_END"            to EventAssetMap(null, "HAPPY"),
            "SUSTAINED_BRAKE_START"     to EventAssetMap(null, "WORRIED"),
            "SUSTAINED_BRAKE_TICK"      to EventAssetMap(null, null),
            "SUSTAINED_BRAKE_END"       to EventAssetMap(null, "HAPPY"),
            "STUCK_TRAFFIC_START"       to EventAssetMap(null, "BORED"),
            "STUCK_TRAFFIC_TICK"        to EventAssetMap(null, null),
            "STUCK_TRAFFIC_END"         to EventAssetMap(null, "HAPPY"),

            // ── GIF-only pool (no audio yet — legacy filename in SOUL_LOGIC) ─
            "CAN_HIGH_SPEED"            to EventAssetMap(null, "EXCITED"),
            "CAN_AGGRESSIVE_THROTTLE"   to EventAssetMap(null, "EXCITED"),
            "CAN_ENGINE_STOP"           to EventAssetMap(null, "SAD"),
            "CAN_WARM_UP_COMPLETE"      to EventAssetMap(null, "HAPPY"),
            "CAN_HIGH_BEAM_IN_CITY"     to EventAssetMap(null, "ANGRY"),
            "CAN_IDLE_TOO_LONG"         to EventAssetMap(null, "BORED"),
            "TRIP_LONG_60M"             to EventAssetMap(null, "ADVENTURE"),
            "TRIP_LONG_120M"            to EventAssetMap(null, "ADVENTURE"),
            "TRIP_SHORT"                to EventAssetMap(null, "SAD"),
            "TRIP_LATE_RETURN"          to EventAssetMap(null, "WORRIED"),
            "MOOD_SWING"                to EventAssetMap(null, "ANGRY"),
            "STRESS_MAX"                to EventAssetMap(null, "PANIC"),
            "BORED_OUT"                 to EventAssetMap(null, "BORED"),

            // ── Time-of-day (now with audio) ─────────────────────────────
            "TIME_EARLY_MORNING"        to EventAssetMap("TIME_EARLY_MORNING", "BORED"),
            "TIME_MORNING"              to EventAssetMap("TIME_MORNING", "HAPPY"),
            "TIME_AFTERNOON"            to EventAssetMap("TIME_AFTERNOON", "HAPPY"),
            "TIME_EVENING"              to EventAssetMap("TIME_EVENING", "HAPPY"),
            "TIME_LATE_NIGHT"           to EventAssetMap("TIME_LATE_NIGHT", "EXCITED"),

            // ── Navigation (Google Maps notification → parser) ───────────
            // NAV_START / NAV_ARRIVED now have audio pools.
            // NAV_TURN_SOON / NAV_ETA_UPDATE are silent — no reaction wired.
            "NAV_START"                 to EventAssetMap("NAV_START", "READY"),
            "NAV_ARRIVED"               to EventAssetMap("NAV_ARRIVED", "WELCOME"),
            // TRIP_ARRIVING fires once when ETA drops below 2 min — the
            // "we're almost there!" anticipation beat. Reuses EXCITED
            // gif pool; audio content TBD.
            "TRIP_ARRIVING"             to EventAssetMap(null, "EXCITED"),

            // ── Music (Phase 6) — GIF-only for now, audio TBD ────────────
            "MUSIC_STARTED"             to EventAssetMap(null, "HAPPY"),
            "MUSIC_STOPPED"             to EventAssetMap(null, "BORED"),
            "MUSIC_SONG_CHANGED"        to EventAssetMap(null, "EXCITED"),

            // TIME_TICK — silent in-drive heartbeat. State delta only.
            "TIME_TICK"                 to EventAssetMap(null, null),

            // ── Weather (OpenMeteo poll) — now with full audio pools ─────
            "WEATHER_HOT"               to EventAssetMap("ambient_hot",  "HOT"),
            "WEATHER_COLD"              to EventAssetMap("ambient_cold", "COLD"),
            "WEATHER_RAINY"             to EventAssetMap("WEATHER_RAINY", "SAD"),
            "WEATHER_SUNNY"             to EventAssetMap("WEATHER_SUNNY", "HAPPY"),
            "WEATHER_THUNDERSTORM"      to EventAssetMap("WEATHER_THUNDERSTORM", "PANIC"),
            "WEATHER_SNOWY"             to EventAssetMap("WEATHER_SNOWY", "EXCITED"),
            "WEATHER_FOG"               to EventAssetMap("WEATHER_FOG", "WORRIED"),
            "WEATHER_CLOUDY"            to EventAssetMap(null, "BORED"),
            // WEATHER_MILD intentionally absent — no reaction wanted

            // ── Spontaneous (zone-based — Emotion.name) ──────────────────
            "IDLE_HAPPY"        to EventAssetMap("IDLE_HAPPY",   "HAPPY"),
            "IDLE_EXCITED"      to EventAssetMap("IDLE_EXCITED", "EXCITED"),
            "IDLE_SAD"          to EventAssetMap("IDLE_SAD",     "SAD"),
            "IDLE_ANGRY"        to EventAssetMap("IDLE_ANGRY",   "ANGRY"),
            "IDLE_BORED"        to EventAssetMap("IDLE_BORED",   "BORED"),
            "IDLE_HUNGRY"       to EventAssetMap("IDLE_HUNGRY",  "HUNGRY"),
            // SLEEPY = let firmware idle handle entirely
            "IDLE_SLEEPY"       to EventAssetMap(null, null),
        )
    }
}

/**
 * Pair of asset-pool locators for one soul event.
 * Either side may be null when only one asset kind should ship for this event.
 */
data class EventAssetMap(
    val audioDir: String?,
    val gifFolder: String?,
)

data class AssetInventoryEntry(
    val event: String,
    val folder: String?,
    val wavCount: Int,
    val gifCount: Int,
)
