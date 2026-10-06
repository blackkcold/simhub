# 0.2 Hardening Audit Remediation

This file records the repository audit findings closed by the 0.2 hardening branch.

| Finding | Resolution |
| --- | --- |
| SyncJobService creates an executor per job | Shared bounded executors; stopped jobs cancel their Future |
| Live SMS queue failure can be silent | Central failure accounting + JobScheduler wake + SMS Provider reconciliation each sync |
| Multipart callbacks can double-count | Per-command/per-part persistent status rows and idempotent updates |
| Delivery callback result ignored | SENT and DELIVERED result codes are both evaluated; delivery failure is explicit |
| Pending delivery can live forever | 48-hour status timeout fails tracking without retrying the SMS |
| Android subscriptionId treated as stable identity | Stable Channel UUID + revision; remote send fails closed after SIM identity change |
| Master Vault Key present on every Android node | New nodes receive independent Node Keys; legacy Android supports online key isolation |
| Local pending SMS encrypted with traffic/master key | Dedicated local Keystore-backed queue key |
| Device bearer tokens effectively permanent | Crash-safe prepare/store/commit rotation every 60 days on Android and modem agents |
| Relay is Android-specific | Generic Node + Channel + capability model added |
| No DJI/USB modem runtime | Linux Modem Agent with ModemManager and external dji4g adapter paths |
| Signal state too coarse | Android LTE/NR dBm/RSRP/RSRQ/SINR plus modem radio metrics |
| Docker build context includes unnecessary files | Root .dockerignore excludes env, git, Android/build artifacts, keys |
| Reverse proxy collapses audit/rate-limit IP | Trusted-proxy CIDR model; XFF accepted only from trusted peers |
| OTA auth bypasses common admin rate-limit path | Admin OTA access now uses require_admin; device auth remains explicit |
| Retention only at startup | Continuous maintenance loop for events/audit/commands/sessions/enrollment tokens |
| No audit retention | Configurable audit retention, default 180 days |
| Health check only proves HTTP process | /readyz checks DB and schema; Docker healthcheck uses readiness |
| TOTP disabled by example default | New .env example requires TOTP and includes generator script |
| CI equates APK build with correctness | Server v4 migration/maintenance tests, modem crypto/store tests, Android JVM tests |
| Historical temporary Android threads | Receiver/service work moved to shared executors; owned service executors shut down |
| Modem SMS store can fill | Received SMS is durably queued/seen before best-effort removal from modem storage |
| Signing | Explicitly excluded from this hardening request |

## Remaining hardware validation

Items requiring physical devices are tracked in `docs/HARDWARE_VALIDATION.md`. They are not represented as automated-pass claims.
