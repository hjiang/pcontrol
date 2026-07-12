# Architecture

## Components

- `server/`: Go JSON API, SQLite store, and server-rendered HTMX administration dashboard.
- `android/core/`: pure Kotlin/JVM domain and policy logic, including foreground-event state transitions.
- `android/app/`: Android services, Room persistence, usage-event adapter, synchronization, and local enforcement adapters.

## Usage attribution flow

`TrackerService` runs a 10-second monitoring tick. It reads Android `UsageEvents` from a cursor kept for the service lifetime, adapts activity transitions to core `AppEvent` values, and calls `AppUsagePoller.updateForegroundPackage` with the retained foreground package. An empty event batch preserves state because foreground events describe transitions, not a heartbeat.

The service checks `PowerManager.isInteractive` before writing counters, so a retained package is not charged while the display is off. For interactive foreground use, the service increments Room `usage_counter` entries for the app and, when available, the browser domain. Sync serializes unsent counter deltas to the server on a guarded side coroutine; each HTTP call is cancellation-aware, has a total timeout, and closes its response so network failure cannot stall monitoring or suppress later heartbeats. During sync handling, the server best-effort records its UTC receipt time in `devices.last_seen_at`; the dashboard renders that value as the device's visible last usage report time.

## Blocking presentation flow

`BlockingCoordinator` evaluates cached policy and Room counters, returning app/web requests rather than treating a policy `BLOCK_*` verdict as a successful presentation. `BrowserAccessibilityService` owns the `TYPE_ACCESSIBILITY_OVERLAY` and a main-thread `BlockingController`; both accessibility events and `TrackerService`'s 10-second evaluation feed that controller. A monotonic foreground generation rejects late evaluations, and app requests take precedence over web requests. The blocking overlay consumes touches without starting an activity, while system navigation remains available. If attachment fails, the controller attempts `GLOBAL_ACTION_HOME` and posts a high-priority block notification. The service removes the blocking view exactly once on confirmed navigation away or destruction; a user-requested Home action retains it until the foreground transition is observed.

HyperOS 3 freezes even foreground-service/accessibility UIDs after they become background-idle, disabling held wake locks and delaying accessibility events until the app is opened. `AccessibilityKeepAliveOverlay` therefore owns a separate one-pixel, non-touchable, non-focusable accessibility window for the accessibility-service lifetime. This keeps the UID window-visible without intercepting input. Its idempotent controller detects platform detachment, retries transient attachment failures, and detaches before reconnection replacement or service destruction; it remains independent of the full-screen blocking view.

## Invariants

- Core logic has no Android dependencies.
- Usage-event batches are processed in chronological order.
- A background transition clears foreground state only for the tracked package.
- Wire JSON remains snake_case and backward-compatible.

## Android UI presentation architecture

- `MainActivity` uses Views/XML with a small Material 3 foundation. `SetupUiState`, `CapabilityFacts`, and `CapabilityRenderer` keep permission collection, pure mapping, and rendering separate; `:core` remains Android-free.
- Required capability order is stable: usage, accessibility, notifications, battery, server, optional updater. `SetupUiState.build` computes required counts and `canStart`; the updater cannot affect the monitoring gate. The accessibility-owned blocking overlay requires no ordinary overlay permission.
- `UpdateUiMapper` maps every `UpdateResult` to `UpdateUiState` (`IDLE`, `CHECKING`, `SUCCESS`, `ACTION_REQUIRED`, `ERROR`). `MainActivity` renders progress/status inline, exposes an internal update-runner seam for tests, and retains the existing coordinator/in-flight guard.
- `validateServerConfiguration` is an Android-free URI/token validator. The server dialog uses Material `TextInputLayout` fields and persists only validated, normalized values through `SecretPrefs`.
- `MainActivity` uses edge-to-edge system bars and `ViewCompat` inset ownership. `MaxWidthLinearLayout` fills compact windows and caps/centers content on wide windows. `AccessibilityBlockingSurface` reuses the themed blocking layout, applies system-bar insets in its accessibility-owned window, keeps allowed-site content informational and non-clickable, and exposes a Go-home control without restoring background activity launching.
- Robolectric unit tests include merged Android resources and pin SDK 26 in `app/src/test/resources/robolectric.properties`; this avoids targetSdk 37 android-all fetching in the JDK 17 build environment. On-device API 37 and accessibility scanner validation remain manual follow-ups.
