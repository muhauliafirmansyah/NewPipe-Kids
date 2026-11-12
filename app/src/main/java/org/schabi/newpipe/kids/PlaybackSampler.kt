/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

/** Converts player samples into actual played intervals without counting seeks or pauses. */
class PlaybackSampler {
    private data class Sample(val position: Long, val duration: Long, val time: Long, val playing: Boolean)
    private var previous: Sample? = null

    fun reset() {
        previous = null
    }

    fun sample(position: Long, duration: Long, monotonicMillis: Long, playing: Boolean): WatchedRange? {
        if (position < 0 || duration <= 0 || position > duration || monotonicMillis < 0) {
            reset()
            return null
        }
        val current = Sample(position, duration, monotonicMillis, playing)
        val before = previous
        previous = current
        if (before == null || !before.playing || before.duration != duration) return null
        val elapsed = monotonicMillis - before.time
        val advanced = position - before.position
        if (elapsed < 0 || advanced <= 0 || advanced > elapsed + SEEK_TOLERANCE_MILLIS) return null
        return WatchedRange(before.position, position)
    }

    companion object {
        private const val SEEK_TOLERANCE_MILLIS = 250L
    }
}
