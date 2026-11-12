/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

/** Tracks one foreground child session. All methods must run off the main thread. */
class ChildSessionController(private val storage: KidsStorage, private val monotonicMillis: () -> Long) {
    private data class Active(val profileId: String, val revision: Long, val timer: SessionTime)
    private var active: Active? = null

    @Synchronized
    fun resume(profileId: String): Long {
        check(active == null) { "Pause the current session before resuming another" }
        val state = storage.read()
        val profile = state.profiles.firstOrNull { it.id == profileId && it.enabled }
            ?: throw IllegalStateException("Profile unavailable")
        check(state.selectedProfileId == profileId) { "Profile is not selected" }
        val timer = SessionTime.restore(profile.remainingMillis).resume(monotonicMillis())
        active = Active(profileId, profile.sessionRevision, timer)
        return timer.remainingMillis
    }

    /** Persist a checkpoint at regular intervals and before any profile switch or backgrounding. */
    @Synchronized
    fun checkpoint(): Long {
        val running = checkNotNull(active) { "No active session" }
        val now = monotonicMillis()
        var next: Active? = null
        try {
            storage.update { state ->
                val profile = state.profiles.firstOrNull { it.id == running.profileId && it.enabled }
                    ?: throw IllegalStateException("Profile unavailable")
                check(state.selectedProfileId == profile.id) { "Profile is no longer selected" }
                val timer = if (profile.sessionRevision != running.revision) {
                    // A parent changed or reset the limit; do not overwrite that decision.
                    SessionTime.restore(profile.remainingMillis).resume(now)
                } else {
                    running.timer.update(now)
                }
                val remaining = minOf(timer.remainingMillis, profile.remainingMillis)
                next = Active(profile.id, profile.sessionRevision, SessionTime.restore(remaining).resume(now))
                state.copy(
                    profiles = state.profiles.map {
                        if (it.id == profile.id) it.copy(remainingMillis = remaining) else it
                    }
                )
            }
        } catch (error: Exception) {
            active = null
            throw error
        }
        active = checkNotNull(next)
        return checkNotNull(active).timer.remainingMillis
    }

    @Synchronized
    fun pause(): Long {
        val remaining = checkpoint()
        active = null
        return remaining
    }

    /** Forget a timer after a storage failure; child access must stay blocked until a fresh load. */
    @Synchronized
    fun abandon() {
        active = null
    }
}
