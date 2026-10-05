# Upgrade to SIM Hub v0.1.5

v0.1.5 is designed as a rolling upgrade from v0.1.0.

## Recommended order

1. Back up the relay database with `./scripts/backup.sh`.
2. Upgrade the relay/PWA and restart the Docker service.
3. Verify `/healthz` reports version `0.1.5`.
4. Open the PWA, create a new browser session and unlock the existing Vault.
5. Upgrade Android nodes one at a time.
6. Confirm each node reports app version `0.1.5` and can receive/send a test SMS.

## Compatibility

- Existing v1 ciphertext remains decryptable.
- The v0.1.5 PWA sends legacy v1 commands to agents older than v0.1.5 and v2 commands to upgraded agents.
- The relay automatically migrates the v0.1.0 SQLite schema, including the event uniqueness constraint.
- Existing Android local queues are preserved. Local database schema migration is automatic.

## Re-enrollment

Do not re-enroll an existing node as part of a normal upgrade. v0.1.5 intentionally blocks silent re-enrollment. Use the Android **Reset enrollment / pair another vault** action only when intentionally changing the relay/Vault. Reset clears SIM Hub local relay queues/credentials but does not delete SMS stored by Android.

## Signing

If v0.1.0 was installed from an ephemeral debug-signed CI APK and v0.1.5 is signed with a different key, Android cannot install it over the old package. In that case export/retain your Vault recovery key, uninstall the old Agent, install v0.1.5, and re-enroll. Configure stable release-signing secrets before future long-term deployments to avoid this.