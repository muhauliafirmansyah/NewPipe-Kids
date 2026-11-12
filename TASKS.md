# NewPipe Kids task tracker

Scope and agreed behavior are in [CONCEPT.md](CONCEPT.md); screen-level paths
are in [USER_FLOW.md](USER_FLOW.md). Check a task only when its behavior is
implemented and verified.

**Current state:** Approval and session rules have focused unit tests. A local
profile and library storage model has been added and its serialization tested;
it is not yet connected to screens or playback or verified on a device. The app
is not yet ready for use as a child-restricted experience.

## 1. Foundations

- [x] Clone NewPipe and build a debug APK.
- [x] Connect and deploy to an Android device over Tailscale and ADB.
- [x] Implement and test standalone approval rules, including overlapping
  approvals and explicit video hides.
- [x] Implement and test standalone active-use session-time calculations,
  including the 1–120 minute limit.
- [ ] Choose and implement persistent, per-profile storage for profiles,
  approvals, hidden items, library snapshots, history, favorites, settings,
  and remaining session time.
  - [x] Add a versioned local storage model and test serialization and profile
    isolation.
  - [ ] Connect storage to app flows and verify persistence on a device.
- [ ] Define stable service-qualified content identifiers and map extractor
  results to them consistently.
- [ ] Establish a maintainable solution for kids-video playback. The older
  extractor played the test video, but required temporary compatibility edits;
  the newer extractor returned an unavailable error for that video.

## 2. Parent setup and profiles

- [ ] Create first-run setup for a parent PIN and initial child profile.
  - [x] Implement and unit-test atomic setup of the PIN and first profile.
  - [ ] Build and connect the first-run screens.
- [ ] Protect Parent mode with the PIN.
  - [x] Implement and unit-test salted PIN setup, verification, and change.
  - [x] Connect parent profile and approval operations to storage with a PIN
    check for each action; test rejected changes leave stored state unchanged.
  - [ ] Connect authorization and failed-attempt limits to Parent mode and
    verify PIN behavior on Android devices.
- [ ] Let parents add, rename, remove, disable, and re-enable profiles.
  - [x] Implement and unit-test profile management rules.
  - [ ] Connect the rules to authenticated Parent mode and local storage.
- [ ] Hide disabled profiles from the child profile picker.
  - [x] Implement and unit-test enabled-profile filtering.
  - [ ] Connect the filter to the child profile picker.
- [ ] Add the optional parent lock for profile switching.
- [ ] Preserve each profile's session when switching profiles.

## 3. Parent content management

- [ ] Show the approved-content list first, including an empty state.
- [ ] Let parents open, disable or hide, re-enable, and remove approvals.
- [ ] Add a screen to search and browse videos, channels, and playlists, open
  them for review, and approve them for selected profiles.
- [ ] Explain that channel approval includes future uploads and playlist
  approval includes future additions.
- [ ] Resolve approvals from individual videos, channels, and playlists;
  preserve access while at least one enabled approval covers a video.
  - [x] Implement and unit-test approval, removal, source disabling, and
    complete source-snapshot replacement across profiles.
  - [ ] Connect these operations to Parent mode, storage, and source refreshes.
- [ ] Make an explicit video hide override all approvals for that profile.
  - [x] Implement and unit-test the video hide operation and its precedence.
  - [ ] Connect the operation to Parent mode and playback enforcement.
- [ ] Refresh approved channels and playlists on app open and periodically
  while in use; update additions and removals.
- [ ] Preserve the last successful library on refresh failure and continue
  applying local hides and approval removals.

## 4. Child experience and playback

- [ ] Build child profile selection and a simple approved-content home.
- [ ] Limit browsing, search, related content, and recommendations to the
  active profile's approved library.
- [ ] Enforce approval checks at every playback entry point, including links,
  playlists, queues, background playback, and profile changes.
- [ ] Stop playback if the current video loses its final approval or is hidden.
- [ ] Track watch history and favorites separately for each profile; mark a
  video watched after 90% has played.
- [ ] Enable autoplay by default and let children toggle it. Prefer unwatched
  approved videos, then the video least recently watched.
- [ ] Follow playlist order when autoplay is enabled; after the playlist ends,
  use general autoplay rules. With autoplay off, stop after each video but
  provide a manual Next button.
- [ ] Show a simple unavailable-video message and skip unavailable videos
  during autoplay.

## 5. Session limits

- [ ] Let parents set each session limit between 1 minute and 2 hours and
  reset a session. Do not add a daily limit for now.
  - [x] Implement and unit-test limit changes and explicit session resets.
  - [ ] Connect these operations to authenticated Parent mode.
- [ ] Count active app use, including browsing and playback; pause the timer
  when the app is backgrounded and persist the remaining time.
- [ ] Design and show a countdown warning before the session ends.
- [ ] Stop playback at the limit and require parent authorization to start
  another session.

## 6. Verification and release readiness

- [ ] Test profile isolation, profile switching, disabled profiles, and PIN
  protection on a device.
- [ ] Test approval overlaps, hides, removals, source updates, and failed
  refreshes across all browsing and playback paths.
- [ ] Test session expiry, backgrounding, app restarts, and parent reset.
- [ ] Test playlist ordering, autoplay choices, unavailable videos, and
  representative made-for-kids videos on a physical Android device.
- [ ] Review NewPipe's GPL obligations and trademark policy before distributing
  a modified app.
