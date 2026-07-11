# Plan 07: Fix app-blocking enforcement (MIUI tick-loop freeze + BlockedActivity self-masking)

## Problem

On MIUI/Xiaomi devices, app blocking doesn't work:
`com.tencent.mm` (WeChat) is shown as blocked on the server dashboard, but
the kid can use it freely on the phone.

### Root causes (confirmed with a diagnostic build)

**Bug 1 — Tick loop stalls when pcontrol is backgrounded.**

`TrackerService` runs a 10-second tick loop on `Dispatchers.IO`. The loop fires
reliably while pcontrol is in the foreground, but MIUI throttles the
background coroutine the moment another app becomes foreground — the process
stays alive (foreground-service notification visible, `oom_adj=50`), but
`delay(10_000)` never wakes. No tick, no counter increment, no enforcement.

This is an OEM-specific throttle not visible in standard Android freezer
diagnostics (`use_freezer=false`, `isFrozen=false`). AppOps
`RUN_ANY_IN_BACKGROUND: allow` and `SYSTEM_ALERT_WINDOW: allow` are already
granted.

**Bug 2 — BlockedActivity self-masks enforcement.**

When `BLOCK_APP` fires, `Enforcer` launches `BlockedActivity`, which belongs
to `com.pcontrol.app`. The next tick sees `foreground=com.pcontrol.app` →
in `BASE_NEVER_BLOCK_PACKAGES` → `ALLOW` → enforcement stops. When the user
presses back (going home) and returns to WeChat, the tick loop has already
stalled (Bug 1), so no re-block happens. The red screen appears briefly then
disappears for good.

### What is NOT the problem

- Policy IS cached correctly (`policyVersion=4, limits=1`).
- `PolicyEngine.evaluateApp` DOES produce `BLOCK_APP` when the limit is
  exceeded.
- `startActivity` for `BlockedActivity` DOES work (user saw the red screen).
- `SYSTEM_ALERT_WINDOW` and `RUN_ANY_IN_BACKGROUND` appops are granted.

## Fix

### Strategy: enforce from the accessibility service

The accessibility service is system-bound
(`BIND_ACCESSIBILITY_SERVICE`) and receives `TYPE_WINDOW_STATE_CHANGED`
events even when pcontrol is in the background — MIUI does not throttle
accessibility services the same way it throttles foreground-service
coroutines.

By adding app-blocking enforcement to `BrowserAccessibilityService`, the
blocked app is detected immediately when it comes to the foreground, and
`BlockedActivity` is launched without depending on the tick loop.

Bug 2 is naturally fixed: when the user dismisses `BlockedActivity` and
navigates back to WeChat, a new `TYPE_WINDOW_STATE_CHANGED` fires for
`com.tencent.mm`, and the accessibility service re-launches
`BlockedActivity` immediately.

### Step 1: Extract shared enforcement logic (`BlockingCoordinator`)

Create `BlockingCoordinator` that encapsulates the "load policy + counters,
evaluate, enforce" flow. Both `TrackerService` and
`BrowserAccessibilityService` use it.

### Step 2: Add app-blocking to `BrowserAccessibilityService`

In `onAccessibilityEvent`, on `TYPE_WINDOW_STATE_CHANGED` events for
non-browser packages, call `BlockingCoordinator.checkAndEnforceApp(pkg)` on
a background coroutine.

### Step 3: Add `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

Add the permission to the manifest and request it at runtime. This helps
keep the tick loop alive for usage tracking on aggressive OEM ROMs.

### Step 4: Revert diagnostic logging

Clean up the `Log.w`/`Log.d` calls added during debugging.

## Status

- [x] Step 1: BlockingCoordinator + tests
- [x] Step 2: Accessibility service enforcement
- [x] Step 3: Battery optimization permission
- [ ] Step 4: Revert diagnostic logging (low priority, logs are non-harmful)
- [x] Build, install, verify on device