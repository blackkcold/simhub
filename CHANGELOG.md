## v0.2.2 — SMS reliability and operational health

- Make enrollment token consumption atomic under SQLite write locking; include concurrency regression test.
- Prioritize live inbound SMS over historical backfill, cap automatic history imports, and load the newest events in PWA first with older-event pagination.
- Reject remote Android SMS when default SMS role is unavailable; report role, permission, SIM and last-success/error metrics.
- Require actual SIM ICCID/IMSI in direct AT and ModemManager adapters; reject unknown identity and recheck immediately before remote modem sending.
- Prevent silent multipart part overwrite and use content-distinct multipart event IDs.
- Treat interrupted/uncertain Android and Linux modem SMS submissions as submitted/unknown, not safe for automatic resend.
- Recognize OTP locally on Linux/DJI modem nodes without leaking codes outside E2EE.
- Synchronize critical Android Keystore-wrapped credential writes and SIM identity revisions.
- Preserve MMS WAP PUSH PDU encrypted in private device storage and alert users; full carrier MMSC media download remains unsupported.
- Add native generic metadata-only Bark and ntfy push adapters, plus a multi-modem systemd template for independently enrolled nodes.
- Retain v0.2.1 release-signing identity and mandatory pinned-certificate verification.

## v0.2.1 — Production hardening

- Require release-signed Android artifacts with pinned certificate verification; remove debug-release fallback workflows.
- Pin GitHub Actions dependencies to immutable commit SHAs and separate signing from release publication permissions.
- Strengthen Android SIM replacement detection with card, port, subscription, carrier and ICCID/number signals; stale commands continue to fail closed by Channel revision.
- Replace new-node plaintext Node-Key enrollment with a one-time Bootstrap Secret and encrypted Node-Key envelope; verify a SHA-256 bootstrap proof before consuming the one-time token.
- Enforce PWA Vault timeout using absolute inactivity time across browser suspension/backgrounding.
- Correct Android SMS history direction handling for FAILED/QUEUED/OUTBOX/SENT and skip drafts.
- Add encrypted durable multipart receive staging and reassembly for DJI/Quectel modem SMS.
- Make relay TOTP and 30-day event retention secure defaults.
- Replace per-event webhook/push threads with a bounded outbound worker pool.
- Add shared bootstrap crypto vectors across WebCrypto, Android/JVM and Python Modem tests.
- Reject USSD/service dialing symbols in Modem SMS destinations so they cannot be silently normalized into a different number.
- Schema version 6 adds one-time enrollment bootstrap ciphertext plus a non-secret SHA-256 bootstrap proof digest.

# Changelog

## v0.1.5 — Reliability & Security

### Reliability
- Scope event identity by Android device to prevent cross-device SMS Provider ID collisions.
- Atomically claim commands before side effects and serialize Android sync cycles.
- Persist command acknowledgements locally and track SMS states as submitted, sent, delivered or failed.
- Recover stale pre-side-effect command claims after process interruption.
- Use a lossless `(SMS date, provider ID)` history watermark that advances only after durable local queueing.
- Restore user-enabled relay mode after reboot and debounce JobScheduler wakeups.
- Merge outbound history/status events in the PWA by provider identity.

### Security
- Add per-device HKDF-SHA256 keys derived from the recovery/master Vault Key.
- Add v2 AES-256-GCM envelopes with `kid` and metadata-bound AAD.
- Preserve v1 event/command compatibility for rolling upgrades.
- Replace persistent browser Admin Token storage with short-lived Secure/HttpOnly/SameSite sessions.
- Add recovery-key import to the PWA.
- Use strict relay-readable metadata/state allowlists.
- Add basic authentication failure rate limiting and a strict Content-Security-Policy.

### Operations
- Add SSE realtime wakeups with 60-second polling fallback.
- Add metadata-only push/tickle adapter hook for FCM/OEM bridges.
- Prevent history sync from generating new-message webhook floods; add OTP/device/freshness filters.
- Add automatic SQLite schema migration, subscription projection, metrics, retention/purge controls and online backup helper.
- Support stable Android release signing from GitHub Secrets without committing signing keys.

### Scope
- Phone-call functionality remains intentionally excluded.
- Full carrier-specific MMS send/download remains out of scope.