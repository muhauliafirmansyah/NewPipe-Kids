/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

/** Refresh each approved source once for all profiles; one failure does not prevent the others. */
class ApprovedSourceRefresher(private val storage: KidsStorage, private val fetcher: SourceVideoFetcher) {
    fun refreshAll(): KidsState {
        val initial = storage.read()
        val groups = initial.profiles.flatMap { profile ->
            profile.sources.map { source -> (source.kind to source.id) to profile.id }
        }.groupBy({ it.first }, { it.second })
        val coordinator = SourceRefreshCoordinator(storage, fetcher)
        groups.forEach { (source, profiles) ->
            // A failed or stale refresh leaves the last complete snapshot untouched.
            runCatching { coordinator.refresh(profiles.toSet(), source.first, source.second) }
        }
        return storage.read()
    }
}
