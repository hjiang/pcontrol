# Plan 10: Make blocking independent of background activity launches

**Status:** Implemented locally — automated tests pass; connected-device validation remains required.

## Problem

WeChat (`com.tencent.mm`) is over its daily limit and the dashboard reports it as
`BLOCKED`, but it remains usable on the phone.

The installed app is the current `android-v0.0.6` release, so this is distinct
from the MIUI tick-throttling problem addressed by Plan 07. The policy and
verdict paths work; the final Android presentation step does not.

## Confirmed root cause

Device used for diagnosis:

- Xiaomi `2602BRT18C` (`dash`)
- HyperOS `OS3.0.304.0.WPLCNXM`
- Android 16 / API 36
- pcontrol `0.0.6` / versionCode 6 / targetSdk 37
- WeChat `8.0.76` (`com.tencent.mm`)

USB-debugging checks confirmed all normal prerequisites:

- usage access is allowed;
- `BrowserAccessibilityService` is enabled and bound;
- `TrackerService` is running as a foreground service;
- draw-over-other-apps is allowed;
- pcontrol is exempt from battery optimization;
- the process is alive and not frozen.

While WeChat was foreground, pcontrol queried the cached policy and counters,
reached `BLOCK_APP`, and attempted to start `BlockedActivity` every ten seconds.
HyperOS rejected every attempt:

```text
ActivityStarterImpl: MIUILOG- Permission Denied Activity
ActivityTaskManager: Abort background activity starts from 10309
ActivityTaskManager: START ... com.pcontrol.app/.BlockedActivity ... result code=102
MIUIOP(10021): ignore
```

WeChat remained the focused window throughout the reproduction. This proves the
failure is after policy evaluation: `Enforcer.launchBlockedActivity()` uses
`FLAG_ACTIVITY_NEW_TASK` (`Enforcer.kt:157-180`), but an ordinary activity
cannot be relied on as a background enforcement surface. Android restricts
background activity starts, and Xiaomi adds a separate background-pop-up app-op
even when `SYSTEM_ALERT_WINDOW` is granted.

The server's `BLOCKED` label is a policy state, not proof that Android displayed
the blocking UI.

## Goals

1. A blocked app must become unusable without starting an activity from the
   background.
2. Blocking must work on the diagnosed HyperOS device with Xiaomi's background
   pop-up permission denied.
3. The user must see which app/site is blocked and why.
4. Home, system navigation, and never-block packages must remain usable.
5. Returning to a blocked app from launcher or recents must block it again
   immediately.
6. The periodic tracker and accessibility-event paths must share one observable,
   testable enforcement contract.
7. A presentation failure must fail closed as far as an ordinary app can: eject
   the user to Home and report/log the degraded outcome, rather than claiming a
   successful block.

## Non-goals

- Device-owner/profile-owner provisioning or package suspension.
- Silent installation or kiosk/lock-task mode.
- Depending on Xiaomi-only intents or asking users to grant the vendor-specific
  background-pop-up permission as the permanent fix.
- Changing server policy semantics or the sync wire format.

## Proposed architecture

### 1. Replace `BlockedActivity` as the enforcement primitive

Render the existing blocked-screen content from the connected accessibility
service as a full-screen `WindowManager` view of type
`TYPE_ACCESSIBILITY_OVERLAY`.

An accessibility overlay is owned by the already-bound
`BrowserAccessibilityService`; it is not a background activity start and does
not depend on Xiaomi's background-pop-up app-op. The view must consume touches
to the covered app while leaving system navigation available.

Retain `BlockedActivity` only temporarily for explicit foreground navigation, or
remove it once all app and web fallback call sites use the new surface. It must
not remain an automatic fallback from a background service because that would
reintroduce the bug.

### 2. Separate decisions from presentation outcomes

Refactor the current boolean-returning coordinator contract. A true return from
`BlockingCoordinator.checkAndEnforceApp()` currently means that the verdict was
`BLOCK_APP`, not that `startActivity()` succeeded.

Introduce explicit values along these lines:

