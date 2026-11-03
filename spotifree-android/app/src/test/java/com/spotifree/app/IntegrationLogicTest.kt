package com.spotifree.app

import com.spotifree.app.data.artwork.ArtworkResolver
import com.spotifree.app.data.prefs.AppSettings
import com.spotifree.app.util.backoffDelayMs
import com.spotifree.app.util.retryWithBackoff
import com.spotifree.app.util.runCatchingCancellable
import com.spotifree.app.workout.TGymBridge
import com.spotifree.app.workout.TGymEvent
import com.spotifree.app.workout.WorkoutStreak
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class IntegrationLogicTest {

    @Test
    fun backoffGrowsAndIsCapped() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 16_000L), (0..5).map { backoffDelayMs(it, maxMs = 16_000) })
    }

    @Test
    fun retryWaitsWithBackoffOnVirtualTime() = runTest {
        var calls = 0
        val r = retryWithBackoff(times = 3, initialMs = 1_000) {
            calls++
            if (calls < 3) error("boom") else "ok"
        }
        assertEquals("ok", r.getOrNull())
        assertEquals(3, calls)
        assertEquals(3_000L, currentTime) // 1 s + 2 s of (virtual) waiting
    }

    @Test
    fun retryGivesUpAndReturnsLastError() = runTest {
        val r = retryWithBackoff(times = 2, initialMs = 10) { error("nope $it") }
        assertEquals("nope 1", r.exceptionOrNull()?.message)
    }

    @Test
    fun cancellationIsNeverSwallowed() {
        try {
            runCatchingCancellable { throw CancellationException("stop") }
            fail("CancellationException must propagate")
        } catch (_: CancellationException) {
        }
        assertTrue(runCatchingCancellable { error("x") }.isFailure)
    }

    @Test
    fun tgymEventsParse() {
        val now = 1_000_000L
        assertEquals(
            TGymEvent.RestStart(now + 90_000, 90, 90, "Press banca"),
            TGymBridge.parse("REST_START", null, 90, now + 90_000, 90, "Press banca", null, 0, now),
        )
        // endsAt missing: computed from seconds
        assertEquals(TGymEvent.RestStart(now + 60_000, 60, 60, ""), TGymBridge.parse("rest_start", null, 60, 0, 0, null, null, 0, now))
        // already over: treated as the end of the rest
        assertEquals(TGymEvent.RestEnd, TGymBridge.parse("REST_START", null, 30, now - 1, 30, null, null, 0, now))
        assertEquals(TGymEvent.RestEnd, TGymBridge.parse("REST_END", null, 0, 0, 0, null, null, 0, now))
        // legacy boolean extra
        assertTrue(TGymBridge.parse(null, true, 45, 0, 0, null, null, 0, now) is TGymEvent.RestStart)
        assertEquals(TGymEvent.RestEnd, TGymBridge.parse(null, false, 0, 0, 0, null, null, 0, now))
        assertEquals(
            TGymEvent.WorkoutFinish(LocalDate.of(2026, 10, 26), 3_600_000),
            TGymBridge.parse("WORKOUT_FINISH", null, 0, 0, 0, null, "2026-10-26", 3_600_000, now),
        )
        assertEquals(null, TGymBridge.parse("UNKNOWN", null, 0, 0, 0, null, null, 0, now))
    }

    @Test
    fun workoutStreak() {
        val d = LocalDate.of(2026, 10, 26)
        var s = WorkoutStreak.mark(AppSettings(), d)
        assertEquals(1, s.workoutStreakDays)
        s = WorkoutStreak.mark(s, d) // same day twice
        assertEquals(1, s.workoutStreakDays)
        s = WorkoutStreak.mark(s, d.plusDays(1))
        assertEquals(2, s.workoutStreakDays)
        assertEquals(2, WorkoutStreak.current(s, d.plusDays(2))) // still alive the day after
        assertEquals(0, WorkoutStreak.current(s, d.plusDays(3)))
        s = WorkoutStreak.mark(s, d.plusDays(4)) // gap: starts again
        assertEquals(1, s.workoutStreakDays)
    }

    @Test
    fun artworkMatching() {
        assertTrue(ArtworkResolver.matches("Bad Bunny", "Tití Me Preguntó", "Bad Bunny", "Titi Me Pregunto"))
        assertTrue(ArtworkResolver.matches("Rosalía", "DESPECHÁ", "ROSALÍA", "Despechá (Remix)"))
        assertFalse(ArtworkResolver.matches("Coldplay", "Yellow", "Bad Bunny", "Yellow"))
        // Properly matches when raw YouTube title includes "Artist - Title" prefix
        assertTrue(ArtworkResolver.matches("Rihanna", "Diamonds", "Rihanna", "Rihanna - Diamonds (Official Music Video)"))
        assertTrue(ArtworkResolver.matches("Arctic Monkeys", "505", "Arctic Monkeys", "Arctic Monkeys - 505"))
        // Rejects karaoke / tribute knockoffs
        assertFalse(ArtworkResolver.matches("Rihanna", "Diamonds (Karaoke Version)", "Rihanna", "Diamonds"))
        assertFalse(ArtworkResolver.matches("Rihanna Tribute Band", "Diamonds (Originally Performed By Rihanna)", "Rihanna", "Diamonds"))
        // Short titles must not match as substrings inside longer unrelated titles
        assertFalse(ArtworkResolver.matches("Rihanna", "I", "Rihanna", "Diamonds"))
    }

    @Test
    fun artworkCleanArtistAndTitle() {
        assertEquals(
            "Rihanna" to "Diamonds",
            ArtworkResolver.cleanArtistAndTitle("Rihanna", "Rihanna - Diamonds (Official Music Video)"),
        )
        assertEquals(
            "Arctic Monkeys" to "505",
            ArtworkResolver.cleanArtistAndTitle("Arctic Monkeys", "Arctic Monkeys - 505"),
        )
        assertEquals(
            "Rihanna" to "Diamonds",
            ArtworkResolver.cleanArtistAndTitle("YouTube", "Rihanna - Diamonds"),
        )
        assertEquals(
            "Queen" to "Bohemian Rhapsody",
            ArtworkResolver.cleanArtistAndTitle("Queen - Topic", "Bohemian Rhapsody - Remastered 2011"),
        )
    }

    @Test
    fun artworkUpgradeAndLowResDetection() {
        assertEquals(
            "https://lh3.googleusercontent.com/abc123=w1200-h1200-l90-rj",
            com.spotifree.app.data.remote.YouTubeIds.upgradeArtwork("https://lh3.googleusercontent.com/abc123=w544-h544-l90-rj"),
        )
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music115/v4/1000x1000bb.jpg",
            com.spotifree.app.data.remote.YouTubeIds.upgradeArtwork("https://is1-ssl.mzstatic.com/image/thumb/Music115/v4/100x100bb.jpg"),
        )
        assertTrue(com.spotifree.app.data.remote.YouTubeIds.isLowResVideoThumbnail(null))
        assertTrue(com.spotifree.app.data.remote.YouTubeIds.isLowResVideoThumbnail("https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"))
        assertFalse(com.spotifree.app.data.remote.YouTubeIds.isLowResVideoThumbnail("https://lh3.googleusercontent.com/abc123=w1200-h1200-l90-rj"))
        assertFalse(com.spotifree.app.data.remote.YouTubeIds.isLowResVideoThumbnail("https://is1-ssl.mzstatic.com/image/thumb/Music115/v4/1000x1000bb.jpg"))
    }

    @Test
    fun splitArtistsHandlesSeparators() {
        val artists = com.spotifree.app.data.library.LibraryRepository.splitArtists("Artist A, Artist B & Artist C feat. Artist D")
        assertEquals(listOf("artist a", "artist b", "artist c", "artist d"), artists)
    }

    @Test
    fun searchQueueSeedingFiltersDuplicatesAndBlocked() {
        val seed = com.spotifree.app.data.model.Track(id = "seed-1", title = "Hit Song", artist = "Artist A")
        val blockedId = "blocked-9"
        val existingQueueIds = setOf("seed-1", "other-already-in-queue")
        val searchPool = listOf(
            com.spotifree.app.data.model.Track(id = "seed-1", title = "Hit Song", artist = "Artist A"),
            com.spotifree.app.data.model.Track(id = "other-already-in-queue", title = "Old", artist = "Artist B"),
            com.spotifree.app.data.model.Track(id = blockedId, title = "Blocked Song", artist = "Bad"),
            com.spotifree.app.data.model.Track(id = "rec-1", title = "Similar 1", artist = "Artist C"),
            com.spotifree.app.data.model.Track(id = "rec-2", title = "Similar 2", artist = "Artist D"),
        )
        val filtered = searchPool.filter { it.id !in existingQueueIds && it.id != blockedId }
        assertEquals(listOf("rec-1", "rec-2"), filtered.map { it.id })
    }

    @Test
    fun cleanTrackTitleStripsVideoNoiseButKeepsRemixes() {
        assertEquals(
            "Starlight Horizon",
            com.spotifree.app.data.remote.MusicRepository.cleanTrackTitle("Starlight Horizon (Official Music Video)"),
        )
        assertEquals(
            "Midnight Echoes (Club Remix)",
            com.spotifree.app.data.remote.MusicRepository.cleanTrackTitle("Midnight Echoes (Club Remix) [Official Audio]"),
        )
    }

    @Test
    fun spreadArtistsPreventsConsecutiveSameArtist() {
        val input = listOf(
            com.spotifree.app.data.model.Track(id = "1", title = "Song 1", artist = "Artist Alpha"),
            com.spotifree.app.data.model.Track(id = "2", title = "Song 2", artist = "Artist Alpha feat. Guest"),
            com.spotifree.app.data.model.Track(id = "3", title = "Song 3", artist = "Artist Beta"),
            com.spotifree.app.data.model.Track(id = "4", title = "Song 4", artist = "Artist Gamma"),
        )
        val spread = com.spotifree.app.data.remote.MusicRepository.spreadArtists(input)
        assertEquals(4, spread.size)
        for (i in 0 until spread.size - 1) {
            val a1 = spread[i].artist.substringBefore(" ")
            val a2 = spread[i + 1].artist.substringBefore(" ")
            if (a1 == "Artist") {
                assertFalse("Same primary artist back-to-back at index $i", spread[i].artist.startsWith("Artist Alpha") && spread[i + 1].artist.startsWith("Artist Alpha"))
            }
        }
    }

    @Test
    fun multiSeedHelpersPickDiverseArtistsAndInterleave() {
        val playlist = listOf(
            com.spotifree.app.data.model.Track(id = "en-1", title = "English 1", artist = "English Singer", videoId = "v1"),
            com.spotifree.app.data.model.Track(id = "en-2", title = "English 2", artist = "English Singer", videoId = "v2"),
            com.spotifree.app.data.model.Track(id = "es-1", title = "Spanish 1", artist = "Cantante Latino", videoId = "v3"),
            com.spotifree.app.data.model.Track(id = "fr-1", title = "French 1", artist = "Artiste Francais", videoId = "v4"),
        )
        val seeds = com.spotifree.app.data.remote.MusicRepository.pickDiverseSeeds(playlist, maxSeeds = 3)
        assertEquals(3, seeds.size)
        assertEquals("en-1", seeds.first().id)
        assertEquals(3, seeds.map { it.artist }.distinct().size)

        val interleaved = com.spotifree.app.data.remote.MusicRepository.interleaveLists(
            listOf(listOf("A1", "A2"), listOf("B1", "B2"), listOf("C1")),
        )
        assertEquals(listOf("A1", "B1", "C1", "A2", "B2"), interleaved)
    }

    @Test
    fun karaokeWordInterpolationDistributesTimestampsAcrossLine() {
        val words = com.spotifree.app.ui.player.interpolateWords("one two three four", startSec = 10.0, endSec = 14.0)
        assertEquals(4, words.size)
        assertEquals("one two three four", words.joinToString("") { it.text })
        assertEquals(10.0, words.first().timeSec, 0.001)
        assertTrue(words.zipWithNext().all { (a, b) -> b.timeSec > a.timeSec })
        assertTrue(words.last().timeSec < 14.0)
    }

    @Test
    fun randomLyricsPaletteNeverRepeatsBackToBack() {
        val picks = (1..30).map { com.spotifree.app.ui.player.nextRandomLyricsPalette() }
        assertTrue(picks.zipWithNext().all { (a, b) -> a != b })
        assertTrue(picks.distinct().size >= 3)
    }

    @Test
    fun offlineModeFirewallBlocksHttpAndDnsImmediately() {
        var offline = true
        val client = AppGraph.buildHttpClient(isOffline = { offline })
        try {
            client.newCall(okhttp3.Request.Builder().url("https://example.com").build()).execute()
            fail("HTTP call must be blocked in Offline Mode")
        } catch (_: com.spotifree.app.playback.OfflineModeException) {
            // Expected: blocked before any socket or DNS resolution
        }
        try {
            client.dns.lookup("example.com")
            fail("DNS lookup must be blocked in Offline Mode")
        } catch (_: com.spotifree.app.playback.OfflineModeException) {
            // Expected
        }
    }

    @Test
    fun widgetUpdaterDisablesNetworkInOfflineMode() {
        assertEquals(coil3.request.CachePolicy.DISABLED, com.spotifree.app.widget.WidgetUpdater.networkPolicy(true, "https://i.ytimg.com/vi/abc/hqdefault.jpg"))
        assertEquals(coil3.request.CachePolicy.ENABLED, com.spotifree.app.widget.WidgetUpdater.networkPolicy(false, "https://i.ytimg.com/vi/abc/hqdefault.jpg"))
    }

    @Test
    fun jamClientBlocksConnectAndSendInOfflineMode() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
        val jam = com.spotifree.app.data.jam.JamClient(AppGraph.buildHttpClient { true }, scope) { true }
        jam.create("wss://example.com/jam", "Host")
        assertEquals("offline", jam.state.value.error)
        assertEquals("idle", jam.state.value.status)
    }

    @Test
    fun recognizerSelectsCorrectMediaRecorderConstructorForSdk() {
        assertFalse(com.spotifree.app.data.recognition.Recognizer.useContextMediaRecorder(29))
        assertFalse(com.spotifree.app.data.recognition.Recognizer.useContextMediaRecorder(30))
        assertTrue(com.spotifree.app.data.recognition.Recognizer.useContextMediaRecorder(31))
    }

    @Test
    fun bluetoothResumeReceiverOnlyResumesOnConnectedState() {
        val a2dp = android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED
        val headset = android.bluetooth.BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED
        assertTrue(com.spotifree.app.playback.BluetoothResumeReceiver.shouldResume(a2dp, android.bluetooth.BluetoothProfile.STATE_CONNECTED))
        assertTrue(com.spotifree.app.playback.BluetoothResumeReceiver.shouldResume(headset, android.bluetooth.BluetoothProfile.STATE_CONNECTED))
        assertFalse(com.spotifree.app.playback.BluetoothResumeReceiver.shouldResume(a2dp, android.bluetooth.BluetoothProfile.STATE_DISCONNECTED))
        assertFalse(com.spotifree.app.playback.BluetoothResumeReceiver.shouldResume(a2dp, android.bluetooth.BluetoothProfile.STATE_DISCONNECTING))
        assertFalse(com.spotifree.app.playback.BluetoothResumeReceiver.shouldResume(a2dp, android.bluetooth.BluetoothProfile.STATE_CONNECTING))
        assertFalse(com.spotifree.app.playback.BluetoothResumeReceiver.shouldResume("android.intent.action.VIEW", android.bluetooth.BluetoothProfile.STATE_CONNECTED))
    }
}
