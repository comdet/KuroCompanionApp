---
description: Cut a new KuroCompanionApp release (bump, tag, push, monitor).
---

# /release — cut a new app release

You are about to cut a new release of the KuroCompanionApp (Android).
This command bundles "what changed since the last tag" into a single
versioned release, pushes the tag, and waits for the GitHub Actions
workflow to publish signed APKs.

User can pass an optional argument:
- `/release` (no args) — auto-detect bump from commit messages
- `/release patch` — force a patch bump (0.X.Y → 0.X.Y+1)
- `/release minor` — force a minor bump (0.X.Y → 0.(X+1).0)
- `/release major` — force a major bump (X.Y.Z → (X+1).0.0)

## Repo location

The repo is at `G:/CarCompanian/KuroCompanionApp/`. Run all git commands
from there. If the current shell isn't in that dir, pass `git -C "G:/CarCompanian/KuroCompanionApp"`
or `cd` into it once at the start.

## Steps

### 1. Verify clean state

```bash
git -C G:/CarCompanian/KuroCompanionApp status --short
```

If anything is uncommitted, **stop and ask the user** — never auto-commit
random in-progress changes into a release. The release script only deals
with the version bump itself.

### 2. Find the last app release

```bash
git -C G:/CarCompanian/KuroCompanionApp tag --sort=-v:refname | grep '^app/v' | head -1
```

Strip the `app/` prefix → that's `LAST_VERSION`. Example: `app/v0.2.3` → `v0.2.3`.

### 3. Collect commits since LAST_VERSION

```bash
git -C G:/CarCompanian/KuroCompanionApp log "app/$LAST_VERSION..HEAD" \
    --pretty=format:'%h %s' --no-merges
```

Filter out commits that touch **only** files outside the Android app:
- `assets-pack/**`   — those belong to the assets-release stream
- `.github/**`, `tools/**`, `README.md`, `RELEASE.md`, `.gitignore`

To check what a commit touched: `git show --stat <hash>`. Drop a commit
from the release list if every changed path matches the above patterns.

### 4. Group commits by intent

Classify each commit's first line into one bucket. Look at the leading
keyword (case-insensitive) or the dominant verb:

| Bucket    | Triggers                                       | Counts toward |
|-----------|-----------------------------------------------|---------------|
| **feat**  | `feat:`, "add", "new", "implement", "support" | minor bump    |
| **fix**   | `fix:`, "fix", "correct", "resolve"           | patch bump    |
| **perf**  | "perf", "optimize", "faster"                  | patch bump    |
| **refactor** | "refactor", "rename", "move", "cleanup"    | patch bump    |
| **docs**  | "doc", "comment", "readme"                    | not shown     |
| **chore** | "bump", "version", "chore"                    | not shown     |

If a commit is ambiguous, ask the user OR put it in `chore` (silent).

### 5. Pick the version bump

- If user passed an explicit arg (`patch`/`minor`/`major`) → use that.
- Else, auto-decide:
  - Any `feat` commit → **minor**
  - Only `fix` / `perf` / `refactor` → **patch**
  - `BREAKING:` keyword anywhere → **major** (but stop and confirm)
- Default if no obvious commits → **patch**

Compute the new version from current versionName in
`companion_android/app/build.gradle.kts` (e.g. `0.2.3` + patch → `0.2.4`).

### 6. Show the summary to the user

Format like a release notes draft:

```
Proposed release: app/v0.2.4   (patch — current 0.2.3)

Commits since app/v0.2.3:

  Fixes:
    abc1234  Overlay clamp + reset position
    def5678  Fix volume 100 wrap on overlay
    ...

  Refactors:
    ...

Skipping (not Android app):
    1234abc  Update RELEASE.md
    ...
```

**Wait for user confirmation** before touching anything. Offer:
- "Confirm with this version"
- "Change to (patch/minor/major)"
- "Abort"

### 7. Bump build.gradle.kts

Edit `companion_android/app/build.gradle.kts`:

- `versionCode` → current + 1
- `versionName` → the new version (e.g. `"0.2.4"`)

Both are on lines like:
```kotlin
versionCode = 5
versionName = "0.2.3"
```

### 8. Commit + tag + push

Commit message format:

```
Release vX.Y.Z

Fixes:
- short description (commit hash)
- ...

Refactors:
- ...
```

The body shows up in the auto-generated release notes too, so make it
human-readable, not raw shell output.

```bash
git -C G:/CarCompanian/KuroCompanionApp add companion_android/app/build.gradle.kts
git -C G:/CarCompanian/KuroCompanionApp commit -m "$(cat <<'EOF'
Release vX.Y.Z

(body here)
EOF
)"
git -C G:/CarCompanian/KuroCompanionApp tag "app/vX.Y.Z"
git -C G:/CarCompanian/KuroCompanionApp push origin main "app/vX.Y.Z"
```

### 9. Monitor the workflow

```bash
sleep 5
gh run watch $(gh run list --repo comdet/KuroCompanionApp --limit 1 \
    --json databaseId --jq '.[0].databaseId') \
    --repo comdet/KuroCompanionApp --exit-status
```

When it finishes, report the release URL:

```bash
gh release view "app/vX.Y.Z" --repo comdet/KuroCompanionApp \
    --json url --jq '.url'
```

### 10. Final summary to user

Print a short summary:

- Released: `app/vX.Y.Z`
- Bump kind: patch / minor / major
- Commits rolled in: N
- Workflow status: success / failed
- Release URL: `https://github.com/comdet/KuroCompanionApp/releases/tag/app/vX.Y.Z`
- Both APK names: `CarCompanion-debug-vX.Y.Z.apk` + `CarCompanion-release-vX.Y.Z.apk`

## Guard rails

- **Never** force-push tags. If `app/vX.Y.Z` already exists, abort and tell user.
- **Never** skip user confirmation on the proposed version + commits.
- **Never** sign-with-debug-keystore unless secrets are missing (workflow handles that).
- If `git push` fails (network, etc.), keep the local commit + tag — user can retry.
- If the workflow fails, fetch the failed job's logs via
  `gh run view <run-id> --log-failed --repo comdet/KuroCompanionApp` and surface them.
- Major bumps are **always** confirm-twice.

## Related

- App version display: `MainActivity` HomeScreen + `SoulDebugActivity` show
  `BuildConfig.VERSION_NAME` so the user can verify what's installed
  on the HUD matches the released version.
- Workflow file: `.github/workflows/release-apk.yml`
- Signing secrets: `RELEASE_KEYSTORE_BASE64` + 3 password/alias secrets
  in repo settings (already configured).
- Release scheme + tag patterns: `RELEASE.md` at repo root.
