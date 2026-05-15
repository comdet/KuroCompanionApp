package com.carcompanion.companion.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicStateMachineTest {

    private fun snap(
        playing: Boolean = false,
        title: String? = null,
        artist: String? = null,
    ) = MusicStateMachine.Snapshot(
        isPlaying = playing,
        title = title,
        artist = artist,
    )

    @Test
    fun firstPlay_emitsStarted() {
        val sm = MusicStateMachine()
        val out = sm.consume(snap(playing = true, title = "Song", artist = "A"))
        assertEquals(1, out.size)
        assertEquals("MUSIC_STARTED", out[0].name)
        assertEquals("Song — A", out[0].detail)
    }

    @Test
    fun playingToPaused_emitsStopped() {
        val sm = MusicStateMachine()
        sm.consume(snap(playing = true, title = "S"))
        val out = sm.consume(snap(playing = false, title = "S"))
        assertEquals(1, out.size)
        assertEquals("MUSIC_STOPPED", out[0].name)
    }

    @Test
    fun resumeAfterPause_emitsStartedNotSongChanged() {
        val sm = MusicStateMachine()
        sm.consume(snap(playing = true, title = "S", artist = "A"))
        sm.consume(snap(playing = false, title = "S", artist = "A"))
        val out = sm.consume(snap(playing = true, title = "S", artist = "A"))
        // Resuming the same track should be MUSIC_STARTED only — not a
        // SONG_CHANGED.
        assertEquals(1, out.size)
        assertEquals("MUSIC_STARTED", out[0].name)
    }

    @Test
    fun titleChangeWhilePlaying_emitsSongChanged() {
        val sm = MusicStateMachine()
        sm.consume(snap(playing = true, title = "Track A", artist = "X"))
        val out = sm.consume(snap(playing = true, title = "Track B", artist = "X"))
        assertEquals(1, out.size)
        assertEquals("MUSIC_SONG_CHANGED", out[0].name)
        assertEquals("Track B — X", out[0].detail)
    }

    @Test
    fun artistChangeWhilePlaying_emitsSongChanged() {
        val sm = MusicStateMachine()
        sm.consume(snap(playing = true, title = "Track", artist = "X"))
        val out = sm.consume(snap(playing = true, title = "Track", artist = "Y"))
        assertEquals(1, out.size)
        assertEquals("MUSIC_SONG_CHANGED", out[0].name)
    }

    @Test
    fun blankTitle_suppressesSongChanged() {
        val sm = MusicStateMachine()
        sm.consume(snap(playing = true, title = "Song", artist = "A"))
        // Many media apps emit a transient blank title while loading the
        // next track — that shouldn't fire a song-changed reaction.
        val out = sm.consume(snap(playing = true, title = "", artist = ""))
        assertTrue(out.none { it.name == "MUSIC_SONG_CHANGED" })
    }

    @Test
    fun nullToBlank_doesNotFire() {
        val sm = MusicStateMachine()
        // First playing snapshot has null title (race: callback arrived
        // before metadata pushed). Second tick still has null. No
        // SONG_CHANGED should fire.
        sm.consume(snap(playing = true, title = null))
        val out = sm.consume(snap(playing = true, title = null))
        assertTrue(out.isEmpty())
    }

    @Test
    fun emptySnapshot_whilePlaying_emitsStopped() {
        // Simulates the listener pushing Snapshot.EMPTY when the session
        // was destroyed — we should detect the stop.
        val sm = MusicStateMachine()
        sm.consume(snap(playing = true, title = "X"))
        val out = sm.consume(MusicStateMachine.Snapshot.EMPTY)
        assertEquals(1, out.size)
        assertEquals("MUSIC_STOPPED", out[0].name)
    }

    @Test
    fun emptyToEmpty_isNoOp() {
        val sm = MusicStateMachine()
        val out1 = sm.consume(MusicStateMachine.Snapshot.EMPTY)
        val out2 = sm.consume(MusicStateMachine.Snapshot.EMPTY)
        assertTrue(out1.isEmpty())
        assertTrue(out2.isEmpty())
    }

    @Test
    fun reset_clearsState_nextPlayIsStarted() {
        val sm = MusicStateMachine()
        sm.consume(snap(playing = true, title = "S"))
        // Without reset, another playing snapshot wouldn't fire STARTED.
        // After reset, the next playing snapshot should treat as fresh.
        sm.reset()
        val out = sm.consume(snap(playing = true, title = "S"))
        assertTrue(out.any { it.name == "MUSIC_STARTED" })
    }

    @Test
    fun detail_handlesNullArtist() {
        val sm = MusicStateMachine()
        val out = sm.consume(snap(playing = true, title = "OnlyTitle", artist = null))
        assertEquals("OnlyTitle", out[0].detail)
    }

    @Test
    fun detail_handlesNullTitleWithArtist() {
        val sm = MusicStateMachine()
        val out = sm.consume(snap(playing = true, title = null, artist = "OnlyArtist"))
        // No title means no SONG_CHANGED, but STARTED still fires with
        // the artist as the detail fallback.
        assertEquals(1, out.size)
        assertEquals("MUSIC_STARTED", out[0].name)
        assertEquals("OnlyArtist", out[0].detail)
    }

    @Test
    fun detail_isNullWhenAllBlank() {
        val sm = MusicStateMachine()
        val out = sm.consume(snap(playing = true, title = "", artist = ""))
        assertEquals(1, out.size)
        assertEquals("MUSIC_STARTED", out[0].name)
        assertEquals(null, out[0].detail)
    }
}
