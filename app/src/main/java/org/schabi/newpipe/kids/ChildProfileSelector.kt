/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

/** Persists the selected profile. Call on a worker thread because storage may write to disk. */
class ChildProfileSelector(private val storage: KidsStorage) {
    fun select(id: String, parentPin: String? = null): KidsState {
        if (parentPin != null) return ParentSettings(storage).selectProfile(parentPin, id)
        return storage.update { state ->
            require(state.profiles.any { it.id == id && it.enabled }) { "Profile unavailable" }
            require(!state.lockProfileSwitching || state.selectedProfileId == null || state.selectedProfileId == id) {
                "Parent authorization required"
            }
            state.copy(selectedProfileId = id)
        }
    }
}
