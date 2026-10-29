package com.spotifree.app.data.lyrics

/**
 * Pure rules behind the synced-lyrics view (kept out of Compose so they can be unit tested).
 */
object LyricsSync {
    /** A playback clock sample that lands this little behind what is on screen is jitter, not a seek. */
    const val BACKWARD_TOLERANCE_MS = 300L

    /** After the user scrolls the lyrics by hand, auto-follow waits this long before taking over again. */
    const val USER_SCROLL_HOLD_MS = 3_500L

    /**
     * Next value of the smoothed playback position shown by the lyrics.
     *
     * [target] is the controller sample plus the time interpolated since it arrived. While playing,
     * a target slightly behind the value already on screen (the interpolation ran a few ms ahead of
     * the next sample) keeps the screen value, so karaoke words never flicker backwards. A bigger
     * difference is a real seek and is followed at once.
     */
    fun smooth(displayed: Long, target: Long, playing: Boolean): Long {
        if (!playing) return target
        val behind = displayed - target
        return if (behind in 1 until BACKWARD_TOLERANCE_MS) displayed else target
    }

    /** Whether auto-scroll may move the list now. */
    fun mayAutoScroll(nowMs: Long, dragging: Boolean, lastUserScrollAtMs: Long): Boolean =
        !dragging && (lastUserScrollAtMs <= 0L || nowMs - lastUserScrollAtMs >= USER_SCROLL_HOLD_MS)

    /** Milliseconds left before auto-scroll may resume (0 when it already may). */
    fun holdRemaining(nowMs: Long, lastUserScrollAtMs: Long): Long =
        if (lastUserScrollAtMs <= 0L) 0L else (USER_SCROLL_HOLD_MS - (nowMs - lastUserScrollAtMs)).coerceAtLeast(0L)
}
