# SIM Hub v0.2.2 — Reliability hardening and deployment gates

## What changed

1. Single-use enrollment token is claimed using `BEGIN IMMEDIATE` and a conditional `UPDATE`; concurrent consumers cannot each create an active node.
2. Android prioritizes live SMS events, runs bounded history reconciliation, and never automatically resends SMS commands with uncertain side effects after process interruption.
3. Direct AT and ModemManager nodes fail closed if actual SIM identity is unavailable. Modem outgoing commands re-read fingerprint before transmission.
4. Modem multipart receiver protects against same-index reference collisions; distinct completed bodies receive distinct event identifiers. Genuine radio-level reordering and reference collisions still require field testing.
5. The PWA boots with the newest 500 events and offers explicit older-event pagination.
6. The Controller surfaces SMS role, SIM and sync health; the Relay can send metadata-only Bark/ntfy alerts.
7. Linux/DJI OTP recognition occurs before AES-GCM event encryption.
8. Android MMS WAP PUSH metadata and raw PDU are preserved in encrypted private app storage and an explicit warning is issued. This **does not** implement carrier-specific MMS media download.

## Remaining non-automatable hardware gates

- 72-hour Android/OriginOS default SMS role and Doze test; record a phone sending OTP during background standby and after reboot.
- Hot-swap equivalent-carrier SIM: old channel revision must be rejected; verify no unintended carrier SMS is sent.
- Real ModemManager mmcli JSON for every chosen USB modem; ICCID/IMSI extraction and SIM removal.
- Physical Quectel/DJI AT port serial responses, empty or read-failed SIM identity and multipart reference collisions.
- Modem transmission interrupted after the carrier accepts but before the API returns, including power loss and USB hot-unplug.
- MMS WAP PUSH notification persistence and attachment retrieval limitations with an actual carrier.
- Push delivery from your configured Bark / private ntfy service: provider acknowledgement does not mean device notification delivery.

## Operational policy

Do not mark a node as SMS-ready just because its heartbeat is online. Remote sending fails closed on missing Android SMS role or on unknown modem SIM identity. If a modem send outcome is unknown, inspect the modem/carrier state before manually retrying. Keep all private API endpoints behind HTTPS. For multiple modems use separate instance-specific config/SQLite DB and bind the exact serial port, not a mutable ttyUSB index.

Do not install SIM Hub as the default SMS app on a primary phone needing carrier MMS until complete MMS receive/media support has been developed and physically validated.

## Release test gates

- Python relay API/retention/migration tests
- Modem cryptography, queue, OTP and SIM identity tests
- WebCrypto and PWA syntax tests
- Android API 37 JVM unit tests and Android build
- Release signing with configured secrets and pinned SHA-256 certificate
