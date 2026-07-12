# Plan 09: Polish the Android app UI

## Status

**Overall:** In Progress — implementation complete; manual device/emulator
verification deferred.

**Stage 0:** Partial — recommended defaults from Section 2 accepted (see below);
before-screenshots and an approved sketch remain manual on-device deliverables
(`docs/ui/android/before/`).

## 1. Purpose

Make the Android app feel calm, trustworthy, intentional, and complete without
changing its parental-control policy, sync protocol, enforcement behavior, or
single-screen setup architecture.

The current app has an app-owned setup screen and an accessibility-owned
blocking surface:

- `MainActivity`: a flat setup checklist, server-configuration dialog, and
  update controls.
- `AccessibilityBlockingSurface`: a full-screen limit-reached overlay that
  reuses `activity_blocked.xml` without starting an activity or requiring the
  ordinary overlay permission.

The redesign will keep the existing XML/View stack, introduce Material 3 as a
small design system, clarify required versus optional setup, improve forms and
feedback, and make both surfaces accessible and adaptive.

## 2. Decisions required before implementation

Confirm these before Stage 1. Defaults are recommended so work can proceed if
no custom brand direction is supplied.

1. **Audience:** treat `MainActivity` as a parent-assisted setup and device
   health screen, not a child dashboard. Do not show usage totals or policy
   details in this project.
2. **Tone:** use a calm, protective visual language. Avoid punitive imagery and
   pure alarm red except for concise error/status accents.
3. **Brand:** use the product name `pcontrol` and a simple shield/check mark.
   Default palette: restrained blue/teal with semantic green, amber, and red.
4. **Theme:** support both light and dark mode with an intentional fixed palette.
   Defer dynamic color until both themes and contrast are approved.
5. **Toolkit:** retain Views/XML; do not rewrite the app in Compose. Add Material
   Components for Android because consistent Material 3 controls, theming,
   forms, and accessibility justify the dependency.
6. **Setup semantics:** usage access, accessibility, notifications, battery
   exemption, and server configuration remain required by the existing
   `allPermissionsGranted()` contract. The accessibility-owned blocking overlay
   does not require the ordinary draw-over-other-apps permission. Install-unknown-apps remains optional
   and belongs in the Updates section.
7. **Blocked screen:** preserve the Back-to-home behavior and provide no
   override control. Returning to blocked content remains governed by the
   existing enforcement path. Allowed sites remain informational and
   non-clickable. Preserve the existing reset-at-midnight statement unless
   policy semantics change in a separate project.
8. **Localization:** make all visible text owned by `MainActivity`, its server
   dialog, and the accessibility blocking surface resource-backed, but English
   is the only
   translation required in this project. Notification/channel copy outside
   these surfaces is deferred.
9. **Validation devices:** support API 26 through API 37, compact phones, a
   tablet/wide window, portrait/landscape, and 200% font scaling.

Any change to these defaults should update this plan before implementation.

**Decision record (Stage 0):** All recommended defaults (1–9) accepted as
written on 2026-07-12 to unblock implementation. No custom brand direction
supplied; default restrained blue/teal palette with semantic green/amber/red.
Baseline on-device screenshots are a manual verification step deferred to the
project owner (no Android emulator available in this build environment); the
absence of durable in-progress/result UI in the old Updates section is recorded
as a baseline defect fixed in Stage 5.

## 3. Goals and non-goals

### Goals

- Establish reusable color, typography, shape, spacing, and component tokens.
- Remove hardcoded visible text, colors, and pixel-based spacing from
  `MainActivity`, its server dialog, and the accessibility blocking surface.
- Make setup progress and the next required action understandable at a glance.
- Clearly separate required monitoring setup, server connection, and optional
  update controls.
- Provide durable, accessible feedback for update checks and form validation.
- Make the blocked screen firm but humane, legible, and visually consistent.
- Correctly handle system bars, display cutouts, the keyboard, large fonts,
  dark mode, and wide windows.
- Add automated tests for UI state and critical view behavior, plus a repeatable
  manual visual/accessibility checklist.

### Non-goals

