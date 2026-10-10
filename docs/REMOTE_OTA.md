# Android 16+ remote APK updates

SIM Hub remote OTA is **explicitly opt-in** on the device and is available for Android 16 (API 36) and newer. Older Android versions show no normal remote-update UI or controller action. A separate Developer options override exposes the test pathway on older versions, but does **not** bypass signature, package-install permission, or system approval.

## Enable

1. Install a stable signed SIM Hub APK, pair the device and connect the Relay.
2. On Android 16+, go to Settings → Remote app updates, enable remote updates. Configure Wi-Fi-only if desired (default: on). Battery below 25% and not charging blocks OTA.
3. Ensure Android has granted the app permission to request installs from this source. This permission does not grant silent-install entitlement.
4. In Web → Devices → Device detail → Remote app updates, click Update to latest stable. The controller requires an elevated administrator session.
5. The Agent verifies latest stable GitHub Release metadata, SHA-256, original signing certificate, package name and monotonically increasing version code.
6. PackageInstaller performs installation. Android may require a tap on the device to confirm. The controller must never equate a queued command or submitted installation session with a successful upgrade.
7. On `ACTION_MY_PACKAGE_REPLACED`, the updated app verifies the installed version, restores the existing JobScheduler and opted-in foreground relay, and queues an acknowledgement.

## Platform limits

- Android 16+ is a **feature visibility and control gate**, not a promise of silent installation.
- Android 17 also restricts background Activity launches; the app does not force-open its UI. It attempts to recover the already authorized relay service using system broadcasts and scheduled jobs.
- The developer override is test-only; it requires developer mode. Disabling developer mode removes the override and local consent on older devices; re-enabling the override requires renewed local consent.
- Android background restrictions, install-source approval, Doze and OEM policy can delay or block unattended updates.
- This feature does not install arbitrary packages, bypass the installer, use Shizuku/Root or change the relay's rootless Docker updater.
- Updates use the existing GitHub stable-release pipeline and are self-updates only. Version rollback is not supported by default.
- Physical Android 16/17 device tests remain necessary to confirm no-user-action eligibility per ROM/installation ownership.

## Troubleshooting

The Device detail panel reports `remoteOtaStage` (`checking`, `downloading`, `verified`, `installing`, `awaiting_confirmation`, `succeeded`, `failed`), Android SDK and local authorization. Errors are sanitized; SMS content and encryption keys never appear in OTA state. Only the device can authorize remote OTA.
