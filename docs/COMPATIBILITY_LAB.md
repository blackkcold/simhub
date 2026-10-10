# Android system compatibility lab / 安卓系统兼容实验室 (v0.12.0)

SIM Hub retains **non-default SMS companion** mode and its existing end-to-end-encrypted Relay. The Android app's **Settings → System compatibility lab / 系统兼容实验室** is a dedicated, optional screen, separated from normal pairing, default-SMS selection and everyday settings.

## Permission boundaries

- `READ_SMS` permits querying system SMS rows *subject to platform rules*; `RECEIVE_SMS` permits eligible broadcast delivery; `SEND_SMS` permits SMS sending. Android 17 may delay protected third-party OTPs. A successful SMS Provider query cannot prove that protected OTPs are visible.
- `READ_OTP_SMS` is an internal AppOp, **not** an ordinary install-time/runtime app permission and not a standard public grant API. The screen displays a **read-only** AppOp status (or `not_queryable`) via platform APIs. It does not alter the AppOp.
- Shizuku (optional) is integrated with the upstream `api` + `provider` SDK, version 13.1.5. Binder presence, its permission to SIM Hub and the service UID are reported **separately**. Granting Shizuku permission does not grant SIM Hub `READ_SMS`, grant `READ_OTP_SMS` or create a platform OTP exemption.
- CompanionDeviceManager (CDM) is supported **only for enrolled nodes on Android API 33+**. Association is self-managed, opt-in, and Android displays its own confirmation. The display name is prefixed `SIM Hub · `. The user may remove *only the matching self-managed associations* from the lab. A logical association to a Relay does not automatically qualify as a connected physical companion device or grant OTP access.
- Google SMS Retriever, SMS User Consent and WebOTP are intended for verification messages meeting each API's own app/website requirements. They **cannot be repurposed for any unrelated third-party OTP**. OEM push SDKs (vivo/OPPO/Huawei) deliver app-originated push, not arbitrary carrier SMS.

**The application never automatically disables or uninstalls OEM Messages, changes OTP/AppOps permissions, runs privileged shell commands, alters compatibility flags or scrapes OTP notifications.** Those approaches can break normal telephony, create security regressions or violate platform safeguards.

## Guided on-device diagnostic sequence / 手机端验证流程

1. In the existing Permissions card, grant normal Android SMS and SIM permissions. Keep the original Messages app as the default where possible.
2. Open **系统兼容实验室** from Android settings. Check device `SDK` versus SIM Hub `targetSdk` (they are distinct); check default SMS role and `READ/RECEIVE/SEND`.
3. Tap **测试数据库权限**. This performs a `_id`-only query to the SMS Provider. It does **not read or display message content**, sender or OTP. Distinguish `accessible_has_rows`, `accessible_empty`, `permission_missing`, `security_exception` and `query_failed`.
4. Tap **手动补扫近期短信** to run the existing bounded 100-row rolling reconciliation. This uses the normal E2EE upload pipeline; it cannot reveal OTPs withheld by Android.
5. Optionally install the official Shizuku app, start it via wireless debugging or ADB, then tap **单独申请授权**. The app shows `shizukuInstalled`, `shizukuRunning`, `shizukuAuthorized` and (if authorized) service UID. The permission request is explicit; Shizuku's non-root server often needs restarting after the phone reboots.
6. Optionally request a **CDM self-managed association** after pairing your own trusted Relay. Android confirmation is mandatory; the association can be removed without deleting SIM Hub server enrollment.
7. For vivo/OPPO/Huawei background limits, use **打开后台与电池优化设置**. Neither this step nor CDM/Shizuku implies real-time OTP.
8. Test an ordinary SMS and a protected test OTP separately, on each SIM, both unlocked and locked, after reboot and after background idle. Compare: native Messages receipt time, SIM Hub Provider probe/sync time and Relay event time. This is how to distinguish OS withholding from job scheduling/network issues.

## Read-only ADB / Shizuku Rish examples

The lab's **复制只读 ADB / Rish 检查命令** copies safe diagnostics for the current APK UID:

```bash
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.manufacturer
adb shell cmd role get-role-holders android.app.role.SMS
adb shell cmd appops get --uid <actual-app-UID> READ_OTP_SMS
adb shell dumpsys package com.blackkcold.simhub
```

Replace `<actual-app-UID>` with the UID shown on the screen or exported summary. If using official Rish, remove the `adb shell` prefix and use `rish -c '...'`. These diagnostics may be unavailable on particular OEM builds. Avoid pasting raw command output containing other installed app details into a public issue without sanitizing it.

The app intentionally does not offer an automated `pm disable-user`, `cmd appops set` or `am compat disable` shortcut. Use the ROM's normal settings to manage your default messaging app; disabling telephony provider or OEM SMS packages can cause message loss.

## Redacted diagnostics / 脱敏诊断

- Compatibility actions are appended to a private rolling audit, with a maximum of 60 entries. Each entry contains only the timestamp and fixed action/outcome categories. No phone number, OTP, SMS text, device token, Shizuku binder value or pairing secret is recorded.
- Exporting a diagnostics ZIP adds `compatibility-audit.json` and `compatibility-status.json` to existing redacted diagnostic artifacts.
- The audit remains available with Developer Mode off. Developer Mode still controls verbose app logger capture and redacted log rotation.
- The separate lab includes **clear audit**. Clearing history never revokes an already-granted permission or removes server enrollment; use the system's permission manager or Shizuku manager to revoke authorization.

## Implementation notes

- `CompatibilityManager`: read-only OS, permission, Shizuku and CDM status; metadata-only Provider query.
- `CompatibilityPolicy`: pure SDK/target and audit-input policy with unit tests.
- `CompatibilityAudit`: local bounded action/outcome history.
- `HubCompatibilityScreen`: one responsive destination; not mixed with regular settings.
- `HubActivity`: explicit Shizuku permission callback, Binder lifecycle callbacks, system CDM confirmation and safe manual actions.
- Official dependencies: `dev.rikka.shizuku:api:13.1.5`, `dev.rikka.shizuku:provider:13.1.5`.

## Limitations

A controlled emulator/JVM/CI build cannot establish that a specific OriginOS/ColorOS device honors Android 17 SMS exemption conditions. **The feature is a diagnostic and opt-in integration**, not a guarantee that Shizuku or CDM makes all OTPs real-time. Keep the v0.11.2 non-default receiver/Provider sync behavior as the reliable baseline.
