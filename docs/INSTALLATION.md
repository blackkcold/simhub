# SIM Hub installation

This guide installs the personal relay server, opens the PWA controller, and enrolls Android and/or Linux/DJI modem SIM Nodes.

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

## 2. Install the relay (recommended v0.4.0+ workflow)

For Linux, configure DNS A records for **two distinct names** (for example `admin.example.com` and `node.example.com`) to point at the public server IPv4. Any AAAA records must resolve to a reachable IPv6 host. Allow inbound TCP 80 and 443. Never publicly expose port 8787.

```bash
git clone https://github.com/blackkcold/simhub.git
cd simhub
python3 scripts/setup.py --admin-domain admin.example.com --node-domain node.example.com
```

The guided installer checks Docker/Compose, DNS and port availability; generates a strong Admin Token, TOTP secret, owner-only `.env`, Caddyfile and starts HTTPS/Relay. DNS entries are **not** created automatically at your registrar or DNS provider. Preserve `.env` securely.

Already use your own Nginx, Caddy or Traefik? Run `python3 scripts/setup.py --mode external --admin-domain admin.example.com --node-domain node.example.com`. External mode does not configure the proxy: terminate HTTPS on both hosts, proxy safely to `127.0.0.1:8787`, overwrite untrusted forwarded-IP headers, and hide `/healthz` and `/readyz` from public access.

To prepare files before DNS propagates, use `--skip-dns-check --no-start`, then `--upgrade` with the same hostnames and mode after DNS becomes valid.

## 3. Verify HTTPS and internal health

Open **`https://admin.example.com`** for the PWA; the separate `https://node.example.com` endpoint is for Android/Modem APIs, not the management UI.

```bash
docker compose exec -T simhub python3 -c "import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:8787/readyz').read().decode())"
```

If using managed Caddy, add `-f docker-compose.yml -f compose.caddy.yml` to Compose commands where needed. Public requests to either `/healthz` or `/readyz` must be denied.

## 4. Prepare the controller

1. Open the PWA in a modern browser.
2. Enter the server admin token.
3. If TOTP is enabled, enter the current six-digit TOTP once to create the browser session.
4. Create, import, or unlock the local Vault.
5. Keep the Master Vault recovery material secure. The server does not possess it and cannot recover encrypted SMS for you. New nodes receive independent Node Keys, not the Master Vault Key.

## 5. Install the Android Agent

Install the Android APK from the release/build you intend to use. Signing policy is intentionally outside this hardening change; keep using a consistent trusted APK source for in-place updates.

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
8. Optionally choose **简体中文 / English / Follow system** from the Android Settings section.
9. Keep **Developer Mode** disabled for normal use; enable it only when diagnostic logs are needed.

The v0.2.1 enrollment deep link contains a one-time token and one-time Bootstrap Secret, not the long-term Node Key. Treat it as sensitive until consumed; after successful enrollment the relay erases the bootstrap envelope and the token cannot be reused.

## 7. Enroll a Linux / DJI Modem Node

1. In PWA Settings choose **Linux / DJI Modem Node**.
2. Create the enrollment package and save the JSON temporarily on the Linux host.
3. Install the agent with `sudo ./modem_agent/install.sh`.
4. Run the enrollment command shown in `modem_agent/README.md`.
5. Delete the temporary enrollment JSON.
6. Enable `simhub-modem.service`.

Incoming concatenated SMS fragments are encrypted and staged durably in the Agent SQLite database, reassembled when complete, and emitted as one SMS event. Incomplete groups older than 24 hours are surfaced with an explicit incomplete marker instead of remaining indefinitely in modem storage.

The Agent first tries the direct Quectel AT path for DJI Gen1/QDC507, then falls back to ModemManager/`mmcli`. The external `dji4g` utility is optional for network-interface setup and is not required for SMS reliability.

## 8. OEM background settings

On vivo/OPPO/Xiaomi/HONOR/Huawei and other aggressive battery-management ROMs, allow autostart and remove battery restrictions for SIM Hub if those controls exist. Keep the persistent foreground-service notification enabled when using always-on relay mode.

## 9. Test the installation

Run these checks in order:

1. PWA shows each Android / Modem node online.
2. Generic Channel/SIM information is visible.
3. Send a normal SMS to the SIM and confirm it appears in the PWA after local decryption.
4. Send an OTP-style SMS and confirm OTP detection/copy works.
5. Send a test SMS remotely from the PWA through a selected subscription.
6. Turn off the Android phone's network, receive/send test data, restore network and verify queued synchronization recovers.
7. Reboot the phone and verify the Agent recovers after boot/unlock according to the configured background mode.
8. If troubleshooting is required, enable Android Developer Mode, reproduce once, then export a redacted diagnostic ZIP as described in `DEVELOPER_DIAGNOSTICS.md`.

## 10. Backups

Run `./scripts/backup.sh` for a consistent SQLite online backup, or back up the Docker `simhub-data` volume for relay metadata/ciphertext. Separately protect the controller's Vault recovery material. The relay database by itself is intentionally insufficient to decrypt SMS content.

## 11. Updating

Server:

```bash
git pull
docker compose up -d --build
```

Android updates should be installed from a trusted GitHub Release or your own signed build. The OTA endpoint only advertises an update; it does not silently install APKs.


## Rolling upgrade from v0.1.x

Upgrade the server/PWA first, then Android nodes. Existing v1/v2 events remain decryptable. Once a legacy Android node reports 0.2.x, use **Isolate key** in the PWA to migrate it from the old Master-derived key to an independent Node Key. Remote 0.2 SMS commands use Channel ID + revision and fail closed when the SIM identity has changed. Do not reset/re-enroll unless intentionally moving the node to another Vault/relay.


## 12. Device token lifecycle

Android and Linux Modem nodes automatically rotate their per-node API bearer token after 60 days. Rotation is a two-phase prepare/commit exchange: the old token remains usable until the node has durably stored the replacement, so a network/process interruption cannot permanently lock out an unattended node.

This token is independent from the E2EE Node Key. Rotating one does not rotate the other.