```kotlin
data class BlockRequest(
    val kind: BlockKind,
    val subject: String,
    val message: String,
    val allowedSites: List<String>,
)

enum class PresentationOutcome {
    SHOWN,
    ALREADY_SHOWN,
    EJECTED_TO_HOME,
    FAILED,
}
```

`BlockingCoordinator` should evaluate Room state and return a decision/request.
A presentation adapter should return the actual outcome. Do not log or return
"blocked" merely because the policy verdict was `BLOCK_APP`.

**Contract:**

- Preconditions: a non-empty package/subject and a connected service for overlay
  presentation.
- Postcondition for `SHOWN`/`ALREADY_SHOWN`: a touch-consuming accessibility
  overlay for that subject is attached exactly once.
- Postcondition for `EJECTED_TO_HOME`: `GLOBAL_ACTION_HOME` succeeded and a
  user-visible notification explains the block.
- Invariant: at most one blocking view is attached per accessibility-service
  instance.
- Invariant: a presentation result may mutate the surface only when its captured
  foreground generation still equals the controller's current generation.
- Resource rule: the service that adds the view removes it on replacement,
  confirmed navigation away, or service destruction. Interruption is handled
  separately as described below.

### 3. Make the accessibility service own overlay lifecycle

Add a small, injectable `BlockingSurface` interface and an Android
`AccessibilityBlockingSurface` implementation owned by
`BrowserAccessibilityService`.

Maintain a monotonic foreground generation. Increment it whenever the effective
foreground package/domain changes; every asynchronous Room evaluation captures
`(generation, package, domain)` and its result is discarded unless all still
match before presentation. The tracker and accessibility triggers must feed the
same serialized controller so an old blocked result cannot cover launcher or an
allowed app after navigation.

Compute one composite foreground decision before mutating the surface. Blocking
has precedence over allowing: `BLOCK_APP` wins, otherwise `BLOCK_WEB` wins,
otherwise WARN/ALLOW may dismiss an active block. In particular, app `ALLOW`
for a browser must not remove an overlay required by that browser's current web
verdict. A domain transition may dismiss a web block only after the composite
result for the new domain is known.

Required state transitions:

- blocked target enters foreground -> add/update the overlay;
- duplicate events for the same blocked target -> no second `addView`;
- another blocked target enters foreground -> replace the model in the existing
  view;
- a non-stale composite ALLOW for launcher, a never-block package, or another
  allowed app -> remove the overlay;
- the user taps "Go home" -> call `performGlobalAction(GLOBAL_ACTION_HOME)` and
  remove only after the launcher transition is observed;
- service interruption while blocked -> retain the attached surface; if the
  platform requires removal, successfully eject Home first, clear debounce
  state, and force unconditional re-evaluation on reconnection;
- service destruction -> remove the view exactly once and clear state;
- target app returns -> evaluate and show again.

Self-generated pcontrol/overlay events must not accidentally dismiss the block
or overwrite the last real foreground package.

Because the degraded path relies on Home, strengthen `NeverBlockResolver` as
part of this work. Resolve and retain every HOME-capable package when the default
HOME handler is null or ambiguous, and include the diagnosed Xiaomi launcher
(`com.miui.home`) in the tested fallback set. Failure to resolve one preferred
launcher must never make all launchers blockable.

### 4. Provide a fail-closed degraded path

If the overlay cannot be attached, immediately call
`AccessibilityService.performGlobalAction(GLOBAL_ACTION_HOME)`. Post a persistent
or high-priority block notification with the same message so the user knows why
they were ejected.

If neither overlay attachment nor Home succeeds, return `FAILED`, emit a
release-build warning containing the subject and failure class, and retry on the
next foreground/tick event. Never fall back automatically to
`startActivity(BlockedActivity)`.

### 5. Keep both enforcement triggers

- Accessibility `TYPE_WINDOW_STATE_CHANGED` remains the immediate trigger.
- `TrackerService` remains the periodic trigger so an app already in the
  foreground is blocked when usage crosses its limit.

Replace the package-only accessibility debounce with state tied to
`(foreground package, decision/presentation state)`. The existing
`lastCheckedPkg` never clears after an `ALLOW`, so accessibility alone cannot
re-evaluate a continuously foreground app when it crosses the threshold. The
periodic path currently masks that gap, but the new contract should make the
behavior explicit and testable.

