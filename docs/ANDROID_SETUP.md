# Android node setup

## Build

The background SIM/SMS/crypto Agent remains Java; the v0.8.0 Android interface uses Kotlin Jetpack Compose, AndroidX Material 3 and Jetpack WindowManager. The APK targets API 37.

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
5. Keep the OEM/system Messages app as default for **companion mode** (recommended on vivo/OriginOS); or optionally request **Set as default SMS app / 设为默认短信应用** for full handler mode.
6. Grant `READ_SMS`, `RECEIVE_SMS`, `SEND_SMS` and SIM access. Companion mode requires the ROM/installer to allow these restricted permissions; a permission denial is not fixed by network settings.
7. Optionally grant Contacts permission for contact-name mapping.
8. Start **always-on relay** for the built-in lowest-latency mode. The server also exposes an optional metadata-only push/tickle adapter hook for FCM/OEM integrations; JobScheduler and SMS Provider reconciliation remain recovery paths.

## Background behavior

The user-started always-on mode uses a visible foreground-service notification. A persisted 15-minute JobScheduler task remains as a recovery path, and SMS events schedule an immediate best-effort job.

On OEM ROMs (vivo/OPPO/Xiaomi/HONOR/Huawei), also allow autostart and remove aggressive battery restrictions for the Agent if the ROM offers those settings. Do not disable the persistent notification; it is the user-visible contract for the private always-on relay.

## Android 17 / OTP

The Agent supports two runtime-selected modes:
- **Companion (non-default):** keeps vivo/OEM Messages as the default handler, receives `SMS_RECEIVED` as a wake signal, reads SMS rows from the system SMS Provider, encrypts and uploads. It never inserts or updates provider SMS rows. For sending, `SmsManager` submits through the selected SIM and Android is responsible for recording non-default-app sent SMS. Foreground relay, provider history scans and JobScheduler provide recovery paths.
- **Full/default handler:** retains `SMS_DELIVER` and writes incoming rows to SMS Provider. SMS Provider updates for outgoing status only occur while SIM Hub holds the role.

**Android 17 / API 37:** for most non-exempt apps, protected OTP SMS may not be delivered via `SMS_RECEIVED` or Provider queries until **three hours after arrival**. WebOTP format protection applies regardless of target SDK. A six-hour bounded rolling rescan eventually revisits newly visible rows; it cannot accelerate protected OTP delivery. OEM restricted-permission allowlisting may also prevent companion mode entirely. Never claim that receiving an ordinary text guarantees immediate OTP delivery.

**Important:** Do not disable OEM Messages, spoof an assistant/companion role, or use Accessibility/notification scraping to evade Android OTP protections. Test plain SMS, OTP, dual SIM, locked screen, reboot and battery optimization on the actual handset before relying on unattended sync.

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


## Native Android UI (v0.8.0)

- The launch screen uses four accessible tabs: **Overview**, **Messages**, **SIM cards** and **Settings**, with a compact bottom bar or expanded side rail.
- **Overview** summarizes Relay health, pending uploads, SIM service and the most recent local messages. An actionable permissions card appears when SMS capability is unavailable.
- **Messages** reads Android's SMS Provider locally, supports recent conversation paging, sender/body search, OTP copy, and an inline composer with an explicit physical subscription selection. Submission is not proof of carrier delivery; MMS/RCS are not advertised as implemented.
- **SIM cards** lists reported subscriptions and radio diagnostics. Network switching shortcuts open system settings instead of requesting unsupported privileged telephony actions.
- **Settings** is the single Compose-adaptive settings surface: QR/short-code enrollment, required/optional permissions, foreground relay, manual recent/older SMS sync, device reset, OTA checking, app language, developer-mode toggles, redacted logs, diagnostic ZIP export and encrypted pool-sharing consent. The old Android MainActivity and duplicate XML settings layouts have been removed.
- Expanded/folded windows use the current app window size and WindowManager hinge state. UI navigation and drafts are in ViewModel memory; ephemeral ECDH keys survive Activity recreation only within the current process, never in saved-state bundles.
- Compose and AndroidX are now required at build and runtime; built-in AGP9 Kotlin + Compose compiler plugin 2.4.20, Compose BOM 2026.09.00, and WindowManager 1.5.1 are pinned.
- Chinese/English string resources, system dark appearance and developer log redaction remain supported.

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


## Optional SMS sharing (v0.8)

Open **Settings → Shared SMS**, enable explicit opt-in, then in the logged-in Web controller open **Devices → Shared pool** and approve that device while the local Vault is unlocked. The relay stores only ciphertext and individually wrapped Pool Keys. This is independent of SMS Provider storage and remote sending permissions. Pool sharing is disabled by default; the current implementation uses the owner's single default pool. Read [Shared SMS security and sync](SHARED_SMS.md) before approving a device.

## v0.9.1 signed in-app updates

Open **Settings → Advanced tools & diagnostics → Check for updates** to
compare Android `versionCode` with the relay's GitHub Release manifest.
A signed update is downloaded into private cache, limited to 80 MiB, checked
against the released SHA-256 and the pinned **SIM Hub signing certificate**,
then submitted to Android PackageInstaller. The system may require you to
authorize installs from this app and confirm each update; OEM policy can
prevent silent installation. Android 12+ can waive confirmation only if
PackageInstaller's documented prerequisites are met.

- **Automatically check updates** is enabled by default and checked about
  once per day during persisted sync jobs; network availability is required.
- **Automatically download & install** is disabled by default. When enabled,
  the app installs eligible releases and handles OS user-action requests by
  notification, without clearing enrollment or app data.
- **Ignore this version** persists the versionCode locally. A newer release
  becomes eligible again. Manual checks can restore the ignored release.
- APK update requires the official `com.blackkcold.simhub` package and its
  original release signing key. Debug builds and unrelated APKs cannot replace
  the signed production package.
- Update metadata is served by the connected Relay. Upgrade Relay first.

