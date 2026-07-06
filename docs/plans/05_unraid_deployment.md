# Plan 05 — Server deployment to Unraid via GHCR images

## Overview

Add the machinery to run `pcontrold` as a Docker container on an Unraid
host, with images built by GitHub Actions and pulled from the GitHub
Container Registry (`ghcr.io`). This replaces the VPS/systemd deployment
path in `deploy/pcontrold.service` for the Unraid use case (the systemd
files stay for anyone still deploying to a bare VPS).

End state:

- `server/Dockerfile` builds a small static image containing only the
  `pcontrold` binary.
- A new workflow `.github/workflows/server-image.yml` publishes
  `ghcr.io/<owner>/pcontrol-server` for `linux/amd64` and `linux/arm64`,
  tagged `latest` (main) and `X.Y.Z` (on `server-v*` tags, mirroring the
  existing `android-*` tag convention).
- `deploy/unraid/` contains an Unraid Docker template XML plus a README
  covering first-run setup (admin hash, volume, GHCR auth, reverse proxy).
- Optional hardening: a `/healthz` endpoint and Docker `HEALTHCHECK` so
  Unraid's dashboard shows real container health.

## Design decisions

- **Image contents**: multi-stage build. Builder stage
  `golang:1.26-alpine` (kept in sync with `server/go.mod` via Renovate);
  final stage `gcr.io/distroless/static-debian12:nonroot`. `CGO_ENABLED=0`
  works because the store uses pure-Go `modernc.org/sqlite`. Distroless
  gives us tzdata + CA certs with no shell, and the binary doubles as the
  `hash-password` tool (`docker run --rm -i <image> hash-password`).
- **State**: single volume. The container uses `--db /data/pcontrol.db`;
  the Unraid template maps `/data` to `/mnt/user/appdata/pcontrol`. The
  SQLite file is the only server state (see AGENTS.md), so backup =
  copying that one directory.