- No Compose migration, fragments, Navigation Component, tabs, or bottom nav.
- No server/dashboard changes and no sync-protocol changes.
- No new usage, policy, service-health, or last-sync data on the phone.
- No ability to stop monitoring, bypass a block, or open allowed sites.
- No redesign of Android system Settings or the package installer.
- No animation framework, remote assets, analytics, or new outbound network use.
- No behavior changes to which capabilities are required, except correcting UI
  copy so the optional updater permission is not described as required.

## 4. Design and architecture contracts

### 4.1 UI state

Introduce a small, pure presentation model rather than spreading strings and
visibility rules through `MainActivity`.

Suggested types:

```kotlin
data class SetupCapability(
    val id: CapabilityId,
    val granted: Boolean,
    val required: Boolean,
)

data class SetupUiState(
    val capabilities: List<SetupCapability>,
    val requiredComplete: Int,
    val requiredTotal: Int,
    val canStart: Boolean,
)
```

Contract for the state builder:

- **Preconditions:** exactly one entry exists for each known capability.
- **Postconditions:** `requiredComplete` counts only granted required entries;
  `canStart` is true if and only if every required entry is granted; optional
  updater state never changes `canStart`.
- **Invariant:** capability order and section membership are stable, so visual
  order and TalkBack order agree.

System permission checks remain thin Android adapters. Do not move Android APIs
into `:core`; this presentation model belongs in `:app`.

### 4.2 Main screen information architecture

One activity, one scrollable content surface, and one persistent bottom action:

1. App bar: `pcontrol`, no duplicate in-content screen title.
2. Status hero: “Setup needed” with `n of n required steps complete`, or
   “Ready to monitor” when all required steps pass.
3. “Required setup” cards: usage access, accessibility, notifications, and
   battery optimization.
4. “Server connection” card: configured/not configured and a configure/edit
   action. Never display the token.
5. “Updates (optional)” card: install-source permission, auto-update switch,
   check button, progress indicator, and durable result/error text.
6. Bottom action: “Start monitoring,” disabled until required setup is complete;
   supporting text points to the first incomplete required step.

Each capability card must contain a title, one-sentence rationale, textual state
(“Ready” or “Action needed”), and one clear action. Color and icons supplement
rather than replace text. Actions should stack below text so 200% fonts do not
collide with trailing controls.

### 4.3 Blocked screen hierarchy

1. Protective lock/shield illustration.
2. Clear headline, e.g. the existing limit message.
3. Subject in a readable surface card.
4. Optional “Sites still available” list.
5. Muted reset explanation.

The screen remains non-interactive and offers no override control. Back must
continue to launch HOME; existing enforcement decides what happens when the
user returns to blocked content. Replace `#FF0000` with themed
error-container/surface roles that preserve urgency without flooding the
display with saturated red.

### 4.4 Edge-to-edge and adaptive behavior

- Call `enableEdgeToEdge()` in `MainActivity`; apply equivalent system-bar inset
  handling to the accessibility-owned blocking window.
- Apply status-bar insets to the app-bar/top content, navigation/gesture insets
  to the bottom action or footer, and IME insets to the server dialog/form.
- One owner consumes each inset; avoid root plus child double-padding.
- Use flexible widths/heights and `start`/`end`, never left/right assumptions.
- On wide configurations, center the main content and cap its useful line length
  through width-specific dimensions/layout resources; do not stretch cards
  edge-to-edge across a tablet.
- No essential content may be clipped at 200% font size, landscape, or split
  screen. Scrolling is acceptable; hidden actions are not.

## 5. Implementation stages

Every implementation task follows red/green/refactor. Before writing production
code, add the stated failing test, run it, and explicitly record **“test failed
as expected”** in the stage notes or commit message.

### Stage 0 — Resolve direction and capture the baseline

**Status:** Partial — decisions are recorded, but baseline screenshots and an
approved sketch remain manual on-device deliverables (`docs/ui/android/before/`).

1. Resolve every decision in Section 2 and update this plan.
2. Capture before screenshots of:
   - main screen with no setup complete;
   - partially complete setup;
   - all required setup complete;
   - server dialog;
   - the current update controls and representative result Toasts where they
     can be captured; record the absence of visible in-progress/result state as
     a baseline defect rather than inventing an unreproducible screenshot;
   - blocked screen with and without allowed sites;
   - light and dark mode.
