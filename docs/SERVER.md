# Relay server

The SIM Hub server is a **personal relay**, not the system of record for decrypted SMS content. Its purpose is to authenticate devices/controllers, persist encrypted events and commands, and bridge an Android SIM Node to the Web/PWA controller over HTTPS.

## Runtime

- Python 3.13
- Python standard library only
- SQLite with WAL mode
- Port `8787` by default
- Docker image runs as UID/GID `65534`
- Persistent state at `/data/simhub.db`

## Main server responsibilities

1. Issue short-lived, single-use enrollment tokens.
2. Register Android devices and return per-device high-entropy bearer tokens.
3. Authenticate device API requests using hashed device-token records.
4. Accept encrypted events idempotently.
5. Queue encrypted commands with expiration and idempotency protection.
6. Store device/SIM/telemetry state.
7. Serve controller APIs and the static PWA.
8. Record operational audit events without SMS body or OTP plaintext.
9. Send optional metadata-only notification webhooks.
10. Serve optional OTA metadata.

## Required configuration

```env
SIMHUB_ADMIN_TOKEN=<high-entropy random token>
SIMHUB_PUBLIC_BASE_URL=https://simhub.example.com
```

Recommended production options:

```env
SIMHUB_TOTP_SECRET=<base32 secret>
SIMHUB_DB=/data/simhub.db
SIMHUB_BIND=0.0.0.0
SIMHUB_PORT=8787
SIMHUB_OFFLINE_AFTER=180
SIMHUB_ENROLL_TTL=600
SIMHUB_COMMAND_TTL=120
SIMHUB_SESSION_TTL=28800
SIMHUB_EVENT_RETENTION_DAYS=0
```

Optional integrations:

```env
SIMHUB_NOTIFY_WEBHOOK_URL=
SIMHUB_NOTIFY_WEBHOOK_BEARER=
SIMHUB_OTA_FILE=/data/ota.json
```

## Security properties

- The relay does not receive the Vault Key during normal enrollment/API operation.
- Device tokens are stored as SHA-256 hashes.
- Enrollment tokens are short-lived, single-use and hashed at rest.
- SMS payloads and remote-send destination/body are stored as AES-GCM ciphertext.
- Notification webhooks contain only generic metadata and never SMS body, sender, recipient or OTP value.
- Raw enrollment query data and authorization headers are not written into normal HTTP logs.

## Deployment topology

```text
Internet
  → HTTPS :443
  → Caddy / Nginx / Traefik
  → 127.0.0.1:8787
  → SIM Hub relay
  → Docker volume /data
```

Do not expose the relay directly over plaintext HTTP on the public Internet. See `DEPLOYMENT.md` for the complete deployment procedure.


### v0.1.5 operational endpoints

- `POST /api/v1/auth/session` — exchange Admin Token + optional TOTP for a short-lived HttpOnly session.
- `GET /api/v1/stream` — authenticated Server-Sent Events wake-up channel for the PWA.
- `GET /api/v1/metrics` — compact authenticated operational counters.
- `DELETE /api/v1/events?before=<unix-seconds>` — purge old relay ciphertext.
- Optional `SIMHUB_PUSH_TICKLE_URL` — metadata-only command-available hook for an external FCM/OEM push adapter.

SQLite remains the default for the personal/single-user deployment. PostgreSQL/Redis are intentionally not introduced in v0.1.5 because they do not improve the core reliability guarantees at this scale.
