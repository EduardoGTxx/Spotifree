package com.spotifree.app

import com.spotifree.app.data.importer.CsvImporter
import com.spotifree.app.data.importer.SpotifyImporter
import com.spotifree.app.data.lyrics.LrcParser
import com.spotifree.app.data.lyrics.Romanizer
import com.spotifree.app.data.update.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {

    /** Ids of imported songs must match the previous (JavaScript) app, or likes/playlists break. */
    @Test
    fun spotifyIdsMatchPreviousApp() {
        assertEquals("spotify-h-rjd026", SpotifyImporter.makeTrack("Tití Me Preguntó", "Bad Bunny", null, null, null).id)
        assertEquals("spotify-h-1ptt17v", SpotifyImporter.makeTrack("DESPECHÁ", "Rosalía", null, null, null).id)
        assertEquals("spotify-h-5gp0d4", SpotifyImporter.makeTrack("Blinding Lights", "  The  Weeknd ", null, null, null).id)
        assertEquals("spotify-h-1q1t1a1", SpotifyImporter.makeTrack("봄날", "BTS", null, null, null).id)
        assertEquals("spotify-4uLU6hMCjMI75M1A2tKUQC", SpotifyImporter.makeTrack("x", "y", null, "spotify:track:4uLU6hMCjMI75M1A2tKUQC", null).id)
    }

    @Test
    fun lrcWithOffsetRepeatsAndWords() {
        val lines = LrcParser.parse(
            """
            [offset:+500]
            [00:01.00][00:10.50]Hola <00:01.50>mundo
            [00:05.00]
            [00:06.250]Segunda línea
            """.trimIndent()
        )
        assertEquals(listOf(0.5, 4.5, 5.75, 10.0), lines.map { it.timeSec })
        assertEquals("Hola mundo", lines[0].text)
        assertTrue(lines[1].isInstrumental)
        assertEquals(2, lines[0].words?.size)
        assertEquals(2, LrcParser.activeIndex(lines, 6.0))
        assertEquals(-1, LrcParser.activeIndex(lines, 0.1))
    }

    @Test
    fun csvExportify() {
        val csv = "Track URI,Track Name,Artist Name(s),Album Name,Duration (ms)\n" +
            "spotify:track:4uLU6hMCjMI75M1A2tKUQC,\"Never Gonna Give You Up\",Rick Astley,Whenever You Need Somebody,213573\n" +
            ",\"Hello, \"\"World\"\"\",Someone,,180000\n"
        val (title, tracks) = CsvImporter.parse(csv, "Mis_favoritas.csv")
        assertEquals("Mis favoritas", title)
        assertEquals(2, tracks.size)
        assertEquals("spotify-4uLU6hMCjMI75M1A2tKUQC", tracks[0].id)
        assertEquals(213, tracks[0].durationSec)
        assertEquals("Hello, \"World\"", tracks[1].title)
    }

    @Test
    fun romanization() {
        assertTrue(Romanizer.romanize("안녕")!!.lowercase().startsWith("an"))
        assertNull(Romanizer.romanize("plain text"))
    }

    @Test
    fun versionCompare() {
        assertTrue(UpdateChecker.compare("2.0.0", "2.0.1") < 0)
        assertTrue(UpdateChecker.compare("2.1.0", "2.0.9") > 0)
        assertTrue(UpdateChecker.compare("2.0.0-beta", "2.0.0") < 0)
        assertEquals(0, UpdateChecker.compare("v2.0.0", "2.0.0"))
    }
}
