# NewPipe-Kids

NewPipe-Kids is an independent, experimental source-code fork of [NewPipe](https://github.com/TeamNewPipe/NewPipe) exploring a parent-curated video experience for children. This project is **not affiliated with or endorsed by TeamNewPipe or NewPipe e.V.**

## Status

**Early development: not ready for children.** The current Kids code provides initial approval, profile, parent PIN, storage, and session-time components with focused unit tests. Those components are not yet integrated with the app's screens or playback. The existing NewPipe interface can still reach unrestricted content, and session limits are not enforced.

This repository is for source development. It does not provide an end-user release; a distinct application name will be needed before distributing a modified app under [NewPipe's trademark policy](https://newpipe-ev.de/policy/trademark/).

## Project documents

- [Concept and agreed behavior](CONCEPT.md)
- [User flows](USER_FLOW.md)
- [Task tracker](TASKS.md)
- [Upstream NewPipe README](doc/README.md)

## Building and testing

The Android project uses JDK 21, the Android SDK, and the Gradle wrapper. From the repository root, run:

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest --tests 'org.schabi.newpipe.kids.*'
```

A connected Android device or emulator is required for instrumented tests. The Kids-specific device test has not yet been run.

## Attribution and license

This project builds on the work of [TeamNewPipe and its contributors](https://github.com/TeamNewPipe/NewPipe). NewPipe-Kids is distributed under the GNU General Public License, version 3 or later; see [LICENSE](LICENSE) and the original copyright notices in the source files.
