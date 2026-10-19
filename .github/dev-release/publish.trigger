SIM Hub Dev prerelease trigger — 2026-10-19
Release source: develop after merged PR #107 (merge 0c65a43d9ca6b1bdfe03e09dbbb2a7470eaee700).
Scope: event-driven battery/Wi-Fi/SIM telemetry and native/Web gauges; manual state refresh; one-shot E2EE location with local and remote consent; revocable relay ciphertext and opt-in OSM map; device privacy tests and documentation.
Verified on PR head 251b92de072b8b45905ec301e4e3a5a99d69ee8e:
CI=success (Android, server-web); P2P preview and emulator sandbox=success; Security/CodeQL six jobs=success; Remote OTA prerelease checks=success.
Dev-only experimental release. Stable main remains unchanged. Physical Android/OEM/Doze and external-network validation is required before any Stable promotion.