3. Record actual screenshots under `docs/ui/android/before/` with device/API,
   window size, theme, and font scale in `README.md`.
4. Produce a low-fidelity screen sketch or annotated screenshot for approval
   before styling begins.
5. Re-check final API 37 edge-to-edge/large-screen behavior documentation before
   implementation because preview guidance may change.

**Success criteria:** scope, palette/tone, hierarchy, and screen sketches are
approved; baseline states are reproducible.

### Stage 1 — Add the Material 3 foundation

**Status:** Partial — `com.google.android.material:material:1.13.0` +

Implementation is complete; API 36 Robolectric and API 37 device validation
remain required by this stage's success criteria.


`androidx.activity:activity-ktx:1.11.0` added; `Theme.Material3.DayNight.NoActionBar`
no-action-bar base plus a v27 light-nav-bar overlay; `values/colors.xml` +
`values-night/colors.xml` palette, `values/dimens.xml` + `values-w600dp` /
`values-w840dp` wide-window overrides, typography & card styles, semantic vector
icons (`ic_shield`, `ic_shield_check`), an adaptive
launcher icon (foreground/background/monochrome under `mipmap-anydpi-v26/`),
edge-to-edge enabled in `MainActivity`, the blocking layout retained for the
accessibility-owned surface, and every user-visible Kotlin/layout string moved
into `strings.xml`.

**Robolectric gotcha discovered & locked in:** `testOptions { unitTests {
isIncludeAndroidResources = true } }` is required for AppCompatActivity
inflation against the merged Material 3/AppCompat resources, but exposing
`targetSdk=37` to Robolectric 4.16.1 on JDK 17 makes `DefaultSdkPicker` try to
fetch an unavailable SDK 37 android-all jar — and SDK 23 fails with
PackageParser errors against AppCompat 1.7.1/Material 1.13.0. The
`app/src/test/resources/robolectric.properties` file pins `sdk=26` (the app's
minSdk) project-wide; tests that need higher levels override with
`@Config(sdk=[..])`. Stage 1's [ThemeAndLayoutTest] adds a class-level
`@Config(sdk = [26])` to make the contract explicit. Tests pass on API 26
in both `notnight` and `night` qualifiers.

**Test first:** add a Robolectric resource/theme smoke test that inflates both
activities under light and dark modes and asserts the expected Material theme
and key views. Run it before adding Material; it must fail as expected.

1. Add the current stable `com.google.android.material:material` dependency,
   verified for the project's AppCompat, minSdk 26, and compileSdk 37 versions.
2. Replace `Theme.AppCompat.DayNight.DarkActionBar` with a no-action-bar Material
   3 DayNight theme. Define surface-specific blocked-screen styling only where
   behavior differs.
3. Add resource tokens, with night variants where needed:
   - `values/colors.xml` and `values-night/colors.xml`;
   - `values/dimens.xml` and wide-window overrides;
   - typography and component styles in `themes.xml`/`styles.xml`;
   - semantic vector icons and state-list/tint resources.
4. Move every user-visible string from both layouts and activities into
   `strings.xml`, including formatted strings and accessibility labels.
5. Add an adaptive launcher icon and monochrome icon after the visual mark is
   approved; reference it from the manifest.
6. Enable edge-to-edge in `MainActivity` and implement a single documented
   inset owner for each activity or accessibility-overlay screen region.

**Success criteria:** `MainActivity` and the accessibility-owned blocking
layout inflate under Robolectric on API 26 and its latest supported SDK
(currently API 36 for Robolectric 4.16.1) in light/dark mode; API 37 is validated
on an emulator/device; no in-scope visible copy or raw
color remains hardcoded in Kotlin/layout XML; app chrome and icons are
intentional; existing behavior tests stay green. Upgrade Robolectric only if a
verified compatible release is available and API 37 JVM coverage is worth the
change.

### Stage 2 — Introduce testable setup presentation state

