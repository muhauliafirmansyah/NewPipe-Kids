/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

data class NextPlayback(val video: StoredContentId, val playlist: StoredContentId?)

/** Playback decisions for a single profile; the caller still enforces access before playback. */
object KidsPlayback {
    /** Records a continuous interval actually played. Seeking and repeated segments do not add coverage. */
    fun recordPlaybackInterval(profile: ChildProfile, video: StoredContentId, startMillis: Long, endMillis: Long, durationMillis: Long, nowMillis: Long): ChildProfile {
        val canonical = VideoIdentity.canonicalize(video)
        require(startMillis >= 0 && endMillis > startMillis && endMillis <= durationMillis && nowMillis >= 0)
        require(profile.approvedLibrary().canPlay(canonical.toContentId())) { "Video not approved" }
        val previous = profile.history.firstOrNull { VideoIdentity.canonicalize(it.video) == canonical }
        val oldRanges = previous?.takeIf { it.durationMillis == durationMillis }?.playedRanges.orEmpty()
        val merged = mutableListOf<WatchedRange>()
        (oldRanges + WatchedRange(startMillis, endMillis)).sortedBy { it.startMillis }.forEach { range ->
            val last = merged.lastOrNull()
            if (last != null && range.startMillis <= last.endMillis) {
                merged[merged.lastIndex] = WatchedRange(last.startMillis, maxOf(last.endMillis, range.endMillis))
            } else {
                merged.add(range)
            }
        }
        val playedMillis = merged.sumOf { it.endMillis - it.startMillis }
        val watched = previous?.watched == true || playedMillis >= durationMillis - durationMillis / 10
        val entry = StoredWatch(canonical, nowMillis, watched, durationMillis, merged)
        return profile.copy(history = profile.history.filterNot { VideoIdentity.canonicalize(it.video) == canonical } + entry)
    }

    /** Selects the next allowed item after the current position, preserving playlist order. */
    fun nextInPlaylist(profile: ChildProfile, playlist: List<StoredContentId>, currentIndex: Int, unavailable: Set<StoredContentId> = emptySet()): StoredContentId? {
        require(currentIndex in -1 until playlist.size)
        val library = profile.approvedLibrary()
        val unavailableIds = unavailable.mapTo(mutableSetOf()) { VideoIdentity.canonicalize(it) }
        return playlist.drop(currentIndex + 1).asSequence().map { VideoIdentity.canonicalize(it) }
            .firstOrNull { it !in unavailableIds && library.canPlay(it.toContentId()) }
    }

    /** Returns null when autoplay is disabled or no approved, available video remains. */
    fun nextAutoplay(profile: ChildProfile, candidates: List<StoredContentId>, unavailable: Set<StoredContentId> = emptySet()): StoredContentId? {
        if (!profile.autoplay) return null
        val library = profile.approvedLibrary()
        val unavailableIds = unavailable.mapTo(mutableSetOf()) { VideoIdentity.canonicalize(it) }
        val history = profile.history.associateBy { VideoIdentity.canonicalize(it.video) }
        val allowed = candidates.map { VideoIdentity.canonicalize(it) }.distinct()
            .filter { it !in unavailableIds && library.canPlay(it.toContentId()) }
        return allowed.firstOrNull { history[it]?.watched != true }
            ?: allowed.minByOrNull { history[it]?.lastWatchedAtMillis ?: Long.MIN_VALUE }
    }

    /** Keep playlist context only while advancing in its order; leave it after the last item. */
    fun nextAfter(profile: ChildProfile, playlist: StoredSource?, current: StoredContentId, candidates: List<StoredContentId>, unavailable: Set<StoredContentId>, manual: Boolean): NextPlayback? {
        if (!manual && !profile.autoplay) return null
        val source = playlist?.takeIf { it.kind == SourceKind.PLAYLIST && it.enabled }
        val index = source?.videos?.indexOf(current) ?: -1
        val ordered = if (source != null && index >= 0) nextInPlaylist(profile, source.videos, index, unavailable) else null
        if (ordered != null) return NextPlayback(ordered, source?.id)
        if (manual) return null
        return nextAutoplay(profile, candidates, unavailable)?.let { NextPlayback(it, null) }
    }
}
