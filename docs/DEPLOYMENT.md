# Deployment

## Recommended topology

```text
Internet
  |
HTTPS :443
  |
Caddy / Nginx / Traefik
  |
127.0.0.1:8787
  |
SIM Hub relay
  |
Docker named volume: simhub-data → /data/simhub.db
```

The relay intentionally binds to loopback in the Docker Compose example. Do not expose port 8787 directly to the public Internet without TLS termination.

## 1. Configure

```bash
cp .env.example .env
python3 scripts/gen_admin_token.py
```

Set `SIMHUB_PUBLIC_BASE_URL` to the HTTPS URL that Android nodes will reach.

New deployments default to `SIMHUB_REQUIRE_TOTP=true`. Generate a Base32 secret with `python3 scripts/gen_totp_secret.py`, set `SIMHUB_TOTP_SECRET`, then use the code only when creating a short-lived browser session; normal API requests use the Secure HttpOnly session cookie.

## 2. Start

```bash
docker compose up -d --build
curl http://127.0.0.1:8787/healthz
```

Then configure your reverse proxy. `Caddyfile.example` is the shortest path.

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
  "versionCode": 2,
  "versionName": "0.2.0",
  "url": "https://github.com/blackkcold/simhub/releases/download/v0.2.0/simhub-agent.apk",
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
