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

1. Open the PWA on your HTTPS relay and unlock/create the local Vault.
2. In Settings, create an enrollment link.
3. Open the `simhub://enroll?...` link on the Android SIM Node, or paste it into the Agent.
4. Tap **Enroll this SIM Node**.
5. Tap **Make default SMS app** and approve Android's system role dialog.
6. Grant SMS/SIM permissions.
7. Optionally grant Contacts permission for contact-name mapping.
8. Start **always-on relay** if you want low-latency remote sending without relying on Google/vendor push.

## Background behavior

The user-started always-on mode uses a visible foreground-service notification. A persisted 15-minute JobScheduler task remains as a recovery path, and SMS events schedule an immediate best-effort job.

On OEM ROMs (vivo/OPPO/Xiaomi/HONOR/Huawei), also allow autostart and remove aggressive battery restrictions for the Agent if the ROM offers those settings. Do not disable the persistent notification; it is the user-visible contract for the private always-on relay.

## Android 17 / OTP

The Agent is designed to hold the SMS role instead of depending on a generic `SMS_RECEIVED` background listener. Incoming SMS is persisted into the Android SMS Provider by the default handler and then encrypted for relay upload.

## MMS limitation

The manifest includes the WAP push receiver required by the default-SMS role and the Agent can surface MMS push/history metadata. It does not implement a full carrier/APN-specific MMS PDU download or send stack. If MMS is important on a specific carrier, validate it separately before making SIM Hub the permanent default handler for that line.
