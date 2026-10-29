package com.spotifree.app

import com.spotifree.app.data.lyrics.LrcParser
import com.spotifree.app.data.lyrics.LyricsSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synced-lyrics rules from the 2026 audit (made-up lyrics only). */
class LyricsSyncTest {

    // ------------------------------------------------------------------ clock smoothing

    @Test
    fun smallBackwardCorrectionsKeepTheScreenValue() {
        // Interpolation ran 120 ms ahead of the next controller sample: no flicker backwards
        assertEquals(10_120L, LyricsSync.smooth(displayed = 10_120L, target = 10_000L, playing = true))
    }

    @Test
    fun forwardSamplesAndRealSeeksAreFollowed() {
        assertEquals(10_250L, LyricsSync.smooth(10_000L, 10_250L, playing = true))
        // Seek back to the start of the song
        assertEquals(1_000L, LyricsSync.smooth(95_000L, 1_000L, playing = true))
        // Exactly the tolerance is already a seek
        assertEquals(9_700L, LyricsSync.smooth(10_000L, 9_700L, playing = true))
    }

    @Test
    fun pausedAlwaysShowsTheSample() {
        assertEquals(10_000L, LyricsSync.smooth(10_120L, 10_000L, playing = false))
    }

    // ------------------------------------------------------------------ auto-scroll vs touch

    @Test
    fun autoScrollWaitsAfterTheUserScrolls() {
        assertTrue(LyricsSync.mayAutoScroll(nowMs = 50_000L, dragging = false, lastUserScrollAtMs = 0L))
        assertFalse(LyricsSync.mayAutoScroll(50_000L, dragging = true, lastUserScrollAtMs = 0L))
        assertFalse(LyricsSync.mayAutoScroll(50_000L, dragging = false, lastUserScrollAtMs = 48_000L))
        assertTrue(LyricsSync.mayAutoScroll(50_000L, dragging = false, lastUserScrollAtMs = 50_000L - LyricsSync.USER_SCROLL_HOLD_MS))
        assertEquals(1_500L, LyricsSync.holdRemaining(50_000L, 48_000L))
        assertEquals(0L, LyricsSync.holdRemaining(50_000L, 0L))
    }

    // ------------------------------------------------------------------ parser

    @Test
    fun byteOrderMarkDoesNotDropTheFirstLine() {
        val lines = LrcParser.parse("﻿[00:01.00]Primera\n[00:02.00]Segunda\n[00:03.00]Tercera")
        assertEquals(listOf("Primera", "Segunda", "Tercera"), lines.map { it.text })
        assertEquals(1.0, lines.first().timeSec, 0.0001)
    }

    @Test
    fun wordByWordLinesKeepTheirWordTimes() {
        val lines = LrcParser.parse("[00:10.00]<00:10.00>Luz <00:10.40>de <00:10.90>prueba\n[00:12.00]Fin")
        val words = lines[0].words
        assertNotNull(words)
        assertEquals(listOf(10.0, 10.4, 10.9), words!!.map { it.timeSec })
        assertEquals("Luz de prueba", lines[0].text)
        assertNull(lines[1].words)
    }

    @Test
    fun crlfMillisecondsAndColonFractions() {
        val lines = LrcParser.parse("[00:01.5]Uno\r\n[00:02:25]Dos\r\n[01:03.123]Tres")
        assertEquals(listOf(1.5, 2.25, 63.123), lines.map { it.timeSec })
    }

    @Test
    fun activeIndexEdges() {
        val lines = LrcParser.parse("[00:01.00]A\n[00:02.00]B\n[00:03.00]C")
        assertEquals(-1, LrcParser.activeIndex(lines, 0.0))
        assertEquals(0, LrcParser.activeIndex(lines, 1.0))
        assertEquals(1, LrcParser.activeIndex(lines, 2.999))
        assertEquals(2, LrcParser.activeIndex(lines, 999.0))
        assertEquals(-1, LrcParser.activeIndex(emptyList(), 5.0))
    }
}
