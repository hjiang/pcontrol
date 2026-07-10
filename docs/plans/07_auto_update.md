# 07 — Android auto-update from GitHub releases

**Status:** Complete

## Outcome

The Android app periodically (roughly once per day) checks the project's
GitHub releases for a newer CI-signed APK. When one exists it
**automatically downloads** the update and **automatically prompts the
system install dialog** so the parent (or, in device-owner mode, no one)
taps once to install. The check/download pipeline is unit-tested in pure
JVM (`:core` + `:app`); wiring to the always-on `TrackerService` loop and
the install intent are verified manually.

The feature is **self-update driven**: it is not a Play Store app and never
will be, so Google's In-App Updates API does not apply.

## Goal

Add a first-class, opt-out auto-update path to the Android client that:

1. Fetches `GET https://api.github.com/repos/hjiang/pcontrol/releases/latest`
   over the existing `OkHttpClient`-based stack (no new HTTP dependency).
2. Compares the release's version against the installed `versionName` using
   semver semantics, normalizing the `android-vX.Y.Z` tag prefix.
3. Downloads the matching `.apk` asset into app-private cache storage.
4. Verifies the downloaded APK's signing certificate matches the currently
   installed app (reject before install rather than presenting a doomed
   install dialog — see Signature continuity gotcha).
5. Triggers the Android package installer via a `FileProvider` `content://`
   URI (`Intent.ACTION_VIEW` + `application/vnd.android.package-archive`).
6. Surfaces a "Install unknown apps" permission grant row + a manual
   "Check for updates" button in `MainActivity`, and a foreground
   notification on a successful download.

## Background

- **Distribution today** (`.github/workflows/android-build.yml`): pushing a
  tag matching `android-*` (e.g. `android-v1.2.3`) builds a release APK,
  optionally signs it with the CI keystore (`ANDROID_KEYSTORE_*` secrets)
  via `apksigner`, and attaches `pcontrol-v1.2.3.apk` to a GitHub Release.
  The release is created with `tag_name: "android-v1.2.3"`,
  `name: "v1.2.3"`.
- **`GET /repos/{owner}/{repo}/releases/latest`** (GitHub REST API) returns
  the newest non-prerelease, non-draft release as JSON:
  `tag_name`, `name`, `published_at`, and `assets[]` where each asset has
  `name`, `size`, `browser_download_url`. Unauthenticated calls are rate
  limited to **60/hour** — comfortably above our once-per-day cadence.
- **`versionName` is injected by CI** via `-PappVersionName` (default
  `"1.0.0"` in `build.gradle.kts`), no `v` prefix. The release tag carries
  `android-vX.Y.Z`. Both must normalize to `X.Y.Z` before comparison.
- **Install authority:** on Android 8+ (minSdk 26) installing a third-party
  APK requires the `REQUEST_INSTALL_PACKAGES` permission **and** the user
  individually marking our app as an allowed source of "unknown apps"
  (`Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES`). A normal app can never
  install silently — the system always shows a confirmation dialog unless
  the app is the device/profile owner. See *Out of scope*.

## Locked decisions

| Decision | Choice | Rationale |
|---|---|---|
| Update source | GitHub `releases/latest` REST API | Single distribution channel already wired in CI |
| Check cadence | Once every ~24h, driven by the existing `TrackerService` foreground loop (a `last_update_check` timestamp gate) | Avoids a new dependency (WorkManager); service is `START_STICKY` and restarts on boot via `BootReceiver`, so the gate is reliable |
| Version compare | Semver, pure JVM, lives in `:core` | Testable with no Android deps; matches "keep `:core` pure" invariant |
| HTTP client | Reuse the app's existing `OkHttpClient` style | Stack is deliberately stdlib/OkHttp; no new HTTP dep |
| Install mechanism | `Intent.ACTION_VIEW` + `FileProvider` content URI + `application/vnd.android.package-archive` | Simplest reliable path for a non-device-owner app; matches Android 8+ requirements (deprecated `ACTION_INSTALL_PACKAGE` not used) |
| Storage | App-private cache dir (`context.cacheDir` subfolder), APK deleted after install attempt or supersession | No storage permissions; auto-cleaned by OS under pressure |
| Signature check | Pre-install: compare downloaded APK's signing cert SHA-256 to installed app's; on mismatch, skip and log | Avoids a doomed install dialog; fails closed on cert mismatch (see gotchas) |
| Repo coordinates | Hardcoded constant `hjiang/pcontrol` (overridable later via manifest meta-data if forked) | Single device, single repo — keep it simple |
| Toggle | `auto_update_enabled` boolean in default `SharedPreferences("pcontrol")`, default **true** | Parent can opt out without touching the server |
| New dependencies | None | Respects "no new deps without strong justification" |

