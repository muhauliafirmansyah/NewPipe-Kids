/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
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
    val videos: List<StoredContentId> = emptyList(),
    val activeRefreshToken: String? = null
)

@Serializable
data class WatchedRange(val startMillis: Long, val endMillis: Long) {
    init {
        require(startMillis >= 0 && endMillis > startMillis)
    }
}

@Serializable
data class StoredWatch(
    val video: StoredContentId,
    val lastWatchedAtMillis: Long,
    val watched: Boolean,
    val durationMillis: Long = 0,
    val playedRanges: List<WatchedRange> = emptyList()
) {
    init {
        require(lastWatchedAtMillis >= 0 && durationMillis >= 0)
        require(playedRanges.all { it.endMillis <= durationMillis })
    }
}

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
    val autoplay: Boolean = true,
    val sessionRevision: Long = 0
) {
    init {
        require(id.isNotBlank() && name.isNotBlank())
        require(sessionLimitMinutes in SessionTime.MIN_MINUTES..SessionTime.MAX_MINUTES)
        require(remainingMillis in 0..sessionLimitMinutes * 60_000L)
        require(sessionRevision >= 0)
        require(sources.map { it.kind to it.id }.distinct().size == sources.size)
        require(history.map { it.video }.distinct().size == history.size)
    }

    fun approvedLibrary(): ApprovedLibrary {
        val entries = sources.associate { source ->
            ContentSource(source.kind, source.id.toContentId()) to source
        }
        return ApprovedLibrary(
            videos = approvedVideos.mapTo(mutableSetOf()) { VideoIdentity.canonicalize(it).toContentId() },
            sources = entries.keys,
            sourceVideos = entries.mapValues { (_, source) ->
                source.videos.mapTo(mutableSetOf()) { VideoIdentity.canonicalize(it).toContentId() }
            },
            hiddenVideos = hiddenVideos.mapTo(mutableSetOf()) { VideoIdentity.canonicalize(it).toContentId() },
            disabledSources = entries.filterValues { !it.enabled }.keys
        )
    }
}

@Serializable
data class KidsState(
    val version: Int = 1,
    val profiles: List<ChildProfile> = emptyList(),
    val lockProfileSwitching: Boolean = false,
    val parentPin: ParentPinCredential? = null,
    val failedPinAttempts: Int = 0,
    val pinLockedUntilMillis: Long = 0,
    val catalog: List<ParentSearchItem> = emptyList(),
    val selectedProfileId: String? = null
) {
    init {
        require(version == 1) { "Unsupported kids storage version: $version" }
        require(profiles.map { it.id }.distinct().size == profiles.size)
        require(failedPinAttempts >= 0 && pinLockedUntilMillis >= 0)
        require(catalog.map { it.kind to it.id }.distinct().size == catalog.size)
        require(selectedProfileId == null || profiles.any { it.id == selectedProfileId })
    }
}

/** Rejects malformed or unsupported state instead of silently replacing the library. */
object KidsStateCodec {
    private val json = Json { encodeDefaults = true }

    fun encode(state: KidsState): String = json.encodeToString(state)
    fun decode(value: String): KidsState = json.decodeFromString(value)
}

private interface KidsStateBackend {
    fun read(): String?
    fun write(value: String)
}

private class PreferencesBackend(private val preferences: SharedPreferences) : KidsStateBackend {
    override fun read(): String? = preferences.getString("state", null)
    override fun write(value: String) {
        check(preferences.edit().putString("state", value).commit()) { "Could not save kids state" }
    }
}

/** SQLite commits each snapshot atomically; a failed write is not exposed as a successful update. */
private class DatabaseBackend(context: Context, name: String, private val legacy: SharedPreferences?) :
    SQLiteOpenHelper(context, name, null, 1), KidsStateBackend {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE kids_state (id INTEGER PRIMARY KEY CHECK (id = 1), payload TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported kids database upgrade: $oldVersion to $newVersion")
    }

    override fun read(): String? {
        val stored = readableDatabase.rawQuery("SELECT payload FROM kids_state WHERE id = 1", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        if (stored != null) return stored
        val previous = legacy?.getString("state", null) ?: return null
        // Do not migrate malformed state or silently replace a previously configured library.
        KidsStateCodec.decode(previous)
        write(previous)
        legacy.edit().remove("state").commit()
        return previous
    }

    override fun write(value: String) {
        val values = ContentValues().apply {
            put("id", 1)
            put("payload", value)
        }
        check(writableDatabase.insertWithOnConflict("kids_state", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) {
            "Could not save kids state"
        }
    }
}

/** Atomic local snapshot. All mutations must go through update to avoid lost writes. */
class KidsStorage private constructor(private val backend: KidsStateBackend) {
    // Kept for isolated unit tests; production uses the transactional SQLite backend.
    internal constructor(preferences: SharedPreferences) : this(PreferencesBackend(preferences))

    @Synchronized
    fun read(): KidsState = backend.read()?.let(KidsStateCodec::decode) ?: KidsState()

    @Synchronized
    fun update(transform: (KidsState) -> KidsState): KidsState {
        val updated = transform(read())
        backend.write(KidsStateCodec.encode(updated))
        return updated
    }

    internal fun closeForTests() {
        (backend as? DatabaseBackend)?.close()
    }

    companion object {
        @Volatile private var instance: KidsStorage? = null

        fun get(context: Context): KidsStorage = instance ?: synchronized(this) {
            instance ?: database(
                context.applicationContext,
                "kids_state.db",
                context.applicationContext.getSharedPreferences("kids_state", Context.MODE_PRIVATE)
            ).also { instance = it }
        }

        internal fun database(context: Context, name: String, legacy: SharedPreferences? = null): KidsStorage = KidsStorage(DatabaseBackend(context.applicationContext, name, legacy))
    }
}
