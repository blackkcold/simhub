SIM Hub Dev prerelease trigger — 2026-11-08
Release base: 0.19.0; source: develop after PR #117.
Scope: controller auth revocation atomicity; shared-pool and key-rotation races; SMS delivery PDU and duplicate-safe drafts; RelayLite query recovery; Android background refresh; notification outbox.
Require exact-commit CI, CodeQL, P2P preview, Android API 29/30 and cloud acceptance checks; signed OCI/APK prerelease.
Real SIM/operator and carrier-NAT/TURN hardware acceptance remains a separate gate. No experimental P2P promotion to Stable.
