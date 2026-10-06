# SIM Hub Linux Modem Agent

The Linux Modem Agent lets a cellular USB modem appear in SIM Hub as the same kind of **Node + Channel** used by Android devices.

## Supported adapter paths

### DJI Gen1 / QDC507

For the first-generation DJI 4G module / QDC507, use the built-in direct Quectel AT adapter:

```bash
--adapter dji-at
```

It discovers the `Quectel USB AT Port` (including known DJI/Quectel USB IDs), then directly uses standard/Quectel AT commands for SIM identity, inbox polling, deletion, operator/signal state and outbound SMS. Outbound messages use UCS2 SMS-SUBMIT PDU; long messages use standard concatenation UDH.

The external `dji4g` project is optional and can still be used separately to configure/connect the DJI module's Linux network interface. SIM Hub does not depend on undocumented `dji4g` subcommands for SMS reliability.

### Generic Linux modem

If ModemManager supports the device:

```bash
--adapter modemmanager
```

or leave `--adapter auto`. The agent uses `mmcli` for SMS and radio status.

## Security

The PWA creates a random **Node Key** for the modem. The Master Vault Key never goes to the modem host.

The relay receives only:

- encrypted SMS payloads;
- encrypted SMS commands;
- routing metadata;
- device/channel state.

The Node Key is stored in `/var/lib/simhub-modem/config.json`, which must be mode `0600`.

## Install

```bash
sudo ./modem_agent/install.sh
```

Then create a **Linux / DJI Modem Node** enrollment package in the PWA and save it temporarily:

```bash
sudo install -o simhub-modem -g simhub-modem -m 600 enrollment.json /var/lib/simhub-modem/enrollment.json

sudo -u simhub-modem /opt/simhub-modem/venv/bin/python \
  /opt/simhub-modem/simhub_modem_agent.py \
  --config /var/lib/simhub-modem/config.json \
  enroll --file /var/lib/simhub-modem/enrollment.json --adapter auto

sudo rm -f /var/lib/simhub-modem/enrollment.json
sudo systemctl enable --now simhub-modem
```

## Channel revision safety

The agent locally fingerprints the SIM/modem identity. When it changes, the channel revision increases. Remote SMS commands include both `channelId` and `channelRevision`; stale commands are rejected instead of being sent through a replacement SIM.

## DJI Dongle 2

Dongle 2 is not assumed to expose the same AT/SMS interface as DJI Gen1. Use `auto`/ModemManager detection first. SIM Hub intentionally treats hardware support as an adapter capability rather than hard-coding DJI branding into the relay protocol.
