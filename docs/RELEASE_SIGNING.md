# Android release signing

SIM Hub never stores a signing private key in the public repository.

To make GitHub Releases produce a stable release-signed APK, add these repository Actions secrets:

- `ANDROID_KEYSTORE_BASE64` — base64 of the `.jks`/keystore file.
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The release workflow decodes the keystore only into the ephemeral GitHub Actions runner and sets `ANDROID_KEYSTORE_FILE` for Gradle. The keystore is not uploaded as an artifact.

If any required signing secret is absent, the workflow deliberately falls back to a clearly named `-debug.apk` rather than pretending the artifact is production-signed.

Keep the release keystore and passwords in an offline backup. Losing the signing key prevents Android from accepting future in-place updates to installations signed with that key.