# Immediate control, reset acknowledgements and Shizuku laboratory (v0.16.2)

## Immediate action contract

User-tapped node refresh makes a direct authenticated command poll and a device-state upload. User-tapped command/SMS synchronization directly reads up to 100 recent device messages, fetches commands, flushes the encrypted event queue and uploads state. A running, user-enabled Android foreground relay long-polls the server (up to 15 seconds per request). Expensive reconciliation and heartbeats still follow the user energy policy. JobScheduler is only a best-effort offline/background fallback: Android Doze and OEM restrictions can delay delivery, so a powered-off, force-stopped or disconnected node cannot be instantly reached. The Relay has an optional PUSH_TICKLE_URL hook, but this version does not bundle an OEM/FCM push client.

## Server-initiated unpair

An administrator requests reset; the Relay writes reset_requested_at, revokes normal node operations, rejects non-reset endpoints with device_reset_pending, and broadcasts its stream epoch. Even a command request already waiting in long poll rechecks the lifecycle and returns HTTP 409. The Android node recognizes only that specific code, marks reset pending, clears normal exponential backoff, submits its authenticated /reset acknowledgement and clears local pairing after a successful 200 or valid 410 deletion receipt. Device lifecycle checks and tombstones remain accessible for recovery. The server may revoke access while a node is offline, but should not claim the device has cleared its local keys until it contacts the Relay again.

## Shizuku laboratory

Local user action is required. Start Shizuku, grant SIM Hub API access, then use diagnostic presets or developer-mode custom Shell commands. The Shizuku UserService runs as Shell UID 2000 when started over wireless ADB, or as the more privileged identity provided by its backend. The Binder returns stdout, stderr, exitCode and duration; a command is limited to 2,048 characters, approximately 16 KiB per output stream and 15 seconds execution. Standard output is displayed locally and not uploaded or stored in CompatibilityAudit; that log records only metadata. Relay commands have no path to this executor. Shizuku is not an Android 17 OTP-exemption mechanism.

## Companion Device Manager

The manifest declares android.software.companion_device_setup as optional, checks the ROM feature at runtime, requests a self-managed association only after Relay enrollment, and requires system confirmation. OEM security exceptions remain possible. A successful association is not equivalent to generic SMS/OTP privileges.

## Acceptance

Run Android API 37 unit/build jobs, Relay regression tests, CodeQL and signed release verification. Separately validate manual refresh, remotely initiated reset (online/offline), on-device Shizuku permission/execution and vivo/OEM system confirmation on real hardware. Do not treat CI alone as hardware acceptance.