**Status:** Done — pure [SetupUiState] / [CapabilityId] / [CapabilityFacts]
model and [SetupUiState.build] builder in `app/src/main/kotlin/com/pcontrol/app/ui/SetupUiState.kt`.
`MainActivity.refreshStatus()` now collects system facts once into a
[CapabilityFacts], builds one [SetupUiState], and renders it through a
[renderSetupState(state)] seam via [CapabilityRenderer], instead of
re-querying [allPermissionsGranted()] for every status line.

The optional `UPDATER` capability never gates [canStart]; capability order and
section membership are stable so visual order and TalkBack order agree.
[allPermissionsGranted()] is preserved unchanged for the start-button gate and
settings intents. All status text is now resource-backed (Stage 2 task 4).

[SetupUiStateTest] covers: none/some/all required granted; optional updater
denied/granted still allows monitoring; stable display order; first incomplete
required capability; determinism; duplicate/missing-id fail-fast contract
checks on the [ORDER] list.

**Test first:** add `SetupUiStateTest.kt` covering none/some/all required
capabilities, optional updater denied/granted, stable ordering, and the first
incomplete required capability. Run it before the state builder exists; it must
fail as expected.

1. Add the pure presentation types and builder described in Section 4.1.
2. Make `refreshStatus()` collect system facts once, build one state, and pass
   it to an internal `renderSetupState(state)` seam. Avoid calling every
   permission check again through `allPermissionsGranted()`.
3. Keep permission collection behind a replaceable internal provider (or pass
   explicit state in tests) so Robolectric render tests do not depend on real
   Settings state or encrypted preferences. Production defaults must continue
   to use the current Android checks and `SecretPrefs`.
4. Render status text from resources, not emoji strings.
5. Keep capability action routing separate from presentation mapping so tests
   do not need to invoke Android Settings.
6. Declare contracts for any non-trivial mapping/render helper and fail early on
   duplicate or missing capability IDs.

**Success criteria:** the optional install permission never gates monitoring;
state counts are correct; no contradictory “all permissions” copy remains;
existing Settings intents and start-service behavior are unchanged.

### Stage 3 — Redesign `MainActivity`

**Status:** Partial — `activity_main.xml` replaced with the hierarchy in
Section 4.2: `CoordinatorLayout` + MaterialToolbar app bar (`navigationIcon`
shield/check mark with a content description), a `status_hero` live-region,
“Required setup”, “Server connection”, and “Updates (optional)” section
headings, `MaterialCardView` capability cards (title + one-sentence rationale +
textual state + 48dp-touch stacked action — actions stack below the text so 200%
fonts do not collide with trailing controls), a `MaterialSwitch` auto-update
toggle (with a separate subtext `TextView`, since MaterialSwitch exposes no
`subText` attribute), an inline `ProgressBar`+status `TextView` for update
feedback, and a persistent bottom CTA in a `colorSurfaceContainer` bar that
stays above system navigation. Wide windows use `MaxWidthLinearLayout` with
`content_max_width`, filling compact windows and centering a capped column on
wider displays. [MainActivityLayoutTest] asserts section headings, hero,
start-button disabled-until-ready, optional updater labeling, and XML view
order. `MainActivity.onCreate` now does an initial `refreshStatus()` so the
screen is populated on the first frame.

**Test first:** add Robolectric tests for the incomplete and complete states.
Assert section headings, textual state, start-button enabled state, optional
updater labeling, logical view order, and update status visibility. Run against
current layout; the tests must fail as expected.

1. Replace `activity_main.xml` with the hierarchy in Section 4.2 using Material
   toolbar, cards, buttons, switch, progress indicator, and Snackbar host/root.
2. Give every action at least a 48dp touch target. Decorative icons must not be
   separately focusable; meaningful icon-only controls need content
   descriptions (prefer text buttons here).
3. Mark screen/section headings semantically and ensure XML order equals visual
   and TalkBack order.
4. Keep the bottom CTA visible without covering the final scroll item or system
   navigation area.
5. Use concise, explanatory copy for why each sensitive permission is needed and
   what the user should do after returning from Settings.
6. Restore scroll position and rendered setup state across configuration changes
   using standard view state; do not add a navigation or state framework.

