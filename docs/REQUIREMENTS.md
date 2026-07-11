# Requirements

## Product

pcontrol is a self-hosted parental-control system. A Go server provides a JSON API and admin dashboard; an Android client records device use, synchronizes it with the server, and enforces cached daily limits locally.

## Usage tracking

- The Android client records foreground app use in device-local time and stores counters by day, kind, and subject.
- A sustained, interactive foreground app session must continue to receive attribution even when no new Android usage event is emitted.
- The client must not attribute usage while the display is non-interactive.
- App and web counters are synchronized as idempotent deltas; Android and server deployments remain backward-compatible.

## Dashboard reporting

- Every device card must visibly show the server time when it last reported usage. A device that has never reported must be shown as `never`.

## Enforcement and privacy

- Enforcement must operate from the cached policy when the server is unavailable.
- A policy-blocked foreground app or browser domain must be made unusable without relying on a background activity launch permission. The accessibility-owned blocking surface consumes app touches; if it cannot attach, the client must attempt to return the user Home and show a block notification.
- Device bearer tokens are stored only as hashes on the server.
- Usage access and the required Android permissions must be granted before monitoring can start.
