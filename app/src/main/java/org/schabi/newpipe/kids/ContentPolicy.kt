/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import kotlinx.serialization.Serializable

/** A service-qualified ID; IDs from different streaming services must never overlap. */
data class ContentId(val serviceId: Int, val id: String) {
    init {
        require(id.isNotBlank())
    }
}

@Serializable
enum class SourceKind { CHANNEL, PLAYLIST }

data class ContentSource(val kind: SourceKind, val id: ContentId)

/**
 * A snapshot of a profile's approved library. Source membership is supplied by the caller from
 * the last successful refresh; a failed refresh must not replace that snapshot with partial data.
 * Hidden videos override every approval, including overlapping sources.
 */
data class ApprovedLibrary(
    val videos: Set<ContentId> = emptySet(),
    val sources: Set<ContentSource> = emptySet(),
    val sourceVideos: Map<ContentSource, Set<ContentId>> = emptyMap(),
    val hiddenVideos: Set<ContentId> = emptySet(),
    val disabledSources: Set<ContentSource> = emptySet()
) {
    fun canPlay(video: ContentId): Boolean {
        if (video in hiddenVideos) return false
        return video in videos || sources.any { source ->
            source !in disabledSources && video in sourceVideos[source].orEmpty()
        }
    }

    fun playableVideos(): Set<ContentId> {
        val candidates = videos + sources.asSequence()
            .filterNot { it in disabledSources }
            .flatMap { sourceVideos[it].orEmpty().asSequence() }
        return candidates.filterTo(mutableSetOf(), ::canPlay)
    }
}
