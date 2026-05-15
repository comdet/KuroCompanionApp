package com.carcompanion.companion.data.repo

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Locates and inspects the unpacked asset pack on external storage.
 *
 * Layout written by [AssetDownloader]:
 *
 *   <externalFilesDir>/assets/
 *       kuro/
 *           manifest.json
 *           audio/[event]/[id].wav
 *           gif/[emotion]/[name].gif
 *
 * "External files dir" here is `context.getExternalFilesDir("assets")` —
 * app-private storage that needs no runtime permission, survives normal
 * uninstall→reinstall cycles is NOT required by Android but generally
 * preserved on HUD-class devices.  When MIUI / restrictive ROMs block
 * external access we fall back to [Context.getFilesDir] internal.
 */
class AssetStore(private val context: Context) {

    /** Root of the assets dir, preferred external location. */
    val root: File
        get() = preferredRoot()

    fun personaDir(persona: String): File = File(root, persona)

    fun manifestFile(persona: String): File =
        File(personaDir(persona), MANIFEST_NAME)

    /** True iff the manifest file is present (no version check yet). */
    fun hasInstalled(persona: String): Boolean = manifestFile(persona).isFile

    /**
     * Read and parse the installed manifest, or null on any failure.
     * Failure modes: file missing, JSON parse error, unexpected schema.
     */
    fun readInstalledManifest(persona: String): AssetsManifest? {
        val f = manifestFile(persona)
        if (!f.isFile) return null
        return try {
            JSON.decodeFromString(AssetsManifest.serializer(), f.readText())
        } catch (e: Exception) {
            Log.w(TAG, "manifest parse fail for $persona: ${e.message}")
            null
        }
    }

    /**
     * Classify the install state of [persona] against the minimum the
     * APK was built for (`BuildConfig.MIN_ASSETS_VERSION`).
     */
    fun classify(persona: String, minVersion: String): State {
        val manifest = readInstalledManifest(persona) ?: return State.MISSING
        return if (compareAssetVersions(manifest.version, minVersion) >= 0)
            State.OK(manifest)
        else
            State.OUTDATED(manifest, minVersion)
    }

    /**
     * Wipe an installed asset pack — used after a failed verify so the
     * next launch retries from scratch.
     */
    fun wipe(persona: String) {
        val d = personaDir(persona)
        if (d.exists()) d.deleteRecursively()
    }

    private fun preferredRoot(): File {
        val ext = context.getExternalFilesDir(EXT_DIR_NAME)
        if (ext != null) return ext
        // Fallback: internal /data/data/<pkg>/files/assets
        return File(context.filesDir, EXT_DIR_NAME).apply { mkdirs() }
    }

    /** Install-state outcome surfaced to UI. */
    sealed class State {
        /** Nothing on disk (or manifest unreadable). */
        object MISSING : State()
        /** Manifest present but `< MIN_ASSETS_VERSION`. */
        data class OUTDATED(val installed: AssetsManifest, val required: String) : State()
        /** All good — manifest matches or exceeds the minimum. */
        data class OK(val installed: AssetsManifest) : State()
    }

    companion object {
        private const val TAG = "AssetStore"
        private const val EXT_DIR_NAME = "assets"
        const val MANIFEST_NAME = "manifest.json"

        val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