**Success criteria:** a user can identify the next required step immediately;
required, connection, and optional update controls are visually distinct; no
text/action overlaps at 200% font; all existing actions still reach the same
Settings destinations.

### Stage 4 — Replace the server configuration dialog

**Status:** Partial — pure [validateServerConfiguration(url, token)] helper
and [ServerConfigError] enum live in
`app/src/main/kotlin/com/pcontrol/app/ui/ServerConfigValidator.kt` (Android-free
URI validation: absolute `http`/`https` only, nonblank host, no query/fragment,
trim whitespace, single trailing-slash trim applied only after other checks
pass so `https://` is reported as `URL_NO_HOST` instead of `URL_BAD_SCHEME`).
[ServerConfigValidatorTest] covers blank/whitespace URL+token, malformed URLs,
non-http schemes, file scheme, hostless URLs with and without path, query,
fragment, valid HTTPS/HTTP-LAN/HTTP-with-path, whitespace and trailing-slash
trimming, and token non-echo.

Production dialog now uses Material `EditText` inputs (URI input type for URL,
password input type for token label/hints) and goes through
[validateServerConfiguration]; an invalid save keeps the dialog open with a
field-level `TextInputLayout` error:

  - `dialog_server_config.xml` uses Material `TextInputLayout` fields, URI
    input for the URL, masked token input, and an accessible password-reveal
    toggle.
  - The positive action validates before persistence, sets field-level errors,
    keeps the dialog open on invalid input, and dismisses only after both fields
    pass. `ServerConfigDialogTest` covers masking/reveal and pre-persistence
    rejection.
  - IME inset behavior remains a manual API 26–37 verification item.

**Test first:** extract a pure `validateServerConfiguration(url, token)` helper
and test blank values, malformed URLs, non-HTTP schemes, whitespace trimming,
valid HTTP/LAN URLs, valid HTTPS URLs, hostless HTTP(S) values, and URLs with a
query or fragment. Add a Robolectric dialog test for masked token input and
non-dismissal on validation error. Run first; tests must fail as expected.

1. Create `dialog_server_config.xml` with Material `TextInputLayout` fields.
2. Use URI-appropriate input for the server URL and password/sensitive input for
   the device token, with an accessible reveal toggle.
3. Validate before persistence:
   - URL is absolute, uses `http` or `https`, and has a nonblank host/authority;
   - URL has no query or fragment (the sync client appends its own endpoint);
   - token is nonblank;
   - trim surrounding whitespace and trailing URL slash as today.
4. Keep the dialog open and show field-level errors when invalid. Save only when
   both fields pass.
5. Never log, announce, or render the full token outside the editable field.
6. Ensure keyboard/IME insets keep the focused field and actions visible.

**Success criteria:** invalid configuration cannot silently save; existing valid
LAN HTTP and VPS HTTPS configurations still work; token handling remains in
`SecretPrefs`; the form is usable with TalkBack and large fonts.

### Stage 5 — Make update feedback durable and accessible

**Status:** Partial — pure [UpdateUiState] / [UpdateUiStatus] (IDLE, CHECKING,
SUCCESS, ACTION_REQUIRED, ERROR) presentation model and [UpdateUiMapper.fromResult]
live in `app/src/main/kotlin/com/pcontrol/app/ui/UpdateUiState.kt`.
[UpdateUiMapperTest] covers every [UpdateResult] variant for the correct
category and that every terminal result carries a message resource (and idle/
checking carry none).

`MainActivity.checkForUpdates()` now resolves the message through
`UpdateUiMapper.fromResult` instead of a hardcoded Kotlin `when` expression.

**Status:** Partial — `update_progress` + `update_status` views live in the
Updates card; `renderUpdateState` translates the pure state machine to view
visibility/text; `Snackbar` gives brief success confirmation while errors and
manual-action guidance remain inline. `accessibilityLiveRegion=polite` is set
on `update_status`. `UpdateCheckProgressTest` covers idle/checking visibility
and the in-flight duplicate-request guard.

**Test first:** add pure mapping tests from every `UpdateResult` to a small
`UpdateUiState` (`idle`, `checking`, `success`, `actionRequired`, `error`) and
Robolectric tests that checking disables duplicate requests and exposes a
progress announcement. Run first; tests must fail as expected.

