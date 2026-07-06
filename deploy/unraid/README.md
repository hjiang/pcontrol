# Deploying pcontrol on Unraid

This directory contains a [Docker template](pcontrol.xml) for running
pcontrold as a container on an Unraid server, with the Docker image pulled
from the GitHub Container Registry (`ghcr.io`).

## Prerequisites

- An Unraid server (6.9+ recommended) with the Docker service enabled.
- A [GitHub](https://github.com) account (needed if you make the package
  private — see [GHCR visibility](#ghcr-visibility) below).

## Step-by-step setup

### 1. Generate an admin password hash

Run this command on the Unraid server (via the web terminal or SSH):

```sh
docker run --rm -i ghcr.io/hjiang/pcontrol-server hash-password
```

Type the password you want (e.g. `my-secret-admin-pw`) and press Enter.
The container prints a long string starting with `$2a$...` — this is the
**bcrypt hash**. Copy it; you'll need it in step 3.

> **Warning about `$` characters:** The hash contains `$` signs. These
> are fine in the Unraid template UI (step 3). If you later switch to a
> `docker-compose.yml` instead, each `$` must be doubled (`$$`) because
> Compose interprets `$` as variable substitution.

### 2. Install the Docker template

Copy the template XML to the Unraid flash drive:

```sh
# From your local machine:
scp deploy/unraid/pcontrol.xml root@tower:/boot/config/plugins/dockerMan/templates-user/

# Or directly on the Unraid server:
cp /path/to/pcontrol.xml /boot/config/plugins/dockerMan/templates-user/
```

Then in the Unraid web UI:

1. Go to **Docker** → **Add Container**.
2. Select **pcontrol** from the template dropdown.
3. Fill in the fields (see step 3).
4. Click **Apply**.

> **Template updates:** To update the template, just re-copy the XML and
> the next time you open the container edit dialog the new defaults will
> show (existing containers are not affected).

### 3. Configure the container

In the Add/Edit Container dialog:

| Field | Value | Notes |
|-------|-------|-------|
| **Repository** | `ghcr.io/hjiang/pcontrol-server:latest` | Pre-filled from the template. Pin a version like `ghcr.io/hjiang/pcontrol-server:0.1.0` for cautious upgrades. |
| **Web port** | `7285` | Host port mapping. Change only if `7285` conflicts with another container. |
| **Appdata path** | `/mnt/user/appdata/pcontrol/` | Host path mapped to `/data` inside the container. The SQLite database lives here. |
| **Admin password hash** | *paste the hash from step 1* | Masked input — the hash is never shown after you save. |
| **Extra Parameters** | `--user 99:100` | Pre-filled. Runs as `nobody:users` so appdata files stay manageable from the Unraid share. |

Click **Apply** to start the container.

### 4. Verify the container is running

```sh
# Check container status
docker ps --filter name=pcontrol

# Check the health status (should show "healthy" after a few seconds)
docker inspect --format='{{.State.Health.Status}}' pcontrol

# Test the web dashboard
curl -s http://unraid-ip:7285/healthz
# Should print: ok
```

Open `http://unraid-ip:7285` in a browser. You should see the admin login
page. Log in with the password you entered in step 1.

### 5. (Recommended) Pin appdata to cache pool

SQLite does not work reliably when the appdata share lives on the array
(where it traverses Unraid's FUSE layer, which has unreliable file
locking). **The appdata share must be stored on cache/pool storage** —
this is the default for `appdata` shares in Unraid, but verify:

1. Go to **Settings** → **Share Settings** → **Appdata**.
2. Set **Use cache pool** to **Prefer** or **Only**.
3. Run the **Mover** if files are already on the array.

### 6. Enable HTTPS for the Android client

The Android device syncs with the server over HTTPS. You have several
options for terminating TLS in front of the container:

**Option A — Nginx Proxy Manager (recommended for Unraid)**
Install the *Nginx Proxy Manager* community app, point a domain or
subdomain to your Unraid server's IP, and create an SSL proxy host
forwarding to `http://pcontrol:7285` (Docker internal DNS) or
`http://unraid-ip:7285`.

**Option B — Existing Caddy / reverse proxy**
If you already run Caddy (or another proxy) on the Unraid host, adapt the
[deploy/Caddyfile](../../deploy/Caddyfile) from this repository.

**Option C — Tailscale**
If you run Tailscale on both the Unraid server and the phone, you can
skip a public domain. Use `tailscale serve` (or `tailscale cert` with
your own reverse proxy) to get HTTPS via Tailscale's automatic
certificates on the machine's Tailscale name.

**After setting up TLS**, update the **Sync server URL** on the Android
app to `https://your-domain.example.com` (or the Tailscale URL).

## GHCR visibility

The Docker image is published to the GitHub Container Registry at
`ghcr.io/hjiang/pcontrol-server`. By default, packages inherit the
visibility of the repository.

- **Public repository**: the image is public and `docker pull` works
  without authentication.
- **Private repository**: you need to authenticate. In Unraid, go to
  **Docker** → **Registry** and add:
  - **URL**: `ghcr.io`
  - **Username**: your GitHub username
  - **Password**: a [GitHub PAT](https://github.com/settings/tokens) with
    `read:packages` scope (no other scopes needed).

## Upgrading

When a new version is published to `ghcr.io`:

1. In the Unraid web UI, go to **Docker** → select the pcontrol container.
2. Click **Force Update** (or **Update** if Unraid shows a newer tag).
3. The container restarts with the new image. **No data migration is
   needed** — the SQLite schema uses idempotent migrations (see
   `server/internal/store/migrations.sql`).

## Backups

The **only state** is the `appdata` directory mapped to `/data`:

```sh
# Stop the container
docker stop pcontrol

# Back up the appdata directory
cp -a /mnt/user/appdata/pcontrol /mnt/user/backups/pcontrol-$(date +%Y%m%d)

# Restart
docker start pcontrol
```

To restore, stop the container, replace the appdata directory, and start
the container again.

## Migrating from a VPS

If you previously ran pcontrold on a VPS with the systemd deployment:

```sh
# Copy the SQLite database from the VPS
scp user@vps:/var/lib/pcontrol/pcontrol.db /mnt/user/appdata/pcontrol/

# Copy the admin password hash (or generate a new one)
# Then restart the container
docker restart pcontrol
```

## Troubleshooting

| Symptom | Likely cause | Fix |
|---------|-------------|-----|
| Container keeps restarting | Missing `PCONTROL_ADMIN_HASH` | Go to Edit, set the admin password hash, Apply. |
| `docker inspect` shows `unhealthy` | The health check can't reach the listener. | Check `docker logs pcontrol` for startup errors. |
| Login page loads but password is rejected | Wrong hash in `PCONTROL_ADMIN_HASH`. | Regenerate the hash and update the container variable. |
| Dashboard works but Android sync fails with TLS error | No HTTPS or self-signed cert. | Set up a reverse proxy with a valid TLS certificate (see step 6). |
| Web UI doesn't load | Port conflict or container not started. | Verify `docker ps` shows the container; try a different host port. |
