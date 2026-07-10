# 06 — Local signed APK build

**Status:** done

## Outcome

Locally built and verified a release-signed APK:
- artifact: `pcontrol-app-release.apk` (copy at repo root; canonical output at
  `android/app/build/outputs/apk/release/app-release.apk`)
- ~4.5 MB, applicationId `com.pcontrol.app`, versionName `1.0.0`
- signed with APK Signature Scheme v2, SHA-256 cert digest
  `6e9652661dd02824e7d0273d7a700a0f0fed5f614b14e0a21aa48a0e99b78b97`
- `gradle test` remains green (only `build.gradle.kts` changed, behaviorally
  a no-op when `key.properties` is absent, i.e. in CI).

Rebuild anytime with `cd android && gradle :app:assembleRelease`.

## Goal

Produce a release-signed APK locally from the Nix dev shell, without relying
on the GitHub Actions signing secrets. This is for self-installed dev builds;
the canonical release artifact still comes from the `android-*` tag CI run
(which uses `ANDROID_KEYSTORE_B64` etc. secrets).

## Background

- `android/app/build.gradle.kts` defines a `release` build type with
  `isMinifyEnabled = false` but **no `signingConfig`** — Gradle produces an
  unsigned APK.
- `.github/workflows/android-build.yml` handles signing only in CI via a
  base64-decoded keystore + `apksigner`, separate from Gradle.
- No local keystore exists; none of the CI signing secrets are available on
  the dev machine.

## Approach

Use the standard Android pattern: a gitignored `android/key.properties`
file points Gradle at a local `.jks` keystore, and `build.gradle.kts`
applies a `signingConfigs.release` block **only when that file exists** —
so CI (which has no `key.properties`) continues to produce an unsigned APK
exactly as before.

### Steps

1. **Prepare gitignore** — add `android/key.properties` and
   `android/*.jks` / `android/*.keystore` to `.gitignore` so the local key
   never gets committed.
2. **Generate a local dev keystore** — `keytool -genkeypair` producing
   `android/release.jks` (alias `pcontrol`, 10000-day validity, SHA256withRSA).
   This is a *self-signed dev key*, distinct from the CI release key. Note the
   consequence in Gotchas below.
3. **Write `android/key.properties** pointing at the keystore + passwords.
4. **Wire signing into Gradle** — in `build.gradle.kts`, read
   `key.properties` if present and, when it is, register
   `signingConfigs.create("release")` and apply it to the `release` build
   type. No-op when the file is absent (CI path unchanged).
5. **Build** — `cd android && gradle :app:assembleRelease`.
6. **Verify** — locate `app-release.apk`, run
   `apksigner verify --print-certs` to confirm the signature and the
   signer fingerprint.
7. **TDD** — no production code changes here; build verification
   (`apksigner verify`) serves as the acceptance check. `gradle test`
   remains green (only `build.gradle.kts` changed, which does not affect
   the JVM unit tests in `:core`/`:app`).

## Gotchas learned

- **Local dev key ≠ CI release key.** An APK signed with this local key is
  NOT the canonical release artifact and cannot be upgraded over (or share
  install lineage with) a CI-signed `android-*` release — different signing
  certs mean Android treats them as different signers, so a side-by-side
  install of both requires uninstalling the other. For a real public release,
  push an `android-*` tag so CI signs with the project's release keystore.
- **`signingConfig` must be declared before the build type references it.**
  Gradle evaluates the DSL in order, so put the conditional
  `signingConfigs` block above `buildTypes`.
- **Use absolute path coercion for `storeFile`.** `key.properties` is read
  relative to the project root; wrapping with `rootProject.file(...)` makes
  it robust regardless of where `gradle` is invoked from.

## Out of scope

- Building a signed AAB (`bundleRelease`) — APK only for now.
- Wiring local signing into CI — CI keeps its apksigner-based flow.
