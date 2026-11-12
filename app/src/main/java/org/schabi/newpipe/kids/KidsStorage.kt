/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A service-qualified content identifier, stored as received from the service. */
@Serializable
data class StoredContentId(val serviceId: Int, val id: String) {
    init {
        require(id.isNotBlank())
    }

    fun toContentId(): ContentId = ContentId(serviceId, id)
}

@Serializable
data class StoredSource(
    val kind: SourceKind,
    val id: StoredContentId,
    val enabled: Boolean = true,
    // Replaced only after a complete successful refresh; order preserves playlist order.
    val videos: List<StoredContentId> = emptyList()
)

@Serializable
data class StoredWatch(val video: StoredContentId, val lastWatchedAtMillis: Long, val watched: Boolean)

@Serializable
data class ChildProfile(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val sessionLimitMinutes: Int,
    val remainingMillis: Long,
    val approvedVideos: Set<StoredContentId> = emptySet(),
    val sources: List<StoredSource> = emptyList(),
    val hiddenVideos: Set<StoredContentId> = emptySet(),
    val favorites: Set<StoredContentId> = emptySet(),
    val history: List<StoredWatch> = emptyList(),
    val autoplay: Boolean = true
) {
    init {
        require(id.isNotBlank() && name.isNotBlank())
        require(sessionLimitMinutes in SessionTime.MIN_MINUTES..SessionTime.MAX_MINUTES)
        require(remainingMillis in 0..sessionLimitMinutes * 60_000L)
        require(sources.map { it.kind to it.id }.distinct().size == sources.size)
        require(history.map { it.video }.distinct().size == history.size)
    }

    fun approvedLibrary(): ApprovedLibrary {
        val entries = sources.associate { source ->
            ContentSource(source.kind, source.id.toContentId()) to source
        }
        return ApprovedLibrary(
            videos = approvedVideos.mapTo(mutableSetOf()) { it.toContentId() },
            sources = entries.keys,
            sourceVideos = entries.mapValues { (_, source) ->
                source.videos.mapTo(mutableSetOf()) { it.toContentId() }
            },
            hiddenVideos = hiddenVideos.mapTo(mutableSetOf()) { it.toContentId() },
            disabledSources = entries.filterValues { !it.enabled }.keys
        )
    }
}

@Serializable
data class KidsState(
    val version: Int = 1,
    val profiles: List<ChildProfile> = emptyList(),
    val lockProfileSwitching: Boolean = false,
    val parentPin: ParentPinCredential? = null
) {
    init {
        require(version == 1) { "Unsupported kids storage version: $version" }
        require(profiles.map { it.id }.distinct().size == profiles.size)
    }
}

/** Rejects malformed or unsupported state instead of silently replacing the library. */
object KidsStateCodec {
    private val json = Json { encodeDefaults = true }

    fun encode(state: KidsState): String = json.encodeToString(state)
    fun decode(value: String): KidsState = json.decodeFromString(value)
}

/** Atomic local snapshot. All mutations must go through update to avoid lost writes. */
class KidsStorage internal constructor(private val preferences: SharedPreferences) {

    @Synchronized
    fun read(): KidsState = preferences.getString(KEY, null)?.let(KidsStateCodec::decode)
        ?: KidsState()

    @Synchronized
    fun update(transform: (KidsState) -> KidsState): KidsState {
        val updated = transform(read())
        check(preferences.edit().putString(KEY, KidsStateCodec.encode(updated)).commit()) {
            "Could not save kids state"
        }
        return updated
    }

    companion object {
        private const val KEY = "state"

        @Volatile private var instance: KidsStorage? = null

        fun get(context: Context): KidsStorage = instance ?: synchronized(this) {
            instance ?: KidsStorage(
                context.applicationContext.getSharedPreferences("kids_state", Context.MODE_PRIVATE)
            ).also { instance = it }
        }
    }
}
