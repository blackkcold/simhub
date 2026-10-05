#!/usr/bin/env python3
"""SIM Hub personal relay server. Python standard library only."""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import logging
import mimetypes
import os
import re
import secrets
import sqlite3
import struct
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

APP_VERSION = "0.1.0"
BIND = os.getenv("SIMHUB_BIND", "0.0.0.0")
PORT = int(os.getenv("SIMHUB_PORT", "8787"))
DB_PATH = Path(os.getenv("SIMHUB_DB", "/data/simhub.db"))
WEB_ROOT = Path(os.getenv("SIMHUB_WEB_ROOT", str(Path(__file__).resolve().parents[1] / "web")))
SCHEMA_PATH = Path(__file__).with_name("schema.sql")
PUBLIC_BASE_URL = os.getenv("SIMHUB_PUBLIC_BASE_URL", "").rstrip("/")
ADMIN_TOKEN = os.getenv("SIMHUB_ADMIN_TOKEN", "")
TOTP_SECRET = os.getenv("SIMHUB_TOTP_SECRET", "").strip().replace(" ", "")
ENROLL_TTL = int(os.getenv("SIMHUB_ENROLL_TTL", "600"))
COMMAND_TTL = int(os.getenv("SIMHUB_COMMAND_TTL", "120"))
OFFLINE_AFTER = int(os.getenv("SIMHUB_OFFLINE_AFTER", "180"))
NOTIFY_WEBHOOK = os.getenv("SIMHUB_NOTIFY_WEBHOOK_URL", "").strip()
NOTIFY_BEARER = os.getenv("SIMHUB_NOTIFY_WEBHOOK_BEARER", "").strip()
OTA_FILE = Path(os.getenv("SIMHUB_OTA_FILE", "/data/ota.json"))
MAX_JSON_BODY = 1_048_576
ALLOWED_COMMANDS = {
    "sms.send",
    "sms.sync_history",
    "device.refresh_state",
    "subscription.refresh",
    "diagnostics.request",
    "ota.check",
}
PHONE_COMMAND_PREFIXES = ("call.", "dialer.", "phone.")
TOKEN_RE = re.compile(r"^[A-Za-z0-9_\-\.~]{20,512}$")
log = logging.getLogger("simhub")
logging.basicConfig(level=os.getenv("SIMHUB_LOG_LEVEL", "INFO"), format="%(asctime)s %(levelname)s %(message)s")


def now() -> int:
    return int(time.time())


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def safe_json_loads(value: str, default: Any) -> Any:
    try:
        return json.loads(value)
    except Exception:
        return default


def new_token(nbytes: int = 36) -> str:
    return secrets.token_urlsafe(nbytes)


def open_db() -> sqlite3.Connection:
    con = sqlite3.connect(DB_PATH, timeout=10)
    con.row_factory = sqlite3.Row
    con.execute("PRAGMA foreign_keys=ON")
    con.execute("PRAGMA busy_timeout=5000")
    return con


def init_db() -> None:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    with open_db() as con:
        con.executescript(SCHEMA_PATH.read_text("utf-8"))


def audit(action: str, target: str, result: str, ip: str) -> None:
    try:
        with open_db() as con:
            con.execute(
                "INSERT INTO audit(occurred_at,action,target,result,ip) VALUES(?,?,?,?,?)",
                (now(), action[:80], target[:120], result[:80], ip[:80]),
            )
    except Exception:
        log.exception("audit_write_failed")