## TDD implementation stages

### Stage 1 — Capture the regression in tests

**Status:** Implemented. Added framework-free controller tests; the initial focused test run failed as expected before the controller contracts existed.

Before production changes, add failing tests for:

1. `BLOCK_APP` does not require or request an Android activity launch.
2. A successful policy verdict with failed presentation is not reported as a
   successful block.
3. Repeated events for the same blocked package attach one view only.
4. Transitioning to launcher/allowed package removes the view exactly once.
5. Returning to the blocked package reattaches the view.
6. An app that remains foreground and crosses its limit is re-evaluated by the
   periodic path.
7. Overlay attachment failure invokes Home; Home failure produces `FAILED` and
   remains retryable.
8. Service destruction always frees an attached WindowManager view.
9. `BLOCK_WEB`'s two-strike fallback uses the same BAL-independent surface.
10. A blocked evaluation that completes after a Home/allowed transition is
    rejected by its stale foreground generation and cannot attach an overlay.
11. Composite arbitration covers app-ALLOW + web-BLOCK, transition to an allowed
    domain, and transition away from the browser without remove/reattach gaps.
12. Service interruption while blocked either retains the surface or ejects Home
    before removal; reconnection always re-evaluates.
13. Null/ambiguous HOME resolution retains all discovered HOME candidates, and
    `com.miui.home` is never blockable on the diagnosed device family.
14. The overlay's "Go home" control invokes the global action and waits for the
    foreground-generation transition before removal.

Use a pure state/controller test with fake `BlockingSurface`, fake global-action
adapter, and fake notification sink. Keep Android framework interaction behind
thin adapters so most tests remain local JVM tests. Run the focused tests and
explicitly record: **test failed as expected**.

Likely test files:

- `android/app/src/test/kotlin/com/pcontrol/app/BlockingControllerTest.kt` (new)
- `android/app/src/test/kotlin/com/pcontrol/app/BlockingCoordinatorTest.kt`
- `android/app/src/test/kotlin/com/pcontrol/app/EnforcerTest.kt`
- `android/app/src/test/kotlin/com/pcontrol/app/BrowserAccessibilityServiceTest.kt`
  or a framework-free event-controller equivalent

### Stage 2 — Implement the overlay surface and explicit result contract

**Status:** Implemented locally. `BlockingController` and `AccessibilityBlockingSurface` now provide the explicit outcome contract.

Likely files:

- `android/app/src/main/kotlin/com/pcontrol/app/BlockingSurface.kt` (new)
- `android/app/src/main/kotlin/com/pcontrol/app/AccessibilityBlockingSurface.kt`
  (new)
- `android/app/src/main/kotlin/com/pcontrol/app/BlockingCoordinator.kt`
- `android/app/src/main/kotlin/com/pcontrol/app/BrowserAccessibilityService.kt`
- `android/app/src/main/kotlin/com/pcontrol/app/TrackerService.kt`
- `android/app/src/main/kotlin/com/pcontrol/app/Enforcer.kt`
- `android/app/src/main/res/layout/activity_blocked.xml` (reuse or extract shared
  content into a neutral overlay layout)

Implementation details:

- use `WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY`;
- use `MATCH_PARENT` within the application area and a touch-consuming root
  view, but do not cover or hide system bars;
- make the window explicitly touchable and non-focusable so it intercepts the
  blocked app without trapping system navigation or the accessibility service;
- add a visible "Go home" control to the neutral overlay layout (the current
  `activity_blocked.xml` has no such control);
- create the correct window context on API levels that require/support it, with
  a minSdk 26 fallback;
- keep foreground generation, composite decision, and all add/update/remove
  operations serialized on the main thread;
- treat duplicate `addView`, missing attachment, and service teardown as
  explicit idempotent states rather than swallowing exceptions;
- preserve the message, subject, and allowed-sites content currently supplied to
  `BlockedActivity`;
- do not hold an `Activity` context or leak a detached service/view.

### Stage 3 — Remove obsolete background-activity dependency

