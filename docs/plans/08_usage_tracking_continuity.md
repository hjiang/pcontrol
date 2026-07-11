# Plan 08: Preserve foreground usage across event-free ticks

## Problem

On the attached Android device, pcontrol has usage access, battery-optimization exemption, and a running foreground service, but reports much less use than actual use. `TrackerService` queries only the previous 60 seconds of `UsageEvents` on each 10-second tick. Foreground events are transitions rather than heartbeats, so an uninterrupted app session has no event after its first minute. The tracker treats that empty result as no foreground app and stops counting.

## Requirements

- Attribute an uninterrupted, interactive foreground session on every tick after its initial foreground event ages out of the query window.
- Process events incrementally from the prior query boundary, rather than repeatedly querying a rolling window.
- Preserve the last foreground package when an incremental event batch is empty.
- Stop attributing usage while the display is non-interactive.
- Keep foreground-state transition logic pure and unit tested in `:core`.
- Do not alter the wire protocol or the persisted counter schema.

## Design

`AppUsagePoller.updateForegroundPackage` will accept the previously known package and a chronologically ordered batch of transitions. It applies transitions in order: foreground transitions replace the state; a background transition clears it only when it belongs to the tracked package; unrelated background events do not erase another active package.

`TrackerService` will retain an event-query cursor for its process lifetime. Its first query retains the existing 60-second bootstrap window; later queries begin at the prior cursor. It will resolve foreground state against `currentForegroundPkg`, and use `PowerManager.isInteractive` to avoid counting while the screen is off.

## Validation

1. Add a core regression test: a package remains foreground when the next event batch is empty.
2. Run it before implementation and confirm it fails because the stateful API is absent.
3. Implement the poller and service changes.
4. Run `cd android && gradle :core:test` and `gradle :app:testDebugUnitTest`.
5. Build/install the debug APK and use USB debugging to confirm the foreground service and usage access remain healthy.

## Status

- [x] Root cause confirmed on the attached device
- [x] Add failing regression test (failed as expected before implementation)
- [x] Implement stateful foreground tracking
- [x] Run automated tests (`gradle test`, `:app:assembleRelease`)
- [x] Restart monitoring and verify the foreground service and continued usage-access polling on the unlocked device
