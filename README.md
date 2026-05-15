# Kuro Companion App

Android-side of the CarCompanion stack — the head-unit app that drives a
soul engine, broadcasts reactions to an ESP32-S3 dashboard robot, and
receives OBD telemetry from an ESP32-C3 bridge.

The firmware halves live in separate repos. This one is **app-only**:
Kotlin + Compose for the HUD, plus the asset packs that ship the
persona's voice and animation.

## Layout

```
companion_android/        Android Studio project (Kotlin/Compose)
assets-pack/<persona>/    WAV + GIF + manifest.json — released separately from APK
tools/                    build_assets_manifest.py
.github/workflows/        release-apk.yml + release-assets.yml
RELEASE.md                tag scheme, signing secrets, release instructions
```

## Build locally

```bash
cd companion_android
gradle assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

First-launch flow: APK ships **without** the audio/gif pack — the app
downloads it from a GitHub Release on first run. See
[RELEASE.md](./RELEASE.md) for the tag scheme.

## Soul engine status

Implementation phases shipped:
- Phase 0 — Wake-up gate (RobotPresence + TripPhaseAnalyzer + WakeOrchestrator)
- Phase 1 — Severity buckets on every event
- Phase 2 — ReactionPolicy gate (priority + speech budget)
- Phase 3 — EpisodeTracker (ROUGH_ROAD, SUSTAINED_BRAKE, STUCK_TRAFFIC)
- Phase 4 — PatternDetector (UNSAFE_OVERTAKE, EMERGENCY_BRAKE)
- Phase 5 — TripPhase 7-state machine + NAV_NEAR_DESTINATION
- Phase 6 — MusicSession (MediaSessionManager integration)
- Asset-pack download / version-check pipeline

Unit-test count: 76 (JUnit) covering pure-logic analyzers.

## Releases

See [RELEASE.md](./RELEASE.md) for the full process. TL;DR:

```bash
# Cut an assets release
git tag assets/kuro/v1.0.0 && git push origin assets/kuro/v1.0.0

# Cut an APK release
git tag app/v0.2.0 && git push origin app/v0.2.0
```

Both pipelines drop signed artifacts on the GitHub Releases page; the
app at runtime checks the latest `assets/<persona>/v*` tag against its
installed manifest and prompts the user to update.
