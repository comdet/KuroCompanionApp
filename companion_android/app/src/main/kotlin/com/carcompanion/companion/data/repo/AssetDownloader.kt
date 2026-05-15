package com.carcompanion.companion.data.repo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Pulls a persona asset ZIP from GitHub Releases, extracts it into the
 * device-side staging area, verifies every file against the bundled
 * manifest, then atomic-swaps it into [AssetStore.personaDir].
 *
 * Lifecycle:
 *
 *   1. `<personaDir>.tmp/`  ← stream extract here
 *   2. parse manifest.json inside it
 *   3. sha256 every file → compare to manifest
 *   4. `<personaDir>` ← rename `<personaDir>.tmp` (delete old first)
 *
 * Anything that fails before step 4 leaves the existing install
 * untouched, so a broken download can't break a working app.
 *
 * The download/extract/verify steps each report progress through
 * [Progress] so the UI can render a single bar across all of them.
 */
class AssetDownloader(
    private val context: Context,
    private val store: AssetStore = AssetStore(context),
) {

    sealed class Progress {
        /** HTTP body still streaming. */
        data class Downloading(val bytesRead: Long, val total: Long) : Progress()
        /** Inside the ZIP — files being written to staging. */
        data class Extracting(val filesDone: Int, val total: Int) : Progress()
        /** Hashing each extracted file against the manifest. */
        data class Verifying(val filesDone: Int, val total: Int) : Progress()
        object Done : Progress()
        data class Failed(val message: String) : Progress()
    }

    /**
     * Fully download + install [persona] from [zipUrl]. Suspends on
     * Dispatchers.IO so it's safe to call from a UI scope.
     */
    suspend fun installFromUrl(
        zipUrl: String,
        persona: String,
        onProgress: (Progress) -> Unit,
    ): Result<AssetsManifest> = withContext(Dispatchers.IO) {
        val workDir = File(store.root, "$persona.tmp")
        val zipFile = File(store.root, "$persona-download.zip")

        try {
            workDir.deleteRecursively()
            zipFile.delete()
            store.root.mkdirs()

            // ── 1. download ────────────────────────────────────────────
            val total = downloadFile(zipUrl, zipFile) { read, totalBytes ->
                onProgress(Progress.Downloading(read, totalBytes))
            }
            if (total < 0) {
                onProgress(Progress.Failed("download_failed"))
                return@withContext Result.failure(IllegalStateException("download failed"))
            }

            // ── 2. extract ─────────────────────────────────────────────
            val extracted = extractZip(zipFile, workDir, persona) { done, totalFiles ->
                onProgress(Progress.Extracting(done, totalFiles))
            }
            if (extracted < 0) {
                onProgress(Progress.Failed("extract_failed"))
                return@withContext Result.failure(IllegalStateException("extract failed"))
            }

            // ── 3. verify ──────────────────────────────────────────────
            val extractedPersonaDir = File(workDir, persona)
            val manifestFile = File(extractedPersonaDir, AssetStore.MANIFEST_NAME)
            if (!manifestFile.isFile) {
                onProgress(Progress.Failed("manifest_missing"))
                return@withContext Result.failure(IllegalStateException("manifest missing"))
            }
            val manifest = AssetStore.JSON.decodeFromString(
                AssetsManifest.serializer(),
                manifestFile.readText(),
            )
            val verifyOk = verifyAll(extractedPersonaDir, manifest) { done, totalFiles ->
                onProgress(Progress.Verifying(done, totalFiles))
            }
            if (!verifyOk) {
                onProgress(Progress.Failed("verify_failed"))
                return@withContext Result.failure(IllegalStateException("verify failed"))
            }

            // ── 4. atomic-ish swap ─────────────────────────────────────
            val finalDir = store.personaDir(persona)
            if (finalDir.exists()) finalDir.deleteRecursively()
            // The extracted dir is workDir/<persona>; the parent (workDir)
            // also contains nothing else useful at this point. Move just
            // the inner dir.
            val ok = extractedPersonaDir.renameTo(finalDir)
            if (!ok) {
                // Fallback: copy then delete.
                extractedPersonaDir.copyRecursively(finalDir, overwrite = true)
                extractedPersonaDir.deleteRecursively()
            }
            workDir.deleteRecursively()
            zipFile.delete()

            onProgress(Progress.Done)
            Result.success(manifest)
        } catch (e: Exception) {
            Log.w(TAG, "install $persona failed: ${e.message}", e)
            workDir.deleteRecursively()
            zipFile.delete()
            onProgress(Progress.Failed(e.message ?: e.javaClass.simpleName))
            Result.failure(e)
        }
    }

    // ── HTTP ──────────────────────────────────────────────────────────

    private fun downloadFile(
        url: String,
        dest: File,
        onProgress: (read: Long, total: Long) -> Unit,
    ): Long {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            requestMethod = "GET"
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "HTTP $code on $url")
                return -1
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
            var read = 0L
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        onProgress(read, total)
                    }
                }
            }
            return read
        } finally {
            conn.disconnect()
        }
    }

    // ── ZIP ───────────────────────────────────────────────────────────

    private fun extractZip(
        zipFile: File,
        intoDir: File,
        @Suppress("UNUSED_PARAMETER") persona: String,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Int {
        intoDir.mkdirs()
        // Pre-scan to know total entry count for the progress bar.
        var totalEntries = 0
        ZipInputStream(FileInputStream(zipFile)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (!e.isDirectory) totalEntries++
                zin.closeEntry()
            }
        }
        var done = 0
        ZipInputStream(FileInputStream(zipFile)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val outFile = File(intoDir, e.name)
                // Zip-slip guard — refuse paths that try to escape.
                if (!outFile.canonicalPath.startsWith(intoDir.canonicalPath + File.separator)) {
                    zin.closeEntry()
                    continue
                }
                if (e.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out -> zin.copyTo(out) }
                    done++
                    onProgress(done, totalEntries)
                }
                zin.closeEntry()
            }
        }
        return done
    }

    // ── Verify ────────────────────────────────────────────────────────

    private fun verifyAll(
        personaDir: File,
        manifest: AssetsManifest,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Boolean {
        var done = 0
        val total = manifest.files.size
        for (entry in manifest.files) {
            val f = File(personaDir, entry.path)
            if (!f.isFile) {
                Log.w(TAG, "missing after extract: ${entry.path}")
                return false
            }
            if (f.length() != entry.size) {
                Log.w(TAG, "size mismatch ${entry.path}: ${f.length()} vs ${entry.size}")
                return false
            }
            val actual = sha256(f)
            if (!actual.equals(entry.sha256, ignoreCase = true)) {
                Log.w(TAG, "sha256 mismatch ${entry.path}: $actual vs ${entry.sha256}")
                return false
            }
            done++
            onProgress(done, total)
        }
        return true
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "AssetDownloader"

        /**
         * Build the public release-asset URL for a given persona+version.
         * Matches the filename the [release-assets.yml] workflow uploads.
         */
        fun zipUrlFor(
            owner: String,
            repo: String,
            persona: String,
            version: String,
        ): String =
            "https://github.com/$owner/$repo/releases/download/" +
                "assets/$persona/$version/$persona-assets-$version.zip"
    }
}
