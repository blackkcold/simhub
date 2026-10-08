# Android developer diagnostics

SIM Hub v0.3.0 adds an opt-in Developer Mode for diagnosing Android node failures without requiring routine `adb logcat` access.

## Enable Developer Mode

Open the Android Agent, scroll to **Settings**, and enable **Developer mode / 开发者模式**.

When enabled, the app writes structured diagnostic events for:

- Relay foreground-service lifecycle;
- enrollment success/failure;
- Relay API sync cycles;
- encrypted event/ack queue counts;
- SMS receive, send and history-sync state;
- JobScheduler lifecycle;
- remote command type/result;
- OTA and device-state refresh failures.

Developer Mode is disabled by default.

## Privacy and redaction

Diagnostic persistence is designed to exclude message content and secrets. Before a line is written to the app-private rotating log, SIM Hub redacts:

- SMS message bodies;
- OTP / verification-code values;
- Admin, Device and bearer tokens;
- Bootstrap secrets;
- Vault, Node and Recovery keys;
- passphrases;
- the middle digits of phone numbers.

Do not treat the diagnostic ZIP as public data: it can still contain device model, Android version, app version, operational state, timestamps and non-secret failure metadata.

## View, clear and export

While Developer Mode is enabled:

- **View recent logs** shows the latest bounded log tail in the app;
- **Clear logs** removes retained diagnostic log files;
- **Export diagnostic package** creates a ZIP and lets Android's document picker choose where to save it.

Logs live in the app-private files directory and rotate automatically. The current implementation caps each log file at roughly 512 KiB and retains up to five files.

## Support workflow

For a reproducible bug:

1. enable Developer Mode;
2. reproduce the issue once;
3. open **View recent logs** to confirm an error was recorded;
4. export the diagnostic ZIP;
5. disable Developer Mode when no longer needed.

The diagnostic subsystem does not change SIM Hub encryption, message transport or remote-command semantics.
