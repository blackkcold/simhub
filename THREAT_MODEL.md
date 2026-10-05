# Threat model

| Threat | Design control |
|---|---|
| Relay database stolen | SMS/contact/OTP/outbound body remains AES-GCM ciphertext |
| Admin token leaked | Token rotation; optional TOTP; relay still lacks Vault Key |
| Device token leaked | Per-device revocation and short operational scope |
| Command replay | Command ID + expiry + idempotency + persistent processed-command set |
| Event retry duplicates | Unique event ID on device and relay |
| Android device offline | Durable local queue + foreground relay + JobScheduler recovery |
| Server offline | Commands remain durable; events retry after connectivity returns |
| Logs leak secrets | Redacted structured logging; only metadata in audit rows |
| SIM change | Subscription snapshot changes are surfaced to controller |
| Malicious remote SMS | Only encrypted `sms.send` payloads that decrypt under the shared Vault Key execute |

The public source repository contains no deployment secrets.
