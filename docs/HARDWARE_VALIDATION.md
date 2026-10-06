# Hardware Validation Gate

Software CI cannot prove radio/OS behavior that requires physical SIMs, OEM power managers or DJI hardware. Before declaring a deployment unattended-production-ready, run this matrix.

## Android

- Android 13, 14, 15, 16 and 17 where available.
- At least one vivo/OriginOS device plus one non-vivo OEM.
- Default-SMS role retained after reboot.
- Incoming SMS while screen-off / Doze / battery saver.
- Incoming OTP after 1h, 6h and 24h idle.
- Wi-Fi only, cellular data only, VPN, temporary offline then recovery.
- App process killed; verify SMS Provider reconciliation restores missed relay events.
- Reboot with always-on relay enabled.
- Dual-SIM receive on both slots.
- Remote send from each Channel.
- Physically replace one SIM and verify an already-queued old-revision command fails rather than sending.
- Multipart SMS with duplicate/bounced SENT callbacks where test tooling allows.
- Delivery report supported/unsupported carrier behavior.
- Token rotation with network interruption between prepare and commit.
- Legacy v0.1.5 -> 0.2 Node Key isolation migration with no pending outbound SMS.

## Linux / USB modem

- ModemManager-supported modem: receive, send, restart ModemManager, unplug/replug USB.
- Fill/recycle SMS storage and verify durable local queue precedes deletion.
- SIM replacement increments Channel revision and rejects stale commands.
- Radio metrics report sane values.
- Relay outage and recovery without duplicate inbound/outbound events.

## DJI Gen1 / QDC507 path

- Confirm the connected unit is accessible through the installed external `dji4g` CLI.
- Receive plain GSM and UCS2 Chinese SMS.
- Send plain GSM and Chinese/UCS2 SMS supported by the CLI/modem.
- USB unplug/replug and host reboot.
- Signal/operator/cell telemetry.
- SIM replacement revision safety.

## DJI Cellular Dongle 2

Do not assume Gen1 compatibility. First establish whether the host exposes a usable ModemManager/QMI/MBIM/AT/SMS interface. If not, keep the device unsupported rather than weakening the generic relay protocol.

## Pass criteria

- No SMS is sent twice during retry/restart tests.
- No stale Channel command is sent through a replacement SIM.
- Every SMS present in Android Provider/modem storage eventually appears in the relay after connectivity returns.
- Relay never logs SMS body, OTP, destination number, Node Key or Master Vault Key.
- A compromised/test Node Key cannot decrypt another node's events.