- **Config**: admin credential passed as `PCONTROL_ADMIN_HASH` (already
  supported by `cmd/pcontrold/main.go`); no new flags needed. The
  container listens on `0.0.0.0:7285` (the systemd default of
  `127.0.0.1` would be unreachable through Docker's bridge network).
- **Port**: `7285` ("PCTL" on a phone keypad) inside and outside the
  container. 8080 is the most-collided port on Unraid (SABnzbd,
  qBittorrent, etc.); 7285 is IANA-unassigned and unused by common
  community apps. Same number on both sides keeps the mapping 1:1.
- **Image name**: `ghcr.io/<owner>/pcontrol-server` — leaves room for
  other images later; avoids colliding with the repo-level package
  namespace.
- **Tagging**: `latest` + `sha-<short>` on every push to `main` touching
  `server/`; `X.Y.Z` + `latest` on `server-vX.Y.Z` tags. Unraid's
  built-in "update available" check works against `latest`; pinning to a
  version tag remains possible for cautious upgrades.
- **User**: the template runs the container with `--user 99:100`
  (Unraid's `nobody:users`) so the appdata files stay manageable from the
  share. Distroless `nonroot` tolerates an arbitrary uid since the binary
  only needs write access to `/data`.

## Stage 1: Dockerfile

**Goal**: `server/Dockerfile` (+ `server/.dockerignore`) producing a
working image locally.

**Success Criteria**:
- `docker build -t pcontrol-server server/` succeeds.
- `docker run --rm -i pcontrol-server hash-password <<< 'x'` prints a
  bcrypt hash.
- Container started with `-e PCONTROL_ADMIN_HASH=... -v $(pwd)/tmp:/data`
  serves the login page on `:7285` and creates `/data/pcontrol.db`;
  restarting it reuses the DB (migrations are idempotent — see AGENTS.md
  gotcha).
- Image runs as non-root; `docker run --user 99:100` also works.

**Tests**: manual smoke test script above (documented in the Dockerfile
header comment); no Go test changes. `cd server && go test ./... && go
vet ./...` must still pass (no source changes expected).

**Sketch**:

```dockerfile
FROM golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags='-s -w' -o /pcontrold ./cmd/pcontrold

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /pcontrold /pcontrold
VOLUME /data
EXPOSE 7285
HEALTHCHECK CMD ["/pcontrold", "healthcheck"]
ENTRYPOINT ["/pcontrold"]
CMD ["--listen", "0.0.0.0:7285", "--db", "/data/pcontrol.db"]
```

(`hash-password` works by overriding CMD: the flag package treats it as
`flag.Arg(0)`.)

**Status**: Complete

## Stage 2: GHCR publish workflow

**Goal**: `.github/workflows/server-image.yml` building and pushing
multi-arch images.

**Success Criteria**:
- Push to `main` touching `server/**` (or the workflow file) publishes
  `ghcr.io/<owner>/pcontrol-server:latest` and `:sha-<short>` for
  `linux/amd64,linux/arm64`.
- Pushing tag `server-v0.1.0` publishes `:0.1.0` (and moves `latest`).
- Pull requests build the image (no push) so Dockerfile breakage is
  caught pre-merge.
- Workflow uses only `permissions: {contents: read, packages: write}` and
  logs in with the ambient `GITHUB_TOKEN` — no new secrets.

**Tests**: CI itself; verify with `docker manifest inspect` that both
architectures are present after the first main-branch run.

**Sketch** (mirrors the style of `server-tests.yml`):

```yaml
on:
  push:
    branches: [main]
    tags: ['server-v*']
    paths: ['server/**', '.github/workflows/server-image.yml']
  pull_request:
    paths: ['server/**', '.github/workflows/server-image.yml']
```

Steps: checkout → `docker/setup-qemu-action` → `docker/setup-buildx-action`
→ `docker/login-action` (ghcr.io, skipped on PRs) → `docker/metadata-action`
(tags: `latest` on default branch, `type=match,pattern=server-v(.*),group=1`,
`type=sha`) → `docker/build-push-action` with `context: server`,
`push: ${{ github.event_name != 'pull_request' }}`, GHA layer cache.

Note: `paths` filters do not apply to tag pushes — tag builds always run,
which is what we want for releases. This is a new-dependency exception
justified under AGENTS.md conventions: the `docker/*` actions are the
standard, maintained way to publish images and are Renovate-managed like
the existing actions.

**Status**: Complete

## Stage 3: Unraid deployment artifacts

**Goal**: `deploy/unraid/pcontrol.xml` (Docker template) and
`deploy/unraid/README.md` documenting the full setup path.

**Success Criteria**:
- Copying `pcontrol.xml` to
  `/boot/config/plugins/dockerMan/templates-user/` on the Unraid host
  makes "pcontrol" appear under Docker → Add Container, pre-filled with:
  - Repository `ghcr.io/<owner>/pcontrol-server:latest`
  - Port mapping host `7285` → container `7285` (bridge network)
  - Path `/mnt/user/appdata/pcontrol` → `/data` (rw)
  - Variable `PCONTROL_ADMIN_HASH` (masked)
  - Extra parameters `--user 99:100`
- README covers, in order:
  1. Generating the hash on the Unraid box:
     `docker run --rm -i ghcr.io/<owner>/pcontrol-server hash-password`.
  2. GHCR visibility: either make the package public (Settings →
     Packages) or add a `docker login ghcr.io` with a read-only PAT via
     Unraid's registry credentials.
  3. Pinning the `pcontrol` appdata share to the cache pool — SQLite over
     Unraid's FUSE `/mnt/user` layer has unreliable locking; appdata must
     live on cache/pool storage (the Unraid default, but worth stating).
  4. Reverse-proxy/TLS options for exposing the dashboard and
     `POST /api/v1/sync` to the phone (existing Caddy per
     `deploy/Caddyfile`, Nginx Proxy Manager, or Tailscale) — the Android
     client needs a stable HTTPS URL.
  5. Upgrade path (Unraid "apply update" repulls `latest`) and backup
     (stop container, copy `/mnt/user/appdata/pcontrol`).
- README warns that bcrypt hashes contain `$` characters: fine in the
  Unraid template UI, but if the user opts for docker-compose instead,
  each `$` must be doubled (`$$`) in the compose file.

**Tests**: manual — install the template on the Unraid host, register a
device, run one sync from the phone, restart the container, confirm data
survives.

**Status**: Complete

## Stage 4 (optional): container health check

**Goal**: real health status in Unraid's Docker UI.

Distroless has no shell/curl, so a Docker `HEALTHCHECK` must exec the
binary itself:

1. Add `GET /healthz` to the web router returning `200 ok` without auth
   (it must not touch the session store; keep it a one-liner in
   `web.NewRouter`).
2. Add a `healthcheck` subcommand to `cmd/pcontrold` (next to
   `hash-password`) that GETs `http://127.0.0.1:7285/healthz` and exits
   0/1.
3. `HEALTHCHECK CMD ["/pcontrold", "healthcheck"]` in the Dockerfile.

**Success Criteria**: `docker ps` shows `healthy`; killing the listener
flips it to `unhealthy`.

**Tests**: Go test for the `/healthz` handler in
`server/internal/web/dashboard_test.go` style (status-code assertion);
manual container check.

**Status**: Complete

## Stage 5: documentation sync

**Goal**: repo docs reflect the new deployment path.

**Success Criteria**:
- README gains a "Deploy on Unraid" section (or links to
  `deploy/unraid/README.md`).
- AGENTS.md repository-layout block lists `deploy/unraid/` and the new
  workflow; the CI paragraph mentions `server-image.yml` and the
  `server-v*` tag convention.
- This plan file's stages are all marked Complete, then per convention
  the plan stays as the historical record (numbered plans are kept, not
  deleted).

**Tests**: n/a (docs).

**Status**: Complete

## Out of scope

- Automating reverse proxy / TLS on Unraid (user-environment specific).
- Publishing to Community Applications (would need a separate template
  repo and feed submission; the user-template XML covers a single host).
- Any Android client changes — the sync URL is already configurable.
- Migrating existing VPS data (a one-line `scp` of the SQLite file,
  documented in the Unraid README, is sufficient).
