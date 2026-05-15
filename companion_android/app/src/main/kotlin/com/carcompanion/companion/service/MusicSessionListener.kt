package com.carcompanion.companion.service

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.carcompanion.companion.domain.MusicStateMachine
import com.carcompanion.companion.domain.SemanticEvent

/**
 * Layer 1.5 — media-app observer. Tracks the "primary" active media
 * session on the device (Spotify, YouTube Music, Apple Music, the AHU's
 * own audio app, podcast players, …) and converts playback / metadata
 * deltas into `MUSIC_*` semantic events via [MusicStateMachine].
 *
 * Driver listens to music in the car constantly per the user, so this
 * is the only Phase-6 input pillar shipped right now — Telephony (phone
 * calls) is intentionally left out.
 *
 * ── Permissions ──────────────────────────────────────────────────────
 *
 * `MediaSessionManager.getActiveSessions` requires a notification-listener
 * permission grant. We piggy-back on [MapsNotificationListener] — the
 * same toggle in Settings → Apps → Special access → Notification access
 * unlocks both nav notifications AND media sessions.
 *
 * If the user hasn't granted it yet, [start] returns false and the rest
 * of the soul pipeline keeps working — music observation is degraded
 * gracefully.
 *
 * ── Primary-session policy ───────────────────────────────────────────
 *
 * Multiple apps can hold media sessions simultaneously (e.g. Spotify in
 * the background, YouTube Music in the foreground). We pick the first
 * entry returned by `getActiveSessions` — Android sorts that list with
 * the most recently active first. When the primary changes (app switch
 * or a session is torn down), we re-bind the callback and feed the new
 * snapshot through the same [MusicStateMachine] — which sees it as a
 * normal title/artist change rather than STOP+START.
 *
 * ── Threading ────────────────────────────────────────────────────────
 *
 * All MediaSessionManager + MediaController callbacks dispatch on the
 * [handler] (main looper by default). State accesses inside this class
 * happen on that thread; the only volatile fields are [primary] /
 * [primaryCallback] which the [stop] path may read from another thread.
 */
class MusicSessionListener(
    context: Context,
    private val notificationListenerComponent: ComponentName,
    private val onEvent: (SemanticEvent) -> Unit,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {
    private val msm: MediaSessionManager? = try {
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    } catch (e: Exception) {
        Log.w(TAG, "MediaSessionManager unavailable: ${e.message}")
        null
    }

    private val stateMachine = MusicStateMachine()

    @Volatile private var primary: MediaController? = null
    @Volatile private var primaryCallback: MediaController.Callback? = null
    @Volatile private var active: Boolean = false

    val isActive: Boolean get() = active
    /** Debug-only snapshots. */
    val currentTitle: String? get() = stateMachine.currentTitle
    val currentArtist: String? get() = stateMachine.currentArtist
    val currentlyPlaying: Boolean get() = stateMachine.currentlyPlaying

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list ->
            // Pick the most recently-active session (or null if list is empty).
            val newPrimary = list?.firstOrNull()
            // Unhook the old callback if we're switching controllers.
            primary?.let { old ->
                primaryCallback?.let { old.unregisterCallback(it) }
            }
            primary = newPrimary
            primaryCallback = null
            if (newPrimary == null) {
                // No active session → feed the state machine an "empty" snapshot
                // so it emits MUSIC_STOPPED if we were playing.
                pushSnapshot(null)
            } else {
                val cb = makeCallback(newPrimary)
                primaryCallback = cb
                newPrimary.registerCallback(cb, handler)
                pushSnapshot(newPrimary)
            }
        }

    private fun makeCallback(controller: MediaController) =
        object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                if (controller === primary) pushSnapshot(controller)
            }
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                if (controller === primary) pushSnapshot(controller)
            }
            override fun onSessionDestroyed() {
                if (controller === primary) {
                    primary = null
                    primaryCallback = null
                    pushSnapshot(null)
                }
            }
        }

    private fun pushSnapshot(controller: MediaController?) {
        val snap = if (controller != null) {
            MusicStateMachine.Snapshot(
                isPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING,
                title = controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
                artist = controller.metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            )
        } else {
            MusicStateMachine.Snapshot.EMPTY
        }
        stateMachine.consume(snap).forEach { onEvent(it) }
    }

    /**
     * Begin observing. Returns false when the user hasn't granted
     * notification-listener access yet (SecurityException from the
     * MediaSessionManager call); caller should re-attempt after the
     * grant via [MapsNotificationListener] is detected.
     */
    fun start(): Boolean {
        if (active) return true
        val mgr = msm ?: return false
        return try {
            mgr.addOnActiveSessionsChangedListener(
                sessionsListener,
                notificationListenerComponent,
                handler,
            )
            // Bootstrap: replay current sessions immediately so we don't wait
            // for a state change to start tracking.
            sessionsListener.onActiveSessionsChanged(
                mgr.getActiveSessions(notificationListenerComponent)
            )
            active = true
            Log.d(TAG, "MusicSessionListener armed")
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification listener access not granted: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "MusicSessionListener start failed: ${e.message}")
            false
        }
    }

    fun stop() {
        if (!active) return
        msm?.removeOnActiveSessionsChangedListener(sessionsListener)
        primary?.let { c ->
            primaryCallback?.let { c.unregisterCallback(it) }
        }
        primary = null
        primaryCallback = null
        stateMachine.reset()
        active = false
    }

    companion object {
        private const val TAG = "MusicSession"
    }
}
