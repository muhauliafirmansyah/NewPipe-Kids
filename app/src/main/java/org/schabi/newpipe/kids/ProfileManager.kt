/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

/** Profile operations. Callers must authorize parent actions before invoking these functions. */
object ProfileManager {
    fun add(state: KidsState, id: String, name: String, limitMinutes: Int): KidsState {
        require(state.profiles.none { it.id == id }) { "Profile already exists" }
        val session = SessionTime.newSession(limitMinutes)
        val profile = ChildProfile(
            id = id,
            name = name.trim(),
            sessionLimitMinutes = limitMinutes,
            remainingMillis = session.remainingMillis
        )
        return state.copy(profiles = state.profiles + profile)
    }

    fun rename(state: KidsState, id: String, name: String): KidsState = change(state, id) { it.copy(name = name.trim()) }

    fun setEnabled(state: KidsState, id: String, enabled: Boolean): KidsState = change(state, id) { it.copy(enabled = enabled) }

    fun setShowShorts(state: KidsState, id: String, show: Boolean): KidsState = change(state, id) { it.copy(showShorts = show) }

    fun remove(state: KidsState, id: String): KidsState {
        require(state.profiles.any { it.id == id }) { "Profile not found" }
        return state.copy(
            profiles = state.profiles.filterNot { it.id == id },
            selectedProfileId = state.selectedProfileId.takeUnless { it == id }
        )
    }

    fun visibleProfiles(state: KidsState): List<ChildProfile> = state.profiles.filter { it.enabled }

    /** Changing the limit cannot silently add time to an existing session. */
    fun setSessionLimit(state: KidsState, id: String, minutes: Int): KidsState {
        val limit = SessionTime.newSession(minutes).remainingMillis
        return change(state, id) { profile ->
            profile.copy(
                sessionLimitMinutes = minutes,
                remainingMillis = minOf(profile.remainingMillis, limit),
                sessionRevision = nextSessionRevision(profile)
            )
        }
    }

    /** A parent action; starts a full session using the profile's current limit. */
    fun resetSession(state: KidsState, id: String): KidsState = change(state, id) { profile ->
        profile.copy(
            remainingMillis = SessionTime.newSession(profile.sessionLimitMinutes).remainingMillis,
            sessionRevision = nextSessionRevision(profile)
        )
    }

    private fun change(state: KidsState, id: String, transform: (ChildProfile) -> ChildProfile): KidsState {
        require(state.profiles.any { it.id == id }) { "Profile not found" }
        return state.copy(profiles = state.profiles.map { if (it.id == id) transform(it) else it })
    }

    private fun nextSessionRevision(profile: ChildProfile): Long {
        check(profile.sessionRevision < Long.MAX_VALUE) { "Session revision exhausted" }
        return profile.sessionRevision + 1
    }
}
