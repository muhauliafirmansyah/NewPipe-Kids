/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

/** Persistent parent actions. Call on a worker thread: PIN derivation and disk writes may be slow. */
class ParentSettings(
    private val storage: KidsStorage,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    /** Performs a rate-limited PIN check before opening Parent mode. */
    fun authenticate(pin: String): KidsState = authorized(pin) { it }

    /** Creates the PIN and first profile together; neither is saved if validation fails. */
    fun setup(pin: String, id: String, name: String, limitMinutes: Int): KidsState = storage.update {
        check(it.profiles.isEmpty()) { "Profiles already exist" }
        ProfileManager.add(ParentPin.setup(it, pin), id, name, limitMinutes)
    }

    fun addProfile(pin: String, id: String, name: String, limitMinutes: Int): KidsState = authorized(pin) { ProfileManager.add(it, id, name, limitMinutes) }

    fun renameProfile(pin: String, id: String, name: String): KidsState = authorized(pin) { ProfileManager.rename(it, id, name) }

    fun setProfileEnabled(pin: String, id: String, enabled: Boolean): KidsState = authorized(pin) { ProfileManager.setEnabled(it, id, enabled) }

    fun setShowShorts(pin: String, id: String, show: Boolean): KidsState = authorized(pin) { ProfileManager.setShowShorts(it, id, show) }

    fun removeProfile(pin: String, id: String): KidsState = authorized(pin) { ProfileManager.remove(it, id) }

    fun setSessionLimit(pin: String, id: String, minutes: Int): KidsState = authorized(pin) { ProfileManager.setSessionLimit(it, id, minutes) }

    fun resetSession(pin: String, id: String): KidsState = authorized(pin) { ProfileManager.resetSession(it, id) }

    fun setProfileSwitchLock(pin: String, enabled: Boolean): KidsState = authorized(pin) { it.copy(lockProfileSwitching = enabled) }

    fun selectProfile(pin: String, id: String): KidsState = authorized(pin) { state ->
        require(state.profiles.any { it.id == id && it.enabled }) { "Profile unavailable" }
        state.copy(selectedProfileId = id)
    }

    /** Search metadata is only for display; authorization always comes from profile approvals. */
    fun approveSearchItem(pin: String, profiles: Set<String>, item: ParentSearchItem): KidsState = authorized(pin) { state ->
        val approved = when (item.kind) {
            ParentContentKind.VIDEO -> {
                require(VideoIdentity.fromUrl(item.id.serviceId, item.url) == item.id) { "Video ID mismatch" }
                ApprovalManager.approveVideo(state, profiles, item.id)
            }

            ParentContentKind.CHANNEL -> {
                require(SourceIdentity.fromUrl(item.id.serviceId, SourceKind.CHANNEL, item.url) == item.id) { "Channel ID mismatch" }
                ApprovalManager.approveSource(state, profiles, SourceKind.CHANNEL, item.id)
            }

            ParentContentKind.PLAYLIST -> {
                require(SourceIdentity.fromUrl(item.id.serviceId, SourceKind.PLAYLIST, item.url) == item.id) { "Playlist ID mismatch" }
                ApprovalManager.approveSource(state, profiles, SourceKind.PLAYLIST, item.id)
            }
        }
        approved.copy(catalog = KidsCatalog.merge(approved.catalog, listOf(item)))
    }

    fun approveVideo(pin: String, profiles: Set<String>, video: StoredContentId): KidsState = authorized(pin) { ApprovalManager.approveVideo(it, profiles, video) }

    fun removeVideoApproval(pin: String, profiles: Set<String>, video: StoredContentId): KidsState = authorized(pin) { ApprovalManager.removeVideoApproval(it, profiles, video) }

    fun setVideoHidden(pin: String, profiles: Set<String>, video: StoredContentId, hidden: Boolean): KidsState = authorized(pin) { ApprovalManager.setVideoHidden(it, profiles, video, hidden) }

    fun approveSource(pin: String, profiles: Set<String>, kind: SourceKind, id: StoredContentId): KidsState = authorized(pin) { ApprovalManager.approveSource(it, profiles, kind, id) }

    fun removeSource(pin: String, profiles: Set<String>, kind: SourceKind, id: StoredContentId): KidsState = authorized(pin) { ApprovalManager.removeSource(it, profiles, kind, id) }

    fun setChannelPlaylistHidden(pin: String, profiles: Set<String>, playlist: StoredContentId, hidden: Boolean): KidsState = authorized(pin) { ApprovalManager.setChannelPlaylistHidden(it, profiles, playlist, hidden) }

    fun setSourceEnabled(
        pin: String,
        profiles: Set<String>,
        kind: SourceKind,
        id: StoredContentId,
        enabled: Boolean
    ): KidsState = authorized(pin) { ApprovalManager.setSourceEnabled(it, profiles, kind, id, enabled) }

    fun changePin(currentPin: String, newPin: String): KidsState = authorized(currentPin) { ParentPin.change(it, currentPin, newPin) }

    private fun authorized(pin: String, action: (KidsState) -> KidsState): KidsState {
        val now = nowMillis()
        require(now >= 0)
        var failure: String? = null
        val updated = storage.update { state ->
            when {
                now < state.pinLockedUntilMillis -> {
                    failure = "Parent PIN temporarily locked"
                    state
                }

                !ParentPin.verify(state, pin) -> {
                    failure = "Incorrect parent PIN"
                    val attempts = if (state.pinLockedUntilMillis > 0) 1 else state.failedPinAttempts + 1
                    state.copy(
                        failedPinAttempts = attempts,
                        pinLockedUntilMillis = if (attempts >= MAX_ATTEMPTS) now + LOCK_MILLIS else 0
                    )
                }

                else -> action(state.copy(failedPinAttempts = 0, pinLockedUntilMillis = 0))
            }
        }
        require(failure == null) { requireNotNull(failure) }
        return updated
    }

    companion object {
        private const val MAX_ATTEMPTS = 5
        private const val LOCK_MILLIS = 30_000L
    }
}
