/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
    private var previewDialog: AlertDialog? = null
    private var previewPlayer: ExoPlayer? = null
    private var foreground = false
    private var refreshGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(root) })
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
                runCatching { ApprovedSourceRefresher(KidsStorage.get(applicationContext), ExtractorSourceFetcher()).refreshAll() }
                runOnUiThread {
                    if (foreground && generation == refreshGeneration && !isFinishing && !isDestroyed) scheduleSourceRefresh(SOURCE_REFRESH_MILLIS, generation)
                }
            }
        }, delay)
    }

    private fun showUnlock() {
        root.removeAllViews()
        label(getString(R.string.kids_parent_title))
        val input = EditText(this).apply {
            hint = getString(R.string.kids_parent_pin_hint)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            isSaveEnabled = false
        }
        root.addView(input)
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
    }

    private fun showProfiles(state: KidsState) {
        root.removeAllViews()
        label(getString(R.string.kids_parent_profiles))
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
        root.removeAllViews()
        label(getString(R.string.kids_parent_approved))
        button(getString(R.string.kids_parent_profiles)) { showProfiles(state) }
        button(getString(R.string.kids_parent_add_content)) { showSearch() }
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
        if (videos.isEmpty() && profile.sources.isEmpty()) label(getString(R.string.kids_parent_empty_approved))
        videos.forEach { id ->
            val item = state.catalog.firstOrNull { it.kind == ParentContentKind.VIDEO && it.id == id }
            val title = item?.title ?: id.id
            val hidden = id in profile.hiddenVideos
            button(if (hidden) "$title (${getString(R.string.kids_parent_hidden)})" else title) {
                showApprovedVideo(profile, id, title, hidden)
            }
        }
        profile.sources.forEach { source ->
            val kind = if (source.kind == SourceKind.CHANNEL) ParentContentKind.CHANNEL else ParentContentKind.PLAYLIST
            val title = state.catalog.firstOrNull { it.kind == kind && it.id == source.id }?.title ?: source.id.id
            button(if (source.enabled) title else "$title (${getString(R.string.kids_parent_hidden)})") {
                showApprovedSource(profile, source, title)
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

    private fun showApprovedSourceVideos(profile: ChildProfile, source: StoredSource, title: String, shown: Int = RESULT_PAGE_SIZE) {
        val state = KidsStorage.get(applicationContext).read()
        val latestProfile = state.profiles.firstOrNull { it.id == profile.id } ?: return showApproved(state)
        val latestSource = latestProfile.sources.firstOrNull { it.kind == source.kind && it.id == source.id } ?: return showApproved(state)
        root.removeAllViews()
        button(getString(R.string.kids_parent_back_approved)) { loadApproved() }
        label(title)
        label(sourceScope(latestSource.kind, latestSource.id))
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
        root.removeAllViews()
        button(getString(R.string.kids_parent_back_approved)) { loadApproved() }
        val services = listOf(ServiceList.YouTube, ServiceList.PeerTube, ServiceList.MediaCCC)
        val selected = VideoIdentity.service(searchServiceId)
        button(getString(R.string.kids_parent_service, selected.serviceInfo.name)) {
            AlertDialog.Builder(this).setTitle(R.string.kids_parent_choose_service)
                .setItems(services.map { it.serviceInfo.name }.toTypedArray()) { _, index ->
                    searchServiceId = services[index].serviceId
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
        }
        root.addView(input)
        val search = button(getString(R.string.kids_parent_search)) {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                runTask(
                    action = { ParentContentSearch.firstPage(text, searchServiceId) },
                    onError = ::showSearchError,
                    onSuccess = { showSearch(text, it.items, it) }
                )
            }
        }
        search.id = R.id.kids_parent_search_button
        results.forEach { item -> button(item.title) { showSearchItem(item) }.tag = "kids-result-${item.kind.name}" }
        if (page?.next != null) {
            button(getString(R.string.kids_parent_more)) {
                runTask(
                    action = { ParentContentSearch.nextPage(page) },
                    onError = ::showSearchError,
                    onSuccess = { showSearch(query, results + it.items, it) }
                )
            }
        }
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

    private fun showSearchItem(item: ParentSearchItem) {
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
            .setPositiveButton(R.string.kids_parent_approve) { _, _ -> chooseProfiles(item) }.show()
    }

    private fun sourceScope(kind: SourceKind, id: StoredContentId): String {
        val account = kind == SourceKind.CHANNEL && id.serviceId == ServiceList.PeerTube.serviceId &&
            runCatching { URI(id.id).path.startsWith("/accounts/") }.getOrDefault(false)
        return getString(if (account) R.string.kids_parent_peertube_account_scope else R.string.kids_parent_future_content)
    }

    private fun browseSearchSource(item: ParentSearchItem) {
        val kind = if (item.kind == ParentContentKind.CHANNEL) SourceKind.CHANNEL else SourceKind.PLAYLIST
        runTask(
            action = { ExtractorSourceFetcher().fetchAll(kind, item.id) },
            onError = ::showSearchError,
            onSuccess = { videos ->
                root.removeAllViews()
                button(getString(R.string.kids_parent_back_search)) { showSearch() }
                label(item.title)
                label(sourceScope(kind, item.id))
                button(getString(R.string.kids_parent_approve)) { chooseProfiles(item) }
                if (videos.isEmpty()) label(getString(R.string.kids_parent_source_empty))
                videos.forEach { video -> button(video.title) { showVideoPreview(video.id, video.title) } }
            }
        )
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

    private fun chooseProfiles(item: ParentSearchItem) {
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
                    approveSearchItem(item, ids)
                }
            }
        }
        dialog.show()
    }

    private fun approveSearchItem(item: ParentSearchItem, ids: Set<String>) {
        val code = parentPin ?: return
        runTask(
            action = {
                val storage = KidsStorage.get(applicationContext)
                val saved = ParentSettings(storage).approveSearchItem(code, ids, item)
                val sourceKind = when (item.kind) {
                    ParentContentKind.CHANNEL -> SourceKind.CHANNEL
                    ParentContentKind.PLAYLIST -> SourceKind.PLAYLIST
                    ParentContentKind.VIDEO -> null
                }
                if (sourceKind == null) {
                    Pair(saved, true)
                } else {
                    val refreshed = runCatching { SourceRefreshCoordinator(storage, ExtractorSourceFetcher()).refresh(ids, sourceKind, item.id) }
                    Pair(if (refreshed.isSuccess) refreshed.getOrThrow() else storage.read(), refreshed.isSuccess)
                }
            },
            onError = ::showSaveError,
            onSuccess = { (state, refreshed) ->
                showApproved(state)
                if (!refreshed) AlertDialog.Builder(this).setMessage(R.string.kids_parent_refresh_error).setPositiveButton(android.R.string.ok, null).show()
            }
        )
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
            getString(R.string.kids_parent_remove)
        )
        AlertDialog.Builder(this).setTitle(profile.name).setItems(actions) { _, index ->
            when (index) {
                0 -> showRename(profile)

                1 -> change { service, pin -> service.setProfileEnabled(pin, profile.id, !profile.enabled) }

                2 -> showLimit(profile)

                3 -> change { service, pin -> service.resetSession(pin, profile.id) }

                4 -> AlertDialog.Builder(this).setMessage(R.string.kids_parent_remove_confirm)
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
