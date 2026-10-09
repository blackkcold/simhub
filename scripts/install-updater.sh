#!/usr/bin/env bash
# One-time privileged host setup. The relay itself remains unprivileged.
set -euo pipefail
if [[ "${EUID}" -ne 0 ]]; then
  echo "Run: sudo bash scripts/install-updater.sh" >&2
  exit 1
fi
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
if [[ ! -f "$ROOT/docker-compose.yml" || ! -f "$ROOT/.env" ]]; then
  echo "Run from an initialized SIM Hub deployment directory." >&2
  exit 1
fi
command -v docker >/dev/null
docker compose version >/dev/null
command -v python3 >/dev/null
command -v systemctl >/dev/null
mkdir -p "$ROOT/.simhub-updater"
chmod 750 "$ROOT/.simhub-updater"
chown root:65534 "$ROOT/.simhub-updater"
SERVICE=/etc/systemd/system/simhub-updater.service
cat > "$SERVICE" <<EOF
[Unit]
Description=SIM Hub restricted release updater
After=docker.service
Requires=docker.service

[Service]
Type=simple
WorkingDirectory=$ROOT
ExecStart=/usr/bin/python3 $ROOT/scripts/simhub_updater.py --serve
Restart=on-failure
RestartSec=3
UMask=0077
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=read-only
ProtectSystem=full
ReadWritePaths=$ROOT
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6
SystemCallArchitectures=native

[Install]
WantedBy=multi-user.target
EOF
chmod 644 "$SERVICE"
systemctl daemon-reload
systemctl enable --now simhub-updater.service
systemctl restart simhub-updater.service
echo "Updater installed. Check: systemctl status simhub-updater"
echo "Relay reconnect (if already deployed): docker compose up -d --no-build --force-recreate simhub"
