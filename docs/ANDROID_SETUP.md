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
2. In Settings, create an Android enrollment link. It contains only a short-lived enrollment token and Bootstrap Secret; the independent long-term Node Key is delivered encrypted after successful enrollment, never as plaintext in the link.
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

## v0.4.0–v0.5.0: UI and remote management

- **v0.4.0 Web controller:** administrator username, Passkey registration/login/step-up, responsive SMS conversation list with direct reply, and **+ New message**. Passkey authenticates the administrator, not the browser-local Vault.
- **v0.5.0 session:** in a valid active tab, browser refresh resumes the locally encrypted Vault after server session verification. The Vault inactivity deadline follows the administrator's default **8-hour idle** period; the session cannot exceed **24 hours** without signing in again. Explicit Lock/logout still requires unlocking.
- **SIM numbers:** Android can report phone numbers only when allowed and supported by the OS/provider; some numbers are blank. They are carried in a Node-Key-encrypted inventory and shown in the Web device/message UI. Enter missing numbers using **Devices → Set number** in the Web controller; this encrypted override remains local to that browser.
- **History:** the Agent supports **Sync latest 100 SMS** (independent newest rescan) and **Load 100 older SMS** (older-backfill cursor); the Web Inbox's **Load older messages** only fetches already-uploaded relay records. Success means the scan was queued; upload progress must be verified separately. Historical channel identities may be unknown on old SMS after a SIM swap.
- **Diagnostics:** **Devices → Diagnostics** sends a `diagnostics.request` command and reads the encrypted health result in a Web dialog. The full Android Developer Mode log still stays on-device unless the user explicitly exports a redacted diagnostic ZIP.

## Wi-Fi loss and mobile data SIM limitations

Android normally routes data to its **system-selected default mobile-data SIM** when Wi-Fi is lost and mobile data is already enabled. A normal third-party APK is not privileged to enable another line or force a different default data SIM.

1. In Android **system mobile network settings**, choose the intended default data SIM and enable mobile data for it.
2. Open **SIM Hub → Open mobile data / default SIM settings** as a shortcut if needed.
3. In the Web **Devices → Cellular failover SIM** action, choose the intended channel. SIM Hub checks that this matches the system's current default subscription and reports a configuration-needed state otherwise.
4. Verify the phone can access the network after switching Wi-Fi off. The OS, not SIM Hub, performs the actual data routing. The feature is a **monitoring/policy validation aid**, not a privileged automatic SIM switch.

See [Compatibility](COMPATIBILITY.md) and [v0.4–v0.5 migration](UPGRADE_0.4_TO_0.5.md).


## Device pairing

**Scan from controller:** Sign in and unlock the Web Vault, select **Add device → Android**, generate the enrollment package, then in the Android Agent tap **Scan controller QR code**. Confirm the HTTPS Relay hostname and finish registration.

**Android-initiated:** On an unpaired Android Agent, enter the **HTTPS device-origin URL** (not the admin hostname if separated), tap **Generate pairing code**, and note the eight-digit code and device fingerprint. In the unlocked Web controller, open **Add device → Pair with Android code**, enter the code, verify the **fingerprint displayed on both devices** and approve with administrator step-up. Android completes the pairing automatically while the request remains valid (five minutes).

Both methods require the usual SMS role and SIM permissions after enrollment. If the device was previously paired, unpair it first. A QR code is a one-time credential and should not be shared or saved publicly; a short code alone never grants access.
