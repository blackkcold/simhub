#!/usr/bin/env python3
"""SIM Hub Linux Modem Agent.

Supports:
- DJI Gen1/QDC507 via the external MIT-licensed dji4g CLI when installed.
- Generic Linux cellular modems through ModemManager/mmcli.

The relay remains blind to SMS plaintext: the agent uses the same v2 AES-GCM
event/command envelopes as the Android Agent, with an independent per-node key.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

VERSION = "0.2.0"
DEFAULT_CONFIG = Path(os.getenv("SIMHUB_MODEM_CONFIG", "/var/lib/simhub-modem/config.json"))
DEFAULT_DB = Path(os.getenv("SIMHUB_MODEM_DB", "/var/lib/simhub-modem/agent.db"))
POLL_SECONDS = max(3, int(os.getenv("SIMHUB_MODEM_POLL_SECONDS", "10")))
MAX_SMS_PER_CYCLE = max(10, min(int(os.getenv("SIMHUB_MODEM_MAX_SMS_PER_CYCLE", "200")), 5000))


def now() -> int:
    return int(time.time())


def b64u(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def ub64u(value: str) -> bytes:
    value += "=" * ((4 - len(value) % 4) % 4)
    return base64.urlsafe_b64decode(value.encode())


def key_id(raw: bytes) -> str:
    return b64u(hashlib.sha256(raw).digest()[:12])


def field(value: Any) -> str:
    return b64u(str("" if value is None else value).encode())


def event_aad(device_id: str, event_id: str, kind: str, occurred_at: int, channel_id: str, has_otp: bool) -> bytes:
    return (
        "simhub-event-v2|"
        + field(device_id) + "|"
        + field(event_id) + "|"
        + field(kind) + "|"
        + str(occurred_at) + "|"
        + field(channel_id) + "|"
        + ("1" if has_otp else "0")
    ).encode()


def command_aad(device_id: str, command_id: str, kind: str, created_at: int, expires_at: int, idem: str) -> bytes:
    return (
        "simhub-command-v2|"
        + field(device_id) + "|"
        + field(command_id) + "|"
        + field(kind) + "|"
        + str(created_at) + "|"
        + str(expires_at) + "|"
        + field(idem)
    ).encode()


def encrypt_payload(key: bytes, kid: str, payload: dict[str, Any], aad: bytes) -> dict[str, Any]:
    iv = os.urandom(12)
    ct = AESGCM(key).encrypt(iv, json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode(), aad)
    return {"v": 2, "alg": "A256GCM", "kid": kid, "iv": b64u(iv), "ct": b64u(ct)}


def decrypt_payload(key: bytes, expected_kid: str, envelope: dict[str, Any], aad: bytes) -> dict[str, Any]:
    if envelope.get("v") != 2 or envelope.get("alg") != "A256GCM":
        raise ValueError("unsupported ciphertext envelope")
    if envelope.get("kid") != expected_kid:
        raise ValueError("node key id mismatch")
    raw = AESGCM(key).decrypt(ub64u(str(envelope["iv"])), ub64u(str(envelope["ct"])), aad)
    value = json.loads(raw)
    if not isinstance(value, dict):
        raise ValueError("command payload must be an object")
    return value


def atomic_write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    os.chmod(tmp, 0o600)
    tmp.replace(path)
    os.chmod(path, 0o600)


def load_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text("utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path} must contain a JSON object")
    return value


class Relay:
    def __init__(self, server: str, device_id: str = "", device_token: str = "") -> None:
        parsed = urllib.parse.urlsplit(server)
        if parsed.scheme != "https" or not parsed.hostname:
            raise ValueError("SIM Hub relay must use HTTPS")
        self.server = server.rstrip("/")
        self.device_id = device_id
        self.device_token = device_token

    def request(self, method: str, path: str, body: dict[str, Any] | None = None, auth: bool = True) -> dict[str, Any]:
        headers = {"Accept": "application/json"}
        if auth:
            headers["Authorization"] = "Device " + self.device_token
            headers["X-SimHub-Device-Id"] = self.device_id
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(body, separators=(",", ":")).encode()
        req = urllib.request.Request(self.server + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=15) as response:
                raw = response.read()
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as exc:
            raw = exc.read().decode("utf-8", "replace")
            try:
                detail = json.loads(raw).get("message", raw)
            except Exception:
                detail = raw
            raise RuntimeError(f"relay HTTP {exc.code}: {detail}") from exc

    def enroll(self, token: str, name: str) -> dict[str, Any]:
        return self.request(
            "POST",
            "/api/v1/enroll",
            {
                "token": token,
                "name": name,
                "model": "Linux cellular modem",
                "osVersion": sys.platform,
                "appVersion": VERSION,
                "nodeType": "modem",
                "capabilities": ["sms.receive", "sms.send", "sms.history", "signal.basic", "signal.radio"],
            },
            auth=False,
        )


class Store:
    def __init__(self, path: Path) -> None:
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(path, timeout=10)
        self.db.row_factory = sqlite3.Row
        self.db.executescript(
            """
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS events(
              id TEXT PRIMARY KEY,
              kind TEXT NOT NULL,
              occurred_at INTEGER NOT NULL,
              channel_id TEXT NOT NULL,
              has_otp INTEGER NOT NULL,
              metadata_json TEXT NOT NULL,
              ciphertext_json TEXT NOT NULL,
              created_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS seen_sms(
              local_id TEXT PRIMARY KEY,
              seen_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS processed_commands(
              id TEXT PRIMARY KEY,
              state TEXT NOT NULL,
              processed_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS command_acks(
              command_id TEXT PRIMARY KEY,
              state TEXT NOT NULL,
              result_json TEXT NOT NULL,
              updated_at INTEGER NOT NULL
            );
            """
        )
        self.db.commit()
        os.chmod(path, 0o600)

    def queue_event(self, event_id: str, kind: str, occurred_at: int, channel_id: str, has_otp: bool, metadata: dict[str, Any], cipher: dict[str, Any]) -> bool:
        cur = self.db.execute(
            "INSERT OR IGNORE INTO events(id,kind,occurred_at,channel_id,has_otp,metadata_json,ciphertext_json,created_at) VALUES(?,?,?,?,?,?,?,?)",
            (event_id, kind, occurred_at, channel_id, 1 if has_otp else 0, json.dumps(metadata, separators=(",", ":")), json.dumps(cipher, separators=(",", ":")), now()),
        )
        self.db.commit()
        return cur.rowcount > 0 or self.db.execute("SELECT 1 FROM events WHERE id=?", (event_id,)).fetchone() is not None

    def pending_events(self, limit: int = 200) -> list[dict[str, Any]]:
        rows = self.db.execute("SELECT * FROM events ORDER BY created_at ASC LIMIT ?", (limit,)).fetchall()
        return [
            {
                "eventId": r["id"],
                "kind": r["kind"],
                "occurredAt": r["occurred_at"],
                "subscriptionId": r["channel_id"],
                "hasOtp": bool(r["has_otp"]),
                "metadata": json.loads(r["metadata_json"]),
                "ciphertext": json.loads(r["ciphertext_json"]),
            }
            for r in rows
        ]

    def mark_event_sent(self, event_id: str) -> None:
        self.db.execute("DELETE FROM events WHERE id=?", (event_id,))
        self.db.commit()

    def seen(self, local_id: str) -> bool:
        return self.db.execute("SELECT 1 FROM seen_sms WHERE local_id=?", (local_id,)).fetchone() is not None

    def mark_seen(self, local_id: str) -> None:
        self.db.execute("INSERT OR IGNORE INTO seen_sms(local_id,seen_at) VALUES(?,?)", (local_id, now()))
        self.db.commit()

    def claim_command(self, command_id: str) -> bool:
        cur = self.db.execute(
            "INSERT OR IGNORE INTO processed_commands(id,state,processed_at) VALUES(?,?,?)",
            (command_id, "claimed", now()),
        )
        self.db.commit()
        return cur.rowcount > 0

    def finish_command(self, command_id: str, state: str) -> None:
        self.db.execute("UPDATE processed_commands SET state=?,processed_at=? WHERE id=?", (state, now(), command_id))
        self.db.execute("DELETE FROM processed_commands WHERE processed_at<?", (now() - 30 * 86400,))
        self.db.commit()

    def queue_ack(self, command_id: str, state: str, result: dict[str, Any]) -> None:
        self.db.execute(
            "INSERT INTO command_acks(command_id,state,result_json,updated_at) VALUES(?,?,?,?) ON CONFLICT(command_id) DO UPDATE SET state=excluded.state,result_json=excluded.result_json,updated_at=excluded.updated_at",
            (command_id, state, json.dumps(result, separators=(",", ":")), now()),
        )
        self.db.commit()

    def pending_acks(self) -> list[sqlite3.Row]:
        return self.db.execute("SELECT * FROM command_acks ORDER BY updated_at ASC LIMIT 200").fetchall()

    def mark_ack_sent(self, command_id: str) -> None:
        self.db.execute("DELETE FROM command_acks WHERE command_id=?", (command_id,))
        self.db.commit()

    def prune(self) -> None:
        self.db.execute("DELETE FROM seen_sms WHERE seen_at<?",(now()-30*86400,))
        self.db.execute("DELETE FROM processed_commands WHERE processed_at<? AND state!='claimed'",(now()-30*86400,))
        self.db.commit()

    def recover_interrupted_claims(self) -> None:
        rows = self.db.execute("SELECT id FROM processed_commands WHERE state='claimed' AND processed_at<?", (now() - 120,)).fetchall()
        for r in rows:
            self.finish_command(r["id"], "failed")
            self.queue_ack(r["id"], "failed", {"reason": "interrupted_unknown"})
        self.db.commit()


@dataclass
class SmsRecord:
    local_id: str
    sender: str
    body: str
    occurred_at: int
    ref: str = ""


class ModemAdapter:
    name = "base"

    def state(self) -> dict[str, Any]:
        raise NotImplementedError

    def fingerprint(self) -> str:
        raise NotImplementedError

    def list_sms(self) -> list[SmsRecord]:
        raise NotImplementedError

    def send_sms(self, to: str, body: str) -> dict[str, Any]:
        raise NotImplementedError

    def delete_sms(self, sms: SmsRecord) -> None:
        return


def run_command(argv: list[str], timeout: int = 20) -> str:
    p = subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=timeout, check=False)
    if p.returncode != 0:
        raise RuntimeError(f"{' '.join(argv[:2])} failed: {p.stdout.strip()[:500]}")
    return p.stdout.strip()


def maybe_json(text: str) -> Any:
    try:
        return json.loads(text)
    except Exception:
        return {}


def deep_pick(value: Any, *names: str) -> Any:
    wanted = {n.lower().replace("-", "").replace("_", "") for n in names}
    if isinstance(value, dict):
        for k, v in value.items():
            norm = str(k).lower().replace("-", "").replace("_", "")
            if norm in wanted and v not in ("", None):
                return v
        for v in value.values():
            found = deep_pick(v, *names)
            if found not in ("", None):
                return found
    elif isinstance(value, list):
        for v in value:
            found = deep_pick(v, *names)
            if found not in ("", None):
                return found
    return None


def decode_ucs2(value: str) -> str:
    v = value.strip().strip('"')
    if len(v) >= 4 and len(v) % 4 == 0 and re.fullmatch(r"[0-9A-Fa-f]+", v):
        try:
            return bytes.fromhex(v).decode("utf-16-be")
        except Exception:
            pass
    return v


class Dji4gAdapter(ModemAdapter):
    name = "dji4g"

    def __init__(self) -> None:
        if not shutil.which("dji4g"):
            raise RuntimeError("dji4g CLI not found")

    def at(self, command: str) -> str:
        return run_command(["dji4g", "at", command], timeout=20)

    def fingerprint(self) -> str:
        for cmd in ("AT+CCID", "AT+CIMI", "ATI"):
            try:
                out = self.at(cmd)
                values = re.findall(r"[0-9A-Za-z._-]{8,}", out)
                if values:
                    return hashlib.sha256((cmd + "|" + "|".join(values)).encode()).hexdigest()
            except Exception:
                continue
        return "dji4g-unknown"

    def state(self) -> dict[str, Any]:
        status = maybe_json(run_command(["dji4g", "status", "--json"], timeout=20))
        try:
            cell = maybe_json(run_command(["dji4g", "cell", "--json"], timeout=20))
        except Exception:
            cell = {}
        carrier = deep_pick(status, "operator", "carrier", "network") or deep_pick(cell, "operator", "carrier") or ""
        rsrp = deep_pick(cell, "rsrp")
        rsrq = deep_pick(cell, "rsrq")
        sinr = deep_pick(cell, "sinr", "snr")
        rssi = deep_pick(status, "rssi", "signal") or deep_pick(cell, "rssi")
        network = deep_pick(cell, "rat", "technology", "networktype") or deep_pick(status, "rat", "technology") or "CELLULAR"
        level = None
        try:
            rv = float(str(rsrp).replace("dBm", "").strip())
            level = 4 if rv >= -90 else 3 if rv >= -100 else 2 if rv >= -110 else 1 if rv >= -120 else 0
        except Exception:
            pass
        return {
            "carrierName": str(carrier),
            "displayName": "DJI 4G / QDC507",
            "serviceState": "IN_SERVICE" if status else "UNKNOWN",
            "networkType": str(network),
            "signalLevel": level,
            "signalRsrp": rsrp,
            "signalRsrq": rsrq,
            "signalSinr": sinr,
            "signalRssi": rssi,
        }

    def list_sms(self) -> list[SmsRecord]:
        self.at("AT+CMGF=1")
        out = self.at('AT+CMGL="ALL"')
        lines = [x.rstrip() for x in out.splitlines()]
        records: list[SmsRecord] = []
        header = re.compile(r'^\+CMGL:\s*(\d+),"([^"]*)","([^"]*)"(?:,"([^"]*)")?(?:,"([^"]*)")?')
        i = 0
        while i < len(lines):
            m = header.match(lines[i].strip())
            if not m:
                i += 1
                continue
            idx = m.group(1)
            sender = decode_ucs2(m.group(3) or "")
            body_lines: list[str] = []
            i += 1
            while i < len(lines) and not lines[i].startswith("+CMGL:") and lines[i].strip() not in {"OK", "ERROR"}:
                if lines[i].strip():
                    body_lines.append(lines[i].strip())
                i += 1
            body = decode_ucs2("\n".join(body_lines))
            occurred=now()
            digest=hashlib.sha256((sender+"\0"+body+"\0"+str(occurred)).encode()).hexdigest()[:16]
            records.append(SmsRecord(local_id=f"dji4g-{idx}-{digest}", sender=sender, body=body, occurred_at=occurred, ref=idx))
        return records[:MAX_SMS_PER_CYCLE]

    def send_sms(self, to: str, body: str) -> dict[str, Any]:
        out = run_command(["dji4g", "sms", "send", to, body], timeout=60)
        match = re.search(r"\+CMGS:\s*(\d+)", out)
        return {"adapter": self.name, "messageRef": int(match.group(1)) if match else None}

    def delete_sms(self, sms: SmsRecord) -> None:
        if sms.ref and str(sms.ref).isdigit():
            self.at("AT+CMGD="+str(sms.ref))


class MmcliAdapter(ModemAdapter):
    name = "modemmanager"

    def __init__(self, modem: str | None = None) -> None:
        if not shutil.which("mmcli"):
            raise RuntimeError("mmcli not found")
        self.modem = modem or os.getenv("SIMHUB_MODEM_ID", "any")
        run_command(["mmcli", "-m", self.modem, "--simple-status"], timeout=20)

    def _sms_paths(self) -> list[str]:
        out = run_command(["mmcli", "-m", self.modem, "--messaging-list-sms"], timeout=20)
        return re.findall(r"(/org/freedesktop/ModemManager1/SMS/\d+)", out)

    def fingerprint(self) -> str:
        out = maybe_json(run_command(["mmcli", "-m", self.modem, "-J"], timeout=20))
        value = deep_pick(out, "simidentifier", "iccid", "equipmentidentifier", "imei") or str(out)
        return hashlib.sha256(str(value).encode()).hexdigest()

    def state(self) -> dict[str, Any]:
        status = maybe_json(run_command(["mmcli", "-m", self.modem, "--simple-status", "-J"], timeout=20))
        try:
            signal = maybe_json(run_command(["mmcli", "-m", self.modem, "--signal-get", "-J"], timeout=20))
        except Exception:
            signal = {}
        carrier = deep_pick(status, "operatorname", "operator") or ""
        rsrp = deep_pick(signal, "rsrp")
        rsrq = deep_pick(signal, "rsrq")
        sinr = deep_pick(signal, "snr", "sinr")
        quality = deep_pick(status, "signalquality")
        level = None
        try:
            q = float(quality[0] if isinstance(quality, list) else quality)
            level = 4 if q >= 80 else 3 if q >= 60 else 2 if q >= 40 else 1 if q >= 20 else 0
        except Exception:
            pass
        return {
            "carrierName": str(carrier),
            "displayName": "Linux Modem",
            "serviceState": str(deep_pick(status, "state", "registrationstate") or "UNKNOWN").upper(),
            "networkType": str(deep_pick(status, "accesstechnologies", "access") or "CELLULAR"),
            "signalLevel": level,
            "signalRsrp": rsrp,
            "signalRsrq": rsrq,
            "signalSinr": sinr,
        }

    def list_sms(self) -> list[SmsRecord]:
        out: list[SmsRecord] = []
        for path in self._sms_paths()[:MAX_SMS_PER_CYCLE]:
            data = maybe_json(run_command(["mmcli", "-s", path, "-J"], timeout=20))
            text = deep_pick(data, "text") or ""
            number = deep_pick(data, "number") or ""
            timestamp = deep_pick(data, "timestamp")
            ts = now()
            if isinstance(timestamp, str):
                try:
                    from datetime import datetime
                    ts = int(datetime.fromisoformat(timestamp.replace("Z", "+00:00")).timestamp())
                except Exception:
                    pass
            digest=hashlib.sha256((str(number)+"\0"+str(text)+"\0"+str(ts)).encode()).hexdigest()[:16]
            out.append(SmsRecord(local_id="mm-"+path.rsplit("/", 1)[-1]+"-"+digest, sender=str(number), body=str(text), occurred_at=ts, ref=path))
        return out

    def send_sms(self, to: str, body: str) -> dict[str, Any]:
        spec=f"text='{body.replace(chr(39), '')}',number='{to}'"
        created=run_command(["mmcli", "-m", self.modem, f"--messaging-create-sms={spec}"], timeout=30)
        match=re.search(r"(/org/freedesktop/ModemManager1/SMS/\d+)",created)
        if not match:
            raise RuntimeError("ModemManager did not return an SMS object")
        path=match.group(1)
        run_command(["mmcli","-s",path,"--send"],timeout=60)
        return {"adapter":self.name,"smsObject":path}

    def delete_sms(self, sms: SmsRecord) -> None:
        if sms.ref:
            run_command(["mmcli","-m",self.modem,"--messaging-delete-sms="+sms.ref],timeout=20)


def build_adapter(name: str) -> ModemAdapter:
    n = (name or "auto").lower()
    if n == "dji4g":
        return Dji4gAdapter()
    if n in {"mmcli", "modemmanager"}:
        return MmcliAdapter()
    if shutil.which("dji4g"):
        try:
            return Dji4gAdapter()
        except Exception:
            pass
    return MmcliAdapter()


class Agent:
    def __init__(self, config_path: Path, db_path: Path) -> None:
        self.config_path = config_path
        self.config = load_json(config_path)
        self.store = Store(db_path)
        self.node_key = ub64u(str(self.config["nodeKey"]))
        self.kid = str(self.config["keyId"])
        if len(self.node_key) != 32 or key_id(self.node_key) != self.kid:
            raise ValueError("configured node key is invalid")
        self.relay = Relay(str(self.config["server"]), str(self.config["deviceId"]), str(self.config["deviceToken"]))
        self.adapter = build_adapter(str(self.config.get("adapter", "auto")))
        self._sync_channel_identity()

    def _sync_channel_identity(self) -> None:
        fingerprint = self.adapter.fingerprint()
        old = str(self.config.get("channelFingerprint", ""))
        if not self.config.get("channelId"):
            self.config["channelId"] = str(uuid.uuid4())
            self.config["channelRevision"] = 1
        elif old and old != fingerprint:
            self.config["channelRevision"] = int(self.config.get("channelRevision", 1)) + 1
        self.config["channelFingerprint"] = fingerprint
        atomic_write_json(self.config_path, self.config)

    @property
    def channel_id(self) -> str:
        return str(self.config["channelId"])

    @property
    def channel_revision(self) -> int:
        return int(self.config.get("channelRevision", 1))

    def encrypt_event(self, event_id: str, kind: str, occurred_at: int, has_otp: bool, payload: dict[str, Any]) -> dict[str, Any]:
        return encrypt_payload(self.node_key, self.kid, payload, event_aad(str(self.config["deviceId"]), event_id, kind, occurred_at, self.channel_id, has_otp))

    def receive(self) -> None:
        for sms in self.adapter.list_sms():
            if self.store.seen(sms.local_id):
                continue
            event_id = "modem-" + sms.local_id
            payload = {
                "direction": "in",
                "sender": sms.sender,
                "body": sms.body,
                "occurredAt": sms.occurred_at,
                "channelId": self.channel_id,
                "channelRevision": self.channel_revision,
                "providerId": sms.local_id,
            }
            cipher = self.encrypt_event(event_id, "sms.received", sms.occurred_at, False, payload)
            if self.store.queue_event(event_id, "sms.received", sms.occurred_at, self.channel_id, False, {"source": self.adapter.name, "parts": 1}, cipher):
                self.store.mark_seen(sms.local_id)
                try:
                    self.adapter.delete_sms(sms)
                except Exception:
                    pass

    def flush_events(self) -> None:
        for event in self.store.pending_events():
            response = self.relay.request("POST", f"/api/v1/devices/{self.config['deviceId']}/events", event)
            if response.get("accepted"):
                self.store.mark_event_sent(str(event["eventId"]))

    def flush_acks(self) -> None:
        for row in self.store.pending_acks():
            self.relay.request(
                "POST",
                f"/api/v1/devices/{self.config['deviceId']}/commands/{row['command_id']}/ack",
                {"state": row["state"], "result": json.loads(row["result_json"])},
            )
            self.store.mark_ack_sent(str(row["command_id"]))

    def process_commands(self) -> None:
        data = self.relay.request("GET", f"/api/v1/devices/{self.config['deviceId']}/commands/pending?limit=50")
        for env in data.get("commands", []):
            command_id = str(env.get("commandId", ""))
            if not command_id or not self.store.claim_command(command_id):
                continue
            ctype = str(env.get("type", ""))
            created = int(env.get("createdAt", 0))
            expires = int(env.get("expiresAt", 0))
            idem = str(env.get("idempotencyKey", command_id))
            if expires and expires < now():
                self.store.finish_command(command_id, "expired")
                self.store.queue_ack(command_id, "expired", {})
                continue
            try:
                payload = decrypt_payload(
                    self.node_key,
                    self.kid,
                    env["ciphertext"],
                    command_aad(str(self.config["deviceId"]), command_id, ctype, created, expires, idem),
                )
                if payload.get("action") != ctype or payload.get("commandId") != command_id:
                    raise ValueError("command binding mismatch")
                if ctype == "sms.send":
                    if str(payload.get("channelId", "")) != self.channel_id or int(payload.get("channelRevision", 0)) != self.channel_revision:
                        raise RuntimeError("channel_changed")
                    to = str(payload.get("to", ""))
                    body = str(payload.get("body", ""))
                    if not re.fullmatch(r"\+?[0-9*# ()-]{3,40}", to) or not body or len(body) > 4000:
                        raise ValueError("invalid_sms")
                    result = self.adapter.send_sms(to, body)
                    ts = now()
                    event_payload = {
                        "direction": "out",
                        "recipient": to,
                        "body": body,
                        "occurredAt": ts,
                        "channelId": self.channel_id,
                        "channelRevision": self.channel_revision,
                        "commandId": command_id,
                        "providerId": result.get("messageRef") or result.get("smsObject") or command_id,
                    }
                    cipher = self.encrypt_event("modem-send-" + command_id, "sms.sent", ts, False, event_payload)
                    self.store.queue_event("modem-send-" + command_id, "sms.sent", ts, self.channel_id, False, {"stage": "modem_sent"}, cipher)
                    self.store.finish_command(command_id, "sent")
                    self.store.queue_ack(command_id, "sent", {"adapter": self.adapter.name})
                elif ctype in {"device.refresh_state", "subscription.refresh"}:
                    self.put_state()
                    self.store.finish_command(command_id, "succeeded")
                    self.store.queue_ack(command_id, "succeeded", {"refreshed": True})
                elif ctype == "diagnostics.request":
                    self.store.finish_command(command_id, "succeeded")
                    self.store.queue_ack(command_id, "succeeded", {"adapter": self.adapter.name})
                else:
                    self.store.finish_command(command_id, "rejected")
                    self.store.queue_ack(command_id, "rejected", {"reason": "unsupported_on_modem"})
            except Exception as exc:
                reason = "channel_changed" if "channel_changed" in str(exc) else exc.__class__.__name__
                self.store.finish_command(command_id, "failed")
                self.store.queue_ack(command_id, "failed", {"reason": reason})

    def put_state(self) -> None:
        state = self.adapter.state()
        channel = {
            "id": self.channel_id,
            "localId": "sim0",
            "kind": "cellular-modem",
            "revision": self.channel_revision,
            "slotIndex": 0,
            **state,
        }
        body = {
            "nodeType": "modem",
            "appVersion": VERSION,
            "model": state.get("displayName", "Linux Modem"),
            "network": "CELLULAR",
            "pendingEvents": len(self.store.pending_events(1000)),
            "cryptoKeyId": self.kid,
            "capabilities": ["sms.receive", "sms.send", "sms.history", "signal.basic", "signal.radio"],
            "channels": [channel],
        }
        self.relay.request("POST", f"/api/v1/devices/{self.config['deviceId']}/state", body)

    def heartbeat(self) -> None:
        self.relay.request(
            "POST",
            f"/api/v1/devices/{self.config['deviceId']}/heartbeat",
            {"appVersion": VERSION, "osVersion": sys.platform},
        )

    def cycle(self) -> None:
        self.store.prune()
        self.store.recover_interrupted_claims()
        self._sync_channel_identity()
        self.receive()
        self.flush_events()
        self.flush_acks()
        self.process_commands()
        self.flush_acks()
        self.flush_events()
        self.put_state()
        self.heartbeat()

    def run(self) -> None:
        backoff = POLL_SECONDS
        while True:
            try:
                self.cycle()
                backoff = POLL_SECONDS
            except KeyboardInterrupt:
                return
            except Exception as exc:
                print(f"simhub-modem: {type(exc).__name__}: {exc}", file=sys.stderr, flush=True)
                backoff = min(max(POLL_SECONDS, backoff * 2), 120)
            time.sleep(backoff)


def enroll(args: argparse.Namespace) -> None:
    payload = load_json(Path(args.file))
    server = str(payload.get("server", ""))
    token = str(payload.get("token", ""))
    node_key = ub64u(str(payload.get("key", "")))
    kid = str(payload.get("keyId", ""))
    name = str(payload.get("name") or "DJI / Modem SIM Node")
    if len(node_key) != 32 or key_id(node_key) != kid:
        raise SystemExit("Enrollment file contains an invalid node key")
    relay = Relay(server)
    response = relay.enroll(token, name)
    config = {
        "version": 1,
        "server": server.rstrip("/"),
        "deviceId": response["deviceId"],
        "deviceToken": response["deviceToken"],
        "nodeKey": b64u(node_key),
        "keyId": kid,
        "name": name,
        "adapter": args.adapter,
        "channelId": str(uuid.uuid4()),
        "channelRevision": 1,
        "channelFingerprint": "",
    }
    path = Path(args.config)
    atomic_write_json(path, config)
    print(f"Enrolled modem node {response['deviceId']} -> {path}")


def main() -> None:
    p = argparse.ArgumentParser(description="SIM Hub Linux Modem Agent")
    p.add_argument("--config", default=str(DEFAULT_CONFIG))
    p.add_argument("--db", default=str(DEFAULT_DB))
    sub = p.add_subparsers(dest="command", required=True)
    e = sub.add_parser("enroll")
    e.add_argument("--file", required=True, help="Enrollment JSON exported by the SIM Hub PWA")
    e.add_argument("--adapter", choices=["auto", "dji4g", "modemmanager", "mmcli"], default="auto")
    sub.add_parser("run")
    sub.add_parser("once")
    args = p.parse_args()
    if args.command == "enroll":
        enroll(args)
        return
    agent = Agent(Path(args.config), Path(args.db))
    if args.command == "once":
        agent.cycle()
    else:
        agent.run()


if __name__ == "__main__":
    main()
