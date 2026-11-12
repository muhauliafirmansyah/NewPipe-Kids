# NewPipe-Kids

NewPipe-Kids is an independent, experimental source-code fork of [NewPipe](https://github.com/TeamNewPipe/NewPipe) exploring a parent-curated video experience for children. This project is **not affiliated with or endorsed by TeamNewPipe or NewPipe e.V.**

## Status

**Early development: not ready for children.** The experimental child launcher, parent controls, session timer, approved-library search, and player are connected. Emulator tests cover several of these paths, but the complete experience has not been tested on a physical device or audited for all bypasses. The Kids flow supports YouTube, PeerTube, and media.ccc.de; parents can choose a PeerTube server. The current player supports direct video streams, including separate video and audio streams where available.

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

The Kids instrumentation tests require a connected Android device or emulator. Live YouTube tests are opt-in because they depend on network access and the upstream service.

## Attribution and license

This project builds on the work of [TeamNewPipe and its contributors](https://github.com/TeamNewPipe/NewPipe). NewPipe-Kids is distributed under the GNU General Public License, version 3 or later; see [LICENSE](LICENSE) and the original copyright notices in the source files.
