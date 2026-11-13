/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

/** Fetches all pages of a source or throws; a partial response must not be returned as complete. */
fun interface SourceVideoFetcher {
    fun fetchAll(kind: SourceKind, id: StoredContentId): List<ParentSearchItem>
}

/** Coordinates source refreshes against the latest stored state. Run on a worker thread. */
class SourceRefreshCoordinator(private val storage: KidsStorage, private val fetcher: SourceVideoFetcher) {
    fun refresh(profileIds: Set<String>, kind: SourceKind, id: StoredContentId): KidsState {
        lateinit var ticket: SourceRefreshTicket
        storage.update { current ->
            val started = ApprovalManager.beginSourceRefresh(current, profileIds, kind, id)
            ticket = started.ticket
            started.state
        }
        // Never change the last successful snapshot if fetching fails.
        val videos = fetcher.fetchAll(kind, id)
        videos.forEach {
            require(it.kind == ParentContentKind.VIDEO && it.id == VideoIdentity.fromUrl(it.id.serviceId, it.url)) {
                "Invalid source video"
            }
        }
        return storage.update { current ->
            val updated = ApprovalManager.completeSourceRefresh(current, ticket, videos.map { it.id })
            updated.copy(catalog = KidsCatalog.merge(updated.catalog, videos))
        }
    }
}
