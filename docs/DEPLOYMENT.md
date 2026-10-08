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

Add `--mode external` when applicable. This non-destructive upgrade applies to existing dual-host installs; earlier single-host deployments require an explicit DNS/proxy migration plan. After Relay/PWA is updated, install the [v0.5.0 signed Android APK](https://github.com/blackkcold/simhub/releases/tag/v0.5.0). See [two-release migration guide](UPGRADE_0.4_TO_0.5.md).

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
