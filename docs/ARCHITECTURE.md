# SIM Hub architecture

SIM Hub is a **personal, self-hosted multi-node SIM/SMS management system**. Android phones and supported Linux cellular modems are SIM Nodes; the user's own server is a relay and durable queue; the Web/PWA is the controller. The design intentionally excludes phone calls, dialer control, call logs, cellular-call audio, SIP and PSTN bridging.

## Components

```text
┌──────────────────────────┐
│ Android / Modem Node(s)  │
│                          │
│ SMS role / companion     │
│ SMS Provider             │
│ OTP parser               │
│ Generic Channel state    │
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

The relay is intentionally blind to SMS content. The controller owns the 256-bit Master Vault Key. For every new node it creates a random 256-bit Node Key, wraps that Node Key with the Master Vault Key, and sends only the Node Key to the target node through the one-time enrollment package. The relay stores the wrapped Node Key envelope, ciphertext, routing metadata and operational state, but never receives either key in plaintext.

Android stores only its independent Node Key using Android Keystore-backed storage; the Linux Modem Agent stores only its own Node Key in a mode-0600 local config. New nodes never receive the Master Vault Key. Legacy v0.1.5 nodes may still use a Master-derived key until the `node.rotate_key` migration command succeeds. SMS body, OTP value, contact name, destination number and outbound SMS body are encrypted before the relay receives them.

## Data flow

### Incoming SMS

```text
Carrier / SIM
  → Android SMS default handler writes to SMS Provider
  → SIM Hub SMS_DELIVER (default) or SMS_RECEIVED wake + Provider scan (companion)
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
  → selects stable channelId + channelRevision
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

The app targets API 37 with minSdk 29. It supports both `ROLE_SMS` (default handler) and a non-default companion receiver backed by `SMS_RECEIVED` plus `READ_SMS` Provider polling. The latter preserves the OEM default SMS app but is subject to Android 17 OTP access delays and ROM permission restrictions. Android `subscriptionId` is treated as a local adapter identifier only; remote routing uses a stable Channel ID plus revision and rejects stale commands after SIM replacement.

The app deliberately requests no dialer role and contains no call-control or call-log path.

## Generic Node and Channel model

The relay protocol does not expose Android-specific routing as the primary business identity.

```text
Node
├── nodeType: android | modem | gateway
├── capabilities[]
└── Channel[]
    ├── id
    ├── localId
    ├── kind
    ├── revision
    ├── carrier/state
    └── signal metrics
```

For Android, `localId` maps to the current `subscriptionId`. For Linux/DJI modems it maps to the adapter's local SIM/modem identifier. Remote SMS commands bind both `channelId` and `channelRevision`; if the underlying SIM fingerprint changes, the revision increments and stale commands fail closed.

## Key hierarchy

```text
Master Vault Key (controller only)
├── wraps Node Key A -> Android A
├── wraps Node Key B -> Android B
└── wraps Node Key C -> Linux/DJI modem
```

The relay stores only wrapped Node Key envelopes. A compromised node therefore exposes that node's content key, not the Master Vault Key or sibling Node Keys. Legacy derived-key nodes can rotate online after pending SMS work drains.

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
modem_agent/             Linux/DJI/USB modem SIM Node
server/                  Personal relay API + SQLite schema
web/                     Browser/PWA controller
docs/                    Architecture, setup, protocol, security
scripts/                 Operational helpers
docker-compose.yml       Default personal-server deployment
Caddyfile.example        HTTPS reverse-proxy example
```

## Explicit non-goals

SIM Hub 0.2.x does not implement phone calls, dialer replacement, call history, call audio capture, SIP/WebRTC, PSTN media bridging, carrier provisioning, RCS replacement, or a full carrier-specific MMS transport stack.
