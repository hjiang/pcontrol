# Plan 13: AGP 9.3.2 + Gradle 9.5.0 coordinated upgrade

## Background

Renovate PRs #47 (`com.android.application` 9.3.2) and #48
(`com.android.library` 9.3.2) both fail the *Android Tests* workflow. The
failure happens at `gradle wrapper --gradle-version 9.4.1` — before any test
runs — with:

```
java.lang.NoClassDefFoundError: org/gradle/features/binding/ProjectTypeBinding
```

Root cause: **AGP 9.3 requires Gradle 9.5.0** (per the AGP 9.3 release-notes
compatibility table). The workflows pin Gradle 9.4.1, and a Renovate PR that
only edits `build.gradle.kts` cannot pass while the wrapper generation is
hardcoded to an older Gradle. An AGP bump therefore must be coordinated with
the Gradle bump in the same change.

Compatibility notes:

- AGP 9.3.x: minimum Gradle **9.5.0**.
- KGP 2.4.10 (already used): "fully supported" up to AGP 9.1.0 / Gradle 9.5.0;
  the hard incompatibility is KMP-only (this project is not KMP). Expect at
  most an untested-combination warning.
- Nix dev shell: `gradle_9` in nixpkgs-unstable is 9.5.1 — no flake change
  needed.
- `maxSdk`/`targetSdk` unchanged (AGP 9.3 supports API level 37).

## Stages

### Stage 1: Version bumps

- [x] `android/build.gradle.kts`: AGP 9.2.1 → 9.3.2 (application + library).
- [x] `.github/workflows/android-tests.yml`: `gradle-version: 9.5.0` (2
      occurrences: setup-gradle + wrapper generation).
- [x] `.github/workflows/android-build.yml`: same, 2 occurrences.
- [x] `AGENTS.md`: update Gradle version references (repo layout + CI note).

**Status:** done

### Stage 2: Local verification

- [x] `nix develop -c bash -c 'cd android && gradle test'` passes with
      Gradle 9.5.1 + AGP 9.3.2.

**Status:** done

### Stage 3: Branch + CI

- [x] Branch `agp-9.3-gradle-9.5`, push, open PR.
- [x] Close #47 and #48 as superseded by this PR.

**Status:** done
