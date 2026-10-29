package com.spotifree.app.workout

import android.content.Intent
import com.spotifree.app.data.prefs.AppSettings
import java.time.LocalDate

/**
 * Contract with the TGym app (it connects only when the user enables "Integración con Spotifree").
 * Same events TGym already emits on its BroadcastChannel ("tgym_sync_bus"), sent to Spotifree as
 * an Android broadcast so they cross app boundaries:
 *
 *   action  com.spotifree.app.action.TGYM_REST   (explicit package com.spotifree.app)
 *   type    "REST_START" | "REST_END" | "WORKOUT_FINISH"
 *   REST_START      seconds (Int), endsAt (Long, epoch ms), total (Int), exerciseName (String)
 *   WORKOUT_FINISH  date ("yyyy-MM-dd", TGym's workout.d), durationMs (Long)
 *
 * Older senders that only put the boolean extra "active" are still understood.
 */
sealed interface TGymEvent {
    data class RestStart(val endsAt: Long, val seconds: Int, val total: Int, val exerciseName: String) : TGymEvent
    data object RestEnd : TGymEvent
    data class WorkoutFinish(val date: LocalDate, val durationMs: Long) : TGymEvent
}

object TGymBridge {
    const val ACTION = "com.spotifree.app.action.TGYM_REST"

    /** Pure parsing of the broadcast extras (unit tested). */
    fun parse(
        type: String?,
        active: Boolean?,
        seconds: Int,
        endsAt: Long,
        total: Int,
        exerciseName: String?,
        date: String?,
        durationMs: Long,
        now: Long = System.currentTimeMillis(),
    ): TGymEvent? = when (type?.uppercase() ?: if (active == true) "REST_START" else if (active == false) "REST_END" else null) {
        "REST_START" -> {
            val sec = seconds.coerceAtLeast(0)
            val end = if (endsAt > 0) endsAt else now + sec * 1000L
            if (end <= now) TGymEvent.RestEnd
            else TGymEvent.RestStart(end, sec, if (total > 0) total else sec, exerciseName.orEmpty())
        }
        "REST_END" -> TGymEvent.RestEnd
        "WORKOUT_FINISH" -> TGymEvent.WorkoutFinish(
            date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now(),
            durationMs.coerceAtLeast(0),
        )
        else -> null
    }

    fun parse(intent: Intent): TGymEvent? = parse(
        type = intent.getStringExtra("type"),
        active = if (intent.hasExtra("active")) intent.getBooleanExtra("active", false) else null,
        seconds = intent.getIntExtra("seconds", 0),
        endsAt = intent.getLongExtra("endsAt", 0L),
        total = intent.getIntExtra("total", 0),
        exerciseName = intent.getStringExtra("exerciseName"),
        date = intent.getStringExtra("date"),
        durationMs = intent.getLongExtra("durationMs", 0L),
    )
}

/** Consecutive-days training streak (shared by the in-app rest timer and TGym's "workout finished"). */
object WorkoutStreak {
    fun mark(s: AppSettings, day: LocalDate): AppSettings {
        val last = s.lastWorkoutDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        return when {
            last == null -> s.copy(workoutStreakDays = 1, lastWorkoutDate = day.toString())
            !day.isAfter(last) -> s // same day or an older workout: nothing changes
            day == last.plusDays(1) -> s.copy(workoutStreakDays = s.workoutStreakDays + 1, lastWorkoutDate = day.toString())
            else -> s.copy(workoutStreakDays = 1, lastWorkoutDate = day.toString())
        }
    }

    /** Streak shown today: it is still alive if the last workout was today or yesterday. */
    fun current(s: AppSettings, today: LocalDate = LocalDate.now()): Int {
        val last = s.lastWorkoutDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return 0
        return if (last == today || last == today.minusDays(1)) s.workoutStreakDays else 0
    }
}
