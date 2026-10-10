# Android energy and background reliability

## Modes

| Mode | Foreground command-check transport | Periodic inventory | SMS ingestion |
|---|---|---|---|
| Eco | 14-minute checks (no long poll) | 15-minute JobScheduler | Broadcast/Provider change + delayed follow-up |
| Balanced (default) | ~45s when interactive, ~120s when idle (no long poll) | 15-minute JobScheduler | Broadcast/Provider change + delayed follow-up |
| Realtime | 15-second server long poll (only when foreground relay is enabled) | 15-minute JobScheduler | Broadcast/Provider change + delayed follow-up |

*The command transport requires the user's separate **foreground relay** toggle. With the toggle off, a persisted 15-minute JobScheduler task is the fallback. Android Doze, background restrictions and device ROMs can delay jobs. These intervals are requested cadences, not OS guarantees.*

Battery Saver automatically uses Eco policy unless the user explicitly selects Realtime. No wake locks, exact alarms, OEM-privileged exemptions or silent SMS role takeover are used.

## Data correctness guarantees

- Incoming SMS events are encrypted and committed to the local SQLite queue before network upload; Provider rows already present in the durable queue/upload receipts are skipped before parsing and re-encrypting.
- In non-default companion mode, SMS_RECEIVED may arrive before the stock SMS app writes Provider. Schedule one immediate scan and a persisted follow-up after 8 seconds; the rolling 6-hour Provider reconciliation remains, gated to 30 minutes when idle and continued in bounded pages when a scan reaches the cap.
- Coordinator coalesces periodic, delayed and immediate jobs without cancelling in-flight uploads. A busy global sync lease causes follow-up scheduling rather than dropping work.
- ACK, idempotency, Channel ID/Revision, Node Key/Vault Key and reset/revocation boundaries remain unchanged.
- Shared SMS pool retries have a separate due check; transient network failures respect existing exponential backoff and Retry-After.

## Remote command delivery

The server stores commands until their **encrypted command's own expiry**. Controller TTL is adapted to the last reported energy mode, bounded to 20 minutes when Eco or foreground relay disabled; this is not a promise that a heavily restricted Android device will be available. SMS send requires explicit confirmation and Eco/non-foreground users are warned about delayed delivery. If the node is not reachable before expiry, the command must expire rather than silently send later.

A server-side optional metadata-only push/tickle adapter is available through `SIMHUB_PUSH_TICKLE_URL`. Android device push-token acquisition requires an externally configured provider; **this release does not ship a configured FCM transport**. Do not put SMS plaintext or keys in any push envelope.

Online/offline classification uses the last *reported actual foreground service liveness*/energy mode. `lastSeenAt` remains the ground truth; presence is not proof of guaranteed command delivery.

## Measurements and diagnostic safety

Android Settings > Runtime shows `commandPolls`, `commandPollErrors`, `maintenanceRuns`, `reconciliationRuns` (metadata only). Counters persist across process restarts and contain no body, phone number or OTP.

Recommended hardware regression matrix:

1. Default and non-default SMS app; SIM 1 and SIM 2; Android 13–17 when available.
2. Screen off for 1h, 6h and 24h; Doze and Battery Saver; Wi-Fi, cellular, VPN, server outage and recovery.
3. Bursts of 100–1,000 SMS, provider ID replay, delayed Provider writes and OTP protected-message availability.
4. Remote SMS command expiration and deduplication, reboot/reset/force-stop conditions, manual sync and shared-pool revocation.
5. Baseline vs optimized `adb shell dumpsys batterystats --charged com.blackkcold.simhub`, Perfetto wakeups, HTTP requests, bytes, CPU time, APK memory and job execution.

Do not claim percentage battery savings without controlled physical-device A/B testing. App power cost and whole-device drain are not interchangeable.

## v0.13.1 reliability repair

- **Provider change generation:** The old second-granularity timestamp could miss same-second incoming SMS. Notifications increment a monotonic stored generation; a scan only acknowledges the generation captured before its query. Later events remain pending. An event-driven latest-100 rescan also handles late Provider writes before the history cursor.
- **Network independence:** Local SMS Provider ingestion/encryption happens before remote command operations, event uploads and retry-backoff gates. Provider follow-up checks remain separately scheduled for companion-mode broadcasts.
- **Balanced cadence:** Foreground command checks are approximately 45 seconds while Android reports the screen interactive; otherwise they are approximately 120 seconds. A disabled or dead foreground relay requests durable background checks. These delays are not Doze guarantees.
- **Truthful status:** `foregroundRelay` now means actual in-process service liveness; `foregroundRelayRequested` means the user selected it. `lastCommandFetchAt`, `lastCommandFetchCount` and `lastCommandFetchError` are metadata-only and appear in Android settings.
- **Manual recovery:** Android Settings provides a single manual operation to inspect recent SMS, check encrypted remote commands and trigger upload. Relay expires commands on controller status reads even when a device does not poll.

No SMS plaintext, sender, OTP, private key, access token or command ciphertext appears in the added diagnostic fields.
