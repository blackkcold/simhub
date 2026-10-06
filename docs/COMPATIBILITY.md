# Compatibility matrix

| Area | Current implementation |
|---|---|
| Minimum Android | 10 / API 29 |
| Target / compile | Android 17 / API 37 |
| Default SMS role | Required for intended operation |
| Dual SIM | Stable Channel ID/revision maps to current Android `subscriptionId`; stale mappings fail closed |
| eSIM | Active subscriptions surfaced via `SubscriptionInfo.isEmbedded()` |
| SMS receive | `SMS_DELIVER` default-handler path |
| SMS send | Android: `SmsManager.createForSubscriptionId()` after Channel validation; Linux: ModemManager or DJI adapter |
| Linux / DJI modem | Generic Node + Channel model; ModemManager and DJI Gen1/QDC507 adapter paths |
| History | Android SMS Provider incremental sync |
| Contacts | Optional `READ_CONTACTS`, encrypted before relay |
| Low-latency command relay | User-started foreground service; optional external FCM/OEM push-tickle adapter can wake HTTPS command fetch |
| Recovery sync | JobScheduler, 15-minute periodic + best-effort immediate job |
| Android 17 OTP | Design assumes real default SMS handler, not generic OTP listener |
| MMS | Metadata/push observation only; full carrier transport not implemented |
| Phone calls | Explicitly unsupported and absent from permissions/API |

Before relying on a device unattended, test: reboot, Doze, OEM battery saver, dual-SIM receive/send, SIM removal/reinsert, loss of data/Wi-Fi, 24h relay outage, role revocation and Android OTA.


## Rolling upgrade

The 0.2 controller/relay retain legacy v1/v2 ciphertext compatibility for rolling upgrades. Upgrade server/PWA first, then Android nodes individually. New nodes use independent Node Keys; legacy v0.1.5 nodes can migrate online through `node.rotate_key`. Remote SMS on 0.2 Android requires stable Channel identity/revision.
