#!/usr/bin/env bash
# Non-root enrollment for a pre-existing rootless Docker deployment.
set -euo pipefail
if [[ "$EUID" -eq 0 ]]; then
  echo "Do not use sudo. Run this as the rootless Docker user." >&2
  exit 1
fi
ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
cd "$ROOT"
for command in docker python3 systemctl setfacl awk cosign; do
  command -v "$command" >/dev/null || { echo "Missing dependency: $command" >&2; exit 1; }
done
[[ -f .env && -f docker-compose.yml ]] || { echo "Initialize deployment first" >&2; exit 1; }
docker compose version >/dev/null
python3 - <<'PY'
import json,subprocess,sys
options=json.loads(subprocess.check_output(
    ["docker","info","--format","{{json .SecurityOptions}}"],text=True))
if not any(str(x).split("=",1)[-1]=="rootless" for x in options):
    sys.exit("REFUSED: connected Docker daemon is not rootless")
PY
CID="$(docker compose ps -q simhub)"
[[ -n "$CID" ]] || { echo "Start the rootless simhub container and migrate its volume before enrollment" >&2; exit 1; }
MAP="$(docker compose exec -T simhub cat /proc/self/uid_map)"
MAPPED_UID="$(printf '%s\n' "$MAP" | awk '$1<=65534 && 65534<$1+$3 {print $2+65534-$1; exit}')"
[[ "$MAPPED_UID" =~ ^[0-9]+$ && "$MAPPED_UID" != "0" && "$MAPPED_UID" != "$(id -u)" ]] ||
  { echo "Cannot resolve mapped UID 65534; refusing unsafe socket permissions" >&2; exit 1; }
mkdir -p .simhub-updater "$HOME/.config/systemd/user"
chmod 700 .simhub-updater
setfacl -m "u:$MAPPED_UID:--x" .simhub-updater
UNIT="$HOME/.config/systemd/user/simhub-updater.service"
cat > "$UNIT" <<EOF
[Unit]
Description=SIM Hub rootless verified OCI updater
After=docker.service
Requires=docker.service

[Service]
Type=simple
WorkingDirectory=$ROOT
Environment=DOCKER_HOST=unix://%t/docker.sock
Environment=SIMHUB_MAPPED_UID=$MAPPED_UID
ExecStart=/usr/bin/python3 $ROOT/scripts/simhub_updater.py --serve
Restart=on-failure
RestartSec=5
UMask=0077
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=full
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6
SystemCallArchitectures=native

[Install]
WantedBy=default.target
EOF
chmod 600 "$UNIT"
systemctl --user daemon-reload
systemctl --user enable --now simhub-updater.service
systemctl --user restart simhub-updater.service
echo "Rootless updater installed for $(id -un)."
echo "Run: systemctl --user status simhub-updater"
echo "Important: disable OLD privileged updater: sudo systemctl disable --now simhub-updater.service"
echo "Enable user linger for boot-time start if required."
