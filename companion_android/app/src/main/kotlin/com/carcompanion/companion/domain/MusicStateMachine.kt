package com.carcompanion.companion.domain

/**
 * Pure-logic state tracker that converts a stream of "music snapshot"
 * observations into `MUSIC_*` semantic events. The Android-side glue
 * ([com.carcompanion.companion.service.MusicSessionListener]) builds
 * snapshots from [android.media.session.MediaController] callbacks and
 * feeds them here; the conversion is a state diff so it can be unit-
 * tested without any framework dependency.
 *
 * ── Events emitted ───────────────────────────────────────────────────
 *   MUSIC_STARTED       — playback transitioned from not-playing to playing
 *   MUSIC_STOPPED       — playback transitioned from playing to not-playing
 *   MUSIC_SONG_CHANGED  — title/artist changed while playing
 *
 * `detail` carries `"<title> — <artist>"` so future reactions can quote
 * the track. Blank titles (some podcast apps or pre-loaded states)
 * suppress `MUSIC_SONG_CHANGED`.
 *
 * The state machine is single-instance: when the user switches apps
 * (Spotify → YouTube Music) the listener picks the new primary session
 * and the machine sees that as a normal title/artist change.
 */
class MusicStateMachine {

    /**
     * One observation point. Fields can be null when the underlying
     * MediaController doesn't expose metadata yet (Android can race the
     * Callback registration against the first metadata push).
     */
    data class Snapshot(
        val isPlaying: Boolean,
        val title: String?,
        val artist: String?,
    ) {
        companion object {
            val EMPTY = Snapshot(isPlaying = false, title = null, artist = null)
        }
    }

    private var last: Snapshot = Snapshot.EMPTY

    /** Snapshot for debug UI. */
    val currentlyPlaying: Boolean get() = last.isPlaying
    val currentTitle: String? get() = last.title
    val currentArtist: String? get() = last.artist

    /**
     * Diff the new snapshot against the previous one. Returns the
     * events that fired this tick (zero, one, or two of them — a
     * play-then-immediate-skip would emit MUSIC_STARTED + MUSIC_SONG_CHANGED
     * if the listener happens to receive both callbacks together).
     */
    fun consume(snap: Snapshot): List<SemanticEvent> {
        val out = mutableListOf<SemanticEvent>()
        val prev = last
        last = snap

        if (snap.isPlaying && !prev.isPlaying) {
            out += SemanticEvent(
                name = "MUSIC_STARTED",
                detail = detailOf(snap),
            )
        } else if (!snap.isPlaying && prev.isPlaying) {
            out += SemanticEvent(name = "MUSIC_STOPPED")
        }

        // Title / artist change while continuously playing — only fire
        // if the new title is meaningful (some apps emit transient blanks
        // while loading the next track).
        if (snap.isPlaying && prev.isPlaying) {
            val titleChanged = snap.title != prev.title
            val artistChanged = snap.artist != prev.artist
            if ((titleChanged || artistChanged) && !snap.title.isNullOrBlank()) {
                out += SemanticEvent(
                    name = "MUSIC_SONG_CHANGED",
                    detail = detailOf(snap),
                )
            }
        }

        return out
    }

    /** Service teardown — clears state. */
    fun reset() {
        last = Snapshot.EMPTY
    }

    private fun detailOf(s: Snapshot): String? {
        val t = s.title?.takeIf { it.isNotBlank() }
        val a = s.artist?.takeIf { it.isNotBlank() }
        return when {
            t != null && a != null -> "$t — $a"
            t != null              -> t
            a != null              -> a
            else                   -> null
        }
    }
}
