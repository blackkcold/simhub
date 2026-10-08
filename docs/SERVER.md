# Relay server

The SIM Hub server is a **personal relay**, not the system of record for decrypted SMS content. Its purpose is to authenticate devices/controllers, persist encrypted events and commands, and bridge Android and Linux/DJI modem SIM Nodes to the Web/PWA controller over HTTPS.

## Runtime

- Python 3.13
- Python standard library only
- SQLite with WAL mode
- Port `8787` by default
- Docker image runs as UID/GID `65534`
- Persistent state at `/data/simhub.db`

## Main server responsibilities

1. Issue short-lived, single-use enrollment tokens.
2. Register generic nodes and return per-node high-entropy bearer tokens.
3. Authenticate device API requests using hashed device-token records.
4. Accept encrypted events idempotently.
5. Queue encrypted commands with expiration and idempotency protection.
6. Store Node/Channel/telemetry state.
7. Serve controller APIs and the static PWA.
8. Record operational audit events without SMS body or OTP plaintext.
9. Send optional metadata-only notification webhooks.
10. Serve optional OTA metadata.

## Required configuration

```env
SIMHUB_ADMIN_TOKEN=<high-entropy random token>
SIMHUB_MANAGEMENT_ORIGIN=https://admin.example.com
SIMHUB_PUBLIC_BASE_URL=https://node.example.com
```

Recommended production options:

```env
SIMHUB_REQUIRE_TOTP=true
SIMHUB_TOTP_SECRET=<base32 secret>
SIMHUB_DB=/data/simhub.db
SIMHUB_BIND=0.0.0.0
SIMHUB_PORT=8787
SIMHUB_OFFLINE_AFTER=180
SIMHUB_ENROLL_TTL=600
SIMHUB_COMMAND_TTL=120
SIMHUB_ADMIN_USERNAME=admin
SIMHUB_SEPARATE_SURFACES=true
SIMHUB_SESSION_TTL=86400
SIMHUB_SESSION_IDLE_TTL=28800
SIMHUB_EVENT_RETENTION_DAYS=30
SIMHUB_AUDIT_RETENTION_DAYS=180
SIMHUB_COMMAND_RETENTION_DAYS=30
SIMHUB_MAINTENANCE_INTERVAL=3600
SIMHUB_TRUSTED_PROXIES=127.0.0.1/32,::1/128
```

Optional integrations:

```env
SIMHUB_NOTIFY_WEBHOOK_URL=
SIMHUB_NOTIFY_WEBHOOK_BEARER=
SIMHUB_OTA_FILE=/data/ota.json
```

## Security properties

- The relay does not receive the Master Vault Key or plaintext Node Keys; it stores only Master-wrapped Node Key envelopes.
- Device tokens are stored as SHA-256 hashes and rotate automatically with a two-phase prepare/commit protocol.
- Enrollment tokens are short-lived, single-use and hashed at rest.
- SMS payloads and remote-send destination/body are stored as AES-GCM ciphertext.
- Notification webhooks contain only generic metadata and never SMS body, sender, recipient or OTP value.
- Raw enrollment query data and authorization headers are not written into normal HTTP logs.

## v0.4.0–v0.5.0 security and device changes

- **v0.4.0:** Admin Username and WebAuthn Passkey are supported in addition to Admin Token/TOTP. Public deployments isolate management and device routes on separate hostnames. `scripts/setup.py` can provision DNS-checked managed Caddy TLS or prepare an external proxy.
- **v0.5.0:** Active-tab Vault recovery is browser-side; the server never receives the Vault Key. Session inactivity defaults to **8 hours**, with **24-hour absolute expiry**. Passwordless refresh cannot bypass the server's session check. Passive absent-cookie checks must not consume password-attempt lockout quota.
- **SIM privacy:** Android MSISDNs, when readable, are wrapped in a Node-Key-encrypted inventory. The Relay stores only ciphertext, not plaintext phone numbers. Charging, Wi-Fi and OS-defined cellular fallback *status* are sanitized operational fields.
- **History and diagnostics:** `sms.sync_recent`, `sms.sync_older` and legacy `sms.sync_history` remain encrypted device commands. `diagnostics.request` returns an encrypted health event, not an unredacted Android log. `device.network_policy` reports expected default-data-SIM configuration; it does not grant privileged SIM switching.

## Deployment topology

```text
Admin browser → HTTPS admin.example.com :443
Android / modem → HTTPS node.example.com :443
  → Caddy / Nginx / Traefik
  → 127.0.0.1:8787
  → SIM Hub relay
  → Docker volume /data
```

Do not expose the relay directly over plaintext HTTP on the public Internet. See `DEPLOYMENT.md` for the complete deployment procedure.


### 0.2 operational endpoints

- `POST /api/v1/auth/session` — exchange Admin Token + optional TOTP for a short-lived HttpOnly session.
- `GET /api/v1/stream` — authenticated Server-Sent Events wake-up channel for the PWA.
- `GET /api/v1/metrics` — compact authenticated operational counters.
- `DELETE /api/v1/events?before=<unix-seconds>` — purge old relay ciphertext.
- `POST /api/v1/devices/{id}/token/prepare` / `commit` — crash-safe bearer-token rotation.
- `GET /readyz` — DB/schema readiness for Docker and orchestration.
- Optional `SIMHUB_PUSH_TICKLE_URL` — metadata-only command-available hook for an external FCM/OEM push adapter.

SQLite remains the default for the personal/single-user deployment. PostgreSQL/Redis are intentionally not introduced because they do not improve the core reliability guarantees at this scale.
