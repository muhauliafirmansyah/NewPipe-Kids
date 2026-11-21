/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.PlayerView
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.peertube.PeertubeInstance
import org.schabi.newpipe.util.PeertubeHelper
import org.schabi.newpipe.util.image.CoilHelper

/** PIN-protected profile and content management. */
class KidsParentActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val sourceWorker = Executors.newSingleThreadExecutor()
    private val refreshHandler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private var parentPin: String? = null
    private var busy = false
    private var selectedProfileId: String? = null
    private var searchServiceId = ServiceList.YouTube.serviceId
    private var searchKind: ParentContentKind? = null
    private var searchQuery = ""
    private var searchResults: List<ParentSearchItem> = emptyList()
    private var searchPage: ParentSearchPage? = null
    private var previewDialog: AlertDialog? = null
    private var previewPlayer: ExoPlayer? = null
    private var approvedListMarker: View? = null
    private var foreground = false
    private var refreshGeneration = 0
    private var tvBack: (() -> Unit)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return KidsTvNavigation.moveFromInput(this, event) || super.dispatchKeyEvent(event)
    }

    private data class SourceBrowseState(
        val source: ParentSearchItem,
        val videos: LinkedHashMap<StoredContentId, ParentSearchItem> = linkedMapOf(),
        val selected: MutableSet<StoredContentId> = linkedSetOf(),
        var next: ParentSourceCursor? = null,
        var incomplete: Boolean = false
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (KidsTvNavigation.isTv(this)) window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = KidsUi.background()
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        toolbar.addView(KidsUi.logo(this), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val done = KidsUi.button(this, getString(R.string.kids_parent_done), KidsUi.ButtonStyle.SOFT).apply {
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }
        toolbar.addView(done, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(root)
        }
        page.addView(toolbar)
        page.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(page)
        KidsTvNavigation.install(this)
        if (KidsTvNavigation.isTv(this)) {
            onBackPressedDispatcher.addCallback(
                this,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        if (!busy) tvBack?.invoke() ?: finish()
                    }
                }
            )
        }
        showUnlock()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        scheduleSourceRefresh(0, ++refreshGeneration)
    }

    override fun onPause() {
        foreground = false
        refreshGeneration++
        refreshHandler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    private fun scheduleSourceRefresh(delay: Long, generation: Int) {
        refreshHandler.postDelayed({
            if (!foreground || generation != refreshGeneration) return@postDelayed
            sourceWorker.execute {
                runCatching {
                    ApprovedSourceRefresher(KidsStorage.get(applicationContext), ExtractorSourceFetcher(), ExtractorChannelPlaylistFetcher(), ::onPlaylistsDiscovered).refreshAll()
                }
                runOnUiThread {
                    if (foreground && generation == refreshGeneration && !isFinishing && !isDestroyed) scheduleSourceRefresh(SOURCE_REFRESH_MILLIS, generation)
                }
            }
        }, delay)
    }

    private fun showUnlock() {
        tvBack = null
        root.removeAllViews()
        label(getString(R.string.kids_parent_title))
        label(getString(R.string.kids_parent_unlock_caption))
        val input = EditText(this).apply {
            hint = getString(R.string.kids_parent_pin_hint)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            isSaveEnabled = false
            KidsUi.input(this)
        }
        addBlock(input, 18)
        val error = label("")
        error.visibility = View.GONE
        val unlock = button(getString(R.string.kids_parent_unlock)) {
            val code = input.text.toString()
            input.text.clear()
            runParentAction(
                action = { ParentSettings(KidsStorage.get(applicationContext)).authenticate(code) },
                onError = {
                    error.setText(R.string.kids_parent_unlock_error)
                    error.visibility = View.VISIBLE
                },
                onSuccess = {
                    parentPin = code
                    showApproved(it)
                }
            )
        }
        unlock.id = R.id.kids_parent_unlock_button
        input.id = R.id.kids_parent_unlock_pin
        error.id = R.id.kids_parent_error
        KidsTvNavigation.requestFocus(input)
    }

    private fun showProfiles(state: KidsState) {
        tvBack = { loadApproved() }
        root.removeAllViews()
        label(getString(R.string.kids_parent_profiles))
        label(getString(R.string.kids_parent_shorts_caption))
        button(getString(R.string.kids_parent_back_approved)) { showApproved(state) }
        button(getString(R.string.kids_parent_add_profile)) { showAddProfile() }
        button(getString(if (state.lockProfileSwitching) R.string.kids_parent_unlock_switch else R.string.kids_parent_lock_switch)) {
            change { service, pin -> service.setProfileSwitchLock(pin, !state.lockProfileSwitching) }
        }
        if (state.profiles.isEmpty()) label(getString(R.string.kids_parent_no_profiles))
        state.profiles.forEach { profile ->
            val title = if (profile.enabled) profile.name else "${profile.name} (${getString(R.string.kids_parent_disabled)})"
            button(title) { showProfileActions(profile) }
        }
    }

    private fun showApproved(state: KidsState) {
        tvBack = null
        root.removeAllViews()
        approvedListMarker = label(getString(R.string.kids_parent_approved))
        label(getString(R.string.kids_parent_caption))
        val profiles = button(getString(R.string.kids_parent_profiles)) { showProfiles(state) }
        button(getString(R.string.kids_parent_add_content)) { showSearch() }
        KidsTvNavigation.requestFocus(profiles)
        if (state.profiles.isEmpty()) {
            label(getString(R.string.kids_parent_no_profiles))
            return
        }
        val profile = state.profiles.firstOrNull { it.id == selectedProfileId } ?: state.profiles.first()
        selectedProfileId = profile.id
        button("${getString(R.string.kids_parent_choose_profile)}: ${profile.name}") {
            AlertDialog.Builder(this).setTitle(R.string.kids_parent_choose_profile)
                .setItems(state.profiles.map { it.name }.toTypedArray()) { _, index ->
                    selectedProfileId = state.profiles[index].id
                    showApproved(state)
                }.show()
        }
        val sourceVideos = profile.sources.flatMap { it.videos }.toSet()
        val videos = profile.approvedVideos + profile.hiddenVideos.filter { it in sourceVideos }
        if (videos.isEmpty() && profile.sources.isEmpty()) {
            val empty = KidsUi.text(this, getString(R.string.kids_parent_empty_approved), 14f, KidsUi.muted).apply {
                background = KidsUi.shape(this@KidsParentActivity, Color.WHITE, 16, KidsUi.border)
                setPadding(dp(16), dp(20), dp(16), dp(20))
            }
            addBlock(empty, 14)
        }
        videos.forEach { id ->
            val item = state.catalog.firstOrNull { it.kind == ParentContentKind.VIDEO && it.id == id }
            val title = item?.title ?: id.id
            val hidden = id in profile.hiddenVideos
            contentButton(item, if (hidden) "$title (${getString(R.string.kids_parent_hidden)})" else title) {
                showApprovedVideo(profile, id, title, hidden)
            }
        }
        profile.sources.forEach { source ->
            val kind = if (source.kind == SourceKind.CHANNEL) ParentContentKind.CHANNEL else ParentContentKind.PLAYLIST
            val title = state.catalog.firstOrNull { it.kind == kind && it.id == source.id }?.title ?: source.id.id
            val item = state.catalog.firstOrNull { it.kind == kind && it.id == source.id }
            contentButton(item, if (source.enabled) title else "$title (${getString(R.string.kids_parent_hidden)})") {
                showApprovedSource(profile, source, title)
            }
        }
        ChildLibrary.sources(profile, includeHiddenChannelPlaylists = true).filter { source ->
            source.kind == SourceKind.PLAYLIST && profile.sources.none { it.kind == source.kind && it.id == source.id }
        }.forEach { source ->
            val item = state.catalog.firstOrNull { it.kind == ParentContentKind.PLAYLIST && it.id == source.id }
            val title = item?.title ?: source.id.id
            val hidden = source.id in profile.hiddenChannelPlaylists
            contentButton(item, if (hidden) "$title (${getString(R.string.kids_parent_hidden)})" else title) {
                showChannelPlaylist(profile, source, title, hidden)
            }
        }
    }

    private fun showApprovedVideo(profile: ChildProfile, id: StoredContentId, title: String, hidden: Boolean) {
        val choices = mutableListOf(getString(R.string.kids_parent_open), getString(if (hidden) R.string.kids_parent_show else R.string.kids_parent_hide))
        if (id in profile.approvedVideos) choices.add(getString(R.string.kids_parent_remove))
        AlertDialog.Builder(this).setTitle(title).setItems(choices.toTypedArray()) { _, index ->
            when (index) {
                0 -> showVideoPreview(id, title)
                1 -> changeApproved { service, pin -> service.setVideoHidden(pin, setOf(profile.id), id, !hidden) }
                2 -> changeApproved { service, pin -> service.removeVideoApproval(pin, setOf(profile.id), id) }
            }
        }.show()
    }

    private fun showApprovedSource(profile: ChildProfile, source: StoredSource, title: String) {
        val choices = arrayOf(getString(R.string.kids_parent_open), getString(if (source.enabled) R.string.kids_parent_hide else R.string.kids_parent_show), getString(R.string.kids_parent_remove))
        AlertDialog.Builder(this).setTitle(title).setItems(choices) { _, index ->
            when (index) {
                0 -> showApprovedSourceVideos(profile, source, title)
                1 -> changeApproved { service, pin -> service.setSourceEnabled(pin, setOf(profile.id), source.kind, source.id, !source.enabled) }
                2 -> changeApproved { service, pin -> service.removeSource(pin, setOf(profile.id), source.kind, source.id) }
            }
        }.show()
    }

    private fun showChannelPlaylist(profile: ChildProfile, source: StoredSource, title: String, hidden: Boolean) {
        val choices = arrayOf(getString(R.string.kids_parent_open), getString(if (hidden) R.string.kids_parent_show else R.string.kids_parent_hide))
        AlertDialog.Builder(this).setTitle(title).setItems(choices) { _, index ->
            when (index) {
                0 -> showApprovedSourceVideos(profile, source, title)
                1 -> changeApproved { service, pin -> service.setChannelPlaylistHidden(pin, setOf(profile.id), source.id, !hidden) }
            }
        }.show()
    }

    private fun showApprovedSourceVideos(profile: ChildProfile, source: StoredSource, title: String, shown: Int = RESULT_PAGE_SIZE) {
        tvBack = { loadApproved() }
        val state = KidsStorage.get(applicationContext).read()
        val latestProfile = state.profiles.firstOrNull { it.id == profile.id } ?: return showApproved(state)
        val direct = latestProfile.sources.firstOrNull { it.kind == source.kind && it.id == source.id }
        val latestSource = direct ?: ChildLibrary.sources(latestProfile, includeHiddenChannelPlaylists = true).firstOrNull { it.kind == source.kind && it.id == source.id }
            ?: return showApproved(state)
        root.removeAllViews()
        button(getString(R.string.kids_parent_back_approved)) { loadApproved() }
        label(title)
        label(if (direct == null) getString(R.string.kids_parent_channel_playlist_scope) else sourceScope(latestSource.kind, latestSource.id))
        if (latestSource.videos.isEmpty()) label(getString(R.string.kids_parent_source_empty))
        latestSource.videos.take(shown).forEach { id ->
            val videoTitle = state.catalog.firstOrNull { it.kind == ParentContentKind.VIDEO && it.id == id }?.title ?: id.id
            val hidden = id in latestProfile.hiddenVideos
            button(if (hidden) "$videoTitle (${getString(R.string.kids_parent_hidden)})" else videoTitle) {
                showApprovedVideo(latestProfile, id, videoTitle, hidden)
            }
        }
        if (latestSource.videos.size > shown) {
            button(getString(R.string.kids_parent_more)) {
                showApprovedSourceVideos(latestProfile, latestSource, title, shown + RESULT_PAGE_SIZE)
            }
        }
    }

    private fun showSearch(query: String = "", results: List<ParentSearchItem> = emptyList(), page: ParentSearchPage? = null) {
        tvBack = { loadApproved() }
        val oldFocus = KidsTvNavigation.remember(currentFocus)
        searchQuery = query
        searchResults = results
        searchPage = page
        root.removeAllViews()
        label(getString(R.string.kids_parent_search_title))
        button(getString(R.string.kids_parent_back_approved)) { loadApproved() }
        val services = listOf(ServiceList.YouTube, ServiceList.PeerTube, ServiceList.MediaCCC)
        val selected = VideoIdentity.service(searchServiceId)
        button(getString(R.string.kids_parent_service, selected.serviceInfo.name)) {
            AlertDialog.Builder(this).setTitle(R.string.kids_parent_choose_service)
                .setItems(services.map { it.serviceInfo.name }.toTypedArray()) { _, index ->
                    searchServiceId = services[index].serviceId
                    searchKind = if (searchServiceId == ServiceList.PeerTube.serviceId) ParentContentKind.VIDEO else null
                    showSearch()
                }.show()
        }
        if (searchServiceId == ServiceList.PeerTube.serviceId) {
            button(getString(R.string.kids_parent_peertube_server, ServiceList.PeerTube.baseUrl)) { choosePeerTubeServer() }
        }
        val input = EditText(this).apply {
            hint = getString(R.string.kids_parent_search_hint)
            setText(query)
            id = R.id.kids_parent_search_input
            setSingleLine(true)
            KidsUi.input(this)
        }
        addBlock(input, 10)
        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val kinds = listOf(
            Triple(null, R.string.kids_category_all, R.id.kids_parent_filter_all),
            Triple(ParentContentKind.VIDEO, R.string.kids_category_videos, R.id.kids_parent_filter_videos),
            Triple(ParentContentKind.CHANNEL, R.string.kids_category_channels, R.id.kids_parent_filter_channels),
            Triple(ParentContentKind.PLAYLIST, R.string.kids_category_playlists, R.id.kids_parent_filter_playlists)
        )
        val selectedFilterId = kinds.firstOrNull { it.first == searchKind }?.third ?: R.id.kids_parent_filter_videos
        if (KidsTvNavigation.isTv(this)) input.nextFocusDownId = selectedFilterId
        kinds.filter { it.first != null || searchServiceId != ServiceList.PeerTube.serviceId }.forEach { (kind, title, viewId) ->
            val chip = KidsUi.button(this, getString(title), if (searchKind == kind) KidsUi.ButtonStyle.SELECTED else KidsUi.ButtonStyle.SOFT).apply {
                id = viewId
                gravity = Gravity.CENTER
                isSelected = searchKind == kind
                if (KidsTvNavigation.isTv(context)) {
                    nextFocusUpId = R.id.kids_parent_search_input
                    nextFocusDownId = R.id.kids_parent_search_button
                }
                setOnClickListener {
                    if (!busy && searchKind != kind) {
                        val text = input.text.toString().trim()
                        searchKind = kind
                        if (text.isEmpty()) {
                            showSearch()
                        } else {
                            showSearch(text)
                            performSearch(text)
                        }
                    }
                }
            }
            filters.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { marginEnd = dp(8) })
        }
        addBlock(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(filters)
            },
            10
        )
        val search = button(getString(R.string.kids_parent_search)) {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) performSearch(text)
        }
        search.id = R.id.kids_parent_search_button
        if (KidsTvNavigation.isTv(this)) search.nextFocusUpId = selectedFilterId
        if (results.isNotEmpty()) label(getString(R.string.kids_parent_search_results))
        if (query.isNotEmpty() && results.isEmpty() && page != null) {
            val message = if (searchServiceId == ServiceList.MediaCCC.serviceId && searchKind == ParentContentKind.PLAYLIST) {
                R.string.kids_parent_search_unsupported
            } else {
                R.string.kids_parent_search_empty
            }
            label(getString(message))
        }
        results.forEach { item -> contentButton(item, item.title) { showSearchItem(item) }.tag = "kids-result-${item.kind.name}" }
        if (page?.next != null) {
            button(getString(R.string.kids_parent_more)) {
                runTask(
                    action = { ParentContentSearch.nextPage(page) },
                    onError = ::showSearchError,
                    onSuccess = { showSearch(query, (results + it.items).distinctBy { item -> item.kind to item.id }, it) }
                )
            }
        }
        KidsTvNavigation.restore(window.decorView, oldFocus, input)
    }

    private fun performSearch(query: String) {
        val service = searchServiceId
        val kind = searchKind
        runTask(
            action = { ParentContentSearch.firstPage(query, service, kind) },
            onError = ::showSearchError,
            onSuccess = { showSearch(query, it.items, it) }
        )
    }

    private fun choosePeerTubeServer() {
        val input = EditText(this).apply {
            setText(ServiceList.PeerTube.baseUrl)
            hint = getString(R.string.kids_parent_peertube_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.kids_parent_peertube_title).setView(input)
            .setNegativeButton(R.string.kids_parent_cancel, null).setPositiveButton(R.string.kids_parent_save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address = runCatching {
                    val uri = URI(input.text.toString().trim())
                    require(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null)
                    require(uri.path.isNullOrEmpty() || uri.path == "/")
                    require(uri.query == null && uri.fragment == null)
                    "${uri.scheme}://${uri.rawAuthority}"
                }.getOrNull()
                if (address == null) {
                    input.error = getString(R.string.kids_parent_peertube_error)
                } else {
                    PeertubeHelper.selectInstance(PeertubeInstance(address, address), applicationContext)
                    dialog.dismiss()
                    showSearch()
                }
            }
        }
        dialog.show()
    }

    private fun showSearchItem(item: ParentSearchItem, onApproved: (() -> Unit)? = null) {
        val message = if (item.kind == ParentContentKind.VIDEO) {
            item.url
        } else {
            val sourceKind = if (item.kind == ParentContentKind.CHANNEL) SourceKind.CHANNEL else SourceKind.PLAYLIST
            "${item.url}\n\n${sourceScope(sourceKind, item.id)}"
        }
        AlertDialog.Builder(this).setTitle(item.title).setMessage(message)
            .setNegativeButton(R.string.kids_parent_cancel, null)
            .setNeutralButton(R.string.kids_parent_open) { _, _ ->
                if (item.kind == ParentContentKind.VIDEO) showVideoPreview(item.id, item.title) else browseSearchSource(item)
            }
            .setPositiveButton(R.string.kids_parent_approve) { _, _ -> chooseProfiles(item, onApproved) }.show()
    }

    private fun sourceScope(kind: SourceKind, id: StoredContentId): String {
        val account = kind == SourceKind.CHANNEL && id.serviceId == ServiceList.PeerTube.serviceId &&
            runCatching { URI(id.id).path.startsWith("/accounts/") }.getOrDefault(false)
        return getString(
            when {
                account -> R.string.kids_parent_peertube_account_scope
                kind == SourceKind.CHANNEL -> R.string.kids_parent_channel_scope
                else -> R.string.kids_parent_future_content
            }
        )
    }

    private fun browseSearchSource(item: ParentSearchItem) {
        val kind = if (item.kind == ParentContentKind.CHANNEL) SourceKind.CHANNEL else SourceKind.PLAYLIST
        runTask(
            action = { ParentSourceBrowser.firstPage(kind, item.id) },
            onError = ::showSearchError,
            onSuccess = { page ->
                val browse = SourceBrowseState(item)
                appendSourcePage(browse, page)
                showSourceBrowser(browse)
            }
        )
    }

    private fun appendSourcePage(browse: SourceBrowseState, page: ParentSourcePage) {
        page.videos.forEach { video ->
            val existing = browse.videos[video.id]
            browse.videos[video.id] = if (existing?.isShortForm == true && !video.isShortForm) video.copy(isShortForm = true) else video
        }
        browse.next = page.next
        browse.incomplete = browse.incomplete || page.incomplete
    }

    private fun showSourceBrowser(browse: SourceBrowseState) {
        tvBack = { showSearch(searchQuery, searchResults, searchPage) }
        val oldFocus = KidsTvNavigation.remember(currentFocus)
        val item = browse.source
        val kind = if (item.kind == ParentContentKind.CHANNEL) SourceKind.CHANNEL else SourceKind.PLAYLIST
        root.removeAllViews()
        val back = button(getString(R.string.kids_parent_back_search)) { showSearch(searchQuery, searchResults, searchPage) }
        label(item.title)
        label(getString(R.string.kids_parent_select_videos))
        label(sourceScope(kind, item.id))
        if (browse.incomplete) label(getString(R.string.kids_parent_source_partial))
        button(getString(if (kind == SourceKind.CHANNEL) R.string.kids_parent_approve_channel else R.string.kids_parent_approve_playlist)) {
            chooseProfiles(item)
        }
        val approveSelection = {
            val videos = browse.videos.values.filter { it.id in browse.selected }
            chooseProfiles(videos) {
                browse.selected.clear()
                showSourceBrowser(browse)
                Toast.makeText(this, R.string.kids_parent_videos_approved, Toast.LENGTH_SHORT).show()
            }
        }
        val selected = button(getString(R.string.kids_parent_approve_selected, browse.selected.size), approveSelection).apply {
            id = R.id.kids_parent_approve_selected
            isEnabled = browse.selected.isNotEmpty()
        }
        var bottomSelected: Button? = null
        fun updateSelection() {
            listOfNotNull(selected, bottomSelected).forEach {
                it.text = getString(R.string.kids_parent_approve_selected, browse.selected.size)
                it.isEnabled = browse.selected.isNotEmpty()
            }
        }
        if (browse.videos.isEmpty() && browse.next == null) label(getString(R.string.kids_parent_source_empty))
        var firstCheckbox: CheckBox? = null
        browse.videos.values.forEach { video ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = KidsUi.shape(this@KidsParentActivity, Color.WHITE, 16, KidsUi.border)
                setPadding(dp(4), dp(6), dp(8), dp(6))
            }
            val checkbox = CheckBox(this).apply {
                tag = "kids-source-select-${video.id.id}"
                contentDescription = getString(R.string.kids_parent_select_video, video.title)
                isChecked = video.id in browse.selected
                setOnCheckedChangeListener { _, checked ->
                    if (checked) browse.selected.add(video.id) else browse.selected.remove(video.id)
                    updateSelection()
                }
            }
            if (firstCheckbox == null) firstCheckbox = checkbox
            row.addView(checkbox)
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = KidsUi.shape(this@KidsParentActivity, KidsUi.purpleLight, 8)
                clipToOutline = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            CoilHelper.loadThumbnail(image, video.thumbnailUrl)
            row.addView(image, LinearLayout.LayoutParams(dp(72), dp(48)))
            val title = KidsUi.button(this, video.title, KidsUi.ButtonStyle.GHOST).apply {
                tag = "kids-source-video-${video.id.id}"
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setOnClickListener {
                    if (!busy) {
                        showSearchItem(video) {
                            browse.selected.remove(video.id)
                            showSourceBrowser(browse)
                            Toast.makeText(this@KidsParentActivity, R.string.kids_parent_videos_approved, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addBlock(row, 10)
        }
        if (browse.videos.isNotEmpty()) {
            bottomSelected = button(getString(R.string.kids_parent_approve_selected, browse.selected.size), approveSelection).apply {
                isEnabled = browse.selected.isNotEmpty()
            }
        }
        browse.next?.let { cursor ->
            button(getString(R.string.kids_parent_more)) {
                runTask(
                    action = { ParentSourceBrowser.nextPage(cursor) },
                    onError = ::showSearchError,
                    onSuccess = { page ->
                        appendSourcePage(browse, page)
                        showSourceBrowser(browse)
                    }
                )
            }
        }
        KidsTvNavigation.restore(window.decorView, oldFocus, firstCheckbox ?: back)
    }

    private fun showVideoPreview(id: StoredContentId, title: String) {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val status = TextView(this).apply { text = getString(R.string.kids_loading_video) }
        val view = PlayerView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)) }
        content.addView(status)
        content.addView(view)
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(content)
            .setPositiveButton(android.R.string.ok, null).create()
        previewDialog = dialog
        dialog.setOnDismissListener {
            view.player = null
            previewPlayer?.release()
            previewPlayer = null
            previewDialog = null
        }
        dialog.show()
        runTask(
            action = { ExtractorVideoResolver.resolve(id) },
            onError = { if (dialog.isShowing) status.setText(R.string.kids_video_unavailable) },
            onSuccess = { resolved ->
                if (dialog.isShowing) {
                    val player = ExoPlayer.Builder(this).build()
                    previewPlayer = player
                    view.player = player
                    player.addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            if (previewPlayer === player && dialog.isShowing) {
                                view.player = null
                                previewPlayer = null
                                player.release()
                                status.setText(R.string.kids_video_unavailable)
                                status.visibility = View.VISIBLE
                            }
                        }
                    })
                    status.visibility = View.GONE
                    player.setMediaSource(ExtractorVideoResolver.mediaSource(this, resolved))
                    player.prepare()
                    player.play()
                }
            }
        )
    }

    private fun chooseProfiles(item: ParentSearchItem, onApproved: (() -> Unit)? = null) = chooseProfiles(listOf(item), onApproved)

    private fun chooseProfiles(items: List<ParentSearchItem>, onApproved: (() -> Unit)? = null) {
        if (items.isEmpty()) return
        val state = KidsStorage.get(applicationContext).read()
        val profiles = state.profiles
        if (profiles.isEmpty()) {
            AlertDialog.Builder(this).setMessage(R.string.kids_parent_no_profiles).setPositiveButton(android.R.string.ok, null).show()
            return
        }
        val checked = BooleanArray(profiles.size)
        val dialog = AlertDialog.Builder(this).setTitle(R.string.kids_parent_select_profiles)
            .setMultiChoiceItems(profiles.map { it.name }.toTypedArray(), checked) { _, index, selected -> checked[index] = selected }
            .setNegativeButton(R.string.kids_parent_cancel, null).setPositiveButton(R.string.kids_parent_approve, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val ids = profiles.indices.filter { checked[it] }.mapTo(mutableSetOf()) { profiles[it].id }
                if (ids.isEmpty()) {
                    dialog.setTitle(R.string.kids_parent_select_required)
                } else {
                    dialog.dismiss()
                    approveSearchItems(items, ids, onApproved)
                }
            }
        }
        dialog.show()
    }

    private fun approveSearchItems(items: List<ParentSearchItem>, ids: Set<String>, onApproved: (() -> Unit)?) {
        val code = parentPin ?: return
        val source = items.singleOrNull()?.takeIf { it.kind != ParentContentKind.VIDEO }
        runTask(
            action = {
                val storage = KidsStorage.get(applicationContext)
                val parent = ParentSettings(storage)
                val saved = if (source == null) parent.approveSearchVideos(code, ids, items) else parent.approveSearchItem(code, ids, source)
                val sourceKind = when (source?.kind) {
                    ParentContentKind.CHANNEL -> SourceKind.CHANNEL
                    ParentContentKind.PLAYLIST -> SourceKind.PLAYLIST
                    else -> null
                }
                if (sourceKind == null) {
                    Pair(saved, true)
                } else {
                    val refreshed = runCatching { SourceRefreshCoordinator(storage, ExtractorSourceFetcher()).refresh(ids, sourceKind, requireNotNull(source).id) }
                    Pair(if (refreshed.isSuccess) refreshed.getOrThrow() else storage.read(), refreshed.isSuccess)
                }
            },
            onError = ::showSaveError,
            onSuccess = { (state, refreshed) ->
                if (onApproved == null) showApproved(state) else onApproved()
                if (source?.kind == ParentContentKind.CHANNEL) {
                    sourceWorker.execute {
                        runCatching { ChannelPlaylistRefreshCoordinator(KidsStorage.get(applicationContext), ExtractorChannelPlaylistFetcher(), ::onPlaylistsDiscovered).refresh(ids, source.id) }
                    }
                }
                if (!refreshed) AlertDialog.Builder(this).setMessage(R.string.kids_parent_refresh_error).setPositiveButton(android.R.string.ok, null).show()
            }
        )
    }

    private fun onPlaylistsDiscovered() {
        runOnUiThread {
            if (foreground && hasWindowFocus() && parentPin != null && !busy && !isFinishing && !isDestroyed && approvedListMarker?.parent === root) loadApproved()
        }
    }

    private fun loadApproved() = runTask(
        action = { KidsStorage.get(applicationContext).read() },
        onError = ::showSaveError,
        onSuccess = ::showApproved
    )

    private fun showSearchError() = AlertDialog.Builder(this).setMessage(R.string.kids_parent_search_error)
        .setPositiveButton(android.R.string.ok, null).show().let { Unit }

    private fun showSaveError() = AlertDialog.Builder(this).setMessage(R.string.kids_parent_save_error)
        .setPositiveButton(android.R.string.ok, null).show().let { Unit }

    private fun showAddProfile() {
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
        }
        val name = EditText(this).apply { hint = getString(R.string.kids_parent_name) }
        val minutes = EditText(this).apply {
            hint = getString(R.string.kids_parent_session_minutes)
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        fields.addView(name)
        fields.addView(minutes)
        val dialog = AlertDialog.Builder(this).setTitle(R.string.kids_parent_add_profile).setView(fields)
            .setNegativeButton(R.string.kids_parent_cancel, null).setPositiveButton(R.string.kids_parent_save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val limit = minutes.text.toString().toIntOrNull()
                val childName = name.text.toString().trim()
                if (childName.isBlank() || limit == null || limit !in 1..120) {
                    minutes.error = getString(R.string.kids_parent_invalid_profile)
                } else {
                    dialog.dismiss()
                    change { service, pin -> service.addProfile(pin, UUID.randomUUID().toString(), childName, limit) }
                }
            }
        }
        dialog.show()
    }

    private fun showProfileActions(profile: ChildProfile) {
        val actions = arrayOf(
            getString(R.string.kids_parent_rename),
            getString(if (profile.enabled) R.string.kids_parent_disable else R.string.kids_parent_enable),
            getString(R.string.kids_parent_set_limit),
            getString(R.string.kids_parent_reset_session),
            getString(if (profile.showShorts) R.string.kids_parent_hide_shorts else R.string.kids_parent_show_shorts),
            getString(R.string.kids_parent_remove)
        )
        AlertDialog.Builder(this).setTitle(profile.name).setItems(actions) { _, index ->
            when (index) {
                0 -> showRename(profile)

                1 -> change { service, pin -> service.setProfileEnabled(pin, profile.id, !profile.enabled) }

                2 -> showLimit(profile)

                3 -> change { service, pin -> service.resetSession(pin, profile.id) }

                4 -> change { service, pin -> service.setShowShorts(pin, profile.id, !profile.showShorts) }

                5 -> AlertDialog.Builder(this).setMessage(R.string.kids_parent_remove_confirm)
                    .setNegativeButton(R.string.kids_parent_cancel, null)
                    .setPositiveButton(R.string.kids_parent_remove) { _, _ ->
                        change { service, pin -> service.removeProfile(pin, profile.id) }
                    }.show()
            }
        }.show()
    }

    private fun showRename(profile: ChildProfile) {
        val input = EditText(this).apply {
            setText(profile.name)
            hint = getString(R.string.kids_parent_name)
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.kids_parent_rename).setView(input)
            .setNegativeButton(R.string.kids_parent_cancel, null).setPositiveButton(R.string.kids_parent_save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isBlank()) {
                    input.error = getString(R.string.kids_setup_name_error)
                } else {
                    dialog.dismiss()
                    change { service, pin -> service.renameProfile(pin, profile.id, name) }
                }
            }
        }
        dialog.show()
    }

    private fun showLimit(profile: ChildProfile) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(profile.sessionLimitMinutes.toString())
            hint = getString(R.string.kids_parent_session_minutes)
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.kids_parent_set_limit).setView(input)
            .setNegativeButton(R.string.kids_parent_cancel, null).setPositiveButton(R.string.kids_parent_save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val limit = input.text.toString().toIntOrNull()
                if (limit == null || limit !in 1..120) {
                    input.error = getString(R.string.kids_setup_limit_error)
                } else {
                    dialog.dismiss()
                    change { service, pin -> service.setSessionLimit(pin, profile.id, limit) }
                }
            }
        }
        dialog.show()
    }

    private fun change(action: (ParentSettings, String) -> KidsState) {
        val code = parentPin ?: return
        runParentAction(
            action = { action(ParentSettings(KidsStorage.get(applicationContext)), code) },
            onError = { AlertDialog.Builder(this).setMessage(R.string.kids_parent_save_error).setPositiveButton(android.R.string.ok, null).show() },
            onSuccess = ::showProfiles
        )
    }

    private fun changeApproved(action: (ParentSettings, String) -> KidsState) {
        val code = parentPin ?: return
        runTask(
            action = { action(ParentSettings(KidsStorage.get(applicationContext)), code) },
            onError = ::showSaveError,
            onSuccess = ::showApproved
        )
    }

    private fun runParentAction(action: () -> KidsState, onError: () -> Unit, onSuccess: (KidsState) -> Unit) {
        runTask(action, onError, onSuccess)
    }

    private fun <T> runTask(action: () -> T, onError: () -> Unit, onSuccess: (T) -> Unit) {
        if (busy) return
        busy = true
        val progress = TextView(this).apply { text = getString(R.string.kids_working) }
        root.addView(progress, 0)
        executor.execute {
            val result = runCatching(action)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                busy = false
                root.removeView(progress)
                result.fold(onSuccess = onSuccess, onFailure = { onError() })
            }
        }
    }

    private fun label(text: String): TextView = KidsUi.text(this, text, if (root.childCount == 0) 25f else 14f, if (root.childCount == 0) KidsUi.ink else KidsUi.muted, root.childCount == 0).also {
        addBlock(it, if (root.childCount == 0) 8 else 8)
    }

    private fun button(text: String, action: () -> Unit): Button {
        val primary = text in setOf(getString(R.string.kids_parent_unlock), getString(R.string.kids_parent_add_content), getString(R.string.kids_parent_add_profile), getString(R.string.kids_parent_search), getString(R.string.kids_parent_approve))
        return KidsUi.button(this, text, if (primary) KidsUi.ButtonStyle.PRIMARY else KidsUi.ButtonStyle.SURFACE).also {
            if (primary) it.gravity = Gravity.CENTER
            it.maxLines = 2
            it.ellipsize = TextUtils.TruncateAt.END
            it.setOnClickListener { if (!busy) action() }
            addBlock(it, 10)
        }
    }

    private fun contentButton(item: ParentSearchItem?, title: String, action: () -> Unit): Button {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = KidsUi.shape(this@KidsParentActivity, Color.WHITE, 16, KidsUi.border)
            clipToOutline = true
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = KidsUi.shape(this@KidsParentActivity, KidsUi.purpleLight, 10)
            clipToOutline = true
            contentDescription = null
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        CoilHelper.loadThumbnail(image, item?.thumbnailUrl)
        row.addView(image, LinearLayout.LayoutParams(dp(82), dp(54)))
        val choice = KidsUi.button(this, title, KidsUi.ButtonStyle.GHOST).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { if (!busy) action() }
        }
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        details.addView(choice, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (item != null) {
            val kind = when (item.kind) {
                ParentContentKind.VIDEO -> R.string.kids_parent_kind_video
                ParentContentKind.CHANNEL -> R.string.kids_parent_kind_channel
                ParentContentKind.PLAYLIST -> R.string.kids_parent_kind_playlist
            }
            val service = runCatching { VideoIdentity.service(item.id.serviceId).serviceInfo.name }.getOrDefault("")
            val meta = KidsUi.text(this, listOf(getString(kind), service).filter { it.isNotBlank() }.joinToString(" · "), 12f, KidsUi.muted).apply {
                setPadding(dp(16), 0, dp(8), dp(4))
            }
            details.addView(meta)
        }
        row.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addBlock(row, 10)
        return choice
    }

    private fun addBlock(view: View, top: Int) {
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onStop() {
        super.onStop()
        previewDialog?.dismiss()
        parentPin = null
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        sourceWorker.shutdownNow()
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val RESULT_PAGE_SIZE = 40
        private const val SOURCE_REFRESH_MILLIS = 15 * 60_000L
    }
}