def totp_valid(code: str) -> bool:
    if not TOTP_SECRET:
        return True
    if not (code and code.isdigit() and len(code) == 6):
        return False
    try:
        padded = TOTP_SECRET.upper() + "=" * ((8 - len(TOTP_SECRET) % 8) % 8)
        key = base64.b32decode(padded, casefold=True)
    except Exception:
        log.error("Invalid SIMHUB_TOTP_SECRET")
        return False
    counter = int(time.time() // 30)
    for drift in (-1, 0, 1):
        msg = struct.pack(">Q", counter + drift)
        digest = hmac.new(key, msg, hashlib.sha1).digest()
        offset = digest[-1] & 0x0F
        val = (struct.unpack(">I", digest[offset:offset+4])[0] & 0x7FFFFFFF) % 1_000_000
        if hmac.compare_digest(f"{val:06d}", code):
            return True
    return False


def admin_auth(headers) -> bool:
    auth = headers.get("Authorization", "")
    if not auth.startswith("Bearer "):
        return False
    presented = auth[7:]
    if not ADMIN_TOKEN or not hmac.compare_digest(presented, ADMIN_TOKEN):
        return False
    return totp_valid(headers.get("X-SimHub-TOTP", ""))


def device_for_token(device_id: str, token: str) -> sqlite3.Row | None:
    if not token or not TOKEN_RE.match(token):
        return None
    with open_db() as con:
        row = con.execute("SELECT * FROM devices WHERE id=?", (device_id,)).fetchone()
    if not row or row["revoked_at"] is not None:
        return None
    if not hmac.compare_digest(row["token_hash"], sha256_text(token)):
        return None
    return row


def validate_cipher(obj: Any) -> bool:
    if not isinstance(obj, dict):
        return False
    return (
        obj.get("v") == 1
        and obj.get("alg") == "A256GCM"
        and isinstance(obj.get("iv"), str)
        and 8 <= len(obj["iv"]) <= 64
        and isinstance(obj.get("ct"), str)
        and 16 <= len(obj["ct"]) <= 500_000
    )


def notify_async(kind: str, device_name: str, has_otp: bool) -> None:
    if not NOTIFY_WEBHOOK:
        return
    # Deliberately no sender, body, OTP value, phone number, or ciphertext.
    payload = {
        "source": "simhub",
        "kind": kind,
        "title": "SIM Hub",
        "message": "New OTP received" if has_otp else ("New SMS received" if kind.startswith("sms.") else "SIM Hub event"),
        "device": device_name,
        "occurredAt": now(),
    }
    def send() -> None:
        try:
            req = urllib.request.Request(
                NOTIFY_WEBHOOK,
                data=json.dumps(payload).encode(),
                headers={"Content-Type": "application/json", **({"Authorization": f"Bearer {NOTIFY_BEARER}"} if NOTIFY_BEARER else {})},
                method="POST",
            )
            urllib.request.urlopen(req, timeout=5).read(1024)
        except Exception as exc:
            log.warning("notification_webhook_failed %s", type(exc).__name__)
    threading.Thread(target=send, daemon=True).start()


class SimHubHandler(BaseHTTPRequestHandler):
    server_version = "SimHubRelay/0.1"
    sys_version = ""

    def log_message(self, fmt: str, *args) -> None:
        # Avoid raw URLs (which may contain enrollment data) and headers in logs.
        log.info("http %s %s -> %s", self.command, urllib.parse.urlsplit(self.path).path, str(args[1]) if len(args) > 1 else "")

    @property
    def ip(self) -> str:
        # Do not trust X-Forwarded-For here; audit the direct peer only.
        return self.client_address[0]

    def security_headers(self) -> None:
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")

    def send_json(self, status: int, obj: Any) -> None:
        data = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.security_headers()
        self.end_headers()
        self.wfile.write(data)

    def send_error_json(self, status: int, code: str, message: str) -> None:
        self.send_json(status, {"error": code, "message": message})

    def read_json(self) -> dict[str, Any] | None:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self.send_error_json(411, "length_required", "Invalid Content-Length")
            return None
        if length <= 0 or length > MAX_JSON_BODY:
            self.send_error_json(413 if length > MAX_JSON_BODY else 400, "invalid_body", "JSON body required and must be <= 1 MiB")
            return None
        try:
            data = json.loads(self.rfile.read(length))
            if not isinstance(data, dict):
                raise ValueError()
            return data
        except Exception:
            self.send_error_json(400, "invalid_json", "Body must be a JSON object")
            return None

    def require_admin(self) -> bool:
        if admin_auth(self.headers):
            return True
        audit("auth.admin", "", "denied", self.ip)
        self.send_error_json(401, "unauthorized", "Valid admin token and TOTP (if configured) required")
        return False

    def require_device(self, device_id: str) -> sqlite3.Row | None:
        auth = self.headers.get("Authorization", "")
        if not auth.startswith("Device "):
            self.send_error_json(401, "unauthorized", "Device token required")
            return None
        row = device_for_token(device_id, auth[7:])
        if not row:
            audit("auth.device", device_id, "denied", self.ip)
            self.send_error_json(401, "unauthorized", "Invalid or revoked device token")
            return None
        return row

    def route(self) -> tuple[str, list[str], dict[str, list[str]]]:
        parsed = urllib.parse.urlsplit(self.path)
        parts = [urllib.parse.unquote(p) for p in parsed.path.split("/") if p]
        return parsed.path, parts, urllib.parse.parse_qs(parsed.query)

    def do_OPTIONS(self) -> None:
        self.send_response(204)
        self.security_headers()
        self.end_headers()

    def do_GET(self) -> None:
        path, p, q = self.route()
        if path == "/healthz":
            self.send_json(200, {"ok": True, "version": APP_VERSION, "time": now()})
            return
        if path == "/api/v1/auth/check":
            if not self.require_admin(): return
            self.send_json(200, {"ok": True, "totpRequired": bool(TOTP_SECRET)})
            return
        if path == "/api/v1/devices":
            if not self.require_admin(): return
            self.get_devices(); return
        if len(p) == 5 and p[:3] == ["api", "v1", "devices"] and p[4] == "state":
            if not self.require_admin(): return
            self.get_device_state(p[3]); return
        if path == "/api/v1/events":
            if not self.require_admin(): return
            self.get_events(q); return
        if path == "/api/v1/audit":
            if not self.require_admin(): return
            self.get_audit(q); return
        if len(p) == 6 and p[:3] == ["api", "v1", "devices"] and p[4:6] == ["commands", "pending"]:
            if not self.require_device(p[3]): return
            self.get_pending_commands(p[3], q); return
        if path == "/api/v1/ota":
            # Device or admin may query OTA. Device id is optional via header.
            if not (admin_auth(self.headers) or self.device_auth_from_headers()):
                self.send_error_json(401, "unauthorized", "Authentication required"); return
            self.get_ota(); return
        if path.startswith("/api/"):
            self.send_error_json(404, "not_found", "API endpoint not found")
            return
        self.serve_static(path)

    def do_POST(self) -> None:
        path, p, q = self.route()
        if path == "/api/v1/enrollments":
            if not self.require_admin(): return
            body = self.read_json()
            if body is None: return
            self.create_enrollment(body); return
        if path == "/api/v1/enroll":
            body = self.read_json()
            if body is None: return
            self.enroll_device(body); return
        if len(p) == 5 and p[:3] == ["api", "v1", "devices"] and p[4] == "events":
            if not self.require_device(p[3]): return
            body = self.read_json()
            if body is None: return
            self.ingest_event(p[3], body); return
        if len(p) == 5 and p[:3] == ["api", "v1", "devices"] and p[4] == "state":
            if not self.require_device(p[3]): return
            body = self.read_json()
            if body is None: return
            self.put_device_state(p[3], body); return
        if len(p) == 5 and p[:3] == ["api", "v1", "devices"] and p[4] == "heartbeat":
            if not self.require_device(p[3]): return
            body = self.read_json()
            if body is None: return
            self.heartbeat(p[3], body); return
        if len(p) == 5 and p[:3] == ["api", "v1", "devices"] and p[4] == "commands":
            if not self.require_admin(): return
            body = self.read_json()
            if body is None: return
            self.create_command(p[3], body); return
        if len(p) == 7 and p[:3] == ["api", "v1", "devices"] and p[4] == "commands" and p[6] == "ack":
            if not self.require_device(p[3]): return
            body = self.read_json()
            if body is None: return
            self.ack_command(p[3], p[5], body); return
        self.send_error_json(404, "not_found", "API endpoint not found")

    def do_PATCH(self) -> None:
        path, p, q = self.route()
        if len(p) == 4 and p[:3] == ["api", "v1", "devices"]:
            if not self.require_admin(): return
            body = self.read_json()
            if body is None: return
            self.patch_device(p[3], body); return
        self.send_error_json(404, "not_found", "API endpoint not found")

    def device_auth_from_headers(self) -> bool:
        device_id = self.headers.get("X-SimHub-Device-Id", "")
        auth = self.headers.get("Authorization", "")
        return bool(device_id and auth.startswith("Device ") and device_for_token(device_id, auth[7:]))

    def create_enrollment(self, body: dict[str, Any]) -> None:
        eid = str(uuid.uuid4())
        token = new_token()
        ts = now()
        ttl = max(60, min(int(body.get("ttlSeconds", ENROLL_TTL)), 3600))
        with open_db() as con:
            con.execute("INSERT INTO enrollment_tokens(id,token_hash,created_at,expires_at) VALUES(?,?,?,?)", (eid, sha256_text(token), ts, ts + ttl))
        audit("enrollment.create", eid, "ok", self.ip)
        self.send_json(201, {"id": eid, "token": token, "expiresAt": ts + ttl, "server": PUBLIC_BASE_URL or None})

    def enroll_device(self, body: dict[str, Any]) -> None:
        token = str(body.get("token", ""))
        if not TOKEN_RE.match(token):
            self.send_error_json(400, "invalid_token", "Enrollment token malformed"); return
        token_hash = sha256_text(token)
        ts = now()
        with open_db() as con:
            row = con.execute("SELECT * FROM enrollment_tokens WHERE token_hash=?", (token_hash,)).fetchone()
            if not row or row["used_at"] is not None or row["expires_at"] < ts:
                audit("enrollment.consume", "", "denied", self.ip)
                self.send_error_json(401, "invalid_enrollment", "Enrollment token invalid, expired, or already used"); return
            device_id = str(uuid.uuid4())
            device_token = new_token(48)
            name = str(body.get("name") or body.get("model") or "Android SIM Node")[:80]
            con.execute(
                "INSERT INTO devices(id,token_hash,name,group_name,model,os_version,app_version,created_at,last_seen_at) VALUES(?,?,?,?,?,?,?,?,?)",
                (device_id, sha256_text(device_token), name, str(body.get("group", ""))[:80], str(body.get("model", ""))[:120], str(body.get("osVersion", ""))[:40], str(body.get("appVersion", ""))[:40], ts, ts),
            )
            con.execute("UPDATE enrollment_tokens SET used_at=? WHERE id=?", (ts, row["id"]))
        audit("enrollment.consume", device_id, "ok", self.ip)
        self.send_json(201, {"deviceId": device_id, "deviceToken": device_token, "serverTime": ts})

    def get_devices(self) -> None:
        ts = now()
        with open_db() as con:
            rows = con.execute("SELECT d.*, s.state_json, s.updated_at AS state_updated_at FROM devices d LEFT JOIN device_state s ON s.device_id=d.id ORDER BY d.created_at DESC").fetchall()
        out=[]
        for r in rows:
            last=r["last_seen_at"] or 0
            out.append({
                "id":r["id"], "name":r["name"], "group":r["group_name"], "model":r["model"], "osVersion":r["os_version"], "appVersion":r["app_version"],
                "createdAt":r["created_at"], "lastSeenAt":r["last_seen_at"], "online": bool(not r["revoked_at"] and ts-last <= OFFLINE_AFTER), "revoked": bool(r["revoked_at"]),
                "state": safe_json_loads(r["state_json"], {}) if r["state_json"] else None,
            })
        self.send_json(200, {"devices":out, "offlineAfterSeconds":OFFLINE_AFTER})

    def patch_device(self, device_id: str, body: dict[str, Any]) -> None:
        sets=[]; vals=[]
        if "name" in body:
            sets.append("name=?"); vals.append(str(body["name"])[:80])
        if "group" in body:
            sets.append("group_name=?"); vals.append(str(body["group"])[:80])
        if body.get("revoke") is True:
            sets.append("revoked_at=?"); vals.append(now())
        if body.get("restore") is True:
            # Restoring a token is intentionally unsupported; re-enroll instead.
            self.send_error_json(400, "reenroll_required", "Revoked devices must be re-enrolled"); return
        if not sets:
            self.send_error_json(400, "no_changes", "No supported fields supplied"); return
        vals.append(device_id)
        with open_db() as con:
            cur=con.execute(f"UPDATE devices SET {','.join(sets)} WHERE id=?", vals)
        if not cur.rowcount:
            self.send_error_json(404, "device_not_found", "Device not found"); return
        audit("device.patch", device_id, "ok", self.ip)
        self.send_json(200, {"ok":True})

    def heartbeat(self, device_id: str, body: dict[str, Any]) -> None:
        ts=now()
        with open_db() as con:
            con.execute("UPDATE devices SET last_seen_at=?, app_version=COALESCE(NULLIF(?,''),app_version), os_version=COALESCE(NULLIF(?,''),os_version) WHERE id=?", (ts, str(body.get("appVersion", ""))[:40], str(body.get("osVersion", ""))[:40], device_id))
        self.send_json(200, {"ok":True,"serverTime":ts})

    def ingest_event(self, device_id: str, body: dict[str, Any]) -> None:
        event_id=str(body.get("eventId", ""))
        kind=str(body.get("kind", ""))[:80]
        cipher=body.get("ciphertext")
        if not event_id or len(event_id)>160 or not kind or not validate_cipher(cipher):
            self.send_error_json(400,"invalid_event","eventId, kind and valid ciphertext are required"); return
        occurred=int(body.get("occurredAt", now()))
        if abs(occurred-now()) > 365*86400:
            occurred=now()
        has_otp=bool(body.get("hasOtp",False))
        sub=str(body.get("subscriptionId", ""))[:80]
        metadata=body.get("metadata", {})
        if not isinstance(metadata, dict): metadata={}
        # Strictly strip dangerous/sensitive accidental fields from relay-readable metadata.
        for forbidden in ("body","text","otp","code","sender","recipient","phone","number","contact"):
            metadata.pop(forbidden, None)
        received=now()
        try:
            with open_db() as con:
                cur=con.execute("INSERT INTO events(id,device_id,kind,occurred_at,received_at,subscription_id,has_otp,metadata_json,ciphertext_json) VALUES(?,?,?,?,?,?,?,?,?)",
                    (event_id,device_id,kind,occurred,received,sub,1 if has_otp else 0,json.dumps(metadata,separators=(",",":")),json.dumps(cipher,separators=(",",":"))))
                seq=cur.lastrowid
                con.execute("UPDATE devices SET last_seen_at=? WHERE id=?", (received,device_id))
                d=con.execute("SELECT name FROM devices WHERE id=?",(device_id,)).fetchone()
        except sqlite3.IntegrityError:
            with open_db() as con:
                row=con.execute("SELECT seq FROM events WHERE id=? AND device_id=?",(event_id,device_id)).fetchone()
            if row:
                self.send_json(200,{"accepted":True,"duplicate":True,"eventId":event_id,"serverSequence":row["seq"]}); return
            self.send_error_json(409,"event_conflict","eventId already exists"); return
        notify_async(kind, d["name"] if d else "SIM Node", has_otp)
        self.send_json(201,{"accepted":True,"eventId":event_id,"serverSequence":seq})

    def get_events(self, q: dict[str,list[str]]) -> None:
        try:
            since=max(0,int(q.get("since",["0"])[0])); limit=max(1,min(int(q.get("limit",["200"])[0]),1000))
        except ValueError:
            self.send_error_json(400,"invalid_query","since/limit must be integers"); return
        device=q.get("device",[""])[0]; kind=q.get("kind",[""])[0]
        where=["seq>?"]; args=[since]
        if device: where.append("device_id=?"); args.append(device)
        if kind: where.append("kind=?"); args.append(kind)
        args.append(limit)
        with open_db() as con:
            rows=con.execute(f"SELECT * FROM events WHERE {' AND '.join(where)} ORDER BY seq ASC LIMIT ?",args).fetchall()
        events=[]
        for r in rows:
            events.append({"seq":r["seq"],"eventId":r["id"],"deviceId":r["device_id"],"kind":r["kind"],"occurredAt":r["occurred_at"],"receivedAt":r["received_at"],"subscriptionId":r["subscription_id"],"hasOtp":bool(r["has_otp"]),"metadata":safe_json_loads(r["metadata_json"],{}),"ciphertext":safe_json_loads(r["ciphertext_json"],{})})
        self.send_json(200,{"events":events,"nextSince":events[-1]["seq"] if events else since})

    def put_device_state(self, device_id: str, body: dict[str, Any]) -> None:
        # State contains operational telemetry only; reject likely message content fields.
        forbidden={"messages","sms","body","otp","contacts"}
        if forbidden.intersection(body):
            self.send_error_json(400,"invalid_state","Message/contact content is not accepted in device state"); return
        raw=json.dumps(body,ensure_ascii=False,separators=(",",":"))
        if len(raw)>200_000:
            self.send_error_json(413,"state_too_large","State exceeds 200 KB"); return
        ts=now()
        with open_db() as con:
            con.execute("INSERT INTO device_state(device_id,state_json,updated_at) VALUES(?,?,?) ON CONFLICT(device_id) DO UPDATE SET state_json=excluded.state_json, updated_at=excluded.updated_at",(device_id,raw,ts))
            con.execute("UPDATE devices SET last_seen_at=? WHERE id=?",(ts,device_id))
        self.send_json(200,{"ok":True,"updatedAt":ts})

    def get_device_state(self, device_id: str) -> None:
        with open_db() as con:
            r=con.execute("SELECT state_json,updated_at FROM device_state WHERE device_id=?",(device_id,)).fetchone()
        if not r:
            self.send_json(200,{"state":None,"updatedAt":None}); return
        self.send_json(200,{"state":safe_json_loads(r["state_json"],{}),"updatedAt":r["updated_at"]})

    def create_command(self, device_id: str, body: dict[str, Any]) -> None:
        ctype=str(body.get("type", ""))
        if ctype.startswith(PHONE_COMMAND_PREFIXES) or ctype not in ALLOWED_COMMANDS:
            self.send_error_json(400,"command_not_allowed","Only SIM/SMS/device commands are supported; phone/call commands are intentionally disabled"); return
        cipher=body.get("ciphertext")
        if not validate_cipher(cipher):
            self.send_error_json(400,"invalid_ciphertext","Valid AES-GCM ciphertext envelope required"); return
        cid=str(body.get("commandId") or uuid.uuid4())
        idem=str(body.get("idempotencyKey") or cid)
        if len(cid)>160 or len(idem)>160:
            self.send_error_json(400,"invalid_id","Command IDs too long"); return
        ts=now()
        exp=int(body.get("expiresAt") or (ts+COMMAND_TTL))
        if exp<=ts or exp>ts+86400:
            self.send_error_json(400,"invalid_expiry","Command expiry must be in the next 24h"); return
        with open_db() as con:
            d=con.execute("SELECT id,revoked_at FROM devices WHERE id=?",(device_id,)).fetchone()
            if not d or d["revoked_at"]:
                self.send_error_json(404,"device_not_found","Active device not found"); return
            try:
                cur=con.execute("INSERT INTO commands(id,device_id,type,created_at,expires_at,idempotency_key,ciphertext_json) VALUES(?,?,?,?,?,?,?)",
                    (cid,device_id,ctype,ts,exp,idem,json.dumps(cipher,separators=(",",":"))))
                seq=cur.lastrowid
            except sqlite3.IntegrityError:
                r=con.execute("SELECT seq,id,state FROM commands WHERE idempotency_key=?",(idem,)).fetchone()
                self.send_json(200,{"accepted":True,"duplicate":True,"commandId":r["id"],"serverSequence":r["seq"],"state":r["state"]}); return
        audit("command.create",f"{device_id}:{ctype}","ok",self.ip)
        self.send_json(201,{"accepted":True,"commandId":cid,"serverSequence":seq,"state":"queued"})

    def get_pending_commands(self, device_id: str, q: dict[str,list[str]]) -> None:
        ts=now()
        try: limit=max(1,min(int(q.get("limit",["50"])[0]),200))
        except ValueError: limit=50
        with open_db() as con:
            con.execute("UPDATE commands SET state='expired' WHERE device_id=? AND state IN ('queued','dispatched') AND expires_at<=?",(device_id,ts))
            rows=con.execute("SELECT * FROM commands WHERE device_id=? AND state IN ('queued','dispatched') AND expires_at>? ORDER BY seq ASC LIMIT ?",(device_id,ts,limit)).fetchall()
            ids=[r["id"] for r in rows]
            if ids:
                con.executemany("UPDATE commands SET state='dispatched' WHERE id=? AND state='queued'",[(i,) for i in ids])
            con.execute("UPDATE devices SET last_seen_at=? WHERE id=?",(ts,device_id))
        out=[{"seq":r["seq"],"commandId":r["id"],"type":r["type"],"createdAt":r["created_at"],"expiresAt":r["expires_at"],"idempotencyKey":r["idempotency_key"],"ciphertext":safe_json_loads(r["ciphertext_json"],{})} for r in rows]
        self.send_json(200,{"commands":out,"serverTime":ts})

    def ack_command(self, device_id: str, command_id: str, body: dict[str, Any]) -> None:
        state=str(body.get("state", ""))
        if state not in {"succeeded","failed","rejected","expired"}:
            self.send_error_json(400,"invalid_state","Invalid command terminal state"); return
        result=body.get("result",{})
        if not isinstance(result,dict): result={}
        # Strip content-like values from relay-readable result.
        for k in list(result):
            if k.lower() in {"body","text","otp","code","recipient","sender","number","phone"}: result.pop(k,None)
        with open_db() as con:
            cur=con.execute("UPDATE commands SET state=?,ack_at=?,result_json=? WHERE id=? AND device_id=?",(state,now(),json.dumps(result,separators=(",",":")),command_id,device_id))
        if not cur.rowcount:
            self.send_error_json(404,"command_not_found","Command not found"); return
        self.send_json(200,{"ok":True})

    def get_audit(self, q: dict[str,list[str]]) -> None:
        try: limit=max(1,min(int(q.get("limit",["200"])[0]),1000))
        except ValueError: limit=200
        with open_db() as con:
            rows=con.execute("SELECT * FROM audit ORDER BY seq DESC LIMIT ?",(limit,)).fetchall()
        self.send_json(200,{"audit":[dict(r) for r in rows]})

    def get_ota(self) -> None:
        if not OTA_FILE.exists():
            self.send_json(200,{"available":False}); return
        try:
            data=json.loads(OTA_FILE.read_text("utf-8"))
            if not isinstance(data,dict): raise ValueError()
            # expected keys: versionCode, versionName, url, sha256, notes
            self.send_json(200,{"available":True,**data})
        except Exception:
            self.send_error_json(500,"ota_invalid","OTA metadata file is invalid")

    def serve_static(self, path: str) -> None:
        rel = "index.html" if path in ("", "/") else path.lstrip("/")
        rel = rel.split("?",1)[0]
        candidate=(WEB_ROOT/rel).resolve()
        try: candidate.relative_to(WEB_ROOT.resolve())
        except ValueError:
            self.send_error(403); return
        if not candidate.is_file():
            # SPA fallback, except obvious file paths.
            if "." not in Path(rel).name:
                candidate=WEB_ROOT/"index.html"
            else:
                self.send_error(404); return
        data=candidate.read_bytes()
        ctype=mimetypes.guess_type(str(candidate))[0] or "application/octet-stream"
        self.send_response(200)
        self.send_header("Content-Type",ctype + ("; charset=utf-8" if ctype.startswith("text/") or ctype in {"application/javascript","application/json"} else ""))
        self.send_header("Content-Length",str(len(data)))
        if candidate.name in {"sw.js","manifest.webmanifest"}:
            self.send_header("Cache-Control","no-cache")
        else:
            self.send_header("Cache-Control","no-store")
        self.security_headers()
        self.end_headers(); self.wfile.write(data)


def main() -> None:
    if len(ADMIN_TOKEN) < 32 or ADMIN_TOKEN.startswith("change-me"):
        raise SystemExit("SIMHUB_ADMIN_TOKEN must be set to a strong >=32 character random token")
    if TOTP_SECRET:
        try:
            padded=TOTP_SECRET.upper()+"="*((8-len(TOTP_SECRET)%8)%8); base64.b32decode(padded,casefold=True)
        except Exception as exc:
            raise SystemExit("SIMHUB_TOTP_SECRET must be valid Base32") from exc
    init_db()
    server=ThreadingHTTPServer((BIND,PORT),SimHubHandler)
    server.daemon_threads=True
    log.info("SIM Hub relay %s listening on %s:%s, db=%s",APP_VERSION,BIND,PORT,DB_PATH)
    try: server.serve_forever()
    except KeyboardInterrupt: pass
    finally: server.server_close()

if __name__ == "__main__":
    main()