## Architecture overview

```
┌──────────────────────── TrackerService foreground loop ─────────────────┐
│  every 10s tick · 60s sync gate · 24h update-check gate                  │
│                                                                          │
│   onTickUpdates()  ── if (now - lastUpdateCheck ≥ 24h && autoUpdateEnabled)│
│      │                                                                   │
│      ▼                                                                   │
│   GitHubReleaseClient.fetchLatestRelease()                               │
│      │  GET https://api.github.com/repos/hjiang/pcontrol/releases/latest  │
│      ▼                                                                   │
│   UpdateChecker.evaluate(installedVersion, release)  (:core, pure)        │
│      │  → NEEDS_UPDATE(version, downloadUrl, size) | UP_TO_DATE           │
│      ▼                                                                   │
│   ApkDownloader.download(url) → File (cache .apk)                         │
│      │                                                                   │
│      ▼                                                                   │
│   SignatureVerifier.matchesInstalled(apkFile)  → bool                    │
│      │  (mismatch → stop, log, notify "update available (manual)")        │
│      ▼                                                                   │
│   ApkInstaller.install(apkFile)                                          │
│      │  FileProvider content:// + ACTION_VIEW→system install dialog       │
│      ▼                                                                   │
│   foreground notification "Update downloaded — tap to install"           │
└──────────────────────────────────────────────────────────────────────────┘
```

New `:app` classes:

```
app/src/main/kotlin/com/pcontrol/app/update/
  GitHubReleaseClient.kt   fetch + parse releases/latest JSON (OkHttp)
  ApkDownloader.kt         stream asset to cache file
  SignatureVerifier.kt     compare archive signer to installed signer
  ApkInstaller.kt          FileProvider + ACTION_VIEW
  UpdateCoordinator.kt     orchestrates fetch→compare→download→verify→install
  UpdateState.kt           prefs keys + last-check/downloaded-apk bookkeeping

core/src/main/kotlin/com/pcontrol/core/
  Version.kt               semver parse + compare (pure)
```

## Stages

### Stage 1 — Manifest & FileProvider plumbing

**Status:** Complete

Set up the install surface with no logic yet; everything compiles and
existing tests stay green.

1. **`AndroidManifest.xml`** — add
   `<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />`.
2. **`res/xml/file_paths.xml`** — declare a `cache-path` (or `external-cache-path`
   not needed) entry exposing the APK subfolder:
   `<cache-path name="updates" path="updates/" />`.
3. **Provider** — register
   ```xml
   <provider
     android:name="androidx.core.content.FileProvider"
     android:authorities="${applicationId}.fileprovider"
     android:exported="false"
     android:grantUriPermissions="true">
     <meta-data android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
   </provider>
   ```
4. No `applicationProvider` glue for the actual install yet — only
   manifest + xml resources. Build stays green: `cd android && gradle :app:testDebugUnitTest`.

### Stage 2 — Pure `:core` version comparison (TDD)

**Status:** Complete

Put semver logic in `:core` so it's unit-testable with no Android deps.

1. **Failing test** `core/src/test/kotlin/com/pcontrol/core/VersionTest.kt`:
   - `Version.compare("1.0.0", "1.0.1")` → negative.
   - `compare("v1.2.3", "1.2.3")` → 0 (strip leading `v`).
   - `Version.parse("android-v1.2.3")` → `1.2.3` (strip optional `android-`
     prefix, then optional `v`).
   - `compare("1.0.0", "1.0.0")` → 0.
   - pre-release suffixes (`1.0.0-rc1`) → optional: keep simple — strip and
     treat as equal to the release unless a second stage adds precedence.
     Document the simplification.
   - malformed input (e.g. empty) → throws or returns `null`; pick one and
     document.
2. **Implement** `Version.kt` minimally to pass.
3. **`cd android && gradle :core:test`** green. Commit.

### Stage 3 — `GitHubReleaseClient` (TDD, MockWebServer)

**Status:** Complete

1. **Failing test** `app/src/test/kotlin/com/pcontrol/app/update/GitHubReleaseClientTest.kt`
   using the existing `mockwebserver` dependency already in `app`'s test scope:
   - enqueue `releases/latest` JSON with two assets, one of them
     `pcontrol-v1.2.3.apk`; assert the client returns the correct version
     (`1.2.3` after normalization), download URL, and size.
   - enqueue a release whose only assets are non-`.apk` → returns null
     (no suitable asset).
   - enqueue a 404 → returns null.
   - network exception → returns null (`@Test` uses `MockWebServer` shutdown).
