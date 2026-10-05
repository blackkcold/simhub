# SIM Hub architecture

SIM Hub is a **personal, self-hosted Android SIM/SMS management system**. Android phones are SIM Nodes; the user's own server is a relay and durable queue; the Web/PWA is the controller. The design intentionally excludes phone calls, dialer control, call logs, cellular-call audio, SIP and PSTN bridging.

## Components

```text
┌──────────────────────────┐
│ Android SIM Node(s)      │
│                          │
│ Default SMS handler      │
│ SMS Provider             │
│ OTP parser               │
│ SIM/subscription state   │
│ AES-256-GCM encryption   │
│ Durable event queue      │
│ Android Keystore         │
└────────────┬─────────────┘
             │ HTTPS
             │ device authentication
             │ encrypted events / commands
             ▼
┌──────────────────────────┐
│ Personal Relay Server    │
│                          │
│ Device registry          │
│ Event / command API      │
│ SQLite WAL               │
│ Idempotency / expiry     │
│ Audit log                │
│ Metadata-only webhook    │
│ Static PWA hosting       │
│ No Vault Key             │
└────────────┬─────────────┘
             │ HTTPS
             │ short-lived browser session
             │ encrypted content
             ▼
┌──────────────────────────┐
│ Web / PWA Controller     │
│                          │
│ Local Vault Key          │
│ Inbox / OTP              │
│ Search / copy            │
│ Remote SMS send          │
│ Devices / groups         │
│ Diagnostics / enrollment │
└──────────────────────────┘
```

## Trust model

The relay is intentionally blind to SMS content. A random 256-bit Vault Key is created by the controller and transferred to the Android node only through the one-time enrollment deep link. The relay stores ciphertext, routing metadata and operational state, but does not receive the Vault Key through an API endpoint.

Android stores the recovery/master Vault Key wrapped by Android Keystore. For v2 traffic, both Android and the controller derive a device-specific AES-256 key using HKDF-SHA256 and the device ID. The controller keeps the master Vault Key only while unlocked. SMS body, OTP value, contact name, destination number and outbound SMS body are encrypted before the relay receives them.

## Data flow

### Incoming SMS

```text
Carrier / SIM
  → Android default SMS handler
  → write to SMS Provider
  → OTP parsing + normalization
  → AES-256-GCM encrypt payload
  → durable local queue
  → HTTPS upload
  → relay SQLite ciphertext record
  → controller fetch
  → browser-side decrypt
```

### Remote SMS send

```text
Controller
  → compose message locally
  → AES-256-GCM encrypt command
  → relay durable command queue
  → Android fetches command
  → validates expiry / ID / authenticated ciphertext
  → selects Android subscriptionId
  → SmsManager sends through selected SIM
  → status event returns through encrypted event path
```

## Reliability model

- Incoming events are persisted locally before network upload.
- Event IDs are unique and relay ingestion is idempotent.
- Commands are written durably before delivery attempts.
- Commands have command IDs, sequence/order metadata, expiry and idempotency keys.
- Android atomically claims command IDs before side effects, persists command state, and durably queues acknowledgements to prevent concurrent duplicate execution.
- A user-started foreground relay provides low-latency private operation; JobScheduler remains a recovery path.
- The server is stateless except for the SQLite data volume and optional OTA metadata file.

## Android model

The app targets API 37 with minSdk 29. It is designed to hold `ROLE_SMS`, which is important for reliable arbitrary SMS/OTP handling on modern Android rather than depending on a generic background SMS listener. Multi-SIM routing uses Android `subscriptionId`, not a fixed SIM1/SIM2 assumption.

The app deliberately requests no dialer role and contains no call-control or call-log path.

## Realtime and wake-up model

- Android reliability does not depend on a permanently alive WebSocket.
- Incoming events are written to a local durable queue first and wake a JobScheduler sync path; optional always-on foreground mode provides lower latency.
- Server command creation can invoke a metadata-only push/tickle adapter hook for FCM/OEM integrations, but command content is always fetched over authenticated HTTPS.
- The PWA uses authenticated Server-Sent Events as a wake-up signal and retains a 60-second polling fallback.

## Server model

The relay uses Python's standard library and SQLite WAL. It provides:

- single-user admin authentication using a high-entropy bearer token;
- Admin Token + optional RFC 6238 TOTP login, exchanged for short-lived HttpOnly browser sessions;
- one-time enrollment tokens;
- hashed device bearer tokens;
- encrypted event storage;
- durable encrypted command queue;
- device/subscription state;
- audit records without SMS plaintext;
- metadata-only notification webhook;
- OTA metadata endpoint;
- PWA static hosting.

TLS is expected to terminate at Caddy, Nginx or Traefik in front of the relay.

## Repository layout

```text
android/                 Android SIM Node
server/                  Personal relay API + SQLite schema
web/                     Browser/PWA controller
docs/                    Architecture, setup, protocol, security
scripts/                 Operational helpers
docker-compose.yml       Default personal-server deployment
Caddyfile.example        HTTPS reverse-proxy example
```

## Explicit non-goals

SIM Hub v0.1.5 does not implement phone calls, dialer replacement, call history, call audio capture, SIP/WebRTC, PSTN media bridging, carrier provisioning, RCS replacement, or a full carrier-specific MMS transport stack.
