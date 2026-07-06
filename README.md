# pcontrol — Parental Control System

A self-hosted parental-control system for one or more Android phones. A Go
server (with a web UI) runs on a public VPS; a Kotlin Android client tracks
app and website usage, syncs it to the server, and enforces daily time limits
locally.

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

# Run Android instrumentation tests
cd android && gradle :app:testDebugUnitTest
```

### Running the server

```sh
# Start the server with a development database
go run ./cmd/pcontrold \
    --listen 127.0.0.1:8080 \
    --admin-password-hash "$(go run ./cmd/pcontrold hash-password <<< 'my-password')"

# The server is now at http://127.0.0.1:8080
# Health check: http://127.0.0.1:8080/healthz
```

**Server flags:**

| Flag | Env var | Default | Description |
|------|---------|---------|-------------|
| `--listen` | — | `127.0.0.1:8080` | HTTP listen address |
| `--db` | — | `pcontrol.db` | SQLite database path |
| `--admin-password-hash` | `PCONTROL_ADMIN_HASH` | — | bcrypt hash of admin password |

**Subcommand:** `go run ./cmd/pcontrold hash-password` reads a password from
stdin (one line) and prints its bcrypt hash to stdout. Pipe the output into
`--admin-password-hash` or `PCONTROL_ADMIN_HASH`.

### Android SDK note

The flake provides the Android SDK via `androidenv.composeAndroidPackages`.
If the derivation fails or times out on your system, install Android Studio
manually, set `ANDROID_HOME` to the SDK path, and remove
`androidComposition.androidsdk` from `flake.nix`.

## Deployment (VPS)

A systemd unit and Caddyfile are provided in the `deploy/` directory.

```sh
# Copy the systemd unit
sudo cp deploy/pcontrold.service /etc/systemd/system/
sudo systemctl daemon-reload

# Edit the unit to set PCONTROL_ADMIN_HASH to your bcrypt hash:
sudo systemctl edit pcontrold
# Add:
# [Service]
# Environment=PCONTROL_ADMIN_HASH=<your-bcrypt-hash>

# Copy the Caddy reverse-proxy config (optional)
sudo cp deploy/Caddyfile /etc/caddy/sites-enabled/pcontrol.example.com
# Replace pcontrol.example.com with your domain

# Start the service
sudo systemctl enable --now pcontrold
```

**Important:** The SQLite file (`pcontrol.db` in `StateDirectory=pcontrol`,
i.e. `/var/lib/pcontrol/pcontrol.db`) is the **only state to back up**.
It contains all devices, usage events, and policy settings. Schedule regular
backups of this single file.

## CI / CD

The repository includes a GitHub Actions workflow for building the Android APK:

| Trigger | Workflow | Artifact |
|---------|----------|----------|
| Push tag `android-*` | `.github/workflows/android-build.yml` | Signed or unsigned APK |

**Usage:**

```sh
# Tag an Android release
git tag android-v1.2.3
git push origin android-v1.2.3
```

The workflow:
1. Builds the release APK via `./gradlew :app:assembleRelease`
2. Signs the APK with `apksigner` (if secrets are configured)
3. Uploads the APK as a build artifact named `pcontrol-android-<version>`

### Signing setup

To enable APK signing in CI, set these [repository secrets](https://docs.github.com/en/actions/security-guides/using-secrets-in-github-actions):

| Secret | Value |
|--------|-------|
| `ANDROID_KEYSTORE_B64` | Base64-encoded JKS keystore (`base64 < release.keystore`) |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias |
| `ANDROID_KEY_PASSWORD` | Key password |

## License

MIT
