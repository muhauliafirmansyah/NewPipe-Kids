# NewPipe Kids task tracker

Scope and agreed behavior are in [CONCEPT.md](CONCEPT.md); screen-level paths
are in [USER_FLOW.md](USER_FLOW.md). Check a task only when its behavior is
implemented and verified.

**Current state:** The experimental launcher, parent screens, child library,
session timer, and player are connected. Unit tests, Android 15 emulator tests,
and Android 16 phone tests cover selected flows, including playback and stopping
after a hide. The redesigned screens have been checked on an emulator; the prior
phone tests predate the redesign. Physical review of the new UI, the phone's
15-minute refresh verification, and a broader bypass review are still outstanding;
the app is not yet ready for use as a child-restricted experience. The Kids flow
supports YouTube, PeerTube, and media.ccc.de, using
direct or merged video streams.
Playback uses NewPipe Extractor v0.27.0 and an isolated YouTube fallback.

## 1. Foundations

- [x] Clone NewPipe and build a debug APK.
- [x] Connect and deploy to an Android device over Tailscale and ADB.
- [x] Implement and test standalone approval rules, including overlapping
  approvals and explicit video hides.
- [x] Implement and test standalone active-use session-time calculations,
  including the 1–120 minute limit.
- [x] Choose and implement persistent, per-profile storage for profiles,
  approvals, hidden items, library snapshots, history, favorites, settings,
  and remaining session time.
  - [x] Add a versioned local storage model and test serialization and profile
    isolation.
  - [x] Verify test-only Android preferences and PIN/profile persistence across
    an app-process restart on an Android 15 emulator.
  - [x] Connect app flows to transactional SQLite storage and verify migration
    and a force-stop/restart on an emulator.
- [x] Define stable service-qualified content identifiers and map extractor
  results to them consistently.
  - [x] Normalize YouTube video IDs and link variants for approval, hide,
    source-snapshot, and playback-policy operations; test alias equivalence.
  - [x] Support and verify identities for PeerTube and media.ccc.de videos and
    supported sources; connect identity mapping to app entry points. The
    audio-only services are outside this video-app concept.
- [x] Keep the fork's upstream extractor and use an isolated, validated
  fallback for YouTube streams when needed. The reported kids video and an
  ordinary video both played for over one minute on an emulator. The fallback
  depends on an unofficial service protocol and needs ongoing regression tests.

## 2. Parent setup and profiles

- [x] Create first-run setup for a parent PIN and initial child profile.
  - [x] Implement and unit-test atomic setup of the PIN and first profile.
  - [x] Build the setup screen and test field validation on an emulator.
  - [x] Connect the screen to the launcher.
  - [x] Verify the complete launcher-to-parent setup flow on an emulator.
- [x] Protect Parent mode with the PIN.
  - [x] Implement and unit-test salted PIN setup, verification, and change.
  - [x] Connect parent profile and approval operations to storage with a PIN
    check for each action; test rejected changes leave stored state unchanged.
  - [x] Persist and unit-test a temporary lock after repeated incorrect PINs.
  - [x] Connect authorization and failed-attempt limits to Parent mode; verify
    unlock behavior on an emulator.
- [x] Let parents add, rename, remove, disable, and re-enable profiles.
  - [x] Implement and unit-test profile management rules.
  - [x] Connect and test the rules in authenticated Parent mode on an emulator.
- [x] Hide disabled profiles from the child profile picker.
  - [x] Implement and unit-test enabled-profile filtering.
  - [x] Connect and test the filter in the child profile picker.
- [x] Add the optional parent lock for profile switching.
- [x] Preserve each profile's session when switching profiles.

## 3. Parent content management

- [x] Show the approved-content list first, including an empty state.
  - [x] Build the parent list and verify its entry point on an emulator.
  - [x] Connect Parent mode to the child launcher.
  - [x] Verify removing an individual approval and hiding an overlapping
    playlist video through Parent mode on an emulator.
  - [x] Verify source and video hide, re-enable, and removal actions on an emulator.