2. **DTOs** with `kotlinx.serialization` (`@SerialName`, `ignoreUnknownKeys`),
   matching existing `SyncClient` style:
   ```kotlin
   @Serializable data class GitHubRelease(
     val tagName: String = "",
     val name: String = "",
     val assets: List<GitHubAsset> = emptyList()
   )
   @Serializable data class GitHubAsset(
     val name: String = "",
     val size: Long = 0,
     @SerialName("browser_download_url") val downloadUrl: String = ""
   )
   ```
3. **`GitHubReleaseClient`** — OkHttp fetch, parse to `GitHubRelease`, pick the
   first asset whose name ends in `.apk` (prefer the `pcontrol-v*.apk` one),
   normalize the version via `Version.parse`, return a
   `UpdateInfo(version: String, downloadUrl: String, sizeBytes: Long)` or null.
4. Also add a pure `UpdateChecker.evaluate(installedVersion, releaseVersion)`
   in `:core` returning `NEEDS_UPDATE` / `UP_TO_DATE` — test in `:core`.
5. **`cd android && gradle :core:test :app:testDebugUnitTest`** green. Commit.

### Stage 4 — `ApkDownloader` (TDD)

**Status:** Complete

1. **Failing test** `ApkDownloaderTest.kt` (MockWebServer serves a few KB of
   bytes as the asset):
   - `download(url)` writes exactly the served bytes to a file under a
     `updates/` subfolder in the (Robolectric) cache dir.
   - partial/failed download (close socket mid-stream) does not leave a
     partial-file behind on disk (delete on failure).
   - a pre-existing stale file of the same destination is overwritten fresh.
2. **Implement** `ApkDownloader`: stream the response body to a
   `File(cacheDir/updates/pcontrol-<version>.apk)`, deleting on exception
   (match the existing `SyncClient` "log and return null" tolerance
   pattern — return `File?`).
3. Clean up any *other* `*.apk` in `updates/` (superseded downloads) so the
   folder never accumulates old versions.
4. **`cd android && gradle :app:testDebugUnitTest`** green. Commit.

### Stage 5 — `SignatureVerifier` (TDD)

**Status:** Complete

Goal: refuse to present an install that Android will reject anyway.

1. **Failing test** `SignatureVerifierTest.kt` (Robolectric, since it needs
   `PackageManager`):
   - Verify that for an APK whose signer matches the installed app's signer,
     `matchesInstalled(apkFile)` returns `true`.
   - Use the locally-built release APK fixture (similar to existing tests)
     or a synthetic signed jar — see gotcha about cross-API availability of
     `getPackageArchiveInfo` with `GET_SIGNING_CERTIFICATES`.
2. **Implement**:
   - Installed signer: `pm.getPackageInfo(packageName, GET_SIGNING_CERTIFICATES)`
     → `signingInfo.apkContentsSigners[0]` → SHA-256 of the encoded cert.
   - Downloaded signer: `pm.getPackageArchiveInfo(apkPath, GET_SIGNING_CERTIFICATES)`
     → same extraction. **Verify this works on minSdk 26** during implementation;
     if not for some API level, fall back to "extract via
     `getPackageArchiveInfo(0)` + manual PKCS7 read" — but prefer the public
     API. If extraction is entirely infeasible on a device, return `true`
     (let the installer be the judge) and log a warning.
3. Mismatch → `false` (coordinator stops the run).
4. **`cd android && gradle :app:testDebugUnitTest`** green. Commit.

### Stage 6 — `ApkInstaller` (manual verification)

**Status:** Complete

Hard to unit test (real system install dialog); kept thin and verified
manually.

1. `ApkInstaller.install(apkFile: File)`:
   ```kotlin
   val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apkFile)
   val intent = Intent(Intent.ACTION_VIEW).apply {
     setDataAndType(uri, "application/vnd.android.package-archive")
     addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
   }
   ctx.startActivity(intent)
   ```
2. No JVM unit test (system dialog). Cover the pure URI-building helper with a
   tiny Robolectric test if feasible; otherwise note in `AGENTS.md` that this
   class is acceptance-tested manually.
3. **Manual:** build a release APK, install, bump `versionName`, build again
   with the same key, and confirm the install dialog appears and succeeds.
4. **`cd android && gradle :app:testDebugUnitTest`** green. Commit.

