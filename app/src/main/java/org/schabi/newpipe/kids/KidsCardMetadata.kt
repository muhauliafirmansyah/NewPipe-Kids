/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import java.util.Locale

internal data class PlaylistCardPreview(val visibleCount: Int?, val thumbnailUrl: String?)

/** Card metadata never makes a hidden or otherwise unavailable playlist video visible. */
internal object KidsCardMetadata {
    fun playlistPreview(
        source: StoredSource,
        library: ApprovedLibrary,
        catalog: Map<StoredContentId, ParentSearchItem>
    ): PlaylistCardPreview {
        if (source.kind != SourceKind.PLAYLIST || !source.enabled || source.videos.isEmpty()) {
            return PlaylistCardPreview(null, null)
        }
        var count = 0
        var thumbnail: String? = null
        source.videos.forEach { id ->
            val item = catalog[id]
            if (item != null && library.canPlay(id.toContentId())) {
                count++
                if (thumbnail == null) thumbnail = item.thumbnailUrl
            }
        }
        return PlaylistCardPreview(count, thumbnail)
    }

    fun duration(seconds: Long?): String? {
        if (seconds == null || seconds <= 0) return null
        val hours = seconds / 3_600
        val minutes = seconds % 3_600 / 60
        val remaining = seconds % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, remaining)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, remaining)
        }
    }
}
