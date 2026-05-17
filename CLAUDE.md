# Notes for Claude Code

This file travels with the repo. Anything that should follow the project
across machines / contributors lives here — machine-specific quirks and
private user context stay in the per-machine memory at
`~/.claude/projects/.../memory/`.

## Project rules

### No hacky workarounds for tooling mismatches

When a build/tooling mismatch shows up (SDK folder missing, AGP doesn't
recognise a config flag, a version-format string isn't accepted), do NOT
solve it by:

- creating symlinks under `~/Library/Android/sdk/` (or its Windows/Linux
  equivalent)
- renaming SDK folders to coerce a folder-name match
- using `suppressUnsupportedCompileSdk` / similar silencer flags
- guessing version-format strings (`android-36-ext20`, etc.) to brute-force
  a lookup

Fix the **root cause** instead. Either let the official tooling download
the component it actually wants, or upgrade/downgrade the version
declaration (AGP, `compileSdk`, JDK, etc.) until the installed tooling
cleanly accepts it.

Filesystem hacks survive locally but break on next Android Studio sync,
on a fresh clone, on CI, or when the team upgrades AGP — and the failure
surfaces months later as a mysterious build error nobody can connect back
to the rename.

If the clean fix needs a download / version bump / explicit user consent,
ask before doing it. Don't sneak around the missing piece.

## Build setup

### Versions (must match across local + CI)

| Tool | Version | Source of truth |
|------|---------|-----------------|
| AGP | 8.9.1 | `companion_android/build.gradle.kts` |
| Kotlin | 2.0.21 | `companion_android/build.gradle.kts` |
| Gradle | 8.12 (via wrapper) | `companion_android/gradle/wrapper/gradle-wrapper.properties` |
| JDK toolchain | 21 | `companion_android/app/build.gradle.kts` (`jvmToolchain(21)`) |
| `compileSdk` / `targetSdk` | 36 (Android 16) | `companion_android/app/build.gradle.kts` |
| `buildToolsVersion` | 36.1.0 (pinned) | `companion_android/app/build.gradle.kts` |
| `minSdk` | 31 (Android 12) | `companion_android/app/build.gradle.kts` |

JVM bytecode target stays at **17** (`compileOptions` + Kotlin
`jvmTarget = JVM_17`) even though the toolchain compiles with JDK 21 —
keeps the DEX-friendly target without forcing every dev to install JDK 17.

### Local (macOS)

JDK 21 is mandatory. Gradle 8.12 will not auto-provision JDK 17 from a
Mac without a toolchain download repository configured, so just use the
JBR bundled with Android Studio.

```bash
cd companion_android
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :app:assembleDebug
```

Add the `JAVA_HOME` export to `~/.zshrc` to drop the prefix.

### th_TH locale gotcha (do not remove)

`companion_android/gradle.properties` forces
`-Duser.country=US -Duser.language=en` for the build JVM. **Keep that
flag** — without it, builds fail on any Thai-locale system with:

```
> Task :app:mergeDebugJavaResource FAILED
   > com.google.common.base.VerifyException (no error message)
```

Root cause: apkzlib's `MsDosDateTimeUtils.packDate` calls
`Calendar.getInstance()` without specifying locale. On `th_TH`, Java
falls back to `BuddhistCalendar` which reports the year in พ.ศ.
(e.g. 2569 instead of 2026). `packDate` then computes
`yearOffset = 2569 - 1980 = 589` and trips
`Verify.verify(yearOffset < 128)`. The ZIP year field is only 7 bits.

The flag affects only the build JVM, not the APK runtime locale.

### CI (`.github/workflows/release-apk.yml`)

Runs on `ubuntu-latest` (`en_US.UTF-8` by default — no Buddhist
calendar issue, but the locale flag in `gradle.properties` is still
applied for consistency). Pin the runner JDK to 21 to match the
project toolchain. The `r0adkll/sign-android-release` step's
`BUILD_TOOLS_VERSION` env var must track `buildToolsVersion` in
`app/build.gradle.kts`.

## Release process

See `RELEASE.md` for the tag scheme + signing-secret list. The
`/release` slash command in `.claude/commands/release.md` automates
"bump version → tag → push → watch CI", but its repo path hardcodes
`G:/CarCompanian/KuroCompanionApp` (Windows). On macOS, run the steps
manually or edit the path before invoking.

## Repo layout reminder

- `companion_android/` — Android Studio project (Kotlin/Compose)
- `assets-pack/<persona>/` — audio + GIF + manifest, released separately
- `tools/build_assets_manifest.py` — used by `release-assets.yml`
- `.github/workflows/` — `release-apk.yml` + `release-assets.yml`
- Two release streams, two tag patterns: `app/vX.Y.Z` for the APK,
  `assets/<persona>/vX.Y.Z` for asset packs. See `RELEASE.md`.
