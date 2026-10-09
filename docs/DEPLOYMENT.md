# Deployment

## Recommended topology (v0.4.0+)

```text
Browsers / admins ----HTTPS----> admin.example.com:443 ----+
                                                           |  Caddy / existing reverse proxy
Android / modem nodes --HTTPS----> node.example.com:443 ---+            |
                                                                     127.0.0.1:8787
                                                                            |
                                                                  SIM Hub Relay / SQLite WAL
```

The two HTTPS hostnames **must be different**. The management hostname serves Web/PWA authentication; the node hostname is restricted to node API endpoints. DNS **A** records must point to your server's public IPv4; any **AAAA** record must be reachable. Allow inbound TCP 80/443 for managed Caddy. The Relay port **8787 binds only to loopback** and must not be publicly exposed.

## 1. Recommended: Linux interactive provisioning

```bash
git clone https://github.com/blackkcold/simhub.git
cd simhub
python3 scripts/setup.py --admin-domain admin.example.com --node-domain node.example.com
```

This script validates Docker/Compose, DNS A/AAAA and ports, generates an owner-only `.env` (strong Admin Token, TOTP secret and username), creates a managed Caddyfile and starts Relay + Caddy. **It will never write DNS records at your DNS provider.** Back up `.env` privately, and register a Passkey under **Settings** after initial token/TOTP login. Passkeys do not unlock the local Vault on other devices.

Already manage HTTPS with Nginx / Caddy / Traefik? Choose external mode:

```bash
python3 scripts/setup.py --mode external --admin-domain admin.example.com --node-domain node.example.com
```

External mode starts the Relay but leaves TLS/reverse-proxy configuration to the operator. Route both hosts to `127.0.0.1:8787`, explicitly set the real client's forwarded IP, and deny public access to `/readyz` and `/healthz`. Never trust arbitrary public `X-Forwarded-For` input. For pre-DNS staging, `--skip-dns-check --no-start` creates config without launching containers.

## 2. Verify and upgrade

For internal health checks run:

```bash
docker compose exec -T simhub python3 -c "import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:8787/readyz').read().decode())"
```

For managed-Caddy logs:

```bash
docker compose -f docker-compose.yml -f compose.caddy.yml logs --tail=50 caddy
```

Upgrade **after a consistent backup**, preserving the original management hostname (Passkey RP ID), node hostname and Node Keys:

```bash
./scripts/backup.sh
git pull --ff-only
python3 scripts/setup.py --upgrade --admin-domain admin.example.com --node-domain node.example.com
```

