package com.carcompanion.companion.data.repo

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the GitHub Releases API to discover the latest available
 * version of a persona's asset pack.  Used by the in-app updater banner
 * — purely informational, doesn't trigger downloads on its own.
 *
 * Endpoint:
 *   GET https://api.github.com/repos/<owner>/<repo>/releases?per_page=30
 *
 * Filter:
 *   tag_name starts with "assets/<persona>/v"
 *
 * The first match (GitHub returns newest first) wins. We never
 * authenticate — release info on a public repo is anonymous-readable.
 */
class GitHubReleaseChecker(
    private val owner: String,
    private val repo: String,
) {

    @Serializable
    private data class ReleaseDto(
        @SerialName("tag_name") val tagName: String,
        @SerialName("html_url") val htmlUrl: String = "",
        @SerialName("published_at") val publishedAt: String? = null,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
    )

    data class LatestAsset(
        val tag: String,
        val version: String,
        val publishedAt: String?,
    )

    /** Latest released version of `assets/<persona>/v…` or null on any failure. */
    suspend fun latestForPersona(persona: String): LatestAsset? =
        withContext(Dispatchers.IO) {
            val raw = fetchReleases() ?: return@withContext null
            val list = try {
                JSON.decodeFromString<List<ReleaseDto>>(raw)
            } catch (e: Exception) {
                Log.w(TAG, "release JSON parse fail: ${e.message}")
                return@withContext null
            }
            val prefix = "assets/$persona/"
            list.asSequence()
                .filter { !it.draft && !it.prerelease }
                .filter { it.tagName.startsWith(prefix) }
                .mapNotNull { r ->
                    val version = r.tagName.removePrefix(prefix)
                    if (version.startsWith("v")) {
                        LatestAsset(r.tagName, version, r.publishedAt)
                    } else null
                }
                .firstOrNull()
        }

    private fun fetchReleases(): String? = try {
        val url = URL("https://api.github.com/repos/$owner/$repo/releases?per_page=30")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "CarCompanion/asset-check")
        }
        try {
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "HTTP ${conn.responseCode} on releases API")
                null
            } else {
                conn.inputStream.bufferedReader().readText()
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        Log.w(TAG, "releases fetch failed: ${e.message}")
        null
    }

    companion object {
        private const val TAG = "GitHubReleaseChecker"
        private val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}
