#!/usr/bin/env bash
set -euo pipefail

PREFIX="${SIMHUB_MODEM_PREFIX:-/opt/simhub-modem}"
STATE="${SIMHUB_MODEM_STATE:-/var/lib/simhub-modem}"

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run as root: sudo ./modem_agent/install.sh" >&2
  exit 1
fi

if ! id simhub-modem >/dev/null 2>&1; then
  useradd --system --home "$STATE" --shell /usr/sbin/nologin simhub-modem
fi
getent group dialout >/dev/null 2>&1 && usermod -a -G dialout simhub-modem || true

install -d -m 0755 "$PREFIX"
install -d -o simhub-modem -g simhub-modem -m 0700 "$STATE"
install -m 0755 modem_agent/simhub_modem_agent.py "$PREFIX/simhub_modem_agent.py"
python3 -m venv "$PREFIX/venv"
"$PREFIX/venv/bin/pip" install --upgrade pip
"$PREFIX/venv/bin/pip" install -r modem_agent/requirements.txt

install -m 0644 modem_agent/simhub-modem.service /etc/systemd/system/simhub-modem.service
systemctl daemon-reload

cat <<EOF
Installed SIM Hub Modem Agent.

1. In the PWA: Settings -> Enroll SIM node -> Linux / DJI Modem Node.
2. Save the generated JSON as:
     $STATE/enrollment.json
   and protect it with chmod 600.
3. Enroll:
     sudo -u simhub-modem $PREFIX/venv/bin/python $PREFIX/simhub_modem_agent.py \
       --config $STATE/config.json enroll --file $STATE/enrollment.json --adapter auto
4. Delete enrollment.json after success.
5. Start:
     systemctl enable --now simhub-modem

Adapter selection:
- dji4g: preferred when the dji4g CLI is installed for DJI Gen1/QDC507.
- modemmanager: generic mmcli/ModemManager path.
EOF
