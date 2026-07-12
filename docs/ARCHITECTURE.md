# Architecture

## Components

- `server/`: Go JSON API, SQLite store, and server-rendered HTMX administration dashboard.
- `android/core/`: pure Kotlin/JVM domain and policy logic, including foreground-event state transitions.
- `android/app/`: Android services, Room persistence, usage-event adapter, synchronization, and local enforcement adapters.

## Usage attribution flow

`TrackerService` runs a 10-second monitoring tick. It reads Android `UsageEvents` from a cursor kept for the service lifetime, adapts activity transitions to core `AppEvent` values, and calls `AppUsagePoller.updateForegroundPackage` with the retained foreground package. An empty event batch preserves state because foreground events describe transitions, not a heartbeat.

The service checks `PowerManager.isInteractive` before writing counters, so a retained package is not charged while the display is off. For interactive foreground use, the service increments Room `usage_counter` entries for the app and, when available, the browser domain. Sync serializes unsent counter deltas to the server. During sync handling, the server best-effort records its UTC receipt time in `devices.last_seen_at`; the dashboard renders that value as the device's visible last usage report time.

## Invariants

- Core logic has no Android dependencies.
- Usage-event batches are processed in chronological order.
- A background transition clears foreground state only for the tracked package.
- Wire JSON remains snake_case and backward-compatible.
