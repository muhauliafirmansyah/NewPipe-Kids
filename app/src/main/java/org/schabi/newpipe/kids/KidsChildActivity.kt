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
import android.text.InputFilter
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
    private lateinit var chrome: LinearLayout
    private lateinit var root: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var navigation: LinearLayout
    private lateinit var contentScroll: ScrollView
    private var timeLabel: TextView? = null
    private var profileNameDialog: AlertDialog? = null
    private var visible = false
    private var generation = 0
    private var displayedState: KidsState? = null
    private var renderedHomeState: KidsState? = null
    private var openSource: Pair<SourceKind, StoredContentId>? = null
    private var setupRequested = false
    private var restoreProfileEditor = false
    private var pickerOpenedWithActiveSession = false
    private var busy = false
    private enum class Screen { HOME, PROFILE_PICKER, PROFILE_EDIT, SOURCE, LOADING, PLAYER, MESSAGE }
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
        restoreProfileEditor = savedInstanceState?.getBoolean("kids_profile_editor") == true
        readIncomingIntent(intent)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility and
            (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()
        window.statusBarColor = 0xFF05065C.toInt()
        window.navigationBarColor = Color.BLACK
        chrome = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = KidsUi.childBackground()
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
            setBackgroundColor(0xF2000000.toInt())
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
        outState.putBoolean("kids_profile_editor", screen == Screen.PROFILE_EDIT)
        super.onSaveInstanceState(outState)
    }

    private fun handleBack(): Boolean = when {
        screen == Screen.PROFILE_EDIT -> {
            displayedState?.let(::showProfilePicker)
            true
        }

        screen == Screen.PROFILE_PICKER && displayedState?.selectedProfileId != null -> {
            activeTab = Tab.HOME
            displayedState?.let(::display)
            true
        }

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
        if (screen == Screen.PROFILE_EDIT) profileNameDialog?.dismiss()
        val restoreSearchFocus = screen == Screen.HOME && (activeTab == Tab.SEARCH || activeTab == Tab.STUFF) &&
            root.findViewById<EditText>(R.id.kids_child_search)?.hasFocus() == true
        screen = Screen.HOME
        chrome.background = KidsUi.childBackground()
        @Suppress("DEPRECATION")
        window.statusBarColor = 0xFF05065C.toInt()
        header.visibility = View.VISIBLE
        root.setPadding(dp(16), dp(4), dp(16), dp(20))
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
        val openRestoredEditor = restoreProfileEditor
        restoreProfileEditor = false
        when (activeTab) {
            Tab.HOME -> showHome(state, profile)
            Tab.SEARCH -> showSearch(state, profile, favorites = false)
            Tab.STUFF -> showSearch(state, profile, favorites = true)
            Tab.PROFILE -> if (openRestoredEditor) showProfileEditor(state) else showProfilePicker(state)
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
        header.visibility = View.VISIBLE
        header.removeAllViews()
        header.addView(KidsUi.logo(this, onDark = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        timeLabel = null
        if (profile != null) {
            timeLabel = KidsUi.text(this, "", 12f, Color.WHITE, true).apply {
                id = R.id.kids_time_remaining
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = KidsUi.shape(this@KidsChildActivity, 0x663B2E9C, 14)
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
            background = KidsUi.ripple(this@KidsChildActivity, 0x553B2E9C, 14)
            KidsUi.icon(this, R.drawable.ic_kids_lock, Color.WHITE)
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
                setTextColor(if (selected) Color.WHITE else 0xFFB8B7C4.toInt())
                contentDescription = getString(R.string.kids_nav_description, getString(title))
                isSelected = selected
                if (tab == Tab.PROFILE) {
                    displayedState?.profiles?.firstOrNull { it.id == displayedState?.selectedProfileId }?.let {
                        KidsAvatars.icon(this, it)
                    }
                } else {
                    KidsUi.icon(this, icon, if (selected) Color.WHITE else 0xFFB8B7C4.toInt(), top = true, size = 24)
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
        screen = Screen.PROFILE_PICKER
        displayedState = state
        chrome.background = KidsUi.childBackground()
        @Suppress("DEPRECATION")
        window.statusBarColor = 0xFF05065C.toInt()
        header.visibility = View.GONE
        navigation.visibility = View.GONE
        root.removeAllViews()
        root.setPadding(dp(16), dp(8), dp(16), dp(14))
        contentScroll.scrollTo(0, 0)
        val current = state.profiles.firstOrNull { it.id == state.selectedProfileId && it.enabled }
        pickerOpenedWithActiveSession = current != null && current.remainingMillis > 0
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(
            backButton {
                if (current == null) {
                    finish()
                } else {
                    activeTab = Tab.HOME
                    display(displayedState ?: state)
                }
            },
            LinearLayout.LayoutParams(dp(48), dp(48))
        )
        top.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        current?.let { addPickerTime(top, it) }
        root.addView(top)
        val body = if (isLandscape()) {
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                root.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            }
        } else {
            addFlexibleSpace(root, 0.8f)
            root
        }
        val intro = if (isLandscape()) {
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                body.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.7f))
            }
        } else {
            root
        }
        val mark = KidsUi.text(this, "▶", 22f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = KidsUi.shape(this@KidsChildActivity, KidsUi.purple, 12)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        intro.addView(mark, LinearLayout.LayoutParams(dp(48), dp(42)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val title = KidsUi.text(this, getString(R.string.kids_who_is_watching), if (isLandscape()) 30f else 44f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-rounded", Typeface.BOLD)
        }
        intro.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(if (isLandscape()) 8 else 24) })
        if (!isLandscape()) addFlexibleSpace(root, 0.6f)
        val profiles = ProfileManager.visibleProfiles(state)
        if (profiles.isEmpty()) {
            if (isLandscape()) {
                body.addView(KidsUi.text(this, getString(R.string.kids_no_enabled_profiles), 15f, Color.WHITE), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            } else {
                addEmptyCard(root, getString(R.string.kids_no_enabled_profiles))
            }
        } else {
            val row = LinearLayout(this).apply {
                id = R.id.kids_profile_picker
                gravity = if (isLandscape()) Gravity.CENTER else Gravity.CENTER_HORIZONTAL or Gravity.TOP
            }
            profiles.forEach { profile ->
                val selected = current?.id == profile.id
                val canEdit = selected && profile.remainingMillis > 0
                val column = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                val size = if (isLandscape()) 72 else 100
                val avatar = KidsAvatars.view(this, profile, size, selected).apply {
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { selectFromPicker(state, profile) }
                }
                column.addView(avatar, LinearLayout.LayoutParams(dp(size), dp(size)))
                val choice = KidsUi.button(this, profile.name, KidsUi.ButtonStyle.GHOST).apply {
                    textSize = 16f
                    gravity = Gravity.CENTER
                    minHeight = 0
                    minimumHeight = dp(36)
                    setPadding(0, dp(3), 0, dp(3))
                    setTextColor(Color.WHITE)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setOnClickListener { selectFromPicker(state, profile) }
                }
                column.addView(choice, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
                if (canEdit) {
                    val edit = KidsUi.button(this, getString(R.string.kids_edit_profile)).apply {
                        id = R.id.kids_edit_profile
                        contentDescription = getString(R.string.kids_edit_profile_description)
                        gravity = Gravity.CENTER
                        minHeight = 0
                        minimumHeight = 0
                        setPadding(dp(14), 0, dp(14), 0)
                        setOnClickListener { showProfileEditor(displayedState ?: state) }
                    }
                    column.addView(edit, LinearLayout.LayoutParams(dp(88), dp(36)))
                } else {
                    column.addView(Space(this), LinearLayout.LayoutParams(1, dp(36)))
                }
                row.addView(column, LinearLayout.LayoutParams(dp(if (isLandscape()) 132 else 148), ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            body.addView(
                HorizontalScrollView(this).apply {
                    isHorizontalScrollBarEnabled = false
                    isFillViewport = true
                    addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, if (isLandscape()) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT))
                },
                if (isLandscape()) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f) else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        if (!isLandscape()) addFlexibleSpace(root, 1f)
        val footer = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }
        if (current != null && current.remainingMillis > 0) {
            val autoplay = KidsUi.button(this, autoplayText(current.autoplay), KidsUi.ButtonStyle.GHOST).apply {
                setTextColor(Color.WHITE)
                setOnClickListener {
                    updateProfile(current.id, { it.copy(autoplay = !it.autoplay) }) { updated ->
                        if (screen == Screen.PROFILE_PICKER) showProfilePicker(updated) else displayedState = updated
                    }
                }
            }
            footer.addView(autoplay, LinearLayout.LayoutParams(0, dp(48), 1f))
        } else {
            footer.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        }
        footer.addView(parentIcon(), LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(footer)
    }

    private fun selectFromPicker(state: KidsState, profile: ChildProfile) {
        if (state.lockProfileSwitching && state.selectedProfileId != null && state.selectedProfileId != profile.id) {
            requestSwitchPin(profile.id)
        } else {
            selectProfile(profile.id)
        }
    }

    private fun addFlexibleSpace(target: LinearLayout, weight: Float) {
        target.addView(Space(this), LinearLayout.LayoutParams(1, 0, weight))
    }

    private fun addPickerTime(target: LinearLayout, profile: ChildProfile) {
        timeLabel = KidsUi.text(this, "", 11f, Color.WHITE, true).apply {
            id = R.id.kids_time_remaining
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        target.addView(timeLabel)
        showRemaining(profile.remainingMillis)
    }

    private fun backButton(action: () -> Unit): Button = KidsUi.button(this, "‹", KidsUi.ButtonStyle.GHOST).apply {
        textSize = 34f
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, 0)
        setTextColor(Color.WHITE)
        contentDescription = getString(R.string.kids_back)
        setOnClickListener { action() }
    }

    private fun parentIcon(): Button = KidsUi.button(this, "", KidsUi.ButtonStyle.GHOST).apply {
        id = R.id.kids_parent_entry
        contentDescription = getString(R.string.kids_parent_entry_description)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(12), dp(12), dp(12))
        KidsUi.icon(this, R.drawable.ic_kids_lock, Color.WHITE)
        setOnClickListener { if (!busy) startActivity(Intent(this@KidsChildActivity, KidsParentActivity::class.java)) }
    }

    private fun showProfileEditor(state: KidsState) {
        val profile = state.profiles.firstOrNull { it.id == state.selectedProfileId && it.enabled && it.remainingMillis > 0 }
        if (profile == null) {
            display(state)
            return
        }
        screen = Screen.PROFILE_EDIT
        displayedState = state
        chrome.setBackgroundColor(0xFF141414.toInt())
        @Suppress("DEPRECATION")
        window.statusBarColor = 0xFF141414.toInt()
        header.visibility = View.GONE
        navigation.visibility = View.GONE
        root.removeAllViews()
        root.setPadding(dp(16), dp(8), dp(16), dp(16))
        contentScroll.scrollTo(0, 0)
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(backButton { showProfilePicker(displayedState ?: state) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        top.addView(
            KidsUi.text(
                this,
                getString(R.string.kids_pick_picture, profile.name),
                if (isLandscape()) {
                    17f
                } else if (resources.configuration.screenWidthDp < 360) {
                    16f
                } else {
                    20f
                },
                Color.WHITE,
                true
            ).apply {
                gravity = Gravity.CENTER
                maxLines = 2
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        top.addView(parentIcon(), LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(top)
        val timeRow = LinearLayout(this).apply { gravity = Gravity.END }
        addPickerTime(timeRow, profile)
        root.addView(timeRow)
        addFlexibleSpace(root, 1f)
        val size = if (isLandscape()) 72 else 144
        root.addView(KidsAvatars.view(this, profile, size, selected = true), LinearLayout.LayoutParams(dp(size), dp(size)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val rename = KidsUi.button(this, "${profile.name}  ✎", KidsUi.ButtonStyle.GHOST).apply {
            id = R.id.kids_edit_profile_name
            contentDescription = getString(R.string.kids_edit_name)
            textSize = if (isLandscape()) 17f else 21f
            gravity = Gravity.CENTER
            minimumHeight = 0
            minHeight = 0
            setTextColor(Color.WHITE)
            setOnClickListener { editProfileName(profile) }
        }
        root.addView(rename, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (isLandscape()) 40 else 56)))
        addFlexibleSpace(root, 1f)
        val avatars = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val optionSize = if (isLandscape()) 50 else 68
        val optionGap = if (isLandscape()) 10 else 14
        KidsAvatars.options.forEach { option ->
            val example = profile.copy(avatarId = option.id)
            val choice = KidsAvatars.view(this, example, optionSize, selected = option.id == profile.avatarId).apply {
                tag = "kids-avatar-${option.id}"
                contentDescription = getString(R.string.kids_avatar_option, getString(option.label))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    updateProfile(profile.id, { it.copy(avatarId = option.id) }) { updated ->
                        if (screen == Screen.PROFILE_EDIT) showProfileEditor(updated) else displayedState = updated
                    }
                }
            }
            avatars.addView(choice, LinearLayout.LayoutParams(dp(optionSize), dp(optionSize)).apply { marginEnd = dp(optionGap) })
        }
        val carousel = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(avatars)
        }
        root.addView(carousel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (isLandscape()) 56 else 80)))
        val selectedIndex = KidsAvatars.options.indexOfFirst { it.id == profile.avatarId }.coerceAtLeast(0)
        carousel.post { carousel.smoothScrollTo(dp((selectedIndex - 1).coerceAtLeast(0) * (optionSize + optionGap)), 0) }
    }

    private fun editProfileName(profile: ChildProfile) {
        val input = EditText(this).apply {
            setText(profile.name)
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(40))
            setSelection(text.length)
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.kids_edit_name).setView(input)
            .setNegativeButton(R.string.kids_parent_cancel, null)
            .setPositiveButton(R.string.kids_parent_save, null).create()
        profileNameDialog = dialog
        dialog.setOnDismissListener { profileNameDialog = null }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isBlank()) {
                    input.error = getString(R.string.kids_setup_name_error)
                } else {
                    dialog.dismiss()
                    updateProfile(profile.id, { it.copy(name = name) }) { updated ->
                        if (screen == Screen.PROFILE_EDIT) showProfileEditor(updated) else displayedState = updated
                    }
                }
            }
        }
        dialog.show()
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
            Category.ALL to R.string.kids_approved_for_you,
            Category.VIDEOS to R.string.kids_category_videos,
            Category.PLAYLISTS to R.string.kids_category_playlists,
            Category.CHANNELS to R.string.kids_category_channels
        ).forEach { (value, title) ->
            val selected = category == value
            val chip = KidsUi.button(this, getString(title), KidsUi.ButtonStyle.GHOST).apply {
                gravity = Gravity.CENTER
                isSelected = selected
                minimumHeight = dp(if (isLandscape()) 38 else 44)
                background = KidsUi.ripple(this@KidsChildActivity, if (selected) Color.WHITE else 0x663B2E9C, 14)
                setTextColor(if (selected) KidsUi.ink else Color.WHITE)
                if (value == Category.ALL) KidsUi.icon(this, R.drawable.ic_kids_star, if (selected) KidsUi.ink else Color.WHITE, size = 20)
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
        val videosHeading = when {
            favorites -> getString(R.string.kids_show_favorites)
            activeTab == Tab.SEARCH -> getString(R.string.kids_category_videos)
            else -> getString(R.string.kids_videos_heading)
        }
        if (sources.isNotEmpty()) {
            if (activeTab != Tab.HOME) section(target, getString(R.string.kids_collections_heading))
            addSourceGrid(target, state, profile, sources)
        }
        if (videos.isNotEmpty()) {
            if (activeTab != Tab.HOME && !isLandscape()) section(target, videosHeading)
            addVideoGrid(target, videos.take(shown)) { item -> openVideo(item) }
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

    private fun addSourceGrid(target: LinearLayout, state: KidsState, profile: ChildProfile, sources: List<StoredSource>) {
        val columns = if (resources.configuration.screenWidthDp >= 600) 2 else 1
        sources.chunked(columns).forEach { items ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            items.forEachIndexed { index, source ->
                if (index > 0) row.addView(Space(this), LinearLayout.LayoutParams(dp(12), 1))
                row.addView(sourceCard(state, profile, source), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            if (items.size < columns) {
                row.addView(Space(this), LinearLayout.LayoutParams(dp(12), 1))
                row.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f))
            }
            addBlock(target, row, 12)
        }
    }

    private fun sourceCard(state: KidsState, profile: ChildProfile, source: StoredSource): View {
        val kind = if (source.kind == SourceKind.PLAYLIST) ParentContentKind.PLAYLIST else ParentContentKind.CHANNEL
        val item = state.catalog.firstOrNull { it.kind == kind && it.id == source.id }
        val title = item?.title ?: source.id.id
        val compact = isLandscape() && resources.configuration.screenHeightDp < 420
        val card = LinearLayout(this).apply {
            orientation = if (compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = KidsUi.shape(this@KidsChildActivity, 0xFF202020.toInt(), 16)
            clipToOutline = true
            isClickable = true
            setOnClickListener { showSource(state, profile, source) }
        }
        val imageSize = if (compact) 100 else 164
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = KidsUi.shape(this@KidsChildActivity, 0xFF53546D.toInt(), if (source.kind == SourceKind.CHANNEL) 100 else 12)
            clipToOutline = true
            contentDescription = getString(if (source.kind == SourceKind.CHANNEL) R.string.kids_channel_label else R.string.kids_playlist_label, title)
            setOnClickListener { showSource(state, profile, source) }
        }
        CoilHelper.loadThumbnail(image, item?.thumbnailUrl)
        card.addView(
            image,
            LinearLayout.LayoutParams(dp(imageSize), dp(imageSize)).apply {
                gravity = Gravity.CENTER
                setMargins(dp(12), dp(20), dp(12), dp(if (compact) 20 else 4))
            }
        )
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val titleButton = KidsUi.button(this, title, KidsUi.ButtonStyle.GHOST).apply {
            gravity = Gravity.CENTER
            textSize = if (compact) 15f else 19f
            setTextColor(Color.WHITE)
            maxLines = 2
            minHeight = 0
            minimumHeight = 0
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), dp(4), dp(8), 0)
            contentDescription = getString(if (source.kind == SourceKind.CHANNEL) R.string.kids_channel_label else R.string.kids_playlist_label, title)
            setOnClickListener { showSource(state, profile, source) }
        }
        textColumn.addView(titleButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val subtitle = KidsUi.text(this, getString(if (source.kind == SourceKind.CHANNEL) R.string.kids_parent_kind_channel else R.string.kids_parent_kind_playlist), 13f, 0xFFB7B7B7.toInt()).apply {
            gravity = Gravity.CENTER
        }
        textColumn.addView(subtitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) })
        card.addView(textColumn, if (compact) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return card
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
            background = KidsUi.shape(this@KidsChildActivity, 0xFF202020.toInt(), 16)
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
            setTextColor(Color.WHITE)
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
        addBlock(root, KidsUi.text(this, title, 25f, Color.WHITE, true), 14)
        addBlock(root, KidsUi.text(this, caption, 14f, 0xFFCAC8DA.toInt()), 7)
    }

    private fun section(target: LinearLayout, title: String) {
        addBlock(target, KidsUi.text(this, title, 16f, Color.WHITE, true), 18)
    }

    private fun emptyCard(message: String, viewId: Int = View.NO_ID) = addEmptyCard(root, message, viewId)

    private fun addEmptyCard(target: LinearLayout, message: String, viewId: Int = View.NO_ID) {
        val card = KidsUi.text(this, message, 15f, Color.WHITE).apply {
            id = viewId
            setPadding(dp(18), dp(22), dp(18), dp(22))
            background = KidsUi.shape(this@KidsChildActivity, 0xFF25243B.toInt(), 18)
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
        addBlock(root, KidsUi.text(this, state.catalog.firstOrNull { it.kind == ParentContentKind.VIDEO && it.id == video }?.title ?: video.id, 20f, Color.WHITE, true), 18)
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
        activeTab = Tab.PROFILE
        showProfilePicker(state)
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
                    if (displayedState?.selectedProfileId != id || screen == Screen.PROFILE_PICKER) {
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
                            if (screen != Screen.PROFILE_PICKER || pickerOpenedWithActiveSession) display(state)
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
