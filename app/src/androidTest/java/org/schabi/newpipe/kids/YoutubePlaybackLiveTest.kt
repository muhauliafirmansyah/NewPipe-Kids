/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt in with -e live true; add -e long true for one minute, and -e fallback true to exercise that client directly. */
@RunWith(AndroidJUnit4::class)
class YoutubePlaybackLiveTest {
    @Test fun playsReportedKidsVideo() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val video = VideoIdentity.youtube("e_04ZrNroTo")
        val stream = if (InstrumentationRegistry.getArguments().getString("fallback") == "true") {
            ResolvedVideo(YoutubePlaybackFallback.resolve(video.id))
        } else {
            ExtractorVideoResolver.resolve(video)
        }
        val error = AtomicReference<PlaybackException?>()
        var player: ExoPlayer? = null
        try {
            instrumentation.runOnMainSync {
                val activePlayer = ExoPlayer.Builder(instrumentation.targetContext).build()
                player = activePlayer
                activePlayer.addListener(object : Player.Listener {
                    override fun onPlayerError(exception: PlaybackException) {
                        error.set(exception)
                    }
                })
                activePlayer.setMediaSource(ExtractorVideoResolver.mediaSource(instrumentation.targetContext, stream))
                activePlayer.prepare()
                activePlayer.play()
            }
            val longRun = InstrumentationRegistry.getArguments().getString("long") == "true"
            val target = if (longRun) 70_000 else 2_000
            val deadline = System.currentTimeMillis() + if (longRun) 120_000 else 30_000
            var position = 0L
            while (position < target && error.get() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(250)
                instrumentation.runOnMainSync { position = requireNotNull(player).currentPosition }
            }
            assertTrue("Playback stopped at $position ms: ${error.get()?.errorCodeName}", position >= target && error.get() == null)
        } finally {
            instrumentation.runOnMainSync { player?.release() }
        }
    }
}