- [x] Let parents open, disable or hide, re-enable, and remove approvals.
  - [x] Add hide, re-enable, and removal actions to the parent list.
  - [x] Add content preview and browsing for videos within approved sources.
  - [x] Verify hide, re-enable, and removal actions on an emulator.
  - [x] Verify video preview playback on an emulator.
- [x] Add a screen to search and browse videos, channels, and playlists, open
  them for review, and approve them for selected profiles.
  - [x] Build parent search and profile assignment; verify a real video search
    and approval on an emulator.
  - [x] Add preview and source browsing to parent search.
  - [x] Verify real channel and playlist approval and refresh on an emulator.
  - [x] Verify channel and playlist search-to-approval UI flows on an emulator.
- [x] Explain that channel approval includes future uploads and playlist
  approval includes future additions.
- [x] Resolve approvals from individual videos, channels, and playlists;
  preserve access while at least one enabled approval covers a video.
  - [x] Implement and unit-test approval, removal, source disabling, and
    complete source-snapshot replacement across profiles.
  - [x] Connect these operations to Parent mode, storage, and source refreshes.
- [x] Make an explicit video hide override all approvals for that profile.
  - [x] Implement and unit-test the video hide operation and its precedence.
  - [x] Connect the operation to Parent mode and playback enforcement; test
    hiding a playing video on an emulator.
- [x] Refresh approved channels and playlists on app open and periodically
  while in use; update additions and removals.
  - [x] Reject out-of-order source refresh results and refreshes for removed
    and re-added approvals in the library model.
  - [x] Coordinate refreshes with storage and test failed fetches, overlapping
    refreshes, and a parent hiding content during a refresh.
  - [x] Implement YouTube channel and playlist pagination with unit tests for
    complete, failed, and looping page sequences.
  - [x] Verify a public YouTube playlist and channel refresh on an emulator.
  - [x] Schedule refreshes on app open and every 15 minutes while Parent or
    child mode remains active; verify the app-open refresh on an emulator.
  - [x] Verify the full 15-minute refresh interval in Parent mode on an emulator.
  - [x] Verify PeerTube and media.ccc.de channel refresh and PeerTube playlist
    refresh on an emulator.
- [x] Preserve the last successful library on refresh failure and continue
  applying local hides and approval removals.
  - [x] Verify an unreachable source keeps cached videos while hides and
    removals still take effect on an emulator.

## 4. Child experience and playback

- [x] Build child profile selection and a simple approved-content home.
  - [x] Connect profile selection, search, favorites and approved playlists to
    a child home; test profile isolation and hidden videos on an emulator.
  - [x] Verify approved video, channel, and playlist filtering on an emulator.
- [x] Limit browsing, search, related content, and recommendations to the
  active profile's approved library.
- [x] Enforce approval checks at every playback entry point, including links,
  playlists, queues, background playback, and profile changes.
  - [x] Disable the legacy launcher, router, and player service, and check the
    active child profile before and during the new player. Emulator tests cover
    an unapproved link and revocation during playback.
  - [x] Audit exported components and test that only the guarded launcher and
    permission-protected system components are externally reachable.
  - [x] Confirm an external shell cannot start Parent mode, setup, the upstream
    router, or the upstream player service on an emulator.
- [x] Stop playback if the current video loses its final approval or is hidden.
- [x] Track watch history and favorites separately for each profile; mark a
  video watched after 90% has played.
  - [x] Implement and unit-test the watch threshold and per-profile history
    update rules, excluding seeks and repeated segments.
  - [x] Connect playback sampling, history persistence, and favorite controls.
  - [x] Verify watch history and favorites through the child interface on an emulator.
- [x] Enable autoplay by default and let children toggle it. Prefer unwatched
  approved videos, then the video least recently watched.
  - [x] Implement and unit-test selection among approved available videos.
  - [x] Connect selection and the toggle to the player.