Add `--mode external` when applicable. This non-destructive upgrade applies to existing dual-host installs; earlier single-host deployments require an explicit DNS/proxy migration plan. After Relay/PWA is updated, install the [latest signed Android APK](https://github.com/blackkcold/simhub/releases/latest). Verify matching version and signing certificate before installation. For legacy releases, consult [migration guidance](UPGRADE_0.4_TO_0.5.md).

## v0.11.1: Ubuntu 24.04 Rootless Docker socket EACCES fix

**Confirmed regression in v0.11.0:** the generated `systemd --user` service included `PrivateTmp=true` and `ProtectSystem=full`. Either filesystem sandbox may implicitly activate `PrivateUsers` in an unprivileged user manager, changing the Docker socket's group ownership as seen by the updater (`nogroup`) and causing `connect: permission denied` despite the host rootless user being authorized.

**Fix:** v0.11.1 removes only these namespace-triggering unit options. The updater remains a dedicated **non-root** service with `NoNewPrivileges=true`, restricted address families and syscall architectures, unchanged Unix socket ACL, signature verification, image Digest enforcement and fixed update operations. Do **not** make `docker.sock` world-accessible or add users to the host rootful Docker group.

### Repair an already-broken v0.11.0 host

Because the updater cannot contact Docker, the Web update button **cannot repair its own unit**. On the dedicated *rootless Docker user* account, update the checked-out deployment scripts and rerun the installer (without sudo):

```bash
cd /path/to/simhub
git pull --ff-only
bash scripts/install-updater.sh
systemctl --user cat simhub-updater.service
systemctl --user is-active simhub-updater.service
journalctl --user -u simhub-updater.service -n 50 --no-pager
```

The installer now checks that the actual service PID shares the calling user's user namespace and that the local control socket returns `rootless-verified`. It reports failure instead of falsely reporting successful installation if the process crashes or the IPC socket cannot be reached. With the unit repaired, update to v0.11.1 through the normal management UI. Do not restart or reinitialize the Relay volume merely to repair this user service.

If an older *system-wide* root updater remains enabled, disable it separately: `sudo systemctl disable --now simhub-updater.service`.

Troubleshooting for overrides and inherited restrictions:

```bash
systemctl --user cat simhub-updater.service
systemctl --user show simhub-updater.service -p PrivateTmp -p ProtectSystem -p PrivateUsers
systemctl --user show simhub-updater.service -p MainPID
docker info --format '{{json .SecurityOptions}}'
stat -Lc '%U:%G %a %n' "$XDG_RUNTIME_DIR/docker.sock"
```

Do not add `PrivateTmp`, `ProtectSystem`, `PrivateUsers`, `ProtectHome`, or equivalent filesystem namespace directives to this user service without first verifying that `docker.sock` remains reachable with the actual user/GID mapping. Ubuntu 24.04 host integration testing is still required after installation; CI checks the unit text and static security invariants but does not emulate this host's user manager.

## v0.11.0: verified, rootless Docker updates

**Security boundary:** The admin PWA only submits a fixed version request to a Unix socket. The updater is never root, must connect to a rootless Docker daemon and refuses unverified images. The service no longer runs Docker builds on the production host. A valid SHA-256 in an unsigned release manifest is *not* accepted as authorization: the server image must have a Cosign signature bound to the exact GitHub Actions release workflow identity.

### Migration from the old root updater

1. On the **original rootful** host, make a consistent SQLite backup and an offline copy of .env, volume data and Vault recovery material. Verify the backup before cutover.
2. **Disable the previous root service:** `sudo systemctl disable --now simhub-updater.service`. Do not leave it running; it retains elevated control even after code changes.
3. Install and configure Docker Engine in [Rootless mode](https://docs.docker.com/engine/security/rootless/) under a dedicated normal user. Configure user lingering and verify `docker info` contains `rootless`. Do not add this user to the rootful docker group or mount /var/run/docker.sock.
4. Move the SIM Hub deployment checkout, its .env and the **verified existing named-volume data** to the dedicated rootless Docker daemon. Rootful and rootless volume stores are separate; never assume switching the Docker context migrates data. Start the rootless SIM Hub and verify `/readyz`, devices, database and HTTPS ingress before removing the old installation.
5. Install a trusted Cosign CLI (see [official instructions](https://docs.sigstore.dev/cosign/system_config/installation/)) and Linux POSIX ACL support (`setfacl`). Keep the rootless Docker user logged in for installation:

```bash
# As the dedicated non-root user; never with sudo:
docker info --format '{{json .SecurityOptions}}'
docker compose up -d --no-build simhub
bash scripts/install-updater.sh
systemctl --user status simhub-updater
```

6. Confirm that .simhub-updater/control.sock exists, is not publicly accessible, and that the management UI reports `rootless-verified`. If the old root service is still running or the image signature is unavailable, updates must fail closed.

### Updating and recovery

- GitHub Actions builds linux/amd64 and linux/arm64 images, pushes to GHCR, signs the immutable OCI **digest** and publishes that digest in update-manifest.json.
- The updater validates the release/schema/version, verifies exact Cosign certificate identity and issuer, pulls the pinned digest, verifies again, backs up SQLite, updates only SIMHUB_IMAGE_REF and SIMHUB_INSTALLED_VERSION in .env and restarts the SIM Hub service.
- On failed readiness or version validation, it restores the previously tagged local image and .env version, then checks readiness again. Restoring an incompatible schema is **not** automatic. Schema changes require supervised migration and a restore runbook.
- The container still runs UID/GID 65534, with read-only root filesystem and no Docker socket. The updater's local socket ACL authorizes only its mapped container UID. Do not grant the whole deployment folder to that UID.
- GHCR image packages must be publicly readable (or the rootless deploy user must have narrowly scoped pull-only access). If the package is private and unauthenticated, update attempts fail rather than using an unsigned fallback.
- Do not enable update automation in a rootful Docker context. On Docker hosts that cannot run Rootless Docker, use a controlled manual update process rather than a web-reachable root daemon.
- GitHub repository **Release immutability** is a repository setting and must be enabled by the repository administrator; signed image verification is enforced independently.

## 3. Backups

The default Compose file stores relay state in the Docker named volume `simhub-data`. SQLite runs in WAL mode. For a consistent backup, run `./scripts/backup.sh`, which uses SQLite's online backup API through the running container. You can also stop the service and archive the volume. If you replace the named volume with a Linux bind mount, ensure UID/GID `65534:65534` can write the directory because the relay intentionally runs as an unprivileged user.

The relay backup does **not** contain the Vault Key. A database backup alone therefore cannot decrypt SMS bodies.

## 4. Metadata-only push bridge

Set:

```env
SIMHUB_NOTIFY_WEBHOOK_URL=https://your-ntfy-or-bridge.example/path
SIMHUB_NOTIFY_WEBHOOK_BEARER=optional-secret
```

The POST body contains only event kind, a generic notification string and device name. It never includes sender, recipient, SMS body or OTP value.

## 5. OTA metadata

Mount a JSON file to `SIMHUB_OTA_FILE`, for example:

```json
{
  "versionCode": 13,
  "versionName": "0.5.0",
  "url": "https://github.com/blackkcold/simhub/releases/download/v0.5.0/simhub-agent-v0.5.0-release.apk",
  "sha256": "...",
  "notes": "Bug fixes"
}
```

The Android app only checks and opens the HTTPS download URL. It does not silently install APKs.


## Retention, notifications and push tickle

- `SIMHUB_EVENT_RETENTION_DAYS`, `SIMHUB_AUDIT_RETENTION_DAYS` and `SIMHUB_COMMAND_RETENTION_DAYS` are enforced continuously by the maintenance loop. `SIMHUB_MAINTENANCE_INTERVAL` controls cadence. Admins may also purge events explicitly.
- `SIMHUB_NOTIFY_OTP_ONLY`, `SIMHUB_NOTIFY_MAX_AGE` and `SIMHUB_NOTIFY_DEVICE_IDS` constrain metadata-only notification delivery. History-sync events never trigger new-message notifications.
- `SIMHUB_PUSH_TICKLE_URL` is an optional metadata-only adapter hook. It receives only device ID/reason/time and can map that device to FCM or an OEM push provider; command content remains on the relay and is fetched over authenticated HTTPS. Always-on relay + JobScheduler remain the built-in fallback.


## Trusted reverse proxy

The relay does **not** trust arbitrary forwarded-IP headers. Configure `SIMHUB_TRUSTED_PROXIES` with only loopback/container CIDRs that can actually reach the relay. When the TCP peer is trusted, the first `X-Forwarded-For` address becomes the audit/rate-limit client IP; otherwise the socket peer is used.

The default Compose deployment enables `SIMHUB_TRUST_DOCKER_GATEWAY=true`. The relay detects its **exact default-route Docker bridge gateway**, accounting for `docker-proxy` SNAT even when the host binds only `127.0.0.1:8787`. Do **not** trust an entire private CIDR merely to fix forwarded IP; an attacker on another container could forge the header. Other networking modes must explicitly configure a suitably narrow trusted peer.

The host-side `curl http://127.0.0.1:8787/readyz` works in this default topology because the exact Docker gateway is admitted. It is still not publicly routed through Caddy.

The provided Caddy example sets `X-Forwarded-For` explicitly. Never configure `0.0.0.0/0` or `::/0` as trusted proxies.

## Health and maintenance

- `/healthz`: process liveness.
- `/readyz`: database connectivity and current schema version.
- Docker healthcheck uses `/readyz`.
- `scripts/backup.sh`: SQLite online backup.
- Maintenance continuously removes expired sessions/enrollment tokens and data beyond configured retention.

## Linux / DJI Modem Agent

Install from `modem_agent/`. The built-in adapter order is:

1. direct Quectel AT serial detection for DJI Gen1/QDC507;
2. ModemManager/`mmcli` for generic Linux cellular modems.

The external `dji4g` utility may still be used separately for DJI network-interface setup, but SIM Hub SMS receive/send does not rely on undocumented CLI AT commands.

Use the PWA to create a Linux/DJI enrollment JSON, then run the documented `enroll` command and enable `simhub-modem.service`. The temporary enrollment JSON should be deleted after successful consumption.

## v0.2.2 direct mobile notifications

Set one of the following, or continue using the generic notification webhook:

```env
SIMHUB_NOTIFY_BARK_URL=https://api.day.app/YOUR_DEVICE_KEY
# or
SIMHUB_NOTIFY_NTFY_URL=https://ntfy.example.com/YOUR_PRIVATE_TOPIC
SIMHUB_NOTIFY_NTFY_TOKEN=YOUR_TOPIC_WRITE_TOKEN
```

The relay sends only `New OTP received` or `New SMS received`, never the actual OTP, sender, recipient or SMS body. Keep topic identifiers and provider tokens private. Configure a single provider to avoid duplicate notifications. PWA must be unlocked to decrypt the message. A push acknowledgement is not equivalent to on-device delivery.