1. Introduce an internal update-runner seam and injectable coroutine dispatcher
   for tests; production defaults must construct the existing
   `UpdateCoordinator` and use the current IO behavior.
2. Show update progress and the latest result inline in the Updates card.
3. Use a Snackbar only for brief confirmation; errors or manual-action guidance
   remain visible beside the controls until replaced by another result.
4. Preserve the existing in-flight guard and all `UpdateCoordinator` behavior.
5. Set an accessibility live region for result text without repeatedly
   announcing unchanged status.
6. Keep “Install unknown apps” explicitly optional and explain that Android's
   install confirmation remains required.

**Success criteria:** every `UpdateResult` has understandable, recoverable UI;
rapid taps cannot launch duplicate work; no critical result exists only in a
Toast; auto-update preference behavior is unchanged.

### Stage 6 — Redesign the accessibility blocking surface

**Status:** Done — the shared `activity_blocked.xml` is the Material 3
presentation inflated by `AccessibilityBlockingSurface`, a
`TYPE_ACCESSIBILITY_OVERLAY` owned by the bound accessibility service.
`BlockedActivity`, background activity launching, and the ordinary overlay
permission remain removed. The layout uses a themed, scrollable
shield/headline/subject-card hierarchy, `MaxWidthLinearLayout` for compact and
wide windows, a non-clickable vertical allowed-sites list, muted reset copy,
and a visible Go-home control. Empty optional views leave no spacing gap.

`AccessibilityBlockingContentRenderer` is an activity-independent binding seam
that preserves the `BlockRequest` message/subject/allowed-sites contract, uses
resource-backed list formatting, and wires the Go-home callback. The surface
owns top/bottom system-bar inset padding through `ViewCompat`.
[AccessibilityBlockingSurfaceLayoutTest] covers message and subject rendering,
shield/themed hierarchy, optional-site hiding, vertical-list/non-clickable
behavior, content order, and the Home callback. Existing blocking-controller
tests retain the HyperOS overlay lifecycle and HOME-fallback coverage; API 37
behavior remains part of Stage 7 manual verification.

**Test first:** add Robolectric tests for default content, subject rendering,
allowed-sites hidden/visible states, content order, and the Go-home callback.
Run against the current surface; tests must fail for the new hierarchy/state
contract as expected.

1. Implement the hierarchy in Section 4.3 with Material typography and semantic
   surface/error-container colors.
2. Format allowed sites as a readable vertical/bulleted list, not one comma
   string. Keep entries non-clickable and preserve their original values.
3. Hide empty subject/allowed-site containers without leaving spacing gaps.
4. Preserve the `BlockRequest` message, subject, and allowed-site contract.
5. Preserve the visible Go-home callback and the coordinator's HOME fallback;
   do not restore background activity launching.
6. Handle top/bottom insets, landscape, wide windows, and 200% fonts in the
   accessibility-owned window.

**Success criteria:** the surface provides no override controls, Go home invokes
HOME, and existing enforcement behavior is unchanged when blocked content is
revisited; urgency is clear without a saturated full-red background; all
message combinations are legible and accessible.

### Stage 7 — Accessibility, adaptive, and visual verification

**Status:** Partial — automated tests/builds and resource/layout contracts are
green; manual device/emulator verification is deferred because no emulator or
physical device is available in this environment.

Completed: `gradle test`, debug/release APK builds, and `gradle :app:lintDebug`;
Robolectric API 26 light/dark inflation and behavior tests; XML 48dp minimum
touch targets for actions; semantic textual status and headings; live regions;
explicit system-bar inset owners; width-capped content; and before/after
screenshot README checklists under `docs/ui/android/`.

Deferred: API 26/API 37 physical validation, TalkBack, hardware keyboard/D-pad,
Accessibility Scanner, gesture/three-button navigation, 200% font scale,
IME/landscape/split-screen screenshots, and matching after screenshots.