**Status:** Implemented locally. Automatic `BlockedActivity` launching, its manifest entry, and the ordinary overlay permission/checklist have been removed.

Once every automatic block path uses the new surface:

- remove automatic `BlockedActivity` launches;
- remove `BlockedActivity` and its manifest entry if there is no explicit
  foreground-only use;
- audit `SYSTEM_ALERT_WINDOW` usage. If no other feature uses ordinary overlays,
  remove the manifest permission, MainActivity checklist row/button, and
  `Settings.canDrawOverlays()` gate. Accessibility overlays do not require that
  permission;
- retain Xiaomi-specific permission instructions only as troubleshooting for old
  releases, not as a runtime requirement.

This permission change must update every human-readable description and test in
the same change.

### Stage 4 — Documentation and diagnostics

**Status:** Implemented. Requirements, architecture, Plan 07, and the project gotchas document the accessibility-overlay and Home-fallback contract.

Update:

- `docs/REQUIREMENTS.md`: a policy-blocked foreground app must be made unusable
  without relying on background activity launch permission;
- `docs/ARCHITECTURE.md`: document decision -> accessibility overlay -> Home
  fallback, ownership, and lifecycle invariants;
- `AGENTS.md`: add the HyperOS background-activity-start gotcha after device
  validation;
- Plan 07: add a short cross-reference explaining that its accessibility trigger
  fix did not remove the `BlockedActivity` BAL dependency.

Add concise release-build diagnostics for presentation outcome and overlay
attach/remove failures. Avoid noisy per-tick logs when state has not changed.

### Stage 5 — Validation

**Status:** Android JVM validation passed (`gradle :app:testDebugUnitTest` and `gradle test`); physical HyperOS validation remains required.

Run local validation:

```sh
cd android && gradle :app:testDebugUnitTest
cd android && gradle test
```

Then build/install the new APK and validate on the connected Xiaomi device while
leaving `MIUIOP(10021)` denied:

1. Confirm usage access, accessibility, and tracker state.
2. Open already-over-limit WeChat.
3. Verify the blocking overlay appears promptly, consumes touches, and includes
   the correct message.
4. Verify logcat contains no pcontrol `Abort background activity starts` or
   `BlockedActivity` launch.
5. Press Home, use launcher/settings, then return through both launcher and
   recents; WeChat must be blocked every time. Repeat with gesture navigation
   and three-button navigation.
6. Delay a block evaluation while navigating Home and verify the stale result
   cannot cover launcher.
7. Leave WeChat open just below the limit and verify it becomes blocked within
   one tracker interval after crossing the limit.
8. Repeat after screen off/on, accessibility interruption/reconnect, and process
   restart; the blocked app must never become interactable during interruption.
9. Exercise web blocking through its two-strike fallback, including a browser
   that is app-allowed while its current domain is web-blocked.
10. Verify `com.miui.home`, other HOME candidates, never-block packages, allowed
    domains, and system settings remain reachable.
11. Run for at least five minutes and inspect `dumpsys window`/logcat for leaked
    or duplicate overlay windows.

## Acceptance criteria

- On the diagnosed HyperOS device, WeChat cannot be interacted with after its
  cached policy evaluates to `BLOCK_APP`, with Xiaomi background pop-ups denied.
- No automatic blocking path calls `startActivity(BlockedActivity)`.
- Returning to a blocked app from launcher or recents re-blocks immediately.
- Crossing a limit while continuously foreground blocks within one 10-second
  tracker interval.
- Home and never-block packages remain usable.
- Overlay resources are removed on every terminal lifecycle path.
- Unit tests cover presentation failure and event/lifecycle transitions.
- `gradle test` passes, and USB-device validation captures no BAL denial for
  pcontrol.

## Immediate workaround (not the fix)

On this Xiaomi device, enabling pcontrol's vendor-specific "Display pop-up
windows while running in the background"/"Start in background" permission would
likely allow the current `BlockedActivity` implementation. This was not changed
during diagnosis. It is unsuitable as the product fix because it is Xiaomi-only,
can be denied by default, and preserves the fragile background-activity design.
