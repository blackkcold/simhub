# SIM Hub installation

This guide installs the personal relay server, opens the PWA controller, and enrolls an Android phone as the SIM Node.

## 1. Requirements

### Server

- Linux server or VM reachable by the Android phone and controller
- Docker Engine + Docker Compose plugin
- DNS name pointing to the server
- TCP 443 available for HTTPS
- Caddy, Nginx or Traefik for TLS termination

### Android

- Android 10 / API 29 or newer
- Physical SIM/eSIM telephony capability
- Ability to set SIM Hub as the default SMS app

## 2. Install the relay

```bash
git clone https://github.com/blackkcold/simhub.git
cd simhub
cp .env.example .env
python3 scripts/gen_admin_token.py
```

Copy the generated token into `SIMHUB_ADMIN_TOKEN` in `.env` and set:

```env
SIMHUB_PUBLIC_BASE_URL=https://simhub.example.com
```

Optional: configure `SIMHUB_TOTP_SECRET` for a second factor.

Start the relay:

```bash
docker compose up -d --build
curl http://127.0.0.1:8787/healthz
```

The health endpoint should return successfully before you continue.

## 3. Enable HTTPS

Use the provided `Caddyfile.example` or your existing reverse proxy. The public endpoint must use HTTPS because SMS/OTP management is high-value authentication infrastructure and the Android application disables cleartext traffic.

With Caddy, adapt the hostname and proxy it to:

```text
127.0.0.1:8787
```

Then verify:

```text
https://simhub.example.com/
```

loads the PWA.

## 4. Prepare the controller

1. Open the PWA in a modern browser.
2. Enter the server admin token.
3. If TOTP is enabled, enter the current six-digit TOTP once to create the browser session.
4. Create, import, or unlock the local Vault.
5. Keep the Vault recovery material secure. The server does not possess the Vault Key and cannot recover encrypted SMS for you.

## 5. Install the Android Agent

For v0.1.5 the GitHub Release contains either `simhub-agent-v0.1.5-release.apk` when stable signing secrets are configured, or an explicitly labelled `simhub-agent-v0.1.5-debug.apk` fallback.

Install it manually on the SIM Node. If Android blocks sideloading, allow installation from the app/browser/file manager you use for the APK.

For a self-built APK:

```bash
cd android
gradle :app:assembleDebug
```

See `ANDROID_SETUP.md` for Android build requirements.

## 6. Enroll the Android SIM Node

1. In the PWA, create a new enrollment link.
2. Open the generated `simhub://enroll?...` link on the Android phone, or paste it into the Agent.
3. Tap **Enroll this SIM Node**.
4. Tap **Make default SMS app** and approve Android's system role dialog.
5. Grant SMS/SIM permissions.
6. Optionally grant Contacts access if you want local contact-name mapping.
7. Enable **always-on relay** for the lowest-latency personal remote operation.

The enrollment deep link includes temporary sensitive enrollment material. Do not store or publish it; generate a new one when needed.

## 7. OEM background settings

On vivo/OPPO/Xiaomi/HONOR/Huawei and other aggressive battery-management ROMs, allow autostart and remove battery restrictions for SIM Hub if those controls exist. Keep the persistent foreground-service notification enabled when using always-on relay mode.

## 8. Test the installation

Run these checks in order:

1. PWA shows the Android device online.
2. SIM/subscription information is visible.
3. Send a normal SMS to the SIM and confirm it appears in the PWA after local decryption.
4. Send an OTP-style SMS and confirm OTP detection/copy works.
5. Send a test SMS remotely from the PWA through a selected subscription.
6. Turn off the Android phone's network, receive/send test data, restore network and verify queued synchronization recovers.
7. Reboot the phone and verify the Agent recovers after boot/unlock according to the configured background mode.

## 9. Backups

Run `./scripts/backup.sh` for a consistent SQLite online backup, or back up the Docker `simhub-data` volume for relay metadata/ciphertext. Separately protect the controller's Vault recovery material. The relay database by itself is intentionally insufficient to decrypt SMS content.

## 10. Updating

Server:

```bash
git pull
docker compose up -d --build
```

Android updates should be installed from a trusted GitHub Release or your own signed build. The OTA endpoint only advertises an update; it does not silently install APKs.


## Rolling upgrade from v0.1.0

Upgrade the server/PWA first, then Android nodes. The v0.1.5 controller automatically sends legacy v1 commands to older agents and v2 commands to v0.1.5+ agents. Existing v1 events remain decryptable. Do not reset/re-enroll a node unless you intentionally want to pair it with another Vault; v0.1.5 requires an explicit reset before re-enrollment.
