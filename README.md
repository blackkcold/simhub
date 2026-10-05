# SIM Hub

A personal, self-hosted Android SIM/SMS hub. One or more Android phones act as **SIM Nodes** and your own server acts only as an encrypted relay between them and the Web/PWA controller.

> Scope: SMS/SIM management only. **No phone calls, dialer, call log, call audio, SIP, or PSTN bridging.**

## What is implemented

- Android 10–17 / API 29–37 Agent, designed to become the default SMS app.
- Receive SMS, persist it to the Android SMS provider, encrypt it locally, queue durably, and relay it to your server.
- Client-side OTP detection; OTP plaintext stays inside the E2EE payload.
- Dual-SIM / eSIM aware routing by Android `subscriptionId`.
- Remote SMS sending with explicit device/subscription selection.
- SMS history synchronization and client-side full-text search.
- Optional local contact-name mapping (requires `READ_CONTACTS`; contact data is encrypted before upload).
- SIM/carrier/signal/service/battery/network/device telemetry.
- Multiple Android nodes, device naming, and device groups.
- Device-side durable offline event queue; server-side durable command queue, expiry, replay/idempotency protection.
- AES-256-GCM application-layer E2EE. The relay does not receive the vault key and cannot decrypt message bodies or OTP values.
- PWA controller with encrypted vault, inbox, OTP copy, send SMS, devices, diagnostics and enrollment deep links.
- Single-user relay auth with a strong admin token and optional TOTP.
- Generic metadata-only notification webhook for ntfy/Bark bridge/custom push relays.
- OTA metadata endpoint and Android update check hook.
- SQLite WAL storage, Docker deployment, health endpoint, audit log, backup-friendly single data volume.

## Architecture

```text
Android SIM Node(s)
  SMS / SIM / OTP / local queue
        │
        │ HTTPS + device token
        │ AES-GCM ciphertext payloads
        ▼
┌───────────────────────────────┐
│ Your personal SIM Hub Relay   │
│ SQLite + event/command queue  │
│ no vault key / no SMS decrypt │
└───────────────────────────────┘
        ▲
        │ HTTPS
        │ encrypted events/commands
        │
Web / PWA Controller
  vault key + decrypt/search/copy/send
```

## Quick start

```bash
cp .env.example .env
python3 scripts/gen_admin_token.py
# put the generated value into SIMHUB_ADMIN_TOKEN in .env
mkdir -p data
docker compose up -d --build
```

Put Caddy/Nginx/Traefik in front of port `8787` and expose the service only over HTTPS. Open the server URL, enter the admin token, create/unlock a local vault, then create an enrollment link and open it on the Android SIM Node.

For a no-Docker smoke test:

```bash
export SIMHUB_ADMIN_TOKEN="$(python3 scripts/gen_admin_token.py --raw)"
export SIMHUB_DB=/tmp/simhub.db
python3 server/simhub_server.py
```

## Android build

The Android module targets API 37 and AGP 9.4.1. CI builds a debug APK. For a local build, install JDK 17, Android SDK Platform 37 and Gradle 9.6+, then:

```bash
cd android
gradle :app:assembleDebug
```

Production signing keys are intentionally not part of this repository.

## Security model

The relay is intentionally "blind" to high-value content. Device and browser share a random 256-bit Vault Key out-of-band during enrollment. SMS bodies, OTP values, contact names and outbound SMS bodies are encrypted using AES-GCM before reaching the relay. See `docs/SECURITY_ARCHITECTURE.md` and `SECURITY.md`.

## Important Android behavior

For Android 17/API 37, real-time arbitrary OTP access cannot be designed around a normal background `RECEIVE_SMS` listener. The Agent is therefore built as a real default SMS handler. On Android 14+ the optional always-on private relay mode runs as a user-started `specialUse` foreground service; a persisted JobScheduler job remains as a recovery path.

## MMS

The project includes the default-SMS eligibility receiver and **MMS metadata/history observation**, but it does not implement a complete carrier-specific MMS PDU download/send stack. SMS/OTP is the production path. This limitation is deliberate rather than pretending MMS is reliable across carrier APNs without a full MMS transport implementation.

## License

MIT. See `THIRD_PARTY_NOTICES.md` for platform/tooling notices.
