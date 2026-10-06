#!/usr/bin/env python3
"""SIM Hub Linux Modem Agent.

Supports:
- DJI Gen1/QDC507 through the Quectel USB AT serial port; the external dji4g CLI is optional for documented outbound SMS/network helpers.
- Generic Linux cellular modems through ModemManager/mmcli.

The relay remains blind to SMS plaintext: the agent uses the same v2 AES-GCM
event/command envelopes as the Android Agent, with an independent per-node key.
"""
from __future__ import annotations

import argparse
import base64
import csv
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
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
import serial
from serial.tools import list_ports

VERSION = "0.2.0"
DEFAULT_CONFIG = Path(os.getenv("SIMHUB_MODEM_CONFIG", "/var/lib/simhub-modem/config.json"))
DEFAULT_DB = Path(os.getenv("SIMHUB_MODEM_DB", "/var/lib/simhub-modem/agent.db"))
POLL_SECONDS = max(3, int(os.getenv("SIMHUB_MODEM_POLL_SECONDS", "10")))
MAX_SMS_PER_CYCLE = max(10, min(int(os.getenv("SIMHUB_MODEM_MAX_SMS_PER_CYCLE", "200")), 5000))
TOKEN_ROTATE_AFTER = 60 * 86400


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


class DjiAtAdapter(ModemAdapter):
    name = "dji-at"
    USB_IDS = {(0x2CA3, 0x4006), (0x2C7C, 0x0125)}

    def __init__(self, port: str | None = None) -> None:
        self.port = port or os.getenv("SIMHUB_AT_PORT") or self._detect_port()
        with self._open() as ser:
            self._command(ser, "AT", timeout=3)
            self._initialize(ser)

    def _detect_port(self) -> str:
        candidates: list[tuple[int, str]] = []
        for p in list_ports.comports():
            desc = " ".join(
                str(v or "") for v in (
                    getattr(p, "description", ""),
                    getattr(p, "interface", ""),
                    getattr(p, "hwid", ""),
                )
            )
            score = 0
            if "Quectel USB AT Port".casefold() in desc.casefold():
                score += 250
            elif "Quectel".casefold() in desc.casefold() and "AT".casefold() in desc.casefold():
                score += 180
            if isinstance(getattr(p, "vid", None), int) and isinstance(getattr(p, "pid", None), int):
                if (p.vid, p.pid) in self.USB_IDS:
                    score += 120
            if score:
                candidates.append((score, str(p.device)))
        for _, device in sorted(candidates, reverse=True):
            try:
                with self._open(device) as ser:
                    out = self._command(ser, "ATI", timeout=3)
                    if "OK" in out:
                        return device
            except Exception:
                continue
        raise RuntimeError("DJI/QDC507 Quectel USB AT Port not found; set SIMHUB_AT_PORT or use ModemManager")

    def _open(self, device: str | None = None):
        baud = int(os.getenv("SIMHUB_AT_BAUD", "115200"))
        return serial.Serial(device or self.port, baudrate=baud, timeout=0.2, write_timeout=5)

    @staticmethod
    def _read_until(ser, timeout: float, prompt: bool = False) -> str:
        deadline = time.monotonic() + timeout
        buf = bytearray()
        while time.monotonic() < deadline:
            waiting = getattr(ser, "in_waiting", 0)
            chunk = ser.read(waiting if waiting else 1)
            if chunk:
                buf.extend(chunk)
                text = buf.decode("utf-8", "replace")
                if prompt and ">" in text:
                    return text
                if "\r\nOK\r\n" in text or "\nOK\r" in text:
                    return text
                if "+CMS ERROR" in text or "+CME ERROR" in text or "\r\nERROR\r\n" in text:
                    raise RuntimeError(text.strip()[-500:])
            else:
                time.sleep(0.03)
        text = buf.decode("utf-8", "replace")
        if prompt and ">" in text:
            return text
        raise TimeoutError(f"AT response timeout: {text[-300:]}")

    def _command(self, ser, command: str, timeout: float = 5) -> str:
        ser.reset_input_buffer()
        ser.write((command + "\r").encode("ascii"))
        ser.flush()
        return self._read_until(ser, timeout)

    def _initialize(self, ser) -> None:
        for command in (
            "ATE0",
            "AT+CMGF=1",
            'AT+CSCS="GSM"',
            "AT+CSDH=1",
            'AT+CPMS="ME","ME","ME"',
            "AT+CNMI=2,1,0,0,0",
        ):
            try:
                self._command(ser, command, timeout=4)
            except Exception:
                if command.startswith("AT+CPMS"):
                    self._command(ser, 'AT+CPMS="SM","SM","SM"', timeout=4)
                elif command.startswith("AT+CNMI"):
                    continue
                else:
                    raise

    @staticmethod
    def _timestamp(value: str) -> int:
        raw = (value or "").strip().strip('"')
        m = re.fullmatch(r"(\d{2}/\d{2}/\d{2},\d{2}:\d{2}:\d{2})([+-])(\d{2})", raw)
        try:
            if m:
                offset = int(m.group(3)) * 15 * (1 if m.group(2) == "+" else -1)
                dt = datetime.strptime(m.group(1), "%y/%m/%d,%H:%M:%S").replace(tzinfo=timezone(timedelta(minutes=offset)))
                return int(dt.timestamp())
            return int(datetime.strptime(raw, "%y/%m/%d,%H:%M:%S").timestamp())
        except Exception:
            return now()

    def fingerprint(self) -> str:
        values: list[str] = []
        with self._open() as ser:
            self._initialize(ser)
            for command in ("AT+QCCID", "AT+CCID", "AT+CIMI", "AT+CGMM"):
                try:
                    response = self._command(ser, command, timeout=4)
                    found = re.findall(r"[0-9A-Za-z._-]{8,}", response)
                    if found:
                        values.extend(found)
                except Exception:
                    continue
        if not values:
            values = [self.port]
        return hashlib.sha256("|".join(values).encode()).hexdigest()

    def state(self) -> dict[str, Any]:
        carrier = ""
        network = "CELLULAR"
        level = None
        signal_dbm = None
        rsrp = rsrq = sinr = None
        with self._open() as ser:
            self._initialize(ser)
            try:
                cops = self._command(ser, "AT+COPS?", timeout=4)
                m = re.search(r'\+COPS:\s*\d+,\d+,"([^"]*)"', cops)
                if m:
                    carrier = decode_ucs2(m.group(1))
            except Exception:
                pass
            try:
                csq = self._command(ser, "AT+CSQ", timeout=4)
                m = re.search(r"\+CSQ:\s*(\d+)", csq)
                if m and 0 <= int(m.group(1)) <= 31:
                    q = int(m.group(1))
                    signal_dbm = -113 + 2 * q
                    level = 4 if signal_dbm >= -85 else 3 if signal_dbm >= -95 else 2 if signal_dbm >= -105 else 1 if signal_dbm >= -115 else 0
            except Exception:
                pass
            try:
                qnw = self._command(ser, "AT+QNWINFO", timeout=4)
                m = re.search(r'\+QNWINFO:\s*"([^"]+)"', qnw)
                if m:
                    network = m.group(1)
            except Exception:
                pass
            try:
                qeng = self._command(ser, 'AT+QENG="servingcell"', timeout=4)
                line = next((x for x in qeng.splitlines() if "+QENG:" in x), "")
                fields = [x.strip().strip('"') for x in line.split(",")]
                if len(fields) >= 17 and "LTE" in fields[2].upper():
                    def number_at(index: int):
                        try:
                            return float(fields[index])
                        except Exception:
                            return None
                    rsrp = number_at(13)
                    rsrq = number_at(14)
                    rssi = number_at(15)
                    sinr = number_at(16)
                    if signal_dbm is None and rssi is not None:
                        signal_dbm = rssi
            except Exception:
                pass
        out = {
            "carrierName": carrier,
            "displayName": "DJI 4G / QDC507",
            "serviceState": "IN_SERVICE" if carrier or signal_dbm is not None else "UNKNOWN",
            "networkType": network,
            "signalLevel": level,
            "signalDbm": signal_dbm,
        }
        if rsrp is not None:
            out["signalRsrp"] = rsrp
        if rsrq is not None:
            out["signalRsrq"] = rsrq
        if sinr is not None:
            out["signalSinr"] = sinr
        return out

    def list_sms(self) -> list[SmsRecord]:
        with self._open() as ser:
            self._initialize(ser)
            raw = self._command(ser, 'AT+CMGL="ALL"', timeout=15)
        lines = [x.rstrip("\r") for x in raw.splitlines()]
        records: list[SmsRecord] = []
        i = 0
        while i < len(lines):
            line = lines[i].strip()
            if not line.startswith("+CMGL:"):
                i += 1
                continue
            try:
                fields = next(csv.reader([line.split(":", 1)[1].strip()], skipinitialspace=True))
                index = str(fields[0]).strip()
                sender = decode_ucs2(fields[2] if len(fields) > 2 else "")
                stamp = fields[4] if len(fields) > 4 else ""
            except Exception:
                i += 1
                continue
            body_lines: list[str] = []
            i += 1
            while i < len(lines):
                candidate = lines[i].strip()
                if candidate.startswith("+CMGL:") or candidate in {"OK", "ERROR"}:
                    break
                if candidate:
                    body_lines.append(candidate)
                i += 1
            body = decode_ucs2("\n".join(body_lines))
            occurred = self._timestamp(stamp)
            digest = hashlib.sha256((index + "\0" + sender + "\0" + body + "\0" + stamp).encode()).hexdigest()[:20]
            records.append(SmsRecord(local_id=f"dji-at-{digest}", sender=sender, body=body, occurred_at=occurred, ref=index))
        return records[:MAX_SMS_PER_CYCLE]

    def _direct_send(self, to: str, body: str) -> dict[str, Any]:
        with self._open() as ser:
            self._initialize(ser)
            unicode_mode = any(ord(ch) > 127 for ch in body)
            if unicode_mode:
                self._command(ser, 'AT+CSCS="UCS2"', timeout=4)
                self._command(ser, "AT+CSMP=17,167,0,8", timeout=4)
                number = to.encode("utf-16-be").hex().upper()
                content = body.encode("utf-16-be").hex().upper()
            else:
                self._command(ser, 'AT+CSCS="GSM"', timeout=4)
                number = to
                content = body
            ser.reset_input_buffer()
            ser.write((f'AT+CMGS="{number}"\r').encode("ascii"))
            ser.flush()
            self._read_until(ser, 8, prompt=True)
            ser.write(content.encode("ascii", "strict") + b"\x1a")
            ser.flush()
            result = self._read_until(ser, 90)
        match = re.search(r"\+CMGS:\s*(\d+)", result)
        return {"adapter": self.name, "messageRef": int(match.group(1)) if match else None}

    def send_sms(self, to: str, body: str) -> dict[str, Any]:
        if shutil.which("dji4g"):
            try:
                out = run_command(["dji4g", "sms", "send", to, body], timeout=90)
                match = re.search(r"\+CMGS:\s*(\d+)", out)
                return {"adapter": "dji4g", "messageRef": int(match.group(1)) if match else None}
            except Exception:
                pass
        return self._direct_send(to, body)

    def delete_sms(self, sms: SmsRecord) -> None:
        if not sms.ref or not str(sms.ref).isdigit():
            return
        with self._open() as ser:
            self._initialize(ser)
            self._command(ser, "AT+CMGD=" + str(sms.ref), timeout=8)


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
    if n in {"dji4g","dji-at","dji"}:
        return DjiAtAdapter()
    if n in {"mmcli", "modemmanager"}:
        return MmcliAdapter()
    try:
        return DjiAtAdapter()
    except Exception:
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

    def rotate_device_token(self) -> None:
        ts=now()
        if self.config.get("tokenRotationPending"):
            self.relay.device_token=str(self.config["deviceToken"])
            committed=self.relay.request("POST",f"/api/v1/devices/{self.config['deviceId']}/token/commit",{})
            self.config["tokenIssuedAt"]=int(committed.get("tokenIssuedAt",ts))
            self.config["tokenRotationPending"]=False
            atomic_write_json(self.config_path,self.config)
            return
        issued=int(self.config.get("tokenIssuedAt",0) or 0)
        if issued>0 and ts-issued<TOKEN_ROTATE_AFTER:
            return
        prepared=self.relay.request("POST",f"/api/v1/devices/{self.config['deviceId']}/token/prepare",{})
        self.config["deviceToken"]=str(prepared["deviceToken"])
        self.config["tokenRotationPending"]=True
        atomic_write_json(self.config_path,self.config)
        self.relay.device_token=str(self.config["deviceToken"])
        committed=self.relay.request("POST",f"/api/v1/devices/{self.config['deviceId']}/token/commit",{})
        self.config["tokenIssuedAt"]=int(committed.get("tokenIssuedAt",ts))
        self.config["tokenRotationPending"]=False
        atomic_write_json(self.config_path,self.config)

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
        self.rotate_device_token()
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
        "tokenIssuedAt": int(response.get("tokenIssuedAt", now())),
        "tokenRotationPending": False,
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
    e.add_argument("--adapter", choices=["auto", "dji-at", "dji4g", "modemmanager", "mmcli"], default="auto")
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
