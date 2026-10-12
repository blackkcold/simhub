# Hardware Validation Gate

Software CI cannot prove radio/OS behavior that requires physical SIMs, OEM power managers or DJI hardware. Before declaring a deployment unattended-production-ready, run this matrix.

## Android

- Android 13, 14, 15, 16 and 17 where available.
- At least one vivo/OriginOS device plus one non-vivo OEM.
- Default-SMS role retained after reboot.
- Incoming SMS while screen-off / Doze / battery saver.
- Incoming OTP after 1h, 6h and 24h idle.
- Wi-Fi only, cellular data only, VPN, temporary offline then recovery.
- App process killed; verify SMS Provider reconciliation restores missed relay events.
- Reboot with always-on relay enabled.
- Dual-SIM receive on both slots.
- Remote send from each Channel.
- Physically replace one SIM and verify an already-queued old-revision command fails rather than sending.
- Multipart SMS with duplicate/bounced SENT callbacks where test tooling allows.
- Delivery report supported/unsupported carrier behavior.
- Token rotation with network interruption between prepare and commit.
- Legacy v0.1.5 -> 0.2 Node Key isolation migration with no pending outbound SMS.

## Linux / USB modem

- ModemManager-supported modem: receive, send, restart ModemManager, unplug/replug USB.
- Fill/recycle SMS storage and verify durable local queue precedes deletion.
- SIM replacement increments Channel revision and rejects stale commands.
- Radio metrics report sane values.
- Relay outage and recovery without duplicate inbound/outbound events.

## DJI Gen1 / QDC507 path

- Confirm the connected unit exposes the expected `Quectel USB AT Port` (known DJI/Quectel USB identity or explicit `SIMHUB_AT_PORT`).
- Receive plain GSM and UCS2 Chinese SMS.
- Send short and concatenated long SMS, including Chinese/UCS2, through the direct PDU path.
- USB unplug/replug and host reboot.
- Signal/operator/cell telemetry.
- SIM replacement revision safety.

## DJI Cellular Dongle 2

Do not assume Gen1 compatibility. First establish whether the host exposes a usable ModemManager/QMI/MBIM/AT/SMS interface. If not, keep the device unsupported rather than weakening the generic relay protocol.

## Pass criteria

- No SMS is sent twice during retry/restart tests.
- No stale Channel command is sent through a replacement SIM.
- Every SMS present in Android Provider/modem storage eventually appears in the relay after connectivity returns.
- Relay never logs SMS body, OTP, destination number, Node Key or Master Vault Key.
- A compromised/test Node Key cannot decrypt another node's events.

## Security and reliability acceptance (current)

- **Vault lock:** after viewing SMS, SIM phone numbers, diagnostic results, an enrollment package and a draft, explicitly lock the Vault and inspect the page's DOM and form values. The old plaintext, pairing bootstrap, recovery field, pending dialogs and tab-resume snapshot must be absent. Unlock again to confirm fresh encrypted loading.
- **Two tabs:** unlock the same Vault in two active tabs; locking one should lock the other, not just hide the panel. A page refresh after locking must never restore the previous decrypted screen.
- **Pairing:** copy the single-use enrollment link into **SIM Hub Agent itself**. Do not open credential-bearing custom-scheme links through a general app chooser. Android must show the selected HTTPS relay hostname and require affirmative confirmation. Token and bootstrap are one-use; clear clipboard/history copies after use where practical.
- **Session boundaries:** refresh while active and signed in; check 8-hour inactivity and 24-hour absolute expiry separately. Passkey authentication must not bypass local Vault decryption on a new browser.
- **Network retry:** bring down the relay or simulate 429/503. The Android node should expose the next retry time and increase delay with jitter, rather than polling every 20 seconds while offline. Restore service and ensure events eventually upload without duplicate sends.
- **History:** scan the same latest 100 twice. The second scan should report zero newly queued items after successful delivery; older-backfill progress must survive a reboot. Provider reconciliation must not send outgoing SMS again.
- **Key rotation:** on a test node with old encrypted events, complete two independent Node-Key rotations. The Relay must retain only Vault-wrapped retired keys and the Controller must still decrypt previous messages. A stale Channel revision must fail closed.
- **Device operations:** verify the Devices panel shows queued, dispatched, executed, failed, rejected, expired and delivered statuses without exposing recipients, bodies or OTP in any Relay API response.
- **Recovery:** create a consistent Relay database backup, record the recovery-key custody, then restore into an isolated environment and confirm encrypted messages remain readable with the owner-provided Vault recovery key.
- **Supply chain:** require CodeQL coverage for Python, JavaScript/TypeScript and Android Java plus signed APK provenance before release.

Physical SIM, cellular modem, screen-off/Doze and vendor ROM behavior **cannot be certified by CI alone**; run the device matrix above before treating an installation as production-grade.

## v0.15.8 controller acceptance: record before unattended deployment

These are **manual gates**, not claims that CI or this patch has passed real-device testing. Capture device model, Android build, relay version, sanitized diagnostics, exact event and command IDs, and pass/fail for each case.

| Case | Acceptance |
| --- | --- |
| v0.15.6 pairing on v0.15.8 Relay | Same-IP pairing succeeds before legacy sunset; changed-IP polling fails; v0.15.8 token polling survives network handover; legacy path rejects after sunset |
| 300 / 1,000 / 2,000 consecutive events | Paginated batches eventually display all valid decryptable SMS exactly once, with no cursor skip after injected errors |
| Payload limits | Oversized encoded JSON splits into smaller native batches; rejected pages preserve the last acknowledged cursor |
| >1,200 historical messages | Repeated older paging continues beyond 1,200 and returns to latest with correct source metadata |
| Two devices sharing a contact number | Source-scoped threads are not mixed; replacing one SIM does not reassign older Channel revisions |
| Eco delayed sync command (2–15 min) | Queued/dispatched/expired/final ACK stays available through the remote task center and app restart |
| Network change, VPN, process kill | Controller identity survives, unauthorized content is never shown after local lock |
| Screenshot and Recents snapshot | Android management center contents are hidden; switching to node-only screen restores ordinary screenshot behavior |
| Signed APK OTA | Normal APK verification and install/update passes on vivo and a second OEM |
| SMS composer retry | Closing page preserves unsent draft and idempotency ID; uncertain retry does not submit a duplicate SMS |

### Deferred engineering and security gates

- Auth-bound hardware Keystore rewrap must have a tested recovery/rollback path and be explicitly verified via CryptoObject; the existing biometric application gate does not meet that condition.
- Large-scale Relay concurrency, SQLite write-contention, stream wakeup latency and restore drills require reproducible load benchmarks before selecting a Rust or service modularization strategy.
- Test failure blocks a production-readiness claim even if signed CI Release assets are successfully published.
