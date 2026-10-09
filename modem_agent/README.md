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


## Multipart receive

For DJI/Quectel direct-AT nodes, concatenated SMS UDH is detected from the PDU representation. Each fragment is encrypted with the node key and durably staged in the Agent SQLite database before the modem copy is deleted.

- complete groups are reassembled into one `sms.received` event;
- process restarts do not lose staged fragments;
- duplicate fragments are idempotent by group/part number;
- groups still incomplete after 24 hours are emitted once with `multipartIncomplete=true`, `partsReceived` and `partsExpected` inside the encrypted payload.

This prevents orphan fragments from filling modem/SIM message storage indefinitely.


## Multiple USB / DJI SIM nodes (v0.2.2)

Each physical modem uses its **own enrollment**, credentials, SQLite queue, and service instance. The instance template is `simhub-modem@.service`.

For example, for a device alias `dongle-a`:

```bash
sudo install -d -o simhub-modem -g simhub-modem -m 0700 /var/lib/simhub-modem/dongle-a
sudo install -m 0600 -o simhub-modem -g simhub-modem enrollment-a.json /var/lib/simhub-modem/dongle-a/enrollment.json

# Bind to a stable /dev/serial/by-id path, NOT /dev/ttyUSB2 which may change after a reboot.
printf '%s\\n' 'SIMHUB_AT_PORT=/dev/serial/by-id/usb-EXACT_MODEM_PORT' | sudo tee /etc/simhub-modem/dongle-a.env
sudo chmod 0640 /etc/simhub-modem/dongle-a.env

sudo -u simhub-modem /opt/simhub-modem/venv/bin/python /opt/simhub-modem/simhub_modem_agent.py \\
  --config /var/lib/simhub-modem/dongle-a/config.json enroll \\
  --file /var/lib/simhub-modem/dongle-a/enrollment.json --adapter dji-at
sudo rm -f /var/lib/simhub-modem/dongle-a/enrollment.json
sudo systemctl enable --now simhub-modem@dongle-a
```

Use a distinct alias and serial port for each modem. For ModemManager, use an instance-specific `SIMHUB_MODEM_ID` (a stable mmcli modem identifier when available), and enroll with `--adapter modemmanager`. Never let two service instances manage the same physical modem storage or AT port. A multi-device autodiscovery wizard is not yet implemented; explicit device bindings are intentional to avoid wrong-SIM sends.

## Two-way enrollment reset

Run `simhub_modem_agent.py --config /var/lib/simhub-modem/config.json reset` to queue an authenticated Relay unpair. Offline requests are retried during regular polling. Once confirmed, the agent removes its local config/event queue and exits; systemd does not restart it after a clean reset. SMS stored in modem hardware is unaffected. Server-initiated unpairing requires Modem Agent v0.3.2 or later.