### Stage 7 — `UpdateCoordinator` + prefs (TDD)

**Status:** Complete

Orchestration of stages 3–6 with state in `SharedPreferences("pcontrol")`.

1. **Failing test** `UpdateCoordinatorTest.kt` (use fakes for client/downloader/
   verifier/installer — define small interfaces so the coordinator is pure
   logic over injected seams):
   - client returns newer version + downloader succeeds + verifier OK →
     installer called once, `last_update_check_ms` updated, downloaded-apk path
     persisted.
   - client returns same version → no download, no install, just the check
     timestamp refreshed.
   - client returns null (network) → nothing downloaded, timestamp refreshed
     (avoid hammering every tick).
   - verifier mismatch → no install, notify "manual update available".
   - `auto_update_enabled == false` → coordinator returns immediately
     without calling the client.
2. **Implement** `UpdateCoordinator` with injected
   `UpdateClient`/`Downloader`/`Verifier`/`Installer` seams. Add the prefs
   keys in `UpdateState.kt`: `auto_update_enabled`, `last_update_check_ms`.
3. **`cd android && gradle :core:test :app:testDebugUnitTest`** green. Commit.

### Stage 8 — UI: "Install unknown apps" row + manual check (manual)

**Status:** Complete

Extend the existing permissions checklist in `MainActivity`.

1. Add `status_updater` / `btn_updater` row + a "Check for updates" button
   (`btn_check_update`), following the exact `R.id` + `refreshStatus` pattern
   used by `status_usage`/`btn_usage`.
2. `hasInstallPermission()`:
   `packageManager.canRequestPackageChecks()` is not available pre-API 34;
   use `Settings.canDrawOverlays`-style check via
   `appOps.unsafeCheckOp(OPSTR_REQUEST_INSTALL_PACKAGES, myUid, packageName)`
   (mirrors the existing `hasUsageStatsPermission()`).
3. `btn_updater` →
   `Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).setData(Uri.parse("package:$packageName"))`.
4. `btn_check_update` → kicks
   `UpdateCoordinator.runOnce()` off the IO scope; toast the outcome.
5. Add a `auto_update_enabled` toggle (simple `Switch` or a checkbox in the
   server dialog — reuse layout primitives already in use).
6. **Manual:** toggle, click check now with a newer release live on GitHub.
7. **`cd android && gradle :app:testDebugUnitTest`** green. Commit.

### Stage 9 — Wire periodic check into `TrackerService`

**Status:** Complete

1. Add `UPDATE_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L` alongside
   `SYNC_INTERVAL_MS`.
2. In the tick loop, gate an `onUpdateCheck()` call on
   `(now - lastUpdateCheck >= UPDATE_CHECK_INTERVAL_MS)` — exactly mirroring
   the existing sync gate. Refresh `last_update_check_ms` from prefs at
   service start so the gate survives restarts.
3. `onUpdateCheck()` constructs the coordinator with real `:app` seams and
   runs it on `Dispatchers.IO`; wrap in the existing `try/catch (continue)`
   pattern so an update failure can never kill tracking.
