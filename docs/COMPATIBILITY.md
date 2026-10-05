# Compatibility matrix

| Area | Current implementation |
|---|---|
| Minimum Android | 10 / API 29 |
| Target / compile | Android 17 / API 37 |
| Default SMS role | Required for intended operation |
| Dual SIM | Routed with Android `subscriptionId` |
| eSIM | Active subscriptions surfaced via `SubscriptionInfo.isEmbedded()` |
| SMS receive | `SMS_DELIVER` default-handler path |
| SMS send | `SmsManager.createForSubscriptionId()` |
| History | Android SMS Provider incremental sync |
| Contacts | Optional `READ_CONTACTS`, encrypted before relay |
| Low-latency command relay | User-started foreground service |
| Recovery sync | JobScheduler, 15-minute periodic + best-effort immediate job |
| Android 17 OTP | Design assumes real default SMS handler, not generic OTP listener |
| MMS | Metadata/push observation only; full carrier transport not implemented |
| Phone calls | Explicitly unsupported and absent from permissions/API |

Before relying on a device unattended, test: reboot, Doze, OEM battery saver, dual-SIM receive/send, SIM removal/reinsert, loss of data/Wi-Fi, 24h relay outage, role revocation and Android OTA.