- [x] Follow playlist order when autoplay is enabled; after the playlist ends,
  use general autoplay rules. With autoplay off, stop after each video but
  provide a manual Next button.
  - [x] Implement and unit-test choosing the next approved available playlist
    item in order.
  - [x] Connect playlist progression and the Next button to the player.
  - [x] Verify playlist playback, the toggle, Next, and the transition to general
    autoplay with real videos on an emulator.
- [x] Show a simple unavailable-video message and skip unavailable videos
  during autoplay.

## 5. Session limits

- [x] Let parents set each session limit between 1 minute and 2 hours and
  reset a session. Do not add a daily limit for now.
  - [x] Implement and unit-test limit changes and explicit session resets.
  - [x] Connect and verify these operations in authenticated Parent mode on an
    emulator.
- [x] Count active app use, including browsing and playback; pause the timer
  when the app is backgrounded and persist the remaining time.
  - [x] Connect the foreground timer and verify background pausing on an emulator.
  - [x] Verify expiry, recreation, parent reset, and storage restart on an emulator.
- [x] Design and show a countdown warning before the session ends.
- [x] Stop playback at the limit and require parent authorization to start
  another session.

## 6. Verification and release readiness

- [x] Test profile isolation, profile switching, disabled profiles, and PIN
  protection on an emulator.
- [x] Test approval overlaps, hides, removals, source updates, failed refreshes,
  shared links, and background playback using unit and emulator tests.
- [x] Test session expiry, backgrounding, an app-process restart, and parent
  reset on an emulator.
- [x] Test playlist ordering, autoplay choices, unavailable videos, and
  representative made-for-kids videos on a physical Android device.
  - [x] Verify rendered video frames, unavailable-video rejection, and more
    than 70 seconds of muted test-player playback for the reported kids video
    and an ordinary video on a Galaxy S24 Ultra with Android 16.
- [ ] Verify first-run setup, persistent storage, periodic refresh, and the
  complete child flow on a physical Android device.
  - [x] Verify test-only preferences and SQLite persistence across an app-process
    restart, plus a 3,000-video cached library, on a Galaxy S24 Ultra with
    Android 16.
  - [x] Verify first-run setup, profile management, child navigation, and
    app-open source refresh in Parent and child modes on the same phone.
  - [x] Verify the child session survives an app-process restart without
    counting time in the background on the same phone.
  - [ ] Verify the full 15-minute refresh interval while the phone remains in
    the foreground; previous attempts were interrupted.
  - [ ] Repeat live parent search, approval dialogs, and preview on the phone
    while the app has focus; the previous run lost focus to a system window.
- [x] Verify approval and offline-refresh behavior across browsing and playback
  paths on a physical Android device.
  - [x] Verify live YouTube search, channel and playlist approvals and hides,
    and PeerTube playlist refresh at the library level on the same phone.
  - [x] Verify a failed refresh preserves the cached library while local hides
    and removal still block browsing and playback access at the policy level.
  - [x] Verify cached browsing, shared links, approval overlap, removal, hides,
    session expiry, and background playback with activity tests on the phone.
- [x] Limit device-test dialog actions to the development app and verify that
  test helpers ignore other apps' windows.
- [ ] Complete a broader bypass and safety review before recommending the app
  as a child-restricted experience.
- [x] Review NewPipe's GPL obligations and trademark policy; record findings
  in [RELEASE_REVIEW.md](RELEASE_REVIEW.md).
- [ ] Before an end-user release, choose a distinct app name and identity and
  provide the corresponding source and notices required for that release.

## 7. Child-friendly UI refresh

- [x] Add Home, Search, Your stuff, and Profile navigation around the approved
  library, with category filters and profile-specific favorites.
- [x] Redesign setup, parent management, collections, and playback screens with
  a consistent visual style and accessible controls.
- [x] Verify navigation, approved-content filtering, rotation, portrait and
  landscape layouts, and live playback on an Android emulator.
- [ ] Review the redesigned screens and playback on a physical Android device.
