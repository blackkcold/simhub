# Threat model

| Threat | Design control |
|---|---|
| Relay database stolen | SMS/contact/OTP/outbound body remains AES-GCM ciphertext |
| Admin token leaked | Optional TOTP is required when configured; PWA exchanges credentials for a short-lived HttpOnly session and does not persist the root token; relay still lacks Vault Key |
| Device token leaked | Per-device revocation and short operational scope |
| Command replay / concurrent fetch | Command ID + expiry + idempotency + per-device v2 AAD + atomic SQLite claim + persistent command state + durable ACK queue |
| Event retry duplicates | Unique event ID on device and relay |
| Android device offline | Durable local queue + foreground relay + JobScheduler recovery |
| Server offline | Commands remain durable; events/ACKs retry after connectivity returns |
| Relay metadata tampering | v2 AES-GCM AAD binds immutable event/command routing fields; modification causes decryption failure |
| Logs leak secrets | Redacted structured logging; only metadata in audit rows |
| SIM change | Subscription snapshot changes are surfaced to controller |
| Malicious remote SMS | Only encrypted `sms.send` payloads that authenticate under the target device-derived key and bound command metadata execute |

The public source repository contains no deployment secrets.
