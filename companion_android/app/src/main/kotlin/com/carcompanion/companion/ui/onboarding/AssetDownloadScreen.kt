package com.carcompanion.companion.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.carcompanion.companion.BuildConfig
import com.carcompanion.companion.data.repo.AssetDownloader
import com.carcompanion.companion.data.repo.AssetStore
import com.carcompanion.companion.data.repo.AssetsManifest
import kotlinx.coroutines.launch

/**
 * First-run / forced-update screen for the persona asset pack.
 *
 * Drives the [AssetDownloader] state machine and shows a single
 * progress bar that spans download → extract → verify. The download
 * URL is computed from BuildConfig (repo/owner) + the persona +
 * MIN_ASSETS_VERSION; bumping the constant in build.gradle.kts is what
 * forces all installs to re-fetch.
 *
 * Reasons we surface this:
 *  - **MISSING**: fresh install, never downloaded → block until done.
 *  - **OUTDATED**: APK newer than installed pack → offer download +
 *    "skip for now" (lets the user still use what they have, but
 *    surfaces a banner inside HomeScreen later).
 */
@Composable
fun AssetDownloadScreen(
    state: AssetStore.State,
    persona: String,
    downloader: AssetDownloader,
    onDone: (AssetsManifest) -> Unit,
    onSkip: (() -> Unit)? = null,   // null = REQUIRED, no skip allowed
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<AssetDownloader.Progress?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var inFlight by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Kuro voice pack",
            style = MaterialTheme.typography.headlineSmall,
        )

        val subtitle = when (state) {
            is AssetStore.State.MISSING ->
                "First-time setup. Downloads ~190 MB. WiFi recommended."
            is AssetStore.State.OUTDATED ->
                "Newer voice pack available — installed ${state.installed.version}, " +
                    "required ${state.required}."
            is AssetStore.State.OK ->
                "Already up to date — version ${state.installed.version}."
        }
        Text(subtitle, style = MaterialTheme.typography.bodyMedium)

        Spacer(Modifier.height(16.dp))

        // Progress block — only meaningful once a download starts.
        when (val s = status) {
            null -> Text(
                if (inFlight) "Starting…" else "Tap Download to fetch the voice pack.",
                style = MaterialTheme.typography.bodyMedium,
            )
            is AssetDownloader.Progress.Downloading -> {
                val frac = if (s.total > 0) s.bytesRead.toFloat() / s.total else -1f
                ProgressBlock(
                    label = "Downloading… ${bytes(s.bytesRead)} / ${bytes(s.total)}",
                    fraction = frac,
                )
            }
            is AssetDownloader.Progress.Extracting -> ProgressBlock(
                label = "Unpacking… ${s.filesDone} / ${s.total} files",
                fraction = if (s.total > 0) s.filesDone.toFloat() / s.total else -1f,
            )
            is AssetDownloader.Progress.Verifying -> ProgressBlock(
                label = "Verifying… ${s.filesDone} / ${s.total}",
                fraction = if (s.total > 0) s.filesDone.toFloat() / s.total else -1f,
            )
            AssetDownloader.Progress.Done -> Text(
                "Done.",
                style = MaterialTheme.typography.titleMedium,
            )
            is AssetDownloader.Progress.Failed -> Text(
                "Failed: ${s.message}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = !inFlight,
                onClick = {
                    inFlight = true
                    error = null
                    scope.launch {
                        val url = AssetDownloader.zipUrlFor(
                            owner = BuildConfig.REPO_OWNER,
                            repo = BuildConfig.REPO_NAME,
                            persona = persona,
                            version = BuildConfig.MIN_ASSETS_VERSION,
                        )
                        downloader.installFromUrl(url, persona) {
                            status = it
                        }.onSuccess { m ->
                            inFlight = false
                            onDone(m)
                        }.onFailure { e ->
                            inFlight = false
                            error = e.message ?: e.javaClass.simpleName
                        }
                    }
                },
            ) {
                Text(when (state) {
                    is AssetStore.State.OUTDATED -> "Update"
                    else -> "Download"
                })
            }
            if (onSkip != null) {
                OutlinedButton(
                    enabled = !inFlight,
                    onClick = onSkip,
                ) { Text("Skip") }
            }
        }
    }
}

@Composable
private fun ProgressBlock(label: String, fraction: Float) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        if (fraction >= 0f) {
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun bytes(n: Long): String {
    if (n < 0) return "?"
    val mb = n / 1024.0 / 1024.0
    return if (mb >= 1.0) "%.1f MB".format(mb) else "${n / 1024} KB"
}
