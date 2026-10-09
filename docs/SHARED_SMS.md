# Shared SMS pool (v0.8)

## Security and consent

Sharing is **off by default**. Each Android device must choose **Settings → Shared SMS → Enable** before the administrator can approve the request in **Web → Devices → Shared pool**. The relay authenticates the device separately for every read, write and status request. Group labels are presentation-only and cannot confer authorization.

The administrator's unlocked local Vault generates a 256-bit Pool Key. The relay receives a Vault-wrapped Pool Key and an independently Node-Key-wrapped Pool Key for each approved Android device. The Android node unwraps only its own envelope with its independent Node Key, stores the Pool Key using Android Keystore-backed encryption and uses it exclusively for sharing. Relay stores SMS ciphertext only, never SMS text or plaintext Pool/Node/Master keys.

**Sharing changes the confidentiality boundary:** any approved pool member can read shared SMS ciphertext that it has the corresponding Pool Key for. Do not approve untrusted phones. Revoking membership prevents further API reads and uploads and requires rotating the Pool Key. Revocation cannot undo SMS already downloaded or saved by another member. Remote reset and security-relevant removal must revoke access and rotate the key. An offline revoked Android will erase cached data as soon as it receives the server's revocation response; the server blocks access immediately.

Pool message authenticated-data string is:
`simhub-pool-sms-v1|default|<originDeviceId>|<originEventId>|<occurredAt>|<channelId>|<epoch>`.

The Relay durably records rotation-required state on opt-out, device revoke, reset or deletion, immediately blocks revoked members, and suspends new pool uploads until the administrator unlocks the local Vault and completes a new key epoch. This safe pending state survives Relay restart; a banner in Web → Devices → Shared pool offers retry. Old ciphertext is not re-encrypted or exposed to the Relay. The admin creates a different independently wrapped Pool Key after a revocation. The Web controller handles **key epochs** and may authorize historical epochs for new devices. Source-device encrypted originals are still individually protected by Node Keys.

## Synchronization behavior

- First grant: scan up to the most recent **100** local SMS and cache the **100** newest encrypted SMS from the pool. All local SMS stay in the system SMS Provider; other devices' SMS are **never inserted** into the provider.
- Android queue events locally under its Keystore-protected local queue key; use batches of **20** encrypted events per upstream call.
- Subsequent pool reads use the relay's monotonic sequence cursor (`since`), while historical pagination uses timestamp + sequence keyset pagination (`beforeTime` and `beforeSeq`). This distinction prevents losing older messages arriving late.
- UI history requests fetch **50** ciphertext events at a time. Cache is bounded to **2,000** encrypted records; historical Provider scan is independently limited to **100** per request.
- Upload/replay deduplication identity: `originDeviceId + originEventId`. Source-device encrypted originals remain distinct.
- Low-latency command and ACK processing runs **before** optional pool traffic. Pool transport failures must not stop ordinary Relay functions. Failed transfers use persistent bounded exponential backoff and pending staged uploads schedule follow-up sync jobs.
- Local opt-out is immediate, even if the network is down; remote opt-out is journaled for retry. Server may still temporarily hold ciphertext created before revocation until the remote request completes.
- Server deletion of device SMS deletes its shared ciphertext as well, and enforces a replay barrier, without changing on-phone SMS.

## SIM identity and metadata

Stable identity uses `deviceId + channelId + channelRevision`, not Android's transient `subscriptionId`. Users may set an arbitrary label and a masked number hint (last four digits). Android stores that metadata under an Android Keystore-protected secret; changes are also Node-Key-encrypted into `sim.profile` events and can be decrypted in the Web controller.

Every SMS displays its source, direction, date/time, and where reliable, a SIM label and masked last four digits. If a historical message cannot be associated with a confirmed channel, the UI must display **historical SIM / unverified**, never infer a current phone number. This does not change the Android subscription's identity or the system default SMS routing.

Shared SMS on another Android is **read-only**. Remote sending through another device's SIM is not implied by permission to read its shared SMS and would require separately authenticated authorization.

## Limitations

The pool key distribution protocol currently supports the single-owner default pool only. Multiple organizations, invitation workflows and per-message ACLs are deliberately excluded. Availability depends on Android permissions, background scheduling and the owner's Relay. SMS E2EE prevents server-side full-text search; clients search locally decrypted loaded records. MMS media, calls and RCS are out of scope.

Do not mistake the Relay's queued count for on-device delivery. No feature is considered production-ready until repository CI, signature checks and physical Android device validation complete.
