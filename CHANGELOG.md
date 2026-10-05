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