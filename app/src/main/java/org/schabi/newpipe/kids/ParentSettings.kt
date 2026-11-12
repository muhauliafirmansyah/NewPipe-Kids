/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

/** Persistent parent actions. Call on a worker thread: PIN derivation and disk writes may be slow. */
class ParentSettings(private val storage: KidsStorage) {
    /** Creates the PIN and first profile together; neither is saved if validation fails. */
    fun setup(pin: String, id: String, name: String, limitMinutes: Int): KidsState = storage.update {
        check(it.profiles.isEmpty()) { "Profiles already exist" }
        ProfileManager.add(ParentPin.setup(it, pin), id, name, limitMinutes)
    }

    fun addProfile(pin: String, id: String, name: String, limitMinutes: Int): KidsState = authorized(pin) { ProfileManager.add(it, id, name, limitMinutes) }

    fun renameProfile(pin: String, id: String, name: String): KidsState = authorized(pin) { ProfileManager.rename(it, id, name) }

    fun setProfileEnabled(pin: String, id: String, enabled: Boolean): KidsState = authorized(pin) { ProfileManager.setEnabled(it, id, enabled) }

    fun removeProfile(pin: String, id: String): KidsState = authorized(pin) { ProfileManager.remove(it, id) }

    fun setSessionLimit(pin: String, id: String, minutes: Int): KidsState = authorized(pin) { ProfileManager.setSessionLimit(it, id, minutes) }

    fun resetSession(pin: String, id: String): KidsState = authorized(pin) { ProfileManager.resetSession(it, id) }

    fun setProfileSwitchLock(pin: String, enabled: Boolean): KidsState = authorized(pin) { it.copy(lockProfileSwitching = enabled) }

    fun approveVideo(pin: String, profiles: Set<String>, video: StoredContentId): KidsState = authorized(pin) { ApprovalManager.approveVideo(it, profiles, video) }

    fun removeVideoApproval(pin: String, profiles: Set<String>, video: StoredContentId): KidsState = authorized(pin) { ApprovalManager.removeVideoApproval(it, profiles, video) }

    fun setVideoHidden(pin: String, profiles: Set<String>, video: StoredContentId, hidden: Boolean): KidsState = authorized(pin) { ApprovalManager.setVideoHidden(it, profiles, video, hidden) }

    fun approveSource(pin: String, profiles: Set<String>, kind: SourceKind, id: StoredContentId): KidsState = authorized(pin) { ApprovalManager.approveSource(it, profiles, kind, id) }

    fun removeSource(pin: String, profiles: Set<String>, kind: SourceKind, id: StoredContentId): KidsState = authorized(pin) { ApprovalManager.removeSource(it, profiles, kind, id) }

    fun setSourceEnabled(
        pin: String,
        profiles: Set<String>,
        kind: SourceKind,
        id: StoredContentId,
        enabled: Boolean
    ): KidsState = authorized(pin) { ApprovalManager.setSourceEnabled(it, profiles, kind, id, enabled) }

    fun changePin(currentPin: String, newPin: String): KidsState = storage.update { ParentPin.change(it, currentPin, newPin) }

    private fun authorized(pin: String, action: (KidsState) -> KidsState): KidsState = storage.update { state ->
        require(ParentPin.verify(state, pin)) { "Incorrect parent PIN" }
        action(state)
    }
}