4. On a successful download (but user hasn't installed yet), post a
   notification on the existing `CHANNEL_ID` channel (or a new
   `CHANNEL_ID_UPDATE` of normal importance) so the parent sees "Update
   downloaded — tap to install".
5. **Manual:** with a newer `android-vX.Y.Z` release present and ≥24h since
   last check, confirm the app auto-downloads and prompts install while
   `TrackerService` is mid-run, without interrupting the 10s tick loop.
6. **`cd android && gradle :app:testDebugUnitTest`** green. Commit.

### Stage 10 — Docs, AGENTS.md gotchas, manual verification & bump

**Status:** Complete

1. Update `AGENTS.md` Android gotcha list with the new gotchas below.
2. Bump `versionCode` / `versionName` in `build.gradle.kts` so the *next*
   release is itself the first to carry auto-update (the update mechanism is
   shipped inside a release; users on `1.0.0` must install the first
   auto-update-enabled release manually — once. document this in the release
   notes).
3. Add a short `docs/plans/07_auto_update.md` "Verification" entry (below) and
   flip all stage statuses to `Complete`.
4. Full CI command set + manual release install test pass.

## Gotchas (preview of lessons — confirm during implementation)

1. **Signature continuity (critical, ties to plan 06).** Android refuses to
   update an app whose new APK is signed with a different certificate than the
   installed one. The CI keystore and the local dev `release.jks` are
   different keys (see plan 06). Auto-update therefore only works between two
   *CI-signed* releases. A locally-built install trying to auto-update to a
   CI-signed APK will hit the signature mismatch — which is *why* Stage 5
   pre-verifies and behaves gracefully instead of presenting a doomed install
   dialog. Doc this in AGENTS.md under the existing "Local dev key ≠ CI
   release key" item.
2. **`targetSdk 37` ⇒ no `file://` URIs.** Must use `FileProvider`
   `content://`. `Intent.ACTION_VIEW` with `FLAG_GRANT_READ_URI_PERMISSION`
   is the supported path; the deprecated `ACTION_INSTALL_PACKAGE` is not used.
3. **`REQUEST_INSTALL_PACKAGES` is a per-source appop, not a manifest-only
   grant.** The parent must, one time, open
   `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` and allow our app. Build this
   into the `MainActivity` permission checklist (Stage 8) just like
   battery/overlay/usage.
4. **No silent install for a normal app.** The system install confirmation
   dialog is unavoidable unless the app is device/profile owner
   (`DevicePolicyManager`). Auto-update means *auto-check + auto-download +
   auto-prompt*, not auto-install. Silent install via Device Owner is out of
   scope (see below); the plan could add it later as a Stage 11 for a parent
   who provisions the phone as Device Owner.
5. **GitHub unauthenticated rate limit is 60/hr.** Our 24h cadence is far
   under that; but if the user hammers "Check for updates" in the UI, guard
   the client so a 403/detectable rate-limit (`X-RateLimit-Remaining`) is
   surfaced to the toast rather than retried next tick.
6. **Tag chaos.** `tag_name` is `android-v1.2.3`, `name` is `v1.2.3`,
   installed `versionName` is `1.2.3`. The `Version.parse` normalizer in
   Stage 2 handles all three forms, stripping leading `android-` then `v`.
   Never compare raw strings.
7. **`getPackageArchiveInfo` + `GET_SIGNING_CERTIFICATES` across API levels.**
   Verify the public API actually populates `signingInfo` for an *archive*
   path on minSdk 26 before relying on Stage 5's pre-check. If a given device
   returns `null` signingInfo, Stage 5 degrades to "trust the installer"
   (return `true`, log) rather than block a valid update.
8. **Download to app-private cache, never shared storage.**
   `context.cacheDir/updates/` avoids runtime storage permissions and is
   auto-purged by the OS. Always delete on failure (Stage 4) and the
   superseded siblings — a half-downloaded APK left around would yield a
   confusing install-fail.
9. **`TrackerService.onSync()` early-returns with no unsynced events**
   (existing gotcha). The update-check gate is **independent** of sync — it
   runs from the tick loop, not from `onSync()`, so an idle phone still checks
   for updates.
10. **The first release carrying auto-update cannot update itself onto v1.0.0
    silently.** Devices still on `1.0.0` (the feature's absence) need one
    manual install of the first auto-update-enabled APK; every release after
    that self-updates. Surface this in the release notes.

## Out of scope

- **Play Store In-App Updates.** Not a Play app.
- **Silent/no-prompt install** via `DevicePolicyManager` device-owner mode.
  Possible stretch Stage 11 for a parent-provisioned Device Owner phone; not
  v1 of this feature.
- **Delta/patch updates.** Full-APK re-download only (~4.5 MB).
- **Update server component.** The Go server is not involved; update comes
  straight from GitHub.
- **Auto-updating the server binary.** Only the Android app.
- **Back-channel to the dashboard** that a phone is updatable — the dashboard
  stays pull-only.

## Verification

```sh
cd android && gradle :core:test                    # Stage 2,3,7 pure logic
cd android && gradle :app:testDebugUnitTest         # 3,4,5,7 (Robolectric+MockWebServer)
```

Manual (acceptance test):

1. `cd android && gradle :app:assembleRelease`, sign with the same key as the
   currently installed build (CI key for a real release, local `release.jks`
   only for the same-key dev path).
2. Install `v1.2.4` on a phone running `v1.2.3`, both CI-signed.
3. With the 24h gate expired (or after tapping "Check for updates"), confirm
   the app logs/warns "Update v1.2.4 available", downloads `pcontrol-v1.2.4.apk`
   into `cacheDir/updates/`, signature-verifies, and presents the system
   install dialog; tapping install replaces the app and reports v1.2.4.
4. Re-run the same with a `signing_key_mismatched` APK → Stage 5 blocks the
   install and posts "manual update available" instead.
5. `cd server && go test ./... && go vet ./...` unchanged (no server code).

Stage statuses are updated as each completes:
`Not Started` → `In Progress` → `Complete`.