1. Add Android UI tests only where Robolectric cannot establish critical focus,
   Settings-intent, or semantics behavior. If instrumentation tests are added,
   configure an AndroidX test runner and only the required `androidTest`
   dependencies, and execute `:app:connectedDebugAndroidTest`. Do not add
   screenshot tooling unless stable baselines cannot be maintained manually.
2. Run lint and automated accessibility checks on both in-scope surfaces.
3. Manually verify:
   - API 26 and API 37;
   - compact portrait/landscape and wide/tablet or resizable window;
   - gesture and three-button navigation;
   - light and dark modes;
   - 100% and 200% font/display scaling;
   - TalkBack traversal and announcements;
   - hardware keyboard/D-pad focus;
   - empty, partial, complete, loading, success, and error states;
   - blocked screen with long subject and multiple allowed sites.
4. Run Android Accessibility Scanner and record any accepted exceptions with a
   rationale; do not suppress unresolved warnings.
5. Capture matching after screenshots under `docs/ui/android/after/` and add a
   before/after index.
6. Update `docs/REQUIREMENTS.md`, `docs/ARCHITECTURE.md`, and `AGENTS.md` with
   final UI contracts, architecture, validation commands, and newly discovered
   gotchas.

**Success criteria:** no clipped or obscured essential content, no touch target
below 48dp, no color-only status, sensible TalkBack/focus order, acceptable
contrast in both themes, and no unexplained accessibility findings.

## 6. Verification commands

Run focused tests after each task and the full suite before completing a stage:

```sh
cd android && gradle :app:testDebugUnitTest
cd android && gradle test
cd android && gradle :app:lintDebug
# Required only when Stage 7 introduces instrumentation tests:
cd android && gradle :app:connectedDebugAndroidTest
```

Before final completion also build both variants:

```sh
cd android && gradle :app:assembleDebug :app:assembleRelease
```

A release build may be unsigned when local `key.properties` is absent; that is
expected. Do not change signing or CI release behavior in this project.

## 7. Rollout and review

- Commit each green task separately with messages such as
  `android/ui: add Material 3 theme tokens (Stage 1)`.
- After each major screen change, review for duplicated resources, unnecessary
  wrappers, stale hardcoded copy, and behavior regressions.
- Install the release candidate on the real target phone before tagging.
- Verify setup Settings round-trips and blocking behavior on-device; static
  screenshots alone are insufficient.
- UI changes do not require sync backward-compatibility work, but they must not
  alter DTOs, Room schemas, tracker cadence, enforcement verdicts, or update
  signature checks.

## 8. Risks and mitigations

- **Theme migration changes widget behavior:** migrate one screen at a time and
  retain Robolectric inflation/behavior tests under API 26 and its latest
  supported SDK; validate API 37 on an emulator/device.
- **Edge-to-edge obscures controls:** assign one inset owner per region and test
  both navigation modes plus IME.
- **Material dependency conflicts with AppCompat:** select a stable compatible
  release in the Nix/Gradle environment and keep the dependency change isolated.
- **Visual polish accidentally changes permission semantics:** lock the required
  capability set in pure state tests before replacing layouts.
- **Large-font failures hide bottom actions:** allow reflow/scrolling and test
  200% font scale before visual approval.
- **Blocked surface becomes bypassable:** retain the touch-consuming accessibility
  overlay, Go-home callback, and blocking-controller regression tests; do not add
  links or override actions or restore background activity launching.
- **Sensitive token exposure:** mask by default, never place it in status text,
  screenshots, logs, or accessibility announcements.
- **Scope creep into a device dashboard:** any new service health, usage, policy,
  or sync status requires a separate requirements/design plan.

## 9. Reference guidance

- [Material 3 themes for Views](https://developer.android.com/develop/ui/views/theming/themes)
- [Edge-to-edge Views](https://developer.android.com/develop/ui/views/layout/edge-to-edge)
- [Responsive/adaptive design with Views](https://developer.android.com/develop/ui/views/layout/responsive-adaptive-design-with-views)
- [Android accessibility principles](https://developer.android.com/guide/topics/ui/accessibility/principles)
- [Accessibility testing](https://developer.android.com/guide/topics/ui/accessibility/testing)
- [Large-screen app quality](https://developer.android.com/docs/quality-guidelines/large-screen-app-quality)
