# Deployment

## Recommended topology

```text
Internet
  |
HTTPS :443
  |
Caddy / Nginx / Traefik
  |
127.0.0.1:8787
  |
SIM Hub relay
  |
./data/simhub.db
```

The relay intentionally binds to loopback in the Docker Compose example. Do not expose port 8787 directly to the public Internet without TLS termination.

## 1. Configure

```bash
cp .env.example .env
python3 scripts/gen_admin_token.py
mkdir -p data
chmod 700 data
```

Set `SIMHUB_PUBLIC_BASE_URL` to the HTTPS URL that Android nodes will reach.

Optional TOTP can be enabled by placing an RFC 4648 Base32 secret in `SIMHUB_TOTP_SECRET`. The PWA sends the 6-digit code only at request time and does not persist it.

## 2. Start

```bash
docker compose up -d --build
curl http://127.0.0.1:8787/healthz
```

Then configure your reverse proxy. `Caddyfile.example` is the shortest path.

## 3. Backups

The only durable relay data is the `data/` volume. SQLite runs in WAL mode. For a consistent online backup use SQLite's backup command from a host with sqlite3 installed, or briefly stop the container and copy `simhub.db` plus any `-wal`/`-shm` files.

The relay backup does **not** contain the Vault Key. A database backup alone therefore cannot decrypt SMS bodies.

## 4. Metadata-only push bridge

Set:

```env
SIMHUB_NOTIFY_WEBHOOK_URL=https://your-ntfy-or-bridge.example/path
SIMHUB_NOTIFY_WEBHOOK_BEARER=optional-secret
```

The POST body contains only event kind, a generic notification string and device name. It never includes sender, recipient, SMS body or OTP value.

## 5. OTA metadata

Mount a JSON file to `SIMHUB_OTA_FILE`, for example:

```json
{
  "versionCode": 2,
  "versionName": "0.2.0",
  "url": "https://github.com/blackkcold/simhub/releases/download/v0.2.0/simhub-agent.apk",
  "sha256": "...",
  "notes": "Bug fixes"
}
```

The Android app only checks and opens the HTTPS download URL. It does not silently install APKs.
