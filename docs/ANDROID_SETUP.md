# Android node setup

## Build

The project is Java-only at runtime and has no AndroidX runtime dependency. It targets API 37.

Requirements:

- JDK 17
- Android SDK Platform 37
- Android Gradle Plugin 9.4.1
- Gradle 9.6+

```bash
cd android
gradle :app:assembleDebug
```

Install the resulting APK with Android Studio or `adb install`.

## First-time setup order

1. Open the PWA on your HTTPS relay and unlock/create the local Master Vault.
2. In Settings, create an Android enrollment link. It contains only this node's independent Node Key, never the Master Vault Key.
3. Open the `simhub://enroll?...` link on the Android SIM Node, or paste it into the Agent.
4. Tap **Enroll this SIM Node / 注册此 SIM 节点**.
5. Tap **Set as default SMS app / 设为默认短信应用** and approve Android's system role dialog.
6. Grant SMS/SIM permissions.
7. Optionally grant Contacts permission for contact-name mapping.
8. Start **always-on relay** for the built-in lowest-latency mode. The server also exposes an optional metadata-only push/tickle adapter hook for FCM/OEM integrations; JobScheduler and SMS Provider reconciliation remain recovery paths.

## Background behavior

The user-started always-on mode uses a visible foreground-service notification. A persisted 15-minute JobScheduler task remains as a recovery path, and SMS events schedule an immediate best-effort job.

On OEM ROMs (vivo/OPPO/Xiaomi/HONOR/Huawei), also allow autostart and remove aggressive battery restrictions for the Agent if the ROM offers those settings. Do not disable the persistent notification; it is the user-visible contract for the private always-on relay.

## Android 17 / OTP

The Agent is designed to hold the SMS role instead of depending on a generic `SMS_RECEIVED` background listener. Incoming SMS is persisted into the Android SMS Provider by the default handler and then encrypted for relay upload.

## MMS limitation

The manifest includes the WAP push receiver required by the default-SMS role and the Agent can surface MMS push/history metadata. It does not implement a full carrier/APN-specific MMS PDU download or send stack. If MMS is important on a specific carrier, validate it separately before making SIM Hub the permanent default handler for that line.


## Re-enrollment safety

0.2.x blocks silent re-enrollment. To pair an existing Android node with another Vault/relay, use **Reset enrollment / pair another vault** first. This clears SIM Hub relay queues and local credentials but does not delete SMS stored in Android's SMS Provider.


## Remote SIM routing safety

The controller addresses a stable `channelId` plus `channelRevision`. Android maps that identity to the current `subscriptionId` immediately before send. If a physical SIM/eSIM identity change increments the revision, a stale remote command is rejected rather than sent through the replacement line.

## Token and key rotation

- Device bearer tokens rotate automatically every 60 days using a crash-safe prepare/commit flow.
- New installations hold only an independent Node Key.
- Upgraded legacy 0.1.5 nodes can receive `node.rotate_key` after pending outbound SMS completes; the legacy Master Vault Key is then deleted from the node.


## v0.3.0 UI, language and diagnostics

- The Android dashboard uses a single-column phone layout and a two-column `sw600dp` layout for unfolded foldables and tablets.
- Light/dark appearance follows the Android system theme.
- The app supports **Follow system**, **简体中文**, and **English** from the Settings section.
- **Developer Mode** is off by default. When enabled it records redacted diagnostic events and exposes View/Clear/Export controls.
- See [Developer diagnostics](DEVELOPER_DIAGNOSTICS.md) for the logging/redaction contract and support workflow.
