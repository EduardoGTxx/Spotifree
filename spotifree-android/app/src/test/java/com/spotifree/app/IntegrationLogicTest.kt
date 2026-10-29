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
    }
}
