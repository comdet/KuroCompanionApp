# Release process

The Android app and the persona asset packs ship as two independent
release streams. Both are driven by tags + GitHub Actions; nothing on
your laptop needs to talk to the Play Store or a signing server.

## Tag scheme

| Stream | Tag pattern | Triggers | Workflow |
|--------|-------------|----------|----------|
| APK | `app/v0.X.Y` | `.github/workflows/release-apk.yml` | builds + signs debug + release APKs |
| Assets per persona | `assets/<persona>/v1.X.Y` | `.github/workflows/release-assets.yml` | builds manifest + zips `assets-pack/<persona>` |

Examples:

```bash
# Cut an app release
git tag app/v0.2.0
git push origin app/v0.2.0

# Cut a Kuro voice-pack release
git tag assets/kuro/v1.0.0
git push origin assets/kuro/v1.0.0
```

The workflow creates / updates a GitHub Release page named after the
tag and attaches the artifacts.

## GitHub Secrets needed for signed APK

`release-apk.yml` signs the release APK only when these four secrets are
present (debug APK is always signed with the AGP debug keystore — no
secrets required).

| Secret | Value |
|--------|-------|
| `RELEASE_KEYSTORE_BASE64` | `base64 -w 0 release.jks` of the JKS / keystore file |
| `RELEASE_KEY_ALIAS` | alias inside the keystore |
| `RELEASE_KEYSTORE_PASSWORD` | keystore password |
| `RELEASE_KEY_PASSWORD` | key password (often same as keystore password) |

Until the secrets land, the workflow falls back to publishing the
unsigned release APK (still acceptable for sideload on HUD-class
devices).

## What the app downloads

`BuildConfig.MIN_ASSETS_VERSION` in `companion_android/app/build.gradle.kts`
is the minimum asset-pack version the APK can work with. On first launch
the app checks `<externalFilesDir>/assets/<persona>/manifest.json`; if
missing or `version < MIN_ASSETS_VERSION`, it shows the download screen
which pulls

```
https://github.com/${REPO_OWNER}/${REPO_NAME}/releases/download/assets/${persona}/${version}/${persona}-assets-${version}.zip
```

The ZIP carries a `manifest.json` with sha256 per file. The downloader
verifies every file before swapping into place, so a corrupted download
can't trash an already-installed pack.

## Bumping an asset pack

1. Drop the new content into `assets-pack/<persona>/audio/<event>/` /
   `assets-pack/<persona>/gif/<emotion>/`.
2. Optionally regenerate the local manifest for inspection:
   ```bash
   python tools/build_assets_manifest.py assets-pack/kuro v1.1.0
   ```
3. `git add` the new files, commit, push.
4. Tag and push the release tag:
   ```bash
   git tag assets/kuro/v1.1.0
   git push origin assets/kuro/v1.1.0
   ```
5. The workflow rebuilds the manifest (authoritative) and attaches the
   zip + manifest JSON to the release.
6. The app on the HUD picks up the new version on next launch and
   prompts the user (banner if already installed; blocking screen if
   the APK was bumped to require it).

## Bumping the APK

1. Edit `versionCode` / `versionName` in
   `companion_android/app/build.gradle.kts`.
2. If the new code needs a newer asset pack, also bump
   `MIN_ASSETS_VERSION` to match.
3. Commit + tag:
   ```bash
   git tag app/v0.2.0
   git push origin app/v0.2.0
   ```
4. Release page goes up at
   `https://github.com/<owner>/<repo>/releases/tag/app/v0.2.0`.
