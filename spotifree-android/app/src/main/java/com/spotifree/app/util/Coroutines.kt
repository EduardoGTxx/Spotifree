package com.spotifree.app.util

import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException

/**
 * Like [runCatching] but never swallows [CancellationException]: a cancelled coroutine (screen
 * closed, job replaced) must stop instead of carrying on with a "failed" result.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}

/** Delay before retry number [attempt] (0-based): initial, initial*factor, … capped at [maxMs]. */
fun backoffDelayMs(attempt: Int, initialMs: Long = 1_000, factor: Double = 2.0, maxMs: Long = 30_000): Long {
    var d = initialMs.toDouble()
    repeat(attempt.coerceAtLeast(0)) { d *= factor }
    return d.toLong().coerceAtMost(maxMs)
}

/**
 * Runs [block] up to [times] times, waiting with exponential backoff between failures.
 * Cancellation is never retried. Returns the last failure when every attempt failed.
 */
suspend fun <T> retryWithBackoff(
    times: Int,
    initialMs: Long = 1_000,
    factor: Double = 2.0,
    maxMs: Long = 30_000,
    shouldRetry: (Throwable) -> Boolean = { true },
    block: suspend (attempt: Int) -> T,
): Result<T> {
    var last: Throwable? = null
    for (attempt in 0 until times) {
        val r = runCatchingCancellable { block(attempt) }
        if (r.isSuccess) return r
        last = r.exceptionOrNull()
        if (last != null && !shouldRetry(last)) break
        if (attempt < times - 1) delay(backoffDelayMs(attempt, initialMs, factor, maxMs))
    }
    return Result.failure(last ?: IllegalStateException("no attempts"))
}
