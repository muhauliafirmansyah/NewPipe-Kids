/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.schabi.newpipe.extractor.ServiceList

class VideoIdentityTest {
    @Test fun `youtube links and IDs resolve to the same case-sensitive identity`() {
        val id = VideoIdentity.youtube("e_04ZrNroTo")
        assertEquals(id, VideoIdentity.youtube("https://m.youtube.com/watch?v=e_04ZrNroTo"))
        assertEquals(id, VideoIdentity.youtube("https://www.youtube.com/watch?v=e_04ZrNroTo&t=42"))
        assertEquals(id, VideoIdentity.youtube("https://youtu.be/e_04ZrNroTo"))
        assertEquals(id, VideoIdentity.youtube("https://youtube.com/shorts/e_04ZrNroTo"))
        assertFalse(id == VideoIdentity.youtube("E_04ZrNroTo"))
    }

    @Test fun `a hidden ID overrides an approved video obtained from another URL`() {
        val approved = VideoIdentity.youtube("https://youtu.be/e_04ZrNroTo")
        val hidden = VideoIdentity.youtube("https://m.youtube.com/watch?v=e_04ZrNroTo")
        val library = ApprovedLibrary(
            videos = setOf(approved.toContentId()),
            hiddenVideos = setOf(hidden.toContentId())
        )
        assertFalse(library.canPlay(approved.toContentId()))
    }

    @Test fun `parent approvals, hides, and playback lookups normalize different links`() {
        val mobile = StoredContentId(0, "https://m.youtube.com/watch?v=e_04ZrNroTo")
        val short = StoredContentId(0, "https://youtu.be/e_04ZrNroTo")
        var state = ProfileManager.add(KidsState(), "a", "A", 5)
        state = ApprovalManager.approveVideo(state, setOf("a"), mobile)
        val library = state.profiles.single().approvedLibrary()
        assertEquals(setOf(VideoIdentity.youtube("e_04ZrNroTo").toContentId()), library.playableVideos())
        assertEquals(true, library.canPlay(short.toContentId()))
        state = ApprovalManager.setVideoHidden(state, setOf("a"), short, true)
        assertFalse(state.profiles.single().approvedLibrary().canPlay(mobile.toContentId()))
        state = ApprovalManager.removeVideoApproval(state, setOf("a"), short)
        assertEquals(emptySet<StoredContentId>(), state.profiles.single().approvedVideos)
    }

    @Test fun `previously stored URL aliases are normalized when loading a library`() {
        val profile = ChildProfile(
            "a",
            "A",
            sessionLimitMinutes = 5,
            remainingMillis = 300_000,
            approvedVideos = setOf(StoredContentId(0, "https://youtu.be/e_04ZrNroTo")),
            hiddenVideos = setOf(StoredContentId(0, "https://m.youtube.com/watch?v=e_04ZrNroTo"))
        )
        assertFalse(profile.approvedLibrary().canPlay(VideoIdentity.youtube("e_04ZrNroTo").toContentId()))
    }

    @Test fun `invalid and unrelated URLs cannot become video identities`() {
        for (value in listOf("", "not-video", "https://example.com/watch?v=e_04ZrNroTo")) {
            try {
                VideoIdentity.youtube(value)
                throw AssertionError("Accepted invalid video input: $value")
            } catch (_: Exception) {
                // An invalid input must not produce an identity.
            }
        }
    }

    @Test fun `peertube identities include the instance and normalize supported paths`() {
        val service = ServiceList.PeerTube.serviceId
        val id = "12345678-1234-1234-1234-123456789abc"
        val short = VideoIdentity.fromUrl(service, "https://video.example/w/$id")
        val long = VideoIdentity.fromUrl(service, "https://video.example/videos/watch/$id")
        val otherHost = VideoIdentity.fromUrl(service, "https://other.example/w/$id")
        assertEquals(short, long)
        assertFalse(short == otherHost)
    }

    @Test fun `media ccc URLs must come from the real service`() {
        val service = ServiceList.MediaCCC.serviceId
        val video = VideoIdentity.fromUrl(service, "https://media.ccc.de/v/test-video")
        assertEquals(video, VideoIdentity.canonicalize(video))
        try {
            VideoIdentity.fromUrl(service, "https://example.org/media.ccc.de/v/test-video")
            throw AssertionError("Accepted an unrelated host")
        } catch (_: Exception) {
            // Identities from an unrelated host must never be treated as an approval.
        }
    }

    @Test fun `shared links resolve to their own supported service`() {
        val peer = "https://video.example/w/12345678-1234-1234-1234-123456789abc"
        val ccc = "https://media.ccc.de/v/test-video"
        assertEquals(VideoIdentity.fromUrl(ServiceList.PeerTube.serviceId, peer), VideoIdentity.fromAnyUrl(peer))
        assertEquals(VideoIdentity.fromUrl(ServiceList.MediaCCC.serviceId, ccc), VideoIdentity.fromAnyUrl(ccc))
        assertEquals(null, VideoIdentity.fromAnyUrl("https://example.org/watch?v=e_04ZrNroTo"))
    }
}
