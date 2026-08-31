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
deploy/          systemd unit + Caddyfile + Unraid Docker template
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
| `--smtp-host` | `PCONTROL_SMTP_HOST` | — | SMTP host for daily email reports (empty disables the job) |
| `--smtp-port` | `PCONTROL_SMTP_PORT` | `587` | SMTP port |
| `--smtp-username` | `PCONTROL_SMTP_USERNAME` | — | SMTP username (optional) |
| `--smtp-password` | `PCONTROL_SMTP_PASSWORD` | — | SMTP password (optional) |
| `--smtp-from` | `PCONTROL_SMTP_FROM` | — | From address for daily reports (optional) |
| `--report-send-after` | `PCONTROL_REPORT_SEND_AFTER` | `3h` | Delay after local midnight before sending daily reports |

### Daily email usage reports (SMTP)

The server can email a daily plain-text usage report per device. Setup needs
an SMTP account that supports **STARTTLS on the submission port (587)** —
implicit-TLS port 465 is not supported, and authentication is only attempted
when a username is configured.

**1. Configure SMTP on the server** — via the `--smtp-*` flags above or their
`PCONTROL_SMTP_*` env vars (env vars are the usual choice in Docker/systemd):

| Env var | Meaning |
|---------|---------|
| `PCONTROL_SMTP_HOST` | SMTP server hostname or IPv6 literal. Empty disables the job |
| `PCONTROL_SMTP_PORT` | SMTP port (default `587`) |
| `PCONTROL_SMTP_USERNAME` | SMTP login (optional; when set, `PCONTROL_SMTP_PASSWORD` is used) |
| `PCONTROL_SMTP_PASSWORD` | SMTP password (or app password) |
| `PCONTROL_SMTP_FROM` | From address for reports (optional; defaults to `pcontrol@localhost`) |
| `PCONTROL_REPORT_SEND_AFTER` | Delay after local midnight before sending (default `3h`) |

**2. Add recipients and a timezone per device** — open a device's page in the
dashboard and use ⚙️ Device Settings to enter the recipient addresses (one
per line) and the device's timezone (an IANA name such as `Europe/Berlin`;
empty means UTC).

**3. Verify** — at startup the server logs
`daily email reports enabled via <host>:<port>`; send failures (invalid
timezone, SMTP errors) are logged per device. A report is sent for the
previous day a few hours after midnight in the device's timezone (3 hours by
default, so late client-side usage ingestion lands before the report is
compiled), at most once per device per day — a server restart never re-sends
a report that was already mailed, and a device with no recipients is skipped.

Worked example (development):

```sh
go run ./cmd/pcontrold \
    --listen 127.0.0.1:8080 \
    --admin-password-hash "$(go run ./cmd/pcontrold hash-password <<< 'my-password')" \
    --smtp-host smtp.example.com \
    --smtp-port 587 \
    --smtp-username reports@example.com \
    --smtp-password 'app-password' \
    --smtp-from 'pcontrol@example.com'
```

Notes:

- The same flags work for any STARTTLS-capable submission server, including
  Gmail/Google Workspace (with an app password) and self-hosted Postfix.
- An invalid `PCONTROL_SMTP_PORT` or `PCONTROL_REPORT_SEND_AFTER` value is
  logged as a warning and falls back to the default.
- A host given as an IPv6 literal (e.g. `2001:db8::1`) is supported and is
  bracketed correctly in the dial address.

**Subcommand:** `go run ./cmd/pcontrold hash-password` reads a password from
stdin (one line) and prints its bcrypt hash to stdout. Pipe the output into
`--admin-password-hash` or `PCONTROL_ADMIN_HASH`.

### Android SDK note

The flake provides the Android SDK via `androidenv.composeAndroidPackages`.
If the derivation fails or times out on your system, install Android Studio
manually, set `ANDROID_HOME` to the SDK path, and remove
`androidComposition.androidsdk` from `flake.nix`.

## Deployment (Docker)

A `server/Dockerfile` produces a distroless container image with the
pcontrold binary. Pre-built images are published to the GitHub Container
Registry:

```
ghcr.io/hjiang/pcontrol-server:latest
```

### Deploy on Unraid

See [`deploy/unraid/README.md`](deploy/unraid/README.md) for the full
setup guide — install the Docker template, generate an admin password
hash, and configure TLS for the Android client.

### Deploy anywhere with Docker

```sh
docker run -d \
  --name pcontrol \
  -p 7285:7285 \
  -v /path/to/appdata:/data \
  -e PCONTROL_ADMIN_HASH='<your-bcrypt-hash>' \
  -e PCONTROL_SMTP_HOST='smtp.example.com' \
  -e PCONTROL_SMTP_PORT='587' \
  -e PCONTROL_SMTP_USERNAME='reports@example.com' \
  -e PCONTROL_SMTP_PASSWORD='<smtp-password>' \
  -e PCONTROL_SMTP_FROM='pcontrol@example.com' \
  ghcr.io/hjiang/pcontrol-server:latest

(The `PCONTROL_SMTP_*` lines enable daily email reports — omit them to
leave the job disabled.)
```

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
# Optional: enable daily email reports (see "Daily email usage reports (SMTP)")
# Environment=PCONTROL_SMTP_HOST=smtp.example.com
# Environment=PCONTROL_SMTP_PORT=587
# Environment=PCONTROL_SMTP_USERNAME=reports@example.com
# Environment=PCONTROL_SMTP_PASSWORD=<smtp-password>
# Environment=PCONTROL_SMTP_FROM=pcontrol@example.com

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

The repository includes Docker image and Android APK workflows:

| Trigger | Workflow | Artifact |
|---------|----------|----------|
| Push to `main` touching `server/**` | `.github/workflows/server-image.yml` | Docker image `ghcr.io/hjiang/pcontrol-server:latest` |
| Push tag `server-v*` | `.github/workflows/server-image.yml` | Docker image `ghcr.io/hjiang/pcontrol-server:X.Y.Z` |
| Push tag `android-*` | `.github/workflows/android-build.yml` | Signed or unsigned APK |

**Usage:**

```sh
# Tag an Android release
git tag android-v1.2.3
git push origin android-v1.2.3

# Tag a server image release
git tag server-v0.1.0
git push origin server-v0.1.0
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
