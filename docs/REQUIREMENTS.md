# Requirements

## Product

pcontrol is a self-hosted parental-control system. A Go server provides a JSON API and admin dashboard; an Android client records device use, synchronizes it with the server, and enforces cached daily limits locally.

## Usage tracking

- The Android client records foreground app use in device-local time and stores counters by day, kind, and subject.
- A sustained, interactive foreground app session must continue to receive attribution even when no new Android usage event is emitted or HyperOS would otherwise idle the accessibility-service UID.
- The client must not attribute usage while the display is non-interactive.
- App and web counters are synchronized as idempotent deltas; Android and server deployments remain backward-compatible.
- Network synchronization must have a total timeout, release every HTTP response, and never stall the 10-second monitoring loop.

## Dashboard reporting

- Every device card must visibly show the server time when it last reported usage. A device that has never reported must be shown as `never`.

## Enforcement and privacy

- Enforcement must operate from the cached policy when the server is unavailable.
- A policy-blocked foreground app or browser domain must be made unusable without relying on a background activity launch permission. The accessibility-owned blocking surface consumes app touches; if it cannot attach, the client must attempt to return the user Home and show a block notification.
- On HyperOS, monitoring must retain a non-touchable accessibility-owned window so the vendor UID freezer cannot suspend attribution, sync, or blocking callbacks while another app is foreground.
- Device bearer tokens are stored only as hashes on the server.
- Usage access and the required Android permissions must be granted before monitoring can start.

## Android UI contracts (plan 09)

- `MainActivity` is a parent-assisted setup and device-health screen; it does not show usage totals, policy details, service health, or last-sync data.
- Required setup consists of usage access, accessibility, notifications, battery optimization, and server configuration. The accessibility-owned blocking overlay requires no ordinary overlay permission. Install-unknown-apps is explicitly optional and never gates monitoring.
- The start action is disabled until every required capability is granted; status is textual (Ready / Action needed), not color-only, and the first incomplete required step is surfaced.
- Server URL validation accepts absolute HTTP(S) URLs with a nonblank host and no query/fragment; surrounding whitespace and a trailing URL slash are normalized. Device tokens remain masked and are never shown outside the editable field.
- Update feedback is durable beside the controls, including loading, success, recoverable error, and manual-action guidance; Android's install confirmation remains required.
- `AccessibilityBlockingSurface` has no override or allowed-site links. Its visible Go-home control invokes HOME, empty subject/sites are hidden, and allowed sites remain informational, non-clickable, and vertically readable.
- App-owned UI supports light/dark themes, edge-to-edge system bars, API 26–37, wide/resizable windows, landscape, and large fonts without clipping essential actions. Visual/on-device verification remains pending where hardware is unavailable.
