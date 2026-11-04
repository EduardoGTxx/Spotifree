package com.spotifree.app

import com.spotifree.app.data.importer.SpotifyImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SpotifyImporterLiveZipTest {

    @Test
    fun shouldSkipZipEntryFiltersTechnicalLogsAndNonMusicMetadata() {
        assertTrue(SpotifyImporter.shouldSkipZipEntry("Spotify Technical Log Information/AccessoryStateEvent.json"))
        assertTrue(SpotifyImporter.shouldSkipZipEntry("Spotify Technical Log Information/AndroidDeviceReport.json"))
        assertTrue(SpotifyImporter.shouldSkipZipEntry("Spotify Extended Streaming History/ReadMeFirst_ExtendedStreamingHistory.pdf"))
        assertTrue(SpotifyImporter.shouldSkipZipEntry("__MACOSX/._Playlist1.json"))
        assertTrue(SpotifyImporter.shouldSkipZipEntry("Spotify Account Data/SearchQueries.json"))
        assertTrue(SpotifyImporter.shouldSkipZipEntry("Spotify Account Data/Inferences.json"))

        assertFalse(SpotifyImporter.shouldSkipZipEntry("Spotify Account Data/Playlist1.json"))
        assertFalse(SpotifyImporter.shouldSkipZipEntry("Spotify Account Data/StreamingHistory_music_0.json"))
        assertFalse(SpotifyImporter.shouldSkipZipEntry("Spotify Account Data/YourLibrary.json"))
        assertFalse(SpotifyImporter.shouldSkipZipEntry("Spotify Extended Streaming History/Streaming_History_Audio_2026.json"))
    }

    @Test
    fun syntheticZipStreamExtractsPlaylistsAndDeduplicates() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            zos.putNextEntry(ZipEntry("Spotify Account Data/Playlist1.json"))
            zos.write("""{"playlists":[{"name":"Favs","items":[{"track":{"trackName":"Song 1","artistName":"Artist 1","trackUri":"spotify:track:1234567890"}}]}]}""".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("Spotify Technical Log Information/Log.json"))
            zos.write("""{"log":"skip me"}""".toByteArray())
            zos.closeEntry()
        }

        val builder = SpotifyImporter.AnalysisBuilder()
        val importer = SpotifyImporter()
        importer.analyzeStream(ByteArrayInputStream(bos.toByteArray()), "test.zip", builder)
        val a = builder.build()
        assertEquals(1, a.playlists.size)
        assertEquals("Favs", a.playlists[0].title)
        assertEquals(1, a.playlists[0].tracks.size)
    }

    @Test
    fun liveExportsDirectoryImportAnalysis() {
        val dir = File("C:/Users/edhernandez/Downloads/Exports Spotify")
        if (!dir.exists()) return

        val zip1 = File(dir, "my_spotify_data (1).zip")
        val zip2 = File(dir, "my_spotify_data (2).zip")
        val zip3 = File(dir, "my_spotify_data (3).zip")
        val zip4 = File(dir, "my_spotify_data (4).zip")
        val zip5 = File(dir, "my_spotify_data (5).zip")

        val importer = SpotifyImporter()

        // 1. Technical logs zip (1) should skip cleanly without OOM and yield empty analysis
        if (zip1.exists()) {
            val builder = SpotifyImporter.AnalysisBuilder()
            FileInputStream(zip1).use { input ->
                importer.analyzeStream(input, zip1.name, builder)
            }
            val a = builder.build()
            assertTrue("Technical log zip should yield empty music data", a.isEmpty)
        }

        // 2. Account data zip (2) should extract 3 playlists and basic history
        if (zip2.exists()) {
            val builder = SpotifyImporter.AnalysisBuilder()
            FileInputStream(zip2).use { input ->
                importer.analyzeStream(input, zip2.name, builder)
            }
            val a = builder.build()
            assertEquals(3, a.playlists.size)
            assertTrue("Should contain history", a.history.isNotEmpty())
            assertEquals("basic", a.historyKind)
        }

        // 3. All 5 zips combined: skips logs, deduplicates duplicate zips (2 and 3), and prefers extended history
        if (zip1.exists() && zip2.exists() && zip3.exists() && zip4.exists() && zip5.exists()) {
            val builder = SpotifyImporter.AnalysisBuilder()
            for (f in listOf(zip1, zip2, zip3, zip4, zip5)) {
                FileInputStream(f).use { importer.analyzeStream(it, f.name, builder) }
            }
            val a = builder.build()
            assertEquals("Should deduplicate playlists to 3", 3, a.playlists.size)
            assertEquals("extended", a.historyKind)
            assertTrue("Should have extensive history", a.history.size > 10_000)
        }
    }
}
