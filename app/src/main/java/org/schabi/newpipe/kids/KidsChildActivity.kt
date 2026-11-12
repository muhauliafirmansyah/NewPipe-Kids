/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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
    private lateinit var header: LinearLayout
    private lateinit var navigation: LinearLayout
    private lateinit var contentScroll: ScrollView
    private var timeLabel: TextView? = null
    private var visible = false
    private var generation = 0
    private var displayedState: KidsState? = null
    private var renderedHomeState: KidsState? = null
    private var openSource: Pair<SourceKind, StoredContentId>? = null
    private var setupRequested = false
    private var busy = false
    private enum class Screen { HOME, SOURCE, LOADING, PLAYER, MESSAGE }
    private enum class Tab { HOME, SEARCH, STUFF, PROFILE }
    private enum class Category { ALL, VIDEOS, PLAYLISTS, CHANNELS }
    private var screen = Screen.HOME
    private var activeTab = Tab.HOME
    private var category = Category.ALL
    private var searchText = ""
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
        activeTab = runCatching { Tab.valueOf(savedInstanceState?.getString("kids_tab").orEmpty()) }.getOrDefault(Tab.HOME)
        category = runCatching { Category.valueOf(savedInstanceState?.getString("kids_category").orEmpty()) }.getOrDefault(Category.ALL)
        searchText = savedInstanceState?.getString("kids_search").orEmpty()
        readIncomingIntent(intent)
        val chrome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = KidsUi.background()
        }
        header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(if (isLandscape()) 4 else 9), dp(16), dp(if (isLandscape()) 4 else 9))
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(20))
        }
        contentScroll = ScrollView(this).apply {
            id = R.id.kids_content_scroll
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(root)
        }
        navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.WHITE)
            elevation = dp(8).toFloat()
        }
        chrome.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        chrome.addView(contentScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        chrome.addView(navigation, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (isLandscape()) 56 else 68)))
        setContentView(chrome)
        renderHeader(null)
        navigation.visibility = View.GONE
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!handleBack()) {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("kids_tab", activeTab.name)
        outState.putString("kids_category", category.name)
        outState.putString("kids_search", searchText)
        super.onSaveInstanceState(outState)
    }

    private fun handleBack(): Boolean = when {
        screen == Screen.PLAYER || screen == Screen.LOADING || screen == Screen.MESSAGE -> {
            returnToLibrary()
            true
        }

        screen == Screen.SOURCE && displayedState != null -> {
            contentScroll.scrollTo(0, 0)
            display(requireNotNull(displayedState))
            true
        }

        activeTab != Tab.HOME -> {
            navigate(Tab.HOME)
            true
        }

        else -> false
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
        renderHeader(null)
        navigation.visibility = View.GONE
        heading(getString(R.string.kids_setup_title), getString(R.string.kids_setup_description))
        button(getString(R.string.kids_setup_title)) { openSetup() }
        if (!setupRequested) openSetup()
    }

    @Suppress("DEPRECATION")
    private fun openSetup() {
        setupRequested = true
        startActivityForResult(Intent(this, KidsSetupActivity::class.java), SETUP_REQUEST)
    }

    private fun display(state: KidsState) {
        val restoreSearchFocus = screen == Screen.HOME && (activeTab == Tab.SEARCH || activeTab == Tab.STUFF) &&
            root.findViewById<EditText>(R.id.kids_child_search)?.hasFocus() == true
        screen = Screen.HOME
        openSource = null
        displayedState = state
        renderedHomeState = state
        root.removeAllViews()
        val profile = state.profiles.firstOrNull { it.id == state.selectedProfileId && it.enabled }
        renderHeader(profile)
        if (profile == null) {
            navigation.visibility = View.GONE
            showProfilePicker(state)
            return
        }
        renderNavigation()
        if (profile.remainingMillis <= 0) {
            showSessionEnded(state)
            return
        }
        when (activeTab) {
            Tab.HOME -> showHome(state, profile)
            Tab.SEARCH -> showSearch(state, profile, favorites = false)
            Tab.STUFF -> showSearch(state, profile, favorites = true)
            Tab.PROFILE -> showChildProfile(state, profile)
        }
        if (restoreSearchFocus) {
            root.findViewById<EditText>(R.id.kids_child_search)?.let { input ->
                input.requestFocus()
                input.setSelection(input.text.length)
            }
        }
        consumeIncomingVideo(state)
    }

    private fun renderHeader(profile: ChildProfile?) {
        header.removeAllViews()
        header.addView(KidsUi.logo(this), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        timeLabel = null
        if (profile != null) {
            timeLabel = KidsUi.text(this, "", 12f, KidsUi.ink, true).apply {
                id = R.id.kids_time_remaining
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = KidsUi.shape(this@KidsChildActivity, Color.WHITE, 14)
            }
            header.addView(timeLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            showRemaining(profile.remainingMillis)
        }
        val parent = KidsUi.button(this, "", KidsUi.ButtonStyle.SOFT).apply {
            id = R.id.kids_parent_entry
            contentDescription = getString(R.string.kids_parent_entry_description)
            gravity = Gravity.CENTER
            val inset = if (isLandscape()) 8 else 12
            setPadding(dp(inset), dp(inset), dp(inset), dp(inset))
            KidsUi.icon(this, R.drawable.ic_kids_lock, KidsUi.ink)
            setOnClickListener { if (!busy) startActivity(Intent(this@KidsChildActivity, KidsParentActivity::class.java)) }
        }
        val size = if (isLandscape()) 40 else 48
        header.addView(parent, LinearLayout.LayoutParams(dp(size), dp(size)).apply { marginStart = dp(8) })
    }

    private fun renderNavigation() {
        navigation.removeAllViews()
        navigation.visibility = View.VISIBLE
        val tabs = listOf(
            Triple(Tab.HOME, R.string.kids_nav_home, R.drawable.ic_home),
            Triple(Tab.SEARCH, R.string.kids_nav_search, R.drawable.ic_search),
            Triple(Tab.STUFF, R.string.kids_nav_stuff, R.drawable.ic_kids_star),
            Triple(Tab.PROFILE, R.string.kids_nav_profile, R.drawable.ic_person)
        )
        tabs.forEach { (tab, title, icon) ->
            val selected = activeTab == tab
            val item = KidsUi.button(this, getString(title), KidsUi.ButtonStyle.GHOST).apply {
                id = when (tab) {
                    Tab.HOME -> R.id.kids_nav_home
                    Tab.SEARCH -> R.id.kids_nav_search
                    Tab.STUFF -> R.id.kids_nav_stuff
                    Tab.PROFILE -> R.id.kids_nav_profile
                }
                gravity = Gravity.CENTER
                textSize = 11f
                setPadding(dp(2), dp(5), dp(2), dp(4))
                setTextColor(if (selected) KidsUi.ink else KidsUi.muted)
                contentDescription = getString(R.string.kids_nav_description, getString(title))
                isSelected = selected
                if (tab == Tab.PROFILE) {
                    KidsUi.profileIcon(this, displayedState?.selectedProfileId.orEmpty(), top = true)
                } else {
                    KidsUi.icon(this, icon, if (selected) KidsUi.purple else KidsUi.muted, top = true, size = 24)
                }
                setOnClickListener { navigate(tab) }
            }
            navigation.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun navigate(tab: Tab) {
        if (busy) return
        (currentFocus as? EditText)?.let { input ->
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
            input.clearFocus()
        }
        stopPlayback()
        activeTab = tab
        category = Category.ALL
        searchText = ""
        contentScroll.scrollTo(0, 0)
        displayedState?.let(::display)
    }

    private fun showProfilePicker(state: KidsState) {
        heading(getString(R.string.kids_choose_profile), getString(R.string.kids_profile_picker_caption))
        val profiles = ProfileManager.visibleProfiles(state)
        if (profiles.isEmpty()) emptyCard(getString(R.string.kids_no_enabled_profiles))
        profiles.forEach { profile ->
            val choice = KidsUi.button(this, profile.name).apply {
                KidsUi.profileIcon(this, profile.id)
                setOnClickListener {
                    if (state.lockProfileSwitching && state.selectedProfileId != null && state.selectedProfileId != profile.id) {
                        requestSwitchPin(profile.id)
                    } else {
                        selectProfile(profile.id)
                    }
                }
            }
            addBlock(root, choice, 10)
        }
        parentButton()
    }

    private fun showSessionEnded(state: KidsState) {
        heading(getString(R.string.kids_profile_heading), getString(R.string.kids_profile_caption))
        emptyCard(getString(R.string.kids_session_ended), R.id.kids_session_ended)
        button(getString(R.string.kids_switch_profile)) { chooseProfile(state) }
        parentButton()
    }

    private fun showHome(state: KidsState, profile: ChildProfile) {
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            Category.ALL to R.string.kids_category_all,
            Category.VIDEOS to R.string.kids_category_videos,
            Category.PLAYLISTS to R.string.kids_category_playlists,
            Category.CHANNELS to R.string.kids_category_channels
        ).forEach { (value, title) ->
            val chip = KidsUi.button(this, getString(title), if (category == value) KidsUi.ButtonStyle.SELECTED else KidsUi.ButtonStyle.SURFACE).apply {
                gravity = Gravity.CENTER
                isSelected = category == value
                minimumHeight = dp(if (isLandscape()) 38 else 44)
                setOnClickListener {
                    category = value
                    display(displayedState ?: state)
                }
            }
            chips.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(if (isLandscape()) 38 else 44)).apply { marginEnd = dp(8) })
        }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }
        addBlock(root, scroll, 2)
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        addBlock(root, results, 8)
        renderLibrary(state, profile, results, "", category, favorites = false)
    }

    private fun showSearch(state: KidsState, profile: ChildProfile, favorites: Boolean) {
        heading(
            getString(if (favorites) R.string.kids_stuff_heading else R.string.kids_search_heading),
            getString(if (favorites) R.string.kids_stuff_caption else R.string.kids_search_caption)
        )
        val input = EditText(this).apply {
            id = R.id.kids_child_search
            hint = getString(R.string.kids_child_search)
            setSingleLine(true)
            setText(searchText)
            KidsUi.input(this)
        }
        addBlock(root, input, 18)
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        addBlock(root, results, 10)
        var shown = RESULT_PAGE_SIZE
        fun update(query: String) {
            renderLibrary(state, profile, results, query, Category.ALL, favorites, shown) { next ->
                shown = next
                update(query)
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchText = s?.toString().orEmpty()
                shown = RESULT_PAGE_SIZE
                update(searchText)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        update(searchText)
    }

    private fun showChildProfile(state: KidsState, profile: ChildProfile) {
        heading(getString(R.string.kids_profile_heading), getString(R.string.kids_profile_caption))
        val name = profile.name.trim()
        val initial = if (name.isEmpty()) "?" else name.substring(0, name.offsetByCodePoints(0, 1)).uppercase(Locale.ROOT)
        val avatar = KidsUi.text(this, initial, 34f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = KidsUi.shape(this@KidsChildActivity, KidsUi.profileColor(profile.id), 100)
            contentDescription = profile.name
        }
        root.addView(
            avatar,
            LinearLayout.LayoutParams(dp(80), dp(80)).apply {
                topMargin = dp(22)
                bottomMargin = dp(12)
            }
        )
        addBlock(root, KidsUi.text(this, profile.name, 23f, KidsUi.ink, true), 0)
        button(getString(R.string.kids_switch_profile)) { chooseProfile(state) }
        button(autoplayText(profile.autoplay)) { updateProfile(profile.id, { it.copy(autoplay = !it.autoplay) }) }
        parentButton()
    }

    private fun renderLibrary(
        state: KidsState,
        profile: ChildProfile,
        target: LinearLayout,
        query: String,
        filter: Category,
        favorites: Boolean,
        shown: Int = RESULT_PAGE_SIZE,
        onMore: ((Int) -> Unit)? = null
    ) {
        target.removeAllViews()
        val queryText = query.trim().lowercase(Locale.ROOT)
        val sources = if (favorites || filter == Category.VIDEOS) {
            emptyList()
        } else {
            profile.sources.filter { source ->
                val kind = if (source.kind == SourceKind.PLAYLIST) ParentContentKind.PLAYLIST else ParentContentKind.CHANNEL
                val title = state.catalog.firstOrNull { it.kind == kind && it.id == source.id }?.title ?: source.id.id
                val kindMatches = filter == Category.ALL || (filter == Category.PLAYLISTS && source.kind == SourceKind.PLAYLIST) ||
                    (filter == Category.CHANNELS && source.kind == SourceKind.CHANNEL)
                source.enabled && kindMatches && title.lowercase(Locale.ROOT).contains(queryText)
            }
        }
        val videos = if (filter == Category.PLAYLISTS || filter == Category.CHANNELS) {
            emptyList()
        } else {
            ChildLibrary.videos(state, profile.id, query).filter { !favorites || it.id in profile.favorites }
        }
        val featuredCount = if (activeTab == Tab.HOME && filter == Category.ALL && sources.isNotEmpty()) {
            minOf(videos.size, if (isLandscape() && resources.configuration.screenWidthDp >= 600) 2 else 1)
        } else {
            0
        }
        val videosHeading = when {
            favorites -> getString(R.string.kids_show_favorites)
            activeTab == Tab.SEARCH -> getString(R.string.kids_category_videos)
            else -> getString(R.string.kids_videos_heading)
        }
        if (featuredCount > 0) {
            if (!isLandscape()) section(target, videosHeading)
            addVideoGrid(target, videos.take(featuredCount)) { item -> openVideo(item) }
        }
        if (sources.isNotEmpty()) {
            section(target, getString(R.string.kids_collections_heading))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            sources.forEach { source ->
                val kind = if (source.kind == SourceKind.PLAYLIST) ParentContentKind.PLAYLIST else ParentContentKind.CHANNEL
                val title = state.catalog.firstOrNull { it.kind == kind && it.id == source.id }?.title ?: source.id.id
                val label = if (source.kind == SourceKind.PLAYLIST) R.string.kids_playlist_label else R.string.kids_channel_label
                val tile = KidsUi.button(this, getString(label, title)).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    KidsUi.icon(this, if (source.kind == SourceKind.PLAYLIST) R.drawable.ic_playlist_play else R.drawable.ic_person, KidsUi.purple)
                    setOnClickListener { showSource(state, profile, source) }
                }
                row.addView(tile, LinearLayout.LayoutParams(dp(190), dp(70)).apply { marginEnd = dp(10) })
            }
            val collections = HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            }
            addBlock(target, collections, 10)
        }
        if (videos.isNotEmpty()) {
            if (featuredCount == 0 && !isLandscape()) section(target, videosHeading)
            addVideoGrid(target, videos.take(shown).drop(featuredCount)) { item -> openVideo(item) }
            if (shown < videos.size) {
                val more = KidsUi.button(this, getString(R.string.kids_parent_more), KidsUi.ButtonStyle.SOFT).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        if (onMore != null) onMore(shown + RESULT_PAGE_SIZE) else renderLibrary(state, profile, target, query, filter, favorites, shown + RESULT_PAGE_SIZE)
                    }
                }
                addBlock(target, more, 12)
            }
        }
        if (sources.isEmpty() && videos.isEmpty()) {
            val message = when {
                favorites && query.isBlank() -> R.string.kids_favorites_empty
                query.isNotBlank() -> R.string.kids_no_matches
                query.isBlank() && filter == Category.ALL -> R.string.kids_home_empty
                else -> R.string.kids_no_videos
            }
            addEmptyCard(target, getString(message))
        }
    }

    private fun addVideoGrid(target: LinearLayout, videos: List<ParentSearchItem>, action: (ParentSearchItem) -> Unit) {
        val columns = if (resources.configuration.screenWidthDp >= 600) 2 else 1
        val available = resources.configuration.screenWidthDp - 32 - (columns - 1) * 12
        val compact = isLandscape() && resources.configuration.screenHeightDp < 420
        val imageHeight = if (compact) 82 else (available / columns * 9 / 16).coerceIn(130, 250)
        videos.chunked(columns).forEach { items ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            items.forEachIndexed { index, item ->
                if (index > 0) row.addView(Space(this), LinearLayout.LayoutParams(dp(12), 1))
                row.addView(videoCard(item, imageHeight) { action(item) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            if (items.size < columns) {
                row.addView(Space(this), LinearLayout.LayoutParams(dp(12), 1))
                row.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
            }
            addBlock(target, row, 12)
        }
    }

    private fun videoCard(item: ParentSearchItem, imageHeight: Int, action: () -> Unit): View {
        val compact = isLandscape() && resources.configuration.screenHeightDp < 420
        val card = LinearLayout(this).apply {
            orientation = if (compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = KidsUi.shape(this@KidsChildActivity, Color.WHITE, 18, KidsUi.border)
            clipToOutline = true
            elevation = dp(2).toFloat()
        }
        val thumbnail = FrameLayout(this).apply {
            tag = "kids-thumbnail-${item.id.id}"
            setBackgroundColor(KidsUi.purpleLight)
            setOnClickListener { action() }
            contentDescription = getString(R.string.kids_watch_description, item.title)
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        thumbnail.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        CoilHelper.loadThumbnail(image, item.thumbnailUrl)
        val service = runCatching { VideoIdentity.service(item.id.serviceId).serviceInfo.name }.getOrDefault("")
        if (service.isNotBlank()) {
            val badge = KidsUi.text(this, service, 10f, Color.WHITE, true).apply {
                setPadding(dp(7), dp(4), dp(7), dp(4))
                background = KidsUi.shape(this@KidsChildActivity, 0xCC20232D.toInt(), 8)
            }
            thumbnail.addView(
                badge,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
                    setMargins(dp(8), dp(8), dp(8), dp(8))
                }
            )
        }
        val thumbnailWidth = if (compact) dp(imageHeight * 16 / 9) else ViewGroup.LayoutParams.MATCH_PARENT
        card.addView(thumbnail, LinearLayout.LayoutParams(thumbnailWidth, dp(imageHeight)))
        val title = KidsUi.button(this, item.title, KidsUi.ButtonStyle.GHOST).apply {
            tag = "kids-video-${item.id.id}"
            textSize = 15f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            minimumHeight = dp(60)
            setOnClickListener { action() }
        }
        val titleLayout = if (compact) {
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        card.addView(title, titleLayout)
        return card
    }

    private fun heading(title: String, caption: String) {
        addBlock(root, KidsUi.text(this, title, 25f, KidsUi.ink, true), 14)
        addBlock(root, KidsUi.text(this, caption, 14f, KidsUi.muted), 7)
    }

    private fun section(target: LinearLayout, title: String) {
        addBlock(target, KidsUi.text(this, title, 16f, KidsUi.ink, true), 18)
    }

    private fun emptyCard(message: String, viewId: Int = View.NO_ID) = addEmptyCard(root, message, viewId)

    private fun addEmptyCard(target: LinearLayout, message: String, viewId: Int = View.NO_ID) {
        val card = KidsUi.text(this, message, 15f, KidsUi.muted).apply {
            id = viewId
            setPadding(dp(18), dp(22), dp(18), dp(22))
            background = KidsUi.shape(this@KidsChildActivity, Color.WHITE, 18, KidsUi.border)
        }
        addBlock(target, card, 16)
    }

    private fun addBlock(target: LinearLayout, view: View, top: Int) {
        target.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) })
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

    private fun showSource(state: KidsState, profile: ChildProfile, source: StoredSource, shown: Int = RESULT_PAGE_SIZE, resetScroll: Boolean = true) {
        screen = Screen.SOURCE
        openSource = source.kind to source.id
        renderedHomeState = state
        root.removeAllViews()
        if (resetScroll) contentScroll.scrollTo(0, 0)
        renderHeader(profile)
        renderNavigation()
        button(getString(R.string.kids_back_to_library)) {
            contentScroll.scrollTo(0, 0)
            display(displayedState ?: state)
        }
        val kind = if (source.kind == SourceKind.PLAYLIST) ParentContentKind.PLAYLIST else ParentContentKind.CHANNEL
        val title = state.catalog.firstOrNull { it.kind == kind && it.id == source.id }?.title ?: source.id.id
        heading(title, getString(if (source.kind == SourceKind.PLAYLIST) R.string.kids_category_playlists else R.string.kids_category_channels))
        val library = profile.approvedLibrary()
        val videos = source.videos.filter { library.canPlay(it.toContentId()) }
        val items = videos.take(shown).mapNotNull { id -> state.catalog.firstOrNull { it.kind == ParentContentKind.VIDEO && it.id == id } }
        if (items.isEmpty()) emptyCard(getString(R.string.kids_no_videos))
        addVideoGrid(root, items) { item ->
            startVideo(profile.id, item.id, source.id.takeIf { source.kind == SourceKind.PLAYLIST }, emptySet(), automatic = false)
        }
        if (videos.size > shown) button(getString(R.string.kids_parent_more)) { showSource(state, profile, source, shown + RESULT_PAGE_SIZE, resetScroll = false) }
    }

    /** Extraction happens off the UI thread, with an approval check both before and after it. */
    private fun startVideo(profileId: String, video: StoredContentId, playlist: StoredContentId?, unavailable: Set<StoredContentId>, automatic: Boolean) {
        stopPlayback()
        screen = Screen.LOADING
        val ticket = ++playbackGeneration
        root.removeAllViews()
        contentScroll.scrollTo(0, 0)
        renderHeader(displayedState?.profiles?.firstOrNull { it.id == profileId })
        navigation.visibility = View.GONE
        heading(getString(R.string.kids_loading_video), getString(R.string.kids_loading_caption))
        addBlock(root, android.widget.ProgressBar(this), 24)
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
        renderHeader(profile)
        navigation.visibility = View.GONE
        button(getString(R.string.kids_back_to_library)) { returnToLibrary() }
        val availableWidth = (resources.configuration.screenWidthDp - 32).coerceAtLeast(240)
        val playerHeight = minOf(availableWidth * 9 / 16, (resources.configuration.screenHeightDp * 0.55f).toInt()).coerceAtLeast(150)
        val view = PlayerView(this).apply {
            id = R.id.kids_player_view
            this.player = player
            background = KidsUi.shape(this@KidsChildActivity, KidsUi.ink, 18)
            clipToOutline = true
        }
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(playerHeight)).apply { topMargin = dp(14) })
        addBlock(root, KidsUi.text(this, state.catalog.firstOrNull { it.kind == ParentContentKind.VIDEO && it.id == video }?.title ?: video.id, 20f, KidsUi.ink, true), 18)
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
        renderHeader(displayedState?.profiles?.firstOrNull { it.id == displayedState?.selectedProfileId })
        navigation.visibility = View.GONE
        emptyCard(getString(R.string.kids_video_unavailable))
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
                        activeTab = Tab.HOME
                        category = Category.ALL
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
                            if (profile == null || source == null || profile.remainingMillis <= 0) display(state) else showSource(state, profile, source, resetScroll = false)
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
        renderHeader(null)
        navigation.visibility = View.GONE
        emptyCard(getString(R.string.kids_storage_error))
        button(getString(R.string.kids_try_again)) { loadState() }
    }

    private fun parentButton() = button(getString(R.string.kids_parent_title)) {
        startActivity(Intent(this, KidsParentActivity::class.java))
    }

    private fun label(text: String): TextView = KidsUi.text(this, text, 16f, KidsUi.ink).also {
        addBlock(root, it, 12)
    }

    private fun button(text: String, action: () -> Unit): Button = KidsUi.button(this, text).also {
        it.setOnClickListener { if (!busy) action() }
        addBlock(root, it, 10)
    }

    private fun isLandscape(): Boolean = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val SETUP_REQUEST = 1
        private const val SOURCE_REFRESH_MILLIS = 15 * 60_000L
        private const val RESULT_PAGE_SIZE = 40
    }
}
