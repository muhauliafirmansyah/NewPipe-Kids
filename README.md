# NewPipe-Kids

NewPipe-Kids is an independent, experimental source-code fork of [NewPipe](https://github.com/TeamNewPipe/NewPipe) exploring a parent-curated video experience for children. This project is **not affiliated with or endorsed by TeamNewPipe or NewPipe e.V.**

## Status

**Early development: not ready for children.** The experimental child launcher, parent controls, session timer, approved-library search, and player are connected. The redesigned screens have been checked on an emulator; earlier flows were also tested on a physical Android phone. The redesign still needs a physical-device review, and the app has not yet been audited for all bypasses or validated for production use. The Kids flow supports YouTube, PeerTube, and media.ccc.de; parents can choose a PeerTube server. The current player supports direct video streams, including separate video and audio streams where available.

The development build uses NewPipe Extractor v0.27.0 and an isolated YouTube playback fallback. The fallback resolves only the video already selected for playback; it does not choose or approve content. Playback depends on upstream service behavior and needs continued regression testing.

Development builds use their own Android application ID, so they do not replace an official NewPipe installation. Data from earlier builds using the upstream application ID is not migrated automatically. The application ID and development name are not a final release identity.

This repository is for source development. It does not provide an end-user release; a distinct application name will be needed before distributing a modified app under [NewPipe's trademark policy](https://newpipe-ev.de/policy/trademark/).

## Project documents

- [Concept and agreed behavior](CONCEPT.md)
- [User flows](USER_FLOW.md)
- [Task tracker](TASKS.md)
- [Release and attribution review](RELEASE_REVIEW.md)
- [Upstream NewPipe README](doc/README.md)

## Building and testing

The Android project uses JDK 21, the Android SDK, and the Gradle wrapper. From the repository root, run:

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest --tests 'org.schabi.newpipe.kids.*'
```

The Kids instrumentation tests require a connected Android device or emulator.
Use Android 10 or newer for the complete dialog-automation suite, and use a
separate development installation with no real parent PIN or child profiles:
tests that modify the main Kids state skip configured installations and restore the
original state after running. The debug application ID includes the current branch
name, so check the test runner with `adb shell pm list instrumentation` after installing.

For wireless debugging over Tailscale, enable Wireless debugging on the phone and
use its current debugging port with the phone's Tailscale IP. Pair first if this
computer has not already been paired; the pairing port and code are separate from
the debugging port. The debugging port may change when Wireless debugging restarts.

```sh
adb pair "$TAILSCALE_IP:$PAIRING_PORT"  # Enter the pairing code when prompted.
adb connect "$TAILSCALE_IP:$DEBUG_PORT"
```

To build and install the test packages, select a device with `ANDROID_SERIAL` and run:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Set `TEST_RUNNER` to the test package and runner shown by `pm list instrumentation`
(for example, `io.github.muhauliafirmansyah.newpipekids.debug.kidsdevelopment.test/androidx.test.runner.AndroidJUnitRunner`
on the `kids-development` branch). Then run the Kids suite with:

```sh
adb -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e package org.schabi.newpipe.kids "$TEST_RUNNER"
```

Live service tests require `-e live true`. To test the actual child-mode refresh
interval, run the following while the device stays unlocked and the test activity
remains in the foreground for at least 15 minutes:

```sh
adb -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e class org.schabi.newpipe.kids.KidsPeriodicSourceRefreshLiveTest#childModeRefreshesWhileItRemainsOpen \
  -e live true -e periodic true -e periodicMode child "$TEST_RUNNER"
```

For Parent mode, use the `parentModeRefreshesWhileItRemainsOpen` method and
`-e periodicMode parent`. Tests report skipped cases separately, so check the
instrumentation output as well as its final result.

## Attribution and license

This project builds on the work of [TeamNewPipe and its contributors](https://github.com/TeamNewPipe/NewPipe). NewPipe-Kids is distributed under the GNU General Public License, version 3 or later; see [LICENSE](LICENSE) and the original copyright notices in the source files.
