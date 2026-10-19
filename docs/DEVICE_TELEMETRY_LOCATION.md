# Device telemetry, one-shot location and privacy

The Android node uses the **existing HTTPS command and sync transport**. No new permanent telemetry socket, GPS tracking service or periodic location timer is introduced.

## Device status

- Full inventory includes device version, SIM channel identity and Revision, battery, charging, default network, Wi-Fi signal level, SIM signal levels and Unix sampling time.
- Battery/Network/Telephony change callbacks run only while the existing user-enabled foreground Relay service is alive. Events are coalesced before device sampling, then piggyback on authenticated heartbeat updates.
- Meaningful state changes: battery delta of at least 2 percentage points, charging-state change, network or Wi-Fi signal change, and SIM signal change.
- Policy limits for **light metadata uploads**: Eco ≥15 minutes, Balanced ≥5 minutes, Realtime ≥1 minute. Callback sampling is also throttled to Eco ≥60 seconds, Balanced ≥20 seconds and Realtime ≥5 seconds. Android scheduling, Doze and connectivity can introduce additional delays.
- A manual device status action directly performs one authenticated upload and does **not** scan the SMS Provider. Remote refresh ACK is successful only after Relay accepts the sample, with `sampledAt` and `requestId` visible in bounded command results.
- Unknown battery and signal are never displayed as healthy zeros. The management UI labels outdated samples and offline snapshots; no fabricated trend chart or continuous animation is used.

## Explicit one-shot location

1. On the **Android SIM Node**, open **Sharing & privacy → Location & privacy**. Local location sharing is **off by default**; enable it and grant Android foreground location permission.
2. Tap **Locate once** to request an Android location sample. Network location is preferred if enabled, with a bounded GPS fallback. A previously obtained location up to five minutes old and within 2 km reported accuracy may be reused; the actual sample time is preserved.
3. Node encrypts coordinates, accuracy and sample time in the existing **Node Key AES-GCM E2EE event envelope** (kind `device.location`). The Relay only stores the latest ciphertext; latitude and longitude are **never added to Relay-visible device health state**.
4. Remote one-shot positioning is a **separate, default-off Android switch**. Even when enabled, the command runs only if the Android node's activity is foreground and location permission is granted. It never grants permissions or starts background tracking.
5. Web and native Android controllers must explicitly load the encrypted position. They decrypt locally after controller/Vault authorization. Web OSM map embeds are **not loaded by default**; selecting the map, or opening the system browser on Android, discloses the coordinate to OpenStreetMap.
6. Either controller can purge the Relay-held location ciphertext; native/web admin purge is step-up protected. Turning off sharing on the node clears queued ciphertext and requests authenticated server deletion. Offline deletion requests are retried during existing node sync cycles.

Each on-device reauthorization generates a fresh random `locationEpoch`. Location uploads require the current registered epoch. The Relay invalidates it on purge, rejecting delayed events from old epochs even if their timestamps are newer than the deletion. All API operations use existing device/admin authentication boundaries.

**OpenStreetMap**: Web uses the official on-demand map embed with visible © OpenStreetMap contributors attribution, restricted CSP and no map background prefetch. The OSM service is external and is only invoked after a user click; it receives the requested coordinate. OSM infrastructure is best-effort and has its own privacy and usage policies.

## Security and test gates

- Relay validation: numeric battery/Wi-Fi metrics, strict SIM Channel ID + Revision correlation, no SSID/BSSID, location coordinates or SMS data in plain health state.
- Regressions: unauthenticated access denied, malformed consent revision rejected, E2EE ciphertext-only reads, most-recent-only storage, both single/batch uploads, purge and replay suppression, default-off remote location, map opt-in, native coordinate validation.
- Location is **not a substitute for emergency tracking**. No guaranteed update latency, background positioning, or near-real-time wake under Doze. Validate on physical Android devices and with OEM battery optimization and permission-revocation scenarios before promoting to Stable.

The Dev channel contains experimental features; Stable promotion requires separate acceptance and review, and must respect the [Stable/Dev boundary](RELEASE_CHANNELS.md).
