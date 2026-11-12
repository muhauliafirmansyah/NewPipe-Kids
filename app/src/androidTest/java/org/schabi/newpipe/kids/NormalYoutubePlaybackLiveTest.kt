/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.upstream.HttpDataSource
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo

@RunWith(AndroidJUnit4::class)
class NormalYoutubePlaybackLiveTest {
    @Test fun unavailableVideoFailsWithoutReturningAStream() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val started = System.currentTimeMillis()
        val result = runCatching { ExtractorVideoResolver.resolve(VideoIdentity.youtube("00000000000")) }
        val elapsed = System.currentTimeMillis() - started
        assertTrue("Unavailable video unexpectedly resolved after $elapsed ms", result.isFailure)
        assertTrue("Unavailable video took $elapsed ms to reject", elapsed < 30_000)
    }

    @Test fun playsOrdinaryYoutubeVideo() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val video = VideoIdentity.youtube("dQw4w9WgXcQ")
        val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=${video.id}")
        val summary = "combined=${info.videoStreams.size}, videoOnly=${info.videoOnlyStreams.size}, audio=${info.audioStreams.size}"
        val resolved = ExtractorVideoResolver.resolve(video)
        val error = AtomicReference<PlaybackException?>()
        var player: ExoPlayer? = null
        try {
            instrumentation.runOnMainSync {
                player = ExoPlayer.Builder(instrumentation.targetContext).build().also { active ->
                    active.addListener(object : Player.Listener {
                        override fun onPlayerError(exception: PlaybackException) {
                            error.set(exception)
                        }
                    })
                    active.setMediaSource(ExtractorVideoResolver.mediaSource(instrumentation.targetContext, resolved))
                    active.prepare()
                    active.play()
                }
            }
            val longRun = InstrumentationRegistry.getArguments().getString("long") == "true"
            val target = if (longRun) 70_000 else 2_000
            val deadline = System.currentTimeMillis() + if (longRun) 120_000 else 30_000
            var position = 0L
            while (position < target && error.get() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(250)
                instrumentation.runOnMainSync { position = requireNotNull(player).currentPosition }
            }
            val failure = error.get()
            var cause: Throwable? = failure
            var status: Int? = null
            while (cause != null) {
                if (cause is HttpDataSource.InvalidResponseCodeException) status = cause.responseCode
                cause = cause.cause
            }
            assertTrue("$summary, separateAudio=${resolved.audioUrl != null}, position=$position, error=${failure?.errorCodeName}, http=$status", position >= target && failure == null)
        } finally {
            instrumentation.runOnMainSync { player?.release() }
        }
    }
}
