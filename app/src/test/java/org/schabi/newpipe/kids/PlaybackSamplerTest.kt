/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackSamplerTest {
    @Test fun `records normal progress but excludes seeks, pauses, and discontinuities`() {
        val sampler = PlaybackSampler()
        assertNull(sampler.sample(0, 100_000, 0, true))
        assertEquals(WatchedRange(0, 1_000), sampler.sample(1_000, 100_000, 1_000, true))
        assertNull(sampler.sample(90_000, 100_000, 2_000, true))
        assertEquals(WatchedRange(90_000, 91_000), sampler.sample(91_000, 100_000, 3_000, false))
        assertNull(sampler.sample(92_000, 100_000, 4_000, true))
        sampler.reset()
        assertNull(sampler.sample(10_000, 100_000, 5_000, true))
        assertNull(sampler.sample(9_000, 100_000, 6_000, true))
    }
}
