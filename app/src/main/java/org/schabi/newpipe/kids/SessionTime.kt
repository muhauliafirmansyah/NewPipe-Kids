/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

/** Remaining active-use time for one profile, measured with a monotonic clock. */
data class SessionTime private constructor(
    val remainingMillis: Long,
    private val activeSinceMillis: Long?
) {
    val exhausted: Boolean get() = remainingMillis == 0L

    fun resume(nowMillis: Long): SessionTime = if (activeSinceMillis != null || exhausted) {
        this
    } else {
        copy(activeSinceMillis = nowMillis)
    }

    /** Update before saving, switching profiles, backgrounding, or checking the time limit. */
    fun update(nowMillis: Long): SessionTime {
        val started = activeSinceMillis ?: return this
        val elapsed = (nowMillis - started).coerceAtLeast(0)
        val remaining = (remainingMillis - elapsed).coerceAtLeast(0)
        return SessionTime(remaining, if (remaining == 0L) null else maxOf(nowMillis, started))
    }

    fun pause(nowMillis: Long): SessionTime = update(nowMillis).copy(activeSinceMillis = null)

    companion object {
        const val MIN_MINUTES = 1
        const val MAX_MINUTES = 120

        fun newSession(minutes: Int): SessionTime {
            require(minutes in MIN_MINUTES..MAX_MINUTES)
            return SessionTime(minutes * 60_000L, null)
        }

        /** Restored sessions are paused; time spent outside the app does not count. */
        fun restore(remainingMillis: Long): SessionTime {
            require(remainingMillis in 0..MAX_MINUTES * 60_000L)
            return SessionTime(remainingMillis, null)
        }
    }
}
