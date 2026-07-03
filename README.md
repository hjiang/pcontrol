# pcontrol — Parental Control System

A self-hosted parental-control system for one Android phone. A Go server (with
a web UI) runs on a public VPS; a Kotlin Android client tracks app and website
usage, syncs it to the server, and enforces daily time limits locally.

## Repository layout

```
server/          Go module (pcontrol/server) — JSON API + web dashboard
  cmd/pcontrold/  Main binary
  internal/       Domain, store, API, web handlers
android/         Gradle project
  core/           Pure Kotlin JVM module — policy engine, domain logic
  app/            Android app — tracking service, enforcement, sync
deploy/          systemd unit + Caddyfile
```

## Development

You need [Nix](https://nixos.org/download.html) with flakes enabled.

```sh
# Enter the dev shell (Go, JDK, Gradle, Android SDK, sqlite)
nix develop

# Run Go tests (server)
cd server && go test ./... && go vet ./...

# Run JVM tests (android core)
cd android && gradle :core:test

# Run the server
go run ./cmd/pcontrold --listen 127.0.0.1:8080
# Server is at http://127.0.0.1:8080/healthz
```

The Android SDK is provided by `nixpkgs.androidenv` inside the dev shell.
If the derivation fails on your system, install Android Studio manually and
set `ANDROID_HOME` to the Android SDK path, then remove
`androidComposition.androidsdk` from `flake.nix`.

## License

MIT
