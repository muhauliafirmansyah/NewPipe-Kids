/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import org.junit.Assert.assertEquals
import org.junit.Test

class YoutubePlaybackFallbackTest {
    private val id = "e_04ZrNroTo"
    private val url = "https://video.googlevideo.com/videoplayback?example=1"

    private fun response(videoId: String = id, streamUrl: String = url, status: String = "OK", audio: Boolean = true): String = """{"playabilityStatus":{"status":"$status"},"videoDetails":{"videoId":"$videoId"},"streamingData":{"formats":[{"mimeType":"video/mp4","url":"$streamUrl"${if (audio) ",\"audioQuality\":\"AUDIO_QUALITY_LOW\"" else ""}}]}}"""

    @Test fun `accepts a combined stream for the requested video`() {
        assertEquals(url, YoutubePlaybackFallback.parse(id, response()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a different video`() {
        YoutubePlaybackFallback.parse(id, response(videoId = "dQw4w9WgXcQ"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a non-googlevideo stream host`() {
        YoutubePlaybackFallback.parse(id, response(streamUrl = "https://example.org/videoplayback"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects unavailable or video-only results`() {
        YoutubePlaybackFallback.parse(id, response(audio = false))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects an unavailable response`() {
        YoutubePlaybackFallback.parse(id, response(status = "UNPLAYABLE"))
    }
}
