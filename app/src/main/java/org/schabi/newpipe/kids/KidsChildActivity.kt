/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.PlayerView
import java.util.Locale
import java.util.concurrent.Executors
import org.schabi.newpipe.R
import org.schabi.newpipe.util.image.CoilHelper

/** Child navigation and foreground session lifecycle. */
class KidsChildActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val network = Executors.newSingleThreadExecutor()
    private val sourceWorker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val storage by lazy { KidsStorage.get(applicationContext) }
    private val sessions by lazy { ChildSessionController(storage, SystemClock::elapsedRealtime) }

    // Accessed on worker only; all session operations are serialized there.
    private var runningProfileId: String? = null
    private lateinit var root: LinearLayout
    private var timeLabel: TextView? = null
    private var visible = false
    private var generation = 0
    private var displayedState: KidsState? = null
    private var renderedHomeState: KidsState? = null
    private var openSource: Pair<SourceKind, StoredContentId>? = null
    private var setupRequested = false
    private var busy = false
    private enum class Screen { HOME, SOURCE, LOADING, PLAYER, MESSAGE }
    private var screen = Screen.HOME
    private var searchText = ""
    private var favoritesOnly = false
    private var playbackGeneration = 0
    private var playback: ActivePlayback? = null
    private var incomingVideo: StoredContentId? = null

    private data class ActivePlayback(
        val profileId: String,
        val video: StoredContentId,
        val playlist: StoredContentId?,
        val unavailable: Set<StoredContentId>,
        val player: ExoPlayer,
        val sampler: PlaybackSampler = PlaybackSampler()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readIncomingIntent(intent)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIncomingIntent(intent)
        displayedState?.let(::consumeIncomingVideo)
    }

    override fun onResume() {
        super.onResume()
        visible = true
        loadState()
    }

    override fun onPause() {
        stopPlayback()
        visible = false
        generation++
        handler.removeCallbacksAndMessages(null)
        worker.execute {
            if (runningProfileId != null) {
                runCatching { sessions.pause() }
                runningProfileId = null
            }
        }
        super.onPause()
    }

    override fun onDestroy() {
        sourceWorker.shutdownNow()
        network.shutdownNow()
        worker.shutdown()
        super.onDestroy()
    }

    @Deprecated("Used for setup completion on supported Android versions")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SETUP_REQUEST && resultCode == RESULT_OK) {
            startActivity(Intent(this, KidsParentActivity::class.java))
        }
    }

    private fun loadState() {
        val ticket = ++generation
        worker.execute {
            val result = runCatching {
                val state = storage.read()
                val profile = state.profiles.firstOrNull { it.id == state.selectedProfileId && it.enabled }
                if (runningProfileId == null && profile != null && profile.remainingMillis > 0) {
                    sessions.resume(profile.id)
                    runningProfileId = profile.id
                }
                state
            }
            runOnUiThread {
                if (!visible || generation != ticket) return@runOnUiThread
                result.fold(onSuccess = { state ->
                    if (state.parentPin == null && state.profiles.isEmpty()) {
                        showSetup()
                    } else if (state.parentPin == null) {
                        showStorageError()
                    } else {
                        display(state)
                        scheduleTick(ticket)
                        scheduleSources(ticket, 0)
                    }
                }, onFailure = { showStorageError() })
            }
        }
    }

    private fun showSetup() {
        root.removeAllViews()
        label(getString(R.string.kids_setup_description))
        button(getString(R.string.kids_setup_title)) { openSetup() }
        if (!setupRequested) openSetup()
    }

    @Suppress("DEPRECATION")
    private fun openSetup() {
        setupRequested = true
        startActivityForResult(Intent(this, KidsSetupActivity::class.java), SETUP_REQUEST)
    }

    private fun display(state: KidsState) {
        screen = Screen.HOME
        openSource = null
        displayedState = state
        renderedHomeState = state
        root.removeAllViews()
        timeLabel = null
        val profile = state.profiles.firstOrNull { it.id == state.selectedProfileId && it.enabled }
        if (profile == null) {
            label(getString(R.string.kids_choose_profile))
            val profiles = ProfileManager.visibleProfiles(state)
            if (profiles.isEmpty()) label(getString(R.string.kids_no_enabled_profiles))
            profiles.forEach { child -> button(child.name) { selectProfile(child.id) } }
            parentButton()
            return
        }
        label(profile.name)
        timeLabel = label("").apply { id = R.id.kids_time_remaining }
        showRemaining(profile.remainingMillis)
        button(getString(R.string.kids_switch_profile)) { chooseProfile(state) }
        parentButton()
        if (profile.remainingMillis <= 0) {
            label(getString(R.string.kids_session_ended)).id = R.id.kids_session_ended
            return
        }
        button(getString(R.string.kids_autoplay_status, getString(if (profile.autoplay) R.string.kids_on else R.string.kids_off))) {
            updateProfile(profile.id, { it.copy(autoplay = !it.autoplay) })
        }
        button(getString(if (favoritesOnly) R.string.kids_show_all else R.string.kids_show_favorites)) {
            favoritesOnly = !favoritesOnly
            display(displayedState ?: state)
        }
        val input = EditText(this).apply {
            hint = getString(R.string.kids_child_search)
            id = R.id.kids_child_search
            setText(searchText)
        }
        root.addView(input)
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(results)
        var shown = RESULT_PAGE_SIZE
        fun showResults(query: String) {
            results.removeAllViews()
            val videos = ChildLibrary.videos(state, profile.id, query).filter { !favoritesOnly || it.id in profile.favorites }
            val queryText = query.trim().lowercase(Locale.ROOT)
            val sources = if (favoritesOnly) {
                emptyList()
            } else {
                profile.sources.filter { source ->
                    val kind = if (source.kind == SourceKind.PLAYLIST) ParentContentKind.PLAYLIST else ParentContentKind.CHANNEL
                    val title = state.catalog.firstOrNull { it.kind == kind && it.id == source.id }?.title ?: source.id.id
                    source.enabled && title.lowercase(Locale.ROOT).contains(queryText)
                }
            }
            sources.forEach { source ->
                val kind = if (source.kind == SourceKind.PLAYLIST) ParentContentKind.PLAYLIST else ParentContentKind.CHANNEL
                val title = state.catalog.firstOrNull { it.kind == kind && it.id == source.id }?.title ?: source.id.id
                val label = if (source.kind == SourceKind.PLAYLIST) R.string.kids_playlist_label else R.string.kids_channel_label
                results.addView(
                    Button(this).apply {
                        text = getString(label, title)
                        setOnClickListener { showSource(state, profile, source) }
                    }
                )
            }
            if (videos.isEmpty() && sources.isEmpty()) {
                results.addView(TextView(this).apply { text = getString(R.string.kids_no_videos) })
            }
            videos.take(shown).forEach { item ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val image = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(96), dp(54))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = null
                }
                CoilHelper.loadThumbnail(image, item.thumbnailUrl)
                row.addView(image)
                row.addView(
                    Button(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        text = item.title
                        tag = "kids-video-${item.id.id}"
                        setOnClickListener { openVideo(item) }
                    }
                )
                results.addView(row)
            }
            if (shown < videos.size) {
                results.addView(
                    Button(this).apply {
                        text = getString(R.string.kids_parent_more)
                        setOnClickListener {
                            shown += RESULT_PAGE_SIZE
                            showResults(query)
                        }
                    }
                )
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchText = s?.toString().orEmpty()
                shown = RESULT_PAGE_SIZE
                showResults(searchText)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        showResults(searchText)
        consumeIncomingVideo(state)
    }

    private fun readIncomingIntent(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
        if (text != null) {
            val url = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE).find(text)?.value?.trimEnd('.', ',', ')', ']')
            incomingVideo = url?.let(VideoIdentity::fromAnyUrl)
        }
    }

    private fun consumeIncomingVideo(state: KidsState) {
        val video = incomingVideo ?: return
        val profileId = state.selectedProfileId ?: return
        incomingVideo = null
        if (runCatching { ChildPlaybackAccess.allowed(state, profileId, video) }.getOrDefault(false)) {
            startVideo(profileId, video, null, emptySet(), automatic = false)
        } else {
            AlertDialog.Builder(this).setMessage(R.string.kids_video_unavailable)
                .setPositiveButton(android.R.string.ok, null).show()
        }
    }

    private fun openVideo(item: ParentSearchItem) {
        val profileId = displayedState?.selectedProfileId ?: return
        startVideo(profileId, item.id, null, emptySet(), automatic = false)
    }

    private fun showSource(state: KidsState, profile: ChildProfile, source: StoredSource, shown: Int = RESULT_PAGE_SIZE) {
        screen = Screen.SOURCE
        openSource = source.kind to source.id
        renderedHomeState = state
        root.removeAllViews()
        timeLabel = label("")
        showRemaining(profile.remainingMillis)
        button(getString(R.string.kids_back_to_library)) { display(state) }
        val kind = if (source.kind == SourceKind.PLAYLIST) ParentContentKind.PLAYLIST else ParentContentKind.CHANNEL
        label(state.catalog.firstOrNull { it.kind == kind && it.id == source.id }?.title ?: source.id.id)
        val library = profile.approvedLibrary()
        val videos = source.videos.filter { library.canPlay(it.toContentId()) }
        if (videos.isEmpty()) label(getString(R.string.kids_no_videos))
        videos.take(shown).forEach { id ->
            val item = state.catalog.firstOrNull { it.kind == ParentContentKind.VIDEO && it.id == id }
            if (item != null) button(item.title) { startVideo(profile.id, id, source.id.takeIf { source.kind == SourceKind.PLAYLIST }, emptySet(), automatic = false) }
        }
        if (videos.size > shown) button(getString(R.string.kids_parent_more)) { showSource(state, profile, source, shown + RESULT_PAGE_SIZE) }
    }

    /** Extraction happens off the UI thread, with an approval check both before and after it. */
    private fun startVideo(profileId: String, video: StoredContentId, playlist: StoredContentId?, unavailable: Set<StoredContentId>, automatic: Boolean) {
        stopPlayback()
        screen = Screen.LOADING
        val ticket = ++playbackGeneration
        root.removeAllViews()
        timeLabel = label("")
        displayedState?.profiles?.firstOrNull { it.id == profileId }?.let { showRemaining(it.remainingMillis) }
        label(getString(R.string.kids_loading_video))
        button(getString(R.string.kids_back_to_library)) { returnToLibrary() }
        worker.execute {
            val permitted = runCatching { ChildPlaybackAccess.allowed(storage.read(), profileId, video) }.getOrDefault(false)
            if (!permitted) {
                runOnUiThread { if (visible && ticket == playbackGeneration) returnToLibrary() }
                return@execute
            }
            network.execute networkTask@{
                val resolved = runCatching { ExtractorVideoResolver.resolve(video) }
                if (worker.isShutdown) return@networkTask
                worker.execute {
                    val state = runCatching { storage.read() }.getOrNull()
                    val stillAllowed = state != null && runCatching { ChildPlaybackAccess.allowed(state, profileId, video) }.getOrDefault(false)
                    runOnUiThread {
                        if (!visible || ticket != playbackGeneration) return@runOnUiThread
                        if (!stillAllowed) {
                            returnToLibrary()
                        } else if (resolved.isFailure) {
                            showUnavailable(profileId, video, playlist, unavailable, automatic)
                        } else {
                            showPlayer(requireNotNull(state), profileId, video, playlist, unavailable, resolved.getOrThrow())
                        }
                    }
                }
            }
        }
    }

    private fun showPlayer(state: KidsState, profileId: String, video: StoredContentId, playlist: StoredContentId?, unavailable: Set<StoredContentId>, resolved: ResolvedVideo) {
        screen = Screen.PLAYER
        displayedState = state
        val profile = state.profiles.first { it.id == profileId }
        val player = ExoPlayer.Builder(this).build()
        val active = ActivePlayback(profileId, video, playlist, unavailable, player)
        playback = active
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                saveSample(active)
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                active.sampler.reset()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playback === active && playbackState == Player.STATE_ENDED) {
                    saveSample(active)
                    findNext(profileId, video, playlist, unavailable, manual = false)
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (playback === active) {
                    stopPlayback()
                    showUnavailable(profileId, video, playlist, unavailable, automatic = true)
                }
            }
        })
        root.removeAllViews()
        timeLabel = label("")
        showRemaining(profile.remainingMillis)
        label(state.catalog.firstOrNull { it.kind == ParentContentKind.VIDEO && it.id == video }?.title ?: video.id)
        root.addView(
            PlayerView(this).apply {
                id = R.id.kids_player_view
                this.player = player
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230))
            }
        )
        val autoplay = button(autoplayText(profile.autoplay)) { }
        autoplay.setOnClickListener {
            updateProfile(profileId, { it.copy(autoplay = !it.autoplay) }) { updated ->
                displayedState = updated
                val enabled = updated.profiles.first { it.id == profileId }.autoplay
                autoplay.text = autoplayText(enabled)
            }
        }
        val favorite = button(favoriteText(video in profile.favorites)) { }
        favorite.setOnClickListener {
            updateProfile(profileId, { current ->
                require(current.approvedLibrary().canPlay(video.toContentId()))
                current.copy(favorites = if (video in current.favorites) current.favorites - video else current.favorites + video)
            }) { updated ->
                displayedState = updated
                favorite.text = favoriteText(video in updated.profiles.first { it.id == profileId }.favorites)
            }
        }
        if (playlist != null) {
            button(getString(R.string.kids_next_video)) {
                saveSample(active)
                findNext(profileId, video, playlist, unavailable, manual = true)
            }
        }
        button(getString(R.string.kids_back_to_library)) { returnToLibrary() }
        player.setMediaSource(ExtractorVideoResolver.mediaSource(this, resolved))
        player.prepare()
        player.play()
    }

    private fun autoplayText(enabled: Boolean) = getString(R.string.kids_autoplay_status, getString(if (enabled) R.string.kids_on else R.string.kids_off))
    private fun favoriteText(favorite: Boolean) = getString(if (favorite) R.string.kids_remove_favorite else R.string.kids_add_favorite)

    private fun saveSample(active: ActivePlayback) {
        if (playback !== active) return
        val player = active.player
        val duration = player.duration
        val range = active.sampler.sample(player.currentPosition, duration, SystemClock.elapsedRealtime(), player.isPlaying) ?: return
        worker.execute {
            runCatching {
                storage.update { state ->
                    if (!ChildPlaybackAccess.allowed(state, active.profileId, active.video)) return@update state
                    state.copy(
                        profiles = state.profiles.map { profile ->
                            if (profile.id == active.profileId) {
                                KidsPlayback.recordPlaybackInterval(profile, active.video, range.startMillis, range.endMillis, duration, System.currentTimeMillis())
                            } else {
                                profile
                            }
                        }
                    )
                }
            }
        }
    }

    private fun stopPlayback() {
        playbackGeneration++
        val active = playback ?: return
        saveSample(active)
        playback = null
        active.player.release()
    }

    private fun returnToLibrary() {
        stopPlayback()
        worker.execute {
            val state = runCatching { storage.read() }.getOrNull()
            runOnUiThread {
                if (!visible) return@runOnUiThread
                if (state == null) showStorageError() else display(state)
            }
        }
    }

    private fun findNext(profileId: String, current: StoredContentId, playlist: StoredContentId?, unavailable: Set<StoredContentId>, manual: Boolean) {
        val ticket = playbackGeneration
        worker.execute {
            val next = runCatching {
                val state = storage.read()
                val profile = state.profiles.firstOrNull { it.id == profileId && it.enabled }
                if (state.selectedProfileId != profileId || profile == null || profile.remainingMillis <= 0 || (!manual && !profile.autoplay)) {
                    null
                } else {
                    val source = profile.sources.firstOrNull { it.kind == SourceKind.PLAYLIST && it.id == playlist && it.enabled }
                    KidsPlayback.nextAfter(profile, source, current, ChildLibrary.videos(state, profileId).map { it.id }, unavailable, manual)
                }
            }.getOrNull()
            runOnUiThread {
                if (!visible || ticket != playbackGeneration) return@runOnUiThread
                if (next != null) {
                    startVideo(profileId, next.video, next.playlist, unavailable, automatic = true)
                } else if (playback == null) {
                    showUnavailableScreen()
                }
            }
        }
    }

    private fun showUnavailable(profileId: String, video: StoredContentId, playlist: StoredContentId?, unavailable: Set<StoredContentId>, automatic: Boolean) {
        Toast.makeText(this, R.string.kids_video_unavailable, Toast.LENGTH_SHORT).show()
        if (automatic) findNext(profileId, video, playlist, unavailable + video, manual = false) else showUnavailableScreen()
    }

    private fun showUnavailableScreen() {
        screen = Screen.MESSAGE
        root.removeAllViews()
        label(getString(R.string.kids_video_unavailable))
        button(getString(R.string.kids_back_to_library)) { returnToLibrary() }
    }

    private fun updateProfile(id: String, transform: (ChildProfile) -> ChildProfile, onSuccess: (KidsState) -> Unit = ::display) {
        worker.execute {
            val result = runCatching {
                storage.update { state ->
                    require(state.selectedProfileId == id)
                    val profile = state.profiles.first { it.id == id && it.enabled && it.remainingMillis > 0 }
                    state.copy(profiles = state.profiles.map { if (it.id == id) transform(profile) else it })
                }
            }
            runOnUiThread {
                if (!visible) return@runOnUiThread
                result.fold(onSuccess = onSuccess, onFailure = { showStorageError() })
            }
        }
    }

    private fun chooseProfile(state: KidsState) {
        val profiles = ProfileManager.visibleProfiles(state)
        if (profiles.isEmpty()) return
        AlertDialog.Builder(this).setTitle(R.string.kids_choose_profile)
            .setItems(profiles.map { it.name }.toTypedArray()) { _, index ->
                val child = profiles[index]
                if (state.lockProfileSwitching && child.id != state.selectedProfileId) {
                    requestSwitchPin(child.id)
                } else {
                    selectProfile(child.id)
                }
            }.show()
    }

    private fun requestSwitchPin(id: String) {
        val input = EditText(this).apply {
            hint = getString(R.string.kids_parent_pin_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            isSaveEnabled = false
        }
        AlertDialog.Builder(this).setTitle(R.string.kids_parent_title).setView(input)
            .setNegativeButton(R.string.kids_parent_cancel, null)
            .setPositiveButton(R.string.kids_parent_unlock) { _, _ ->
                val pin = input.text.toString()
                input.text.clear()
                selectProfile(id, pin)
            }.show()
    }

    private fun selectProfile(id: String, pin: String? = null) {
        if (busy) return
        busy = true
        val ticket = ++generation
        handler.removeCallbacksAndMessages(null)
        worker.execute {
            val result = runCatching {
                val previous = runningProfileId
                if (previous != null) {
                    sessions.pause()
                    runningProfileId = null
                }
                try {
                    val state = ChildProfileSelector(storage).select(id, pin)
                    val next = state.profiles.first { it.id == id }
                    if (next.remainingMillis > 0) {
                        sessions.resume(id)
                        runningProfileId = id
                    }
                    state
                } catch (error: Exception) {
                    if (previous != null) {
                        sessions.resume(previous)
                        runningProfileId = previous
                    }
                    throw error
                }
            }
            runOnUiThread {
                busy = false
                if (!visible || generation != ticket) return@runOnUiThread
                result.fold(onSuccess = {
                    if (displayedState?.selectedProfileId != id) {
                        searchText = ""
                        favoritesOnly = false
                    }
                    display(it)
                }, onFailure = {
                    AlertDialog.Builder(this).setMessage(R.string.kids_parent_unlock_error)
                        .setPositiveButton(android.R.string.ok, null).show()
                })
                scheduleTick(ticket)
                scheduleSources(ticket, 0)
            }
        }
    }

    private fun scheduleTick(ticket: Int) {
        handler.postDelayed({
            if (!visible || generation != ticket) return@postDelayed
            playback?.let(::saveSample)
            worker.execute {
                val result = runCatching {
                    if (runningProfileId != null) sessions.checkpoint()
                    storage.read()
                }
                if (result.isFailure) {
                    sessions.abandon()
                    runningProfileId = null
                }
                runOnUiThread {
                    if (!visible || generation != ticket) return@runOnUiThread
                    result.fold(onSuccess = { state ->
                        displayedState = state
                        val profile = state.profiles.firstOrNull { it.id == state.selectedProfileId && it.enabled }
                        val active = playback
                        if (profile == null || profile.remainingMillis <= 0 ||
                            (active != null && !ChildPlaybackAccess.allowed(state, active.profileId, active.video))
                        ) {
                            stopPlayback()
                            display(state)
                        } else {
                            showRemaining(profile.remainingMillis)
                        }
                        scheduleTick(ticket)
                    }, onFailure = { showStorageError() })
                }
            }
        }, 1_000)
    }

    private fun scheduleSources(ticket: Int, delay: Long) {
        handler.postDelayed({
            if (!visible || generation != ticket) return@postDelayed
            sourceWorker.execute {
                val state = runCatching { ApprovedSourceRefresher(storage, ExtractorSourceFetcher()).refreshAll() }.getOrNull()
                runOnUiThread {
                    if (!visible || generation != ticket) return@runOnUiThread
                    if (state != null) {
                        val old = renderedHomeState
                        displayedState = state
                        val active = playback
                        if (active != null && !ChildPlaybackAccess.allowed(state, active.profileId, active.video)) {
                            stopPlayback()
                            display(state)
                        } else if (screen == Screen.HOME && old != null &&
                            (old.catalog != state.catalog || old.profiles.map { it.sources } != state.profiles.map { it.sources })
                        ) {
                            display(state)
                        } else if (screen == Screen.SOURCE && old != null &&
                            (old.catalog != state.catalog || old.profiles.map { it.sources } != state.profiles.map { it.sources })
                        ) {
                            val profile = state.profiles.firstOrNull { it.id == state.selectedProfileId && it.enabled }
                            val source = profile?.sources?.firstOrNull { (it.kind to it.id) == openSource && it.enabled }
                            if (profile == null || source == null || profile.remainingMillis <= 0) display(state) else showSource(state, profile, source)
                        }
                    }
                    scheduleSources(ticket, SOURCE_REFRESH_MILLIS)
                }
            }
        }, delay)
    }

    private fun showRemaining(millis: Long) {
        val seconds = (millis + 999) / 1_000
        val clock = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
        val minutes = displayedState?.profiles?.firstOrNull { it.id == displayedState?.selectedProfileId }?.sessionLimitMinutes ?: 1
        val warningMillis = minOf(60_000L, maxOf(10_000L, minutes * 6_000L))
        timeLabel?.text = if (millis in 1..warningMillis) {
            getString(R.string.kids_session_warning, clock)
        } else {
            getString(R.string.kids_session_remaining, clock)
        }
    }

    private fun showStorageError() {
        stopPlayback()
        root.removeAllViews()
        label(getString(R.string.kids_storage_error))
        button(getString(R.string.kids_try_again)) { loadState() }
    }

    private fun parentButton() = button(getString(R.string.kids_parent_title)) {
        startActivity(Intent(this, KidsParentActivity::class.java))
    }

    private fun label(text: String): TextView = TextView(this).also {
        it.text = text
        root.addView(it)
    }

    private fun button(text: String, action: () -> Unit): Button = Button(this).also {
        it.text = text
        it.setOnClickListener { if (!busy) action() }
        root.addView(it)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val SETUP_REQUEST = 1
        private const val SOURCE_REFRESH_MILLIS = 15 * 60_000L
        private const val RESULT_PAGE_SIZE = 40
    }
}
