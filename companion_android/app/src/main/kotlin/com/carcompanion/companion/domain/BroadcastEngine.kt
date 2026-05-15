package com.carcompanion.companion.domain

import android.util.Log
import com.carcompanion.companion.data.Reaction
import com.carcompanion.companion.data.SpontaneousEntry
import com.carcompanion.companion.data.repo.AssetLoader
import com.carcompanion.companion.network.CcpItem
import com.carcompanion.companion.network.CompanionCcpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.launch

/**
 * Layer 4 — Broadcast.
 *
 * Translates the soul engine's output into hardware-facing side-effects:
 *   1. Pushes the current Emotion's emoji to the overlay mood badge (callback).
 *   2. For each reaction picked by [CharacterEngine], loads the named GIF and
 *      WAV out of [AssetLoader] and CCP-ships them to the ESP32-S3 robot via
 *      [CompanionCcpClient]. Missing assets are skipped silently — the emoji
 *      path still updates so the user sees the character react.
 *
 * Network work runs on the provided [coroutineScope] (Dispatchers.IO inside
 * [CompanionCcpClient.send]), so a slow ESP32 never blocks the soul pipeline.
 */
class BroadcastEngine(
    private val assetLoader: AssetLoader,
    private val ccpClient: CompanionCcpClient,
    private val getHost: () -> String,
    private val onMoodEmoji: (String) -> Unit,
    private val scope: CoroutineScope,
    private val onSendResult: (String) -> Unit = {},
    /**
     * Invoked after every successful CCP push. Drives [RobotPresence] so
     * the wake orchestrator can observe the robot answering through normal
     * outbound traffic without a dedicated ping.
     */
    private val onCcpSuccess: () -> Unit = {},
    /** Minimum gap between CCP pushes. Protects ESP32 from event bursts. */
    private val minSendGapMs: Long = 1500L,
) {

    /**
     * Wall-clock of the most recent CCP push start. Throttle compares the
     * arrival time of a new reaction to this and drops if too close.
     *
     * Why drop-newest rather than queue: the robot can only render one
     * (gif + wav) pair at a time, and the play loop is several seconds. By the
     * time a queued event reaches the head, the soul state has moved on.
     * Dropping keeps the freshest reaction in the air and prevents stale
     * playback after the situation has resolved.
     */
    private val lastSendMs = AtomicLong(0L)
    // Manual-test exempted from throttle so user can spam Test buttons.
    @Volatile private var lastWasManual: Boolean = false

    fun onEmotionChanged(emotion: Emotion) {
        onMoodEmoji(emotion.emoji)
    }

    /**
     * Event-driven reaction picked by the CharacterEngine.
     *
     * Asset resolution priority for each side (gif / wav):
     *   1. Explicit filename in reaction (legacy / pinned override)
     *   2. Random pick from the event's content pool (via [AssetLoader.EVENT_TO_ASSETS])
     *   3. Skip (no asset of that kind)
     */
    fun onReaction(reaction: Reaction, eventName: String) {
        playAssets(
            explicitGif = reaction.gif,
            explicitWav = reaction.audio,
            poolKey = eventName,
            tag = "reaction[$eventName]",
        )
    }

    /**
     * Spontaneous "idle thought" emitted by [SpontaneousTicker]. The pool key is
     * synthesized from the current emotion zone (`IDLE_<ZONE>`); see
     * [AssetLoader.EVENT_TO_ASSETS] for which zones push assets and which let
     * firmware-side idle play through (e.g. IDLE_SLEEPY = no push).
     */
    fun onSpontaneous(entry: SpontaneousEntry, zone: Emotion) {
        playAssets(
            explicitGif = entry.gif,
            explicitWav = entry.audio,
            poolKey = "IDLE_${zone.name}",
            tag = "spontaneous[${zone.name}]",
        )
    }

    /**
     * Manual test entry-point used by the Cfg "Test GIF / Test WAV" buttons.
     * Pure legacy path (filename → loadGif/loadWav, recursive search through
     * all pool folders). No pool fallback so the user always sees exactly the
     * file they picked.
     */
    fun manualTest(gifName: String?, wavName: String?) {
        playAssets(
            explicitGif = gifName,
            explicitWav = wavName,
            poolKey = null,
            tag = "test",
        )
    }

    /**
     * Resolves assets (explicit-or-pool) and CCP-ships them. Always sets
     * PRIORITY=1 so the robot interrupts its idle loop immediately.
     *
     * Throttle: if a previous push happened less than [minSendGapMs] ago, the
     * new one is dropped (with a log line). Manual-test path bypasses throttle
     * so the Cfg "Test GIF/WAV" buttons stay responsive.
     *
     * @param explicitGif  filename to use (overrides pool)
     * @param explicitWav  filename to use (overrides pool)
     * @param poolKey      event id for [AssetLoader.EVENT_TO_ASSETS] lookup, or
     *                     null to disable pool fallback (manual-test path)
     */
    private fun playAssets(
        explicitGif: String?,
        explicitWav: String?,
        poolKey: String?,
        tag: String,
    ) {
        val isManual = poolKey == null
        if (!isManual) {
            val now = System.currentTimeMillis()
            val gap = now - lastSendMs.get()
            if (gap < minSendGapMs) {
                Log.d(TAG, "$tag throttled (gap=${gap}ms < ${minSendGapMs}ms)")
                onSendResult("$tag throttled (${gap}ms)")
                return
            }
        }

        val mapping = poolKey?.let { AssetLoader.EVENT_TO_ASSETS[it] }
        val gifBytes = explicitGif?.let { assetLoader.loadGif(it) }
            ?: mapping?.gifFolder?.let { assetLoader.pickRandomGif(it) }
        val wavBytes = explicitWav?.let { assetLoader.loadWav(it) }
            ?: mapping?.audioDir?.let { assetLoader.pickRandomWav(it) }

        if (gifBytes == null && wavBytes == null) {
            Log.d(TAG, "$tag → no shippable assets " +
                "(gif=$explicitGif wav=$explicitWav pool=$poolKey)")
            return
        }

        // Reserve the slot before launching so concurrent invocations also see
        // the updated stamp and get throttled.
        if (!isManual) lastSendMs.set(System.currentTimeMillis())

        val items = buildList {
            gifBytes?.let { add(CcpItem.gif(it)) }
            wavBytes?.let { add(CcpItem.wav(it)) }
            add(CcpItem.priority(true))
        }
        val host = getHost()
        scope.launch(Dispatchers.IO) {
            runCatching { ccpClient.send(host, items = items) }
                .onSuccess {
                    onCcpSuccess()
                    val gb = gifBytes?.size ?: 0
                    val wb = wavBytes?.size ?: 0
                    Log.d(TAG, "$tag → CCP ok (gif=${gb}B wav=${wb}B) host=$host resp=$it")
                    onSendResult("$tag → ${it.take(40)}")
                }
                .onFailure {
                    Log.w(TAG, "$tag → CCP failed: ${it.message}")
                    onSendResult("$tag $host send failed: ${it.message}")
                }
        }
    }

    companion object {
        private const val TAG = "BroadcastEngine"
    }
}
