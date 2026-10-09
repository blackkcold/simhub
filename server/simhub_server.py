#!/usr/bin/env python3
"""SIM Hub personal relay server. Python standard library only."""
from __future__ import annotations

import base64
import hashlib
import hmac
import ipaddress
import json
import logging
import mimetypes
import os
import re
import secrets
import socket
import sqlite3
import struct
import threading
import time
import urllib.parse
import urllib.request
import uuid
from http.cookies import SimpleCookie
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import passkeys
from typing import Any

APP_VERSION = "0.5.3"
SERVER_STARTED_AT = int(time.time())
DEPLOYED_AT = os.getenv("SIMHUB_DEPLOYED_AT", "").strip()
BIND = os.getenv("SIMHUB_BIND", "0.0.0.0")
PORT = int(os.getenv("SIMHUB_PORT", "8787"))
DB_PATH = Path(os.getenv("SIMHUB_DB", "/data/simhub.db"))
WEB_ROOT = Path(os.getenv("SIMHUB_WEB_ROOT", str(Path(__file__).resolve().parents[1] / "web")))
SCHEMA_PATH = Path(__file__).with_name("schema.sql")
PUBLIC_BASE_URL = os.getenv("SIMHUB_PUBLIC_BASE_URL", "").rstrip("/")
ADMIN_TOKEN = os.getenv("SIMHUB_ADMIN_TOKEN", "")
ADMIN_USERNAME = os.getenv("SIMHUB_ADMIN_USERNAME", "admin").strip()
TOTP_SECRET = os.getenv("SIMHUB_TOTP_SECRET", "").strip().replace(" ", "")
ENROLL_TTL = int(os.getenv("SIMHUB_ENROLL_TTL", "600"))
COMMAND_TTL = int(os.getenv("SIMHUB_COMMAND_TTL", "120"))
OFFLINE_AFTER = int(os.getenv("SIMHUB_OFFLINE_AFTER", "180"))
EVENT_RETENTION_DAYS = max(0, int(os.getenv("SIMHUB_EVENT_RETENTION_DAYS", "30")))
AUDIT_RETENTION_DAYS = max(0, int(os.getenv("SIMHUB_AUDIT_RETENTION_DAYS", "180")))
COMMAND_RETENTION_DAYS = max(1, int(os.getenv("SIMHUB_COMMAND_RETENTION_DAYS", "30")))
MAINTENANCE_INTERVAL = max(300, min(int(os.getenv("SIMHUB_MAINTENANCE_INTERVAL", "3600")), 86400))
REQUIRE_TOTP = os.getenv("SIMHUB_REQUIRE_TOTP", "true").lower() in {"1","true","yes","on"}
TRUSTED_PROXY_CIDRS = tuple(x.strip() for x in os.getenv("SIMHUB_TRUSTED_PROXIES", "127.0.0.1/32,::1/128").split(",") if x.strip())
# The loopback-published Docker port is SNATed by docker-proxy to the exact
# bridge gateway address. Trust that one peer only when explicitly enabled.
TRUST_DOCKER_GATEWAY = os.getenv("SIMHUB_TRUST_DOCKER_GATEWAY", "false").lower() in {"1", "true", "yes", "on"}
def docker_gateway_ip() -> str:
    if not TRUST_DOCKER_GATEWAY:
        return ""
    try:
        with open("/proc/net/route", encoding="ascii") as routes:
            for line in list(routes)[1:]:
                fields = line.split()
                if len(fields) > 3 and fields[1] == "00000000" and int(fields[3], 16) & 2:
                    return socket.inet_ntoa(bytes.fromhex(fields[2])[::-1])
    except (OSError, ValueError, IndexError):
        pass
    return ""
DOCKER_GATEWAY_IP = docker_gateway_ip()
SESSION_TTL = max(900, min(int(os.getenv("SIMHUB_SESSION_TTL", "86400")), 604800))
SESSION_IDLE_TTL = max(60, min(int(os.getenv("SIMHUB_SESSION_IDLE_TTL", "28800")), SESSION_TTL))
STEPUP_TTL = max(30, min(int(os.getenv("SIMHUB_STEPUP_TTL", "120")), 600))
MANAGEMENT_ORIGIN = os.getenv("SIMHUB_MANAGEMENT_ORIGIN", PUBLIC_BASE_URL).rstrip("/")
SEPARATE_SURFACES = os.getenv("SIMHUB_SEPARATE_SURFACES", "false").lower() in {"1","true","yes","on"}
MAX_HTTP_CONNECTIONS = max(8, min(int(os.getenv("SIMHUB_MAX_HTTP_CONNECTIONS", "64")), 512))
HTTP_READ_TIMEOUT = max(5, min(int(os.getenv("SIMHUB_HTTP_TIMEOUT", "15")), 120))
MAX_SSE_PER_SESSION = max(1, min(int(os.getenv("SIMHUB_MAX_SSE_PER_SESSION", "3")), 10))
NOTIFY_WEBHOOK = os.getenv("SIMHUB_NOTIFY_WEBHOOK_URL", "").strip()
NOTIFY_BARK_URL = os.getenv("SIMHUB_NOTIFY_BARK_URL", "").strip()
NOTIFY_NTFY_URL = os.getenv("SIMHUB_NOTIFY_NTFY_URL", "").strip()
NOTIFY_NTFY_TOKEN = os.getenv("SIMHUB_NOTIFY_NTFY_TOKEN", "").strip()
NOTIFY_BEARER = os.getenv("SIMHUB_NOTIFY_WEBHOOK_BEARER", "").strip()
NOTIFY_OTP_ONLY = os.getenv("SIMHUB_NOTIFY_OTP_ONLY", "false").lower() in {"1","true","yes","on"}
NOTIFY_MAX_AGE = max(30, min(int(os.getenv("SIMHUB_NOTIFY_MAX_AGE", "300")), 3600))
NOTIFY_DEVICE_IDS = {x.strip() for x in os.getenv("SIMHUB_NOTIFY_DEVICE_IDS", "").split(",") if x.strip()}
PUSH_TICKLE_URL = os.getenv("SIMHUB_PUSH_TICKLE_URL", "").strip()
PUSH_TICKLE_BEARER = os.getenv("SIMHUB_PUSH_TICKLE_BEARER", "").strip()
OTA_FILE = Path(os.getenv("SIMHUB_OTA_FILE", "/data/ota.json"))
MAX_JSON_BODY = 1_048_576
ALLOWED_COMMANDS = {
    "sms.send",
    "sms.sync_history",
    "sms.sync_recent",
    "sms.sync_older",
    "device.network_policy",
    "device.refresh_state",
    "subscription.refresh",
    "diagnostics.request",
    "ota.check",
    "node.rotate_key",
}
PHONE_COMMAND_PREFIXES = ("call.", "dialer.", "phone.")
TOKEN_RE = re.compile(r"^[A-Za-z0-9_\-\.~]{20,512}$")
BOOTSTRAP_PROOF_RE = re.compile(r"^[A-Za-z0-9_-]{43}$")
SESSION_COOKIE = "simhub_session"
log = logging.getLogger("simhub")
logging.basicConfig(level=os.getenv("SIMHUB_LOG_LEVEL", "INFO"), format="%(asctime)s %(levelname)s %(message)s")

_stream_condition = threading.Condition()
_stream_epoch = 0
_auth_lock = threading.Lock()
_auth_failures: dict[str, list[int]] = {}
_auth_gc_at = 0
_rate_entries: dict[tuple[str,str], list[int]] = {}
_rate_gc_at = 0
_sse_lock = threading.Lock()
_sse_clients: dict[str,int] = {}

def rate_allowed(bucket: str, key: str, max_requests: int, window: int = 60) -> bool:
    """Bounded memory limiter; deny new keys when saturated."""
    global _rate_gc_at
    ts = now()
    with _auth_lock:
        if ts - _rate_gc_at >= 30:
            for k, values in list(_rate_entries.items()):
                fresh = [v for v in values if ts - v < window]
                if fresh: _rate_entries[k] = fresh
                else: _rate_entries.pop(k, None)
            _rate_gc_at = ts
        k = (bucket, key[:160])
        if k not in _rate_entries and len(_rate_entries) >= 4096:
            return False
        values = [v for v in _rate_entries.get(k, []) if ts - v < window]
        if len(values) >= max_requests:
            _rate_entries[k] = values
            return False
        values.append(ts)
        _rate_entries[k] = values
        return True

class BoundedHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, address, handler):
        self._slots = threading.BoundedSemaphore(MAX_HTTP_CONNECTIONS)
        super().__init__(address, handler)
    def get_request(self):
        sock, addr = super().get_request()
        sock.settimeout(HTTP_READ_TIMEOUT)
        return sock, addr
    def process_request(self, request, client_address):
        if not self._slots.acquire(blocking=False):
            request.close()
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self._slots.release()
            raise
    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self._slots.release()


class BoundedExecutor:
    def __init__(self,max_workers:int,max_pending:int,name:str) -> None:
        self._pool=ThreadPoolExecutor(max_workers=max_workers,thread_name_prefix=name)
        self._slots=threading.BoundedSemaphore(max_pending)
    def submit(self,fn) -> bool:
        if not self._slots.acquire(blocking=False):
            return False
        try:
            future=self._pool.submit(fn)
        except Exception:
            self._slots.release()
            raise
        future.add_done_callback(lambda _:self._slots.release())
        return True

_outbound_executor=BoundedExecutor(4,64,"simhub-outbound")


def now() -> int:
    return int(time.time())


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def new_token(nbytes: int = 36) -> str:
    return secrets.token_urlsafe(nbytes)


def trusted_proxy(peer: str) -> bool:
    try:
        addr=ipaddress.ip_address(peer)
        return (bool(DOCKER_GATEWAY_IP and peer == DOCKER_GATEWAY_IP) or any(addr in ipaddress.ip_network(cidr,strict=False) for cidr in TRUSTED_PROXY_CIDRS))
    except ValueError:
        return False


def forwarded_client_ip(peer: str, headers) -> str:
    if not trusted_proxy(peer):
        return peer
    raw=headers.get("X-Forwarded-For","")
    candidate=raw.split(",",1)[0].strip() if raw else ""
    try:
        return str(ipaddress.ip_address(candidate)) if candidate else peer
    except ValueError:
        return peer


def safe_json_loads(value: str | None, default: Any) -> Any:
    if value is None:
        return default
    try:
        return json.loads(value)
    except Exception:
        return default


@contextmanager
def open_db():
    con = sqlite3.connect(DB_PATH, timeout=10)
    con.row_factory = sqlite3.Row
    con.execute("PRAGMA foreign_keys=ON")
    con.execute("PRAGMA busy_timeout=5000")
    try:
        yield con
        con.commit()
    except Exception:
        con.rollback()
        raise
    finally:
        con.close()


def _table_sql(con: sqlite3.Connection, name: str) -> str:
    row = con.execute("SELECT sql FROM sqlite_master WHERE type='table' AND name=?", (name,)).fetchone()
    return row["sql"] if row and row["sql"] else ""


def _migrate_v2(con: sqlite3.Connection) -> None:
    events_sql = _table_sql(con, "events")
    if "id TEXT NOT NULL UNIQUE" in events_sql:
        con.execute("ALTER TABLE events RENAME TO events_legacy_v1")
        con.executescript("""
        CREATE TABLE events(
          seq INTEGER PRIMARY KEY AUTOINCREMENT,
          id TEXT NOT NULL,
          device_id TEXT NOT NULL,
          kind TEXT NOT NULL,
          occurred_at INTEGER NOT NULL,
          received_at INTEGER NOT NULL,
          subscription_id TEXT NOT NULL DEFAULT '',
          has_otp INTEGER NOT NULL DEFAULT 0,
          metadata_json TEXT NOT NULL DEFAULT '{}',
          ciphertext_json TEXT NOT NULL,
          FOREIGN KEY(device_id) REFERENCES devices(id),
          UNIQUE(device_id,id)
        );
        INSERT INTO events(seq,id,device_id,kind,occurred_at,received_at,subscription_id,has_otp,metadata_json,ciphertext_json)
        SELECT seq,id,device_id,kind,occurred_at,received_at,subscription_id,has_otp,metadata_json,ciphertext_json FROM events_legacy_v1;
        DROP TABLE events_legacy_v1;
        CREATE INDEX IF NOT EXISTS idx_events_device_seq ON events(device_id,seq);
        CREATE INDEX IF NOT EXISTS idx_events_kind_seq ON events(kind,seq);
        """)
    commands_sql = _table_sql(con, "commands")
    if "idempotency_key TEXT NOT NULL UNIQUE" in commands_sql:
        con.execute("ALTER TABLE commands RENAME TO commands_legacy_v1")
        con.executescript("""
        CREATE TABLE commands(
          seq INTEGER PRIMARY KEY AUTOINCREMENT,
          id TEXT NOT NULL UNIQUE,
          device_id TEXT NOT NULL,
          type TEXT NOT NULL,
          created_at INTEGER NOT NULL,
          expires_at INTEGER NOT NULL,
          idempotency_key TEXT NOT NULL,
          ciphertext_json TEXT NOT NULL,
          state TEXT NOT NULL DEFAULT 'queued',
          ack_at INTEGER,
          result_json TEXT NOT NULL DEFAULT '{}',
          FOREIGN KEY(device_id) REFERENCES devices(id),
          UNIQUE(device_id,idempotency_key)
        );
        INSERT INTO commands(seq,id,device_id,type,created_at,expires_at,idempotency_key,ciphertext_json,state,ack_at,result_json)
        SELECT seq,id,device_id,type,created_at,expires_at,idempotency_key,ciphertext_json,state,ack_at,result_json FROM commands_legacy_v1;
        DROP TABLE commands_legacy_v1;
        CREATE INDEX IF NOT EXISTS idx_commands_device_state ON commands(device_id,state,seq);
        """)


def _columns(con: sqlite3.Connection, name: str) -> set[str]:
    return {str(r["name"]) for r in con.execute(f"PRAGMA table_info({name})").fetchall()}


def _add_column(con: sqlite3.Connection, table: str, name: str, ddl: str) -> None:
    if name not in _columns(con, table):
        con.execute(f"ALTER TABLE {table} ADD COLUMN {name} {ddl}")


def _migrate_v3(con: sqlite3.Connection) -> None:
    for name,ddl in (
        ("node_type","TEXT NOT NULL DEFAULT 'android'"),
        ("capabilities_json","TEXT NOT NULL DEFAULT '[]'"),
        ("key_id","TEXT NOT NULL DEFAULT ''"),
        ("wrapped_key_json","TEXT NOT NULL DEFAULT '{}'"),
    ):
        _add_column(con,"enrollment_tokens",name,ddl)
    for name,ddl in (
        ("node_type","TEXT NOT NULL DEFAULT 'android'"),
        ("capabilities_json","TEXT NOT NULL DEFAULT '[]'"),
        ("key_id","TEXT NOT NULL DEFAULT ''"),
        ("wrapped_key_json","TEXT NOT NULL DEFAULT '{}'"),
        ("pending_key_id","TEXT NOT NULL DEFAULT ''"),
        ("pending_wrapped_key_json","TEXT NOT NULL DEFAULT '{}'"),
    ):
        _add_column(con,"devices",name,ddl)


def _migrate_v4(con: sqlite3.Connection) -> None:
    for name,ddl in (
        ("token_issued_at","INTEGER NOT NULL DEFAULT 0"),
        ("pending_token_hash","TEXT NOT NULL DEFAULT ''"),
        ("pending_token_expires_at","INTEGER NOT NULL DEFAULT 0"),
    ):
        _add_column(con,"devices",name,ddl)
    con.execute("UPDATE devices SET token_issued_at=created_at WHERE token_issued_at<=0")


def _migrate_v5(con: sqlite3.Connection) -> None:
    _add_column(con,"enrollment_tokens","bootstrap_envelope_json","TEXT NOT NULL DEFAULT '{}'")


def _migrate_v6(con: sqlite3.Connection) -> None:
    _add_column(con,"enrollment_tokens","bootstrap_hash","TEXT NOT NULL DEFAULT ''")


def _migrate_v7(con: sqlite3.Connection) -> None:
    _add_column(con, "admin_sessions", "admin_fingerprint", "TEXT NOT NULL DEFAULT ''")
    _add_column(con, "admin_sessions", "elevated_until", "INTEGER NOT NULL DEFAULT 0")
    con.execute("DELETE FROM admin_sessions WHERE admin_fingerprint=''")

def _migrate_v8(con: sqlite3.Connection) -> None:
    for name, ddl in (
        ("sms_purged_before", "INTEGER NOT NULL DEFAULT 0"),
        ("sms_epoch", "INTEGER NOT NULL DEFAULT 0"),
        ("reset_requested_at", "INTEGER NOT NULL DEFAULT 0"),
        ("reset_source", "TEXT NOT NULL DEFAULT ''"),
    ):
        _add_column(con, "devices", name, ddl)


def purge_device_record(con: sqlite3.Connection, device_id: str, ts: int) -> bool:
    """Delete all business data while retaining only an expiring opaque reset receipt."""
    row = con.execute("SELECT token_hash,pending_token_hash FROM devices WHERE id=?", (device_id,)).fetchone()
    if row is None:
        return False
    for token_hash in (row["token_hash"], row["pending_token_hash"]):
        if token_hash:
            con.execute(
                "INSERT OR IGNORE INTO device_reset_tombstones(device_id,token_hash,created_at,expires_at) VALUES(?,?,?,?)",
                (device_id, token_hash, ts, ts + 90 * 86400),
            )
    for table in ("events", "commands", "device_state", "subscriptions", "channels", "device_key_history"):
        con.execute(f"DELETE FROM {table} WHERE device_id=?", (device_id,))
    con.execute("DELETE FROM devices WHERE id=?", (device_id,))
    return True


def init_db() -> None:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    with open_db() as con:
        con.executescript(SCHEMA_PATH.read_text("utf-8"))
        _migrate_v2(con)
        _migrate_v3(con)
        _migrate_v4(con)
        _migrate_v5(con)
        _migrate_v6(con)
        _migrate_v7(con)
        _migrate_v8(con)
        con.executescript(SCHEMA_PATH.read_text("utf-8"))
        con.execute("PRAGMA user_version=9")
    run_maintenance()


def run_maintenance() -> dict[str,int]:
    ts=now(); result={"events":0,"audit":0,"commands":0,"sessions":0,"enrollments":0,"pendingTokens":0,"resetTombstones":0}
    with open_db() as con:
        if EVENT_RETENTION_DAYS>0:
            result["events"]=con.execute("DELETE FROM events WHERE received_at<?",(ts-EVENT_RETENTION_DAYS*86400,)).rowcount
        if AUDIT_RETENTION_DAYS>0:
            result["audit"]=con.execute("DELETE FROM audit WHERE occurred_at<?",(ts-AUDIT_RETENTION_DAYS*86400,)).rowcount
        result["commands"]=con.execute("DELETE FROM commands WHERE created_at<? AND state NOT IN ('queued','dispatched')",(ts-COMMAND_RETENTION_DAYS*86400,)).rowcount
        result["sessions"]=con.execute("DELETE FROM admin_sessions WHERE expires_at<=? OR last_seen_at<=? OR admin_fingerprint!=?",(ts,ts-SESSION_IDLE_TTL,session_fingerprint())).rowcount
        result["enrollments"]=con.execute("DELETE FROM enrollment_tokens WHERE expires_at<?",(ts-86400,)).rowcount
        result["pendingTokens"]=con.execute("UPDATE devices SET pending_token_hash='',pending_token_expires_at=0 WHERE pending_token_expires_at>0 AND pending_token_expires_at<?",(ts,)).rowcount
        result["resetTombstones"]=con.execute("DELETE FROM device_reset_tombstones WHERE expires_at<?",(ts,)).rowcount
    return result


def maintenance_loop() -> None:
    while True:
        time.sleep(MAINTENANCE_INTERVAL)
        try:
            removed=run_maintenance()
            if any(removed.values()): log.info("maintenance removed=%s",removed)
        except Exception:
            log.exception("maintenance_failed")


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


def _cookie_session(headers) -> str:
    raw = headers.get("Cookie", "")
    if not raw:
        return ""
    try:
        c = SimpleCookie()
        c.load(raw)
        return c[SESSION_COOKIE].value if SESSION_COOKIE in c else ""
    except Exception:
        return ""


def session_fingerprint() -> str:
    return sha256_text("simhub-session-v1|" + ADMIN_TOKEN)

def csrf_for_session(token: str) -> str:
    return hmac.new(ADMIN_TOKEN.encode(), ("simhub-csrf-v1|" + token).encode(), hashlib.sha256).hexdigest()

def session_valid(headers) -> bool:
    token = _cookie_session(headers)
    if not token or not TOKEN_RE.match(token):
        return False
    ts = now()
    digest = sha256_text(token)
    with open_db() as con:
        row = con.execute("SELECT expires_at,last_seen_at,admin_fingerprint FROM admin_sessions WHERE token_hash=?", (digest,)).fetchone()
        if not row or row["expires_at"] <= ts or ts-row["last_seen_at"] >= SESSION_IDLE_TTL or not hmac.compare_digest(row["admin_fingerprint"], session_fingerprint()):
            if row:
                con.execute("DELETE FROM admin_sessions WHERE token_hash=?", (digest,))
            return False
        if headers.get("X-SimHub-Activity") == "1" and ts - row["last_seen_at"] >= 60:
            con.execute("UPDATE admin_sessions SET last_seen_at=? WHERE token_hash=?", (ts,digest))
    return True


def direct_admin_auth(headers) -> bool:
    auth = headers.get("Authorization", "")
    if not auth.startswith("Bearer "):
        return False
    presented = auth[7:]
    return bool(ADMIN_TOKEN and hmac.compare_digest(presented, ADMIN_TOKEN) and totp_valid(headers.get("X-SimHub-TOTP", "")))


def admin_auth(headers) -> bool:
    return session_valid(headers) or direct_admin_auth(headers)


def auth_rate_allowed(ip: str) -> bool:
    global _auth_gc_at
    ts = now()
    with _auth_lock:
        if ts - _auth_gc_at >= 30:
            for key in list(_auth_failures):
                valid = [v for v in _auth_failures[key] if ts-v < 60]
                if valid: _auth_failures[key] = valid
                else: _auth_failures.pop(key, None)
            _auth_gc_at = ts
        attempts = [v for v in _auth_failures.get(ip, []) if ts-v < 60]
        return len(attempts) < 5 and (ip in _auth_failures or len(_auth_failures) < 4096)


def auth_rate_fail(ip: str) -> None:
    with _auth_lock:
        if ip in _auth_failures or len(_auth_failures) < 4096:
            _auth_failures.setdefault(ip, []).append(now())


def auth_rate_success(ip: str) -> None:
    with _auth_lock:
        _auth_failures.pop(ip, None)


def create_session(ip: str) -> tuple[str,int]:
    token = new_token(48)
    ts = now()
    exp = ts + SESSION_TTL
    with open_db() as con:
        con.execute("DELETE FROM admin_sessions WHERE expires_at<=?", (ts,))
        con.execute(
            "INSERT INTO admin_sessions(token_hash,created_at,expires_at,last_seen_at,ip,admin_fingerprint) VALUES(?,?,?,?,?,?)",
            (sha256_text(token),ts,exp,ts,ip[:80],session_fingerprint()),
        )
    return token, exp


def delete_session(headers) -> None:
    token = _cookie_session(headers)
    if token:
        with open_db() as con:
            con.execute("DELETE FROM admin_sessions WHERE token_hash=?", (sha256_text(token),))


def device_for_token(device_id: str, token: str, allow_pending: bool=False) -> sqlite3.Row | None:
    if not token or not TOKEN_RE.match(token):
        return None
    digest=sha256_text(token); ts=now()
    with open_db() as con:
        row = con.execute("SELECT * FROM devices WHERE id=?", (device_id,)).fetchone()
    if not row or row["revoked_at"] is not None:
        return None
    if hmac.compare_digest(row["token_hash"], digest):
        return row
    if allow_pending and row["pending_token_hash"] and row["pending_token_expires_at"]>=ts and hmac.compare_digest(row["pending_token_hash"],digest):
        return row
    return None


def validate_cipher(obj: Any) -> bool:
    if not isinstance(obj, dict) or obj.get("alg") != "A256GCM":
        return False
    if obj.get("v") not in {1,2}:
        return False
    if not isinstance(obj.get("iv"), str) or not (8 <= len(obj["iv"]) <= 64):
        return False
    if not isinstance(obj.get("ct"), str) or not (16 <= len(obj["ct"]) <= 500_000):
        return False
    if obj.get("v") == 2 and (not isinstance(obj.get("kid"), str) or not (8 <= len(obj["kid"]) <= 80)):
        return False
    return True


def sanitize_metadata(kind: str, value: Any) -> dict[str,Any]:
    if not isinstance(value, dict):
        return {}
    allowed_by_kind = {
        "sms.received": {"parts","source"},
        "sms.history": {"providerType","history"},
        "sms.sent": {"stage"},
        "sms.failed": {"stage","resultCode"},
        "sms.delivered": {"delivery"},
        "mms.history": {"metadataOnly"},
        "mms.received_pending": {"metadataOnly"},
    }
    allowed = allowed_by_kind.get(kind, set())
    out: dict[str,Any] = {}
    for key in allowed:
        if key not in value:
            continue
        v = value[key]
        if isinstance(v, (str,int,float,bool)) or v is None:
            out[key] = v
    return out


def sanitize_state(body: Any) -> dict[str,Any]:
    if not isinstance(body, dict):
        return {}
    scalar = {"androidVersion","sdk","model","appVersion","network","pendingEvents","batteryPct","charging","queueFailures","lastQueueFailureAt","nodeType","cryptoKeyId","cryptoKeyMode","smsRoleHeld","smsReceivePermission","smsSendPermission","smsOperational","lastSmsReceivedAt","lastSmsSentAt","lastSyncSuccessAt","lastSyncError","nextSyncAllowedAt","syncBackoffFailures","dataFallbackEnabled","dataFallbackChannelId","dataFallbackStatus","internetValidated","wifiConnected","cellularConnected"}
    out: dict[str,Any] = {}
    for k in scalar:
        v=body.get(k)
        if isinstance(v,(str,int,float,bool)) or v is None:
            out[k]=v
    capabilities=[]
    for cap in body.get("capabilities",[]) if isinstance(body.get("capabilities"),list) else []:
        if isinstance(cap,str) and re.fullmatch(r"[a-z0-9._-]{1,64}",cap):
            capabilities.append(cap)
    out["capabilities"]=capabilities[:64]

    subs=[]
    for item in body.get("subscriptions",[]) if isinstance(body.get("subscriptions"),list) else []:
        if not isinstance(item,dict):
            continue
        x={}
        for k in {"subscriptionId","channelId","channelRevision","slotIndex","carrierName","displayName","isEmbedded","opportunistic","signalLevel","signalDbm","signalRssi","signalRsrp","signalRsrq","signalSinr","serviceState","roaming","networkType"}:
            v=item.get(k)
            if isinstance(v,(str,int,float,bool)) or v is None:
                x[k]=v
        if "subscriptionId" in x:
            subs.append(x)
    out["subscriptions"]=subs[:32]

    channels=[]
    for item in body.get("channels",[]) if isinstance(body.get("channels"),list) else []:
        if not isinstance(item,dict):
            continue
        x={}
        for k in {"id","localId","kind","revision","slotIndex","carrierName","displayName","isEmbedded","opportunistic","signalLevel","signalRsrp","signalRsrq","signalSinr","serviceState","roaming","networkType"}:
            v=item.get(k)
            if isinstance(v,(str,int,float,bool)) or v is None:
                x[k]=v
        if isinstance(x.get("id"),str) and x["id"]:
            channels.append(x)
    out["channels"]=channels[:64]
    item=body.get("encryptedSimNumbers")
    if isinstance(item,dict) and isinstance(item.get("eventId"),str) and isinstance(item.get("occurredAt"),int) and validate_cipher(item.get("ciphertext")):
        out["encryptedSimNumbers"]={"eventId":item["eventId"][:128],"occurredAt":item["occurredAt"],"ciphertext":item["ciphertext"]}
    return out

def normalize_node_type(value: Any) -> str:
    v=str(value or "android").lower()
    return v if v in {"android","modem","gateway"} else "android"


def normalize_capabilities(value: Any) -> list[str]:
    out=[]
    for cap in value if isinstance(value,list) else []:
        if isinstance(cap,str) and re.fullmatch(r"[a-z0-9._-]{1,64}",cap):
            out.append(cap)
    return out[:64]


def normalize_wrapped_key(key_id: Any, wrapped: Any) -> tuple[str,dict[str,Any]]:
    kid=str(key_id or "")
    if not kid:
        return "",{}
    if len(kid)>80 or not re.fullmatch(r"[A-Za-z0-9_-]{8,80}",kid):
        raise ValueError("Invalid keyId")
    if not validate_cipher(wrapped):
        raise ValueError("Invalid wrapped key envelope")
    return kid,wrapped


def signal_stream() -> None:
    global _stream_epoch
    with _stream_condition:
        _stream_epoch += 1
        _stream_condition.notify_all()


def notify_async(kind: str, device_id: str, device_name: str, has_otp: bool, occurred_at: int) -> None:
    if not (NOTIFY_WEBHOOK or NOTIFY_BARK_URL or NOTIFY_NTFY_URL) or kind != "sms.received":
        return
    if abs(now()-occurred_at) > NOTIFY_MAX_AGE:
        return
    if NOTIFY_OTP_ONLY and not has_otp:
        return
    if NOTIFY_DEVICE_IDS and device_id not in NOTIFY_DEVICE_IDS:
        return
    payload = {
        "source":"simhub",
        "kind":kind,
        "title":"SIM Hub",
        "message":"New OTP received" if has_otp else "New SMS received",
        "device":device_name,
        "occurredAt":occurred_at,
    }
    def send() -> None:
        # Notification providers receive only generic metadata, never sender/body/OTP.
        if NOTIFY_WEBHOOK:
            try:
                req=urllib.request.Request(
                    NOTIFY_WEBHOOK,data=json.dumps(payload).encode(),
                    headers={"Content-Type":"application/json", **({"Authorization":f"Bearer {NOTIFY_BEARER}"} if NOTIFY_BEARER else {})},method="POST",
                )
                urllib.request.urlopen(req,timeout=5).read(1024)
            except Exception as exc:
                log.warning("notification_webhook_failed %s",type(exc).__name__)
        if NOTIFY_BARK_URL:
            try:
                body={"title":"SIM Hub","body":payload["message"],"group":"SIM Hub"}
                req=urllib.request.Request(NOTIFY_BARK_URL,data=json.dumps(body).encode(),
                    headers={"Content-Type":"application/json"},method="POST")
                urllib.request.urlopen(req,timeout=5).read(1024)
            except Exception as exc:
                log.warning("notification_bark_failed %s",type(exc).__name__)
        if NOTIFY_NTFY_URL:
            try:
                headers={"Title":"SIM Hub","Priority":"4" if has_otp else "3","Content-Type":"text/plain; charset=utf-8"}
                if NOTIFY_NTFY_TOKEN:
                    headers["Authorization"]="Bearer "+NOTIFY_NTFY_TOKEN
                req=urllib.request.Request(NOTIFY_NTFY_URL,data=payload["message"].encode("utf-8"),
                    headers=headers,method="POST")
                urllib.request.urlopen(req,timeout=5).read(1024)
            except Exception as exc:
                log.warning("notification_ntfy_failed %s",type(exc).__name__)
    if not _outbound_executor.submit(send):
        log.warning("notification_dropped queue_full")


def push_tickle_async(device_id: str, reason: str) -> None:
    if not PUSH_TICKLE_URL:
        return
    payload={"source":"simhub","deviceId":device_id,"reason":reason,"occurredAt":now()}
    def send() -> None:
        try:
            req=urllib.request.Request(
                PUSH_TICKLE_URL,
                data=json.dumps(payload).encode(),
                headers={"Content-Type":"application/json", **({"Authorization":f"Bearer {PUSH_TICKLE_BEARER}"} if PUSH_TICKLE_BEARER else {})},
                method="POST",
            )
            urllib.request.urlopen(req,timeout=5).read(1024)
        except Exception as exc:
            log.warning("push_tickle_failed %s",type(exc).__name__)
    if not _outbound_executor.submit(send):
        log.warning("push_tickle_dropped queue_full")


class SimHubHandler(BaseHTTPRequestHandler):
    server_version = "SimHubRelay/0.3.1"
    sys_version = ""

    def log_message(self, fmt: str, *args) -> None:
        log.info("http %s %s -> %s", self.command, urllib.parse.urlsplit(self.path).path, str(args[1]) if len(args)>1 else "")

    @property
    def ip(self) -> str:
        return forwarded_client_ip(self.client_address[0],self.headers)

    def security_headers(self) -> None:
        self.send_header("X-Content-Type-Options","nosniff")
        self.send_header("X-Frame-Options","DENY")
        self.send_header("Referrer-Policy","no-referrer")
        self.send_header("Cache-Control","no-store")
        self.send_header("Permissions-Policy","camera=(), microphone=(), geolocation=()")
        self.send_header("Cross-Origin-Opener-Policy","same-origin")
        self.send_header("Cross-Origin-Resource-Policy","same-origin")
        self.send_header("X-Permitted-Cross-Domain-Policies","none")
        self.send_header("Content-Security-Policy","default-src 'self'; connect-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'; manifest-src 'self'")

    def send_json(self,status:int,obj:Any,extra_headers:dict[str,str]|None=None) -> None:
        data=json.dumps(obj,ensure_ascii=False,separators=(",",":")).encode()
        self.send_response(status)
        self.send_header("Content-Type","application/json; charset=utf-8")
        self.send_header("Content-Length",str(len(data)))
        if extra_headers:
            for k,v in extra_headers.items():
                self.send_header(k,v)
        self.security_headers()
        self.end_headers()
        self.wfile.write(data)

    def send_error_json(self,status:int,code:str,message:str) -> None:
        self.send_json(status,{"error":code,"message":message},{"Retry-After":"60"} if status==429 else None)

    def read_json(self) -> dict[str,Any] | None:
        path=self.route()[0]
        max_body = MAX_JSON_BODY if re.search(r"/(?:events|commands|state|events/batch)$",path) else 65536
        try:
            length=int(self.headers.get("Content-Length","0"))
        except ValueError:
            self.send_error_json(411,"length_required","Invalid Content-Length"); return None
        if length<=0 or length>max_body:
            self.send_error_json(413 if length>max_body else 400,"invalid_body","JSON body size exceeds route limit"); return None
        try:
            data=json.loads(self.rfile.read(length))
            if not isinstance(data,dict): raise ValueError()
            return data
        except Exception:
            self.send_error_json(400,"invalid_json","Body must be a JSON object"); return None

    def require_admin(self) -> bool:
        if not auth_rate_allowed(self.ip):
            self.send_error_json(429,"rate_limited","Too many authentication failures"); return False
        if admin_auth(self.headers):
            if not self.csrf_valid():
                audit("auth.csrf","","denied",self.ip)
                self.send_error_json(403,"invalid_csrf","CSRF token required"); return False
            if not rate_allowed("admin", _cookie_session(self.headers) or self.ip, 120):
                self.send_error_json(429,"rate_limited","Admin request rate exceeded"); return False
            auth_rate_success(self.ip); return True
        auth_rate_fail(self.ip)
        audit("auth.admin","","denied",self.ip)
        self.send_error_json(401,"unauthorized","Authenticated admin session required"); return False

    def require_device(self,device_id:str) -> sqlite3.Row | None:
        if not rate_allowed("device", device_id+":"+self.ip, 180):
            self.send_error_json(429,"rate_limited","Device request rate exceeded"); return None
        auth=self.headers.get("Authorization","")
        if not auth.startswith("Device "):
            self.send_error_json(401,"unauthorized","Device token required"); return None
        row=device_for_token(device_id,auth[7:])
        if not row:
            audit("auth.device",device_id,"denied",self.ip)
            self.send_error_json(401,"unauthorized","Invalid or revoked device token"); return None
        if row["reset_requested_at"] and not self.path.split("?",1)[0].endswith("/reset"):
            self.send_error_json(409,"device_reset_pending","Only a reset acknowledgement is allowed"); return None
        return row

    def device_auth_from_headers(self) -> bool:
        device_id=self.headers.get("X-SimHub-Device-Id","")
        auth=self.headers.get("Authorization","")
        return bool(device_id and auth.startswith("Device ") and device_for_token(device_id,auth[7:]))

    def route(self) -> tuple[str,list[str],dict[str,list[str]]]:
        parsed=urllib.parse.urlsplit(self.path)
        return parsed.path,[urllib.parse.unquote(p) for p in parsed.path.split("/") if p],urllib.parse.parse_qs(parsed.query)

    def is_device_route(self, method: str, path: str) -> bool:
        if method == "POST" and path == "/api/v1/enroll": return True
        if method == "GET" and path == "/api/v1/ota": return True
        if re.fullmatch(r"/api/v1/devices/[^/]+/lifecycle", path): return method == "GET"
        if re.fullmatch(r"/api/v1/devices/[^/]+/reset", path): return method == "POST"
        if re.fullmatch(r"/api/v1/devices/[^/]+/commands/pending", path):
            return method == "GET"
        if re.fullmatch(r"/api/v1/devices/[^/]+/(?:events(?:/batch)?|state|heartbeat|token/(?:prepare|commit))", path):
            return method == "POST"
        if re.fullmatch(r"/api/v1/devices/[^/]+/commands/[^/]+/ack", path):
            return method == "POST"
        return False

    def preflight(self, method: str, path: str) -> bool:
        if method == "GET" and path in {"/healthz", "/readyz"}:
            if ipaddress.ip_address(self.client_address[0]).is_loopback or (DOCKER_GATEWAY_IP and self.client_address[0] == DOCKER_GATEWAY_IP): return True
            self.send_error_json(404,"not_found","Endpoint not available publicly"); return False
        # Distinct budgets prevent static shell reloads / multiple enrolled devices
        # behind one NAT from exhausting the authentication request budget.
        bucket = "auth" if path.startswith("/api/v1/auth/") else ("api" if path.startswith("/api/") else "static")
        budget = 180 if bucket == "auth" else (600 if bucket == "api" else 500)
        if not rate_allowed("ip:"+bucket, self.ip, budget):
            self.send_error_json(429, "rate_limited", "Request rate exceeded"); return False
        origin = self.headers.get("Origin", "").rstrip("/")
        if origin:
            allowed = {MANAGEMENT_ORIGIN, PUBLIC_BASE_URL}
            if origin not in allowed or self.headers.get("Sec-Fetch-Site", "") == "cross-site":
                self.send_error_json(403, "bad_origin", "Untrusted origin"); return False
        if SEPARATE_SURFACES:
            admin_host = urllib.parse.urlsplit(MANAGEMENT_ORIGIN).netloc.lower()
            device_host = urllib.parse.urlsplit(PUBLIC_BASE_URL).netloc.lower()
            request_host = self.headers.get("Host", "").lower()
            if request_host not in {admin_host, device_host}:
                self.send_error_json(421, "host_mismatch", "Unrecognized hostname"); return False
            if request_host == device_host and not self.is_device_route(method, path):
                self.send_error_json(404, "not_found", "Endpoint is not available on the device origin"); return False
            if request_host == admin_host and self.is_device_route(method, path) and path != "/api/v1/ota":
                self.send_error_json(404, "not_found", "Endpoint is not available on the management origin"); return False
        if method in {"POST","PATCH","PUT"}:
            if self.headers.get("Content-Type", "").split(";",1)[0].strip().lower() != "application/json":
                self.send_error_json(415, "unsupported_media_type", "JSON Content-Type required"); return False
        return True

    def csrf_valid(self) -> bool:
        if self.command in {"GET", "HEAD", "OPTIONS"}: return True
        if direct_admin_auth(self.headers): return True
        raw = _cookie_session(self.headers)
        token = self.headers.get("X-SimHub-CSRF", "")
        return bool(raw and token and hmac.compare_digest(token, csrf_for_session(raw)))

    def require_stepup(self) -> bool:
        if not self.require_admin(): return False
        if direct_admin_auth(self.headers): return True
        token = _cookie_session(self.headers)
        if token:
            with open_db() as con:
                row = con.execute("SELECT elevated_until FROM admin_sessions WHERE token_hash=?", (sha256_text(token),)).fetchone()
                if row and row["elevated_until"] >= now(): return True
        self.send_error_json(403, "stepup_required", "Recent second-factor verification required")
        return False

    def do_OPTIONS(self) -> None:
        self.send_response(204); self.security_headers(); self.end_headers()

    def do_GET(self) -> None:
        path,p,q=self.route()
        if not self.preflight("GET", path): return
        if path=="/healthz":
            self.send_json(200,{"ok":True,"version":APP_VERSION,"time":now()}); return
        if path=="/readyz":
            try:
                with open_db() as con:
                    version=con.execute("PRAGMA user_version").fetchone()[0]
                    con.execute("SELECT 1").fetchone()
                if version<9:
                    self.send_error_json(503,"schema_not_ready","Database schema is not current"); return
                self.send_json(200,{"ok":True,"version":APP_VERSION,"schemaVersion":version,"time":now()}); return
            except Exception:
                self.send_error_json(503,"database_not_ready","Database is not ready"); return
        if path=="/api/v1/auth/check":
            # Passive browser session probes are not password guesses. Never
            # increase the authentication-failure bucket for an absent or expired
            # cookie; retain the independent per-IP preflight budget.
            if not admin_auth(self.headers):
                if self.headers.get("Authorization"):
                    if not auth_rate_allowed(self.ip):
                        self.send_error_json(429,"rate_limited","Too many authentication failures"); return
                    auth_rate_fail(self.ip)
                self.send_error_json(401,"unauthorized","No active administrator session"); return
            with open_db() as con:
                num_keys=con.execute("SELECT COUNT(*) FROM admin_passkeys").fetchone()[0]
            self.send_json(200,{"ok":True,"username":ADMIN_USERNAME,"passkeyCount":num_keys,"totpRequired":bool(TOTP_SECRET),"sessionTtlSeconds":SESSION_TTL,"sessionIdleTtlSeconds":SESSION_IDLE_TTL,"csrfToken":csrf_for_session(_cookie_session(self.headers)) if session_valid(self.headers) else None}); return
        if path=="/api/v1/auth/passkeys":
            if not self.require_admin(): return
            with open_db() as con:
                keys=passkeys.list_keys(con)
            self.send_json(200,{"username":ADMIN_USERNAME,"passkeys":keys}); return
        if path=="/api/v1/version":
            if not self.require_admin(): return
            self.send_json(200,{"version":APP_VERSION,"startedAt":SERVER_STARTED_AT,"deployedAt":DEPLOYED_AT or None}); return
        if len(p)==5 and p[:3]==["api","v1","devices"] and p[4]=="lifecycle":
            self.get_device_lifecycle(p[3]); return
        if path=="/api/v1/devices":
            if not self.require_admin(): return
            self.get_devices(); return
        if path=="/api/v1/events":
            if not self.require_admin(): return
            self.get_events(q); return
        if path=="/api/v1/audit":
            if not self.require_admin(): return
            self.get_audit(q); return
        if path=="/api/v1/commands/recent":
            if not self.require_admin(): return
            self.get_recent_commands(q); return
        if path=="/api/v1/metrics":
            if not self.require_admin(): return
            self.get_metrics(); return
        if path=="/api/v1/stream":
            if not self.require_admin(): return
            self.stream_events(); return
        if path=="/api/v1/ota":
            if self.device_auth_from_headers():
                self.get_ota(); return
            if not self.require_admin(): return
            self.get_ota(); return
        if len(p)==5 and p[:3]==["api","v1","devices"] and p[4]=="state":
            if not self.require_admin(): return
            self.get_device_state(p[3]); return
        if len(p)==6 and p[:3]==["api","v1","devices"] and p[4:6]==["commands","pending"]:
            if not self.require_device(p[3]): return
            self.get_pending_commands(p[3],q); return
        if path.startswith("/api/"):
            self.send_error_json(404,"not_found","API endpoint not found"); return
        self.serve_static(path)

    def do_POST(self) -> None:
        path,p,q=self.route()
        if not self.preflight("POST", path): return
        if path.startswith("/api/v1/auth/passkeys/"):
            body=self.read_json()
            if body is None: return
            self.passkey_action(path,body); return
        if path=="/api/v1/auth/session":
            body=self.read_json()
            if body is None:return
            self.create_admin_session(body); return
        if path=="/api/v1/auth/logout":
            if not self.require_admin(): return
            delete_session(self.headers)
            self.send_json(200,{"ok":True},{"Set-Cookie":f"{SESSION_COOKIE}=; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=0"}); return
        if path=="/api/v1/auth/elevate":
            if not self.require_admin(): return
            body=self.read_json()
            if body is None: return
            if not auth_rate_allowed(self.ip):
                self.send_error_json(429,"rate_limited","Too many authentication attempts"); return
            correct = totp_valid(str(body.get("totp",""))) if TOTP_SECRET else hmac.compare_digest(str(body.get("adminToken","")), ADMIN_TOKEN)
            if not correct:
                auth_rate_fail(self.ip)
                audit("auth.stepup","","denied",self.ip)
                self.send_error_json(401,"invalid_second_factor","Invalid second factor"); return
            session = _cookie_session(self.headers)
            if not session or not session_valid(self.headers):
                self.send_error_json(401,"unauthorized","Session required for step-up"); return
            expiry = now()+STEPUP_TTL
            with open_db() as con:
                con.execute("UPDATE admin_sessions SET elevated_until=? WHERE token_hash=?", (expiry,sha256_text(session)))
            auth_rate_success(self.ip)
            audit("auth.stepup","","ok",self.ip)
            self.send_json(200,{"ok":True,"elevatedUntil":expiry}); return
        if path=="/api/v1/auth/revoke-all":
            if not self.require_stepup(): return
            with open_db() as con:
                con.execute("DELETE FROM admin_sessions")
            audit("auth.revoke_all","","ok",self.ip)
            self.send_json(200,{"ok":True},{"Set-Cookie":f"{SESSION_COOKIE}=; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=0"}); return
        if len(p)==5 and p[:3]==["api","v1","devices"] and p[4]=="reset-request":
            if not self.require_stepup(): return
            self.admin_request_reset(p[3]); return
        if len(p)==5 and p[:3]==["api","v1","devices"] and p[4]=="reset":
            if not self.require_device(p[3]): return
            self.complete_device_reset(p[3]); return
        if path=="/api/v1/enrollments":
            if not self.require_stepup():return
            body=self.read_json()
            if body is None:return
            self.create_enrollment(body); return
        if path=="/api/v1/enroll":
            body=self.read_json()
            if body is None:return
            self.enroll_device(body); return
        if len(p)==6 and p[:3]==["api","v1","devices"] and p[4:6]==["events","batch"]:
            if not self.require_device(p[3]):return
            body=self.read_json()
            if body is None:return
            self.ingest_events_batch(p[3],body); return
        if len(p)==5 and p[:3]==["api","v1","devices"] and p[4]=="events":
            if not self.require_device(p[3]):return
            body=self.read_json()
            if body is None:return
            self.ingest_event(p[3],body); return
        if len(p)==5 and p[:3]==["api","v1","devices"] and p[4]=="state":
            if not self.require_device(p[3]):return
            body=self.read_json()
            if body is None:return
            self.put_device_state(p[3],body); return
        if len(p)==5 and p[:3]==["api","v1","devices"] and p[4]=="heartbeat":
            if not self.require_device(p[3]):return
            body=self.read_json()
            if body is None:return
            self.heartbeat(p[3],body); return
        if len(p)==6 and p[:3]==["api","v1","devices"] and p[4:6]==["token","prepare"]:
            if not self.require_device(p[3]):return
            self.prepare_device_token(p[3]); return
        if len(p)==6 and p[:3]==["api","v1","devices"] and p[4:6]==["token","commit"]:
            auth=self.headers.get("Authorization","")
            if not auth.startswith("Device ") or not device_for_token(p[3],auth[7:],allow_pending=True):
                self.send_error_json(401,"unauthorized","Current or pending device token required"); return
            self.commit_device_token(p[3],auth[7:]); return
        if len(p)==5 and p[:3]==["api","v1","devices"] and p[4]=="commands":
            if not self.require_admin():return
            body=self.read_json()
            if body is None:return
            if str(body.get("type","")) in {"sms.send","node.rotate_key","device.network_policy"} and not self.require_stepup():return
            self.create_command(p[3],body); return
        if len(p)==7 and p[:3]==["api","v1","devices"] and p[4]=="commands" and p[6]=="ack":
            if not self.require_device(p[3]):return
            body=self.read_json()
            if body is None:return
            self.ack_command(p[3],p[5],body); return
        self.send_error_json(404,"not_found","API endpoint not found")

    def do_DELETE(self) -> None:
        path,p,q=self.route()
        if not self.preflight("DELETE", path): return
        if len(p)==5 and p[:3]==["api","v1","devices"] and p[4]=="sms":
            if not self.require_stepup():return
            self.purge_device_sms(p[3]); return
        if len(p)==4 and p[:3]==["api","v1","devices"]:
            if not self.require_stepup():return
            self.force_delete_device(p[3]); return
        if path=="/api/v1/events":
            if not self.require_stepup():return
            try: before=int(q.get("before",["0"])[0])
            except ValueError:
                self.send_error_json(400,"invalid_query","before must be a Unix timestamp");return
            if before<=0 or before>now()+300:
                self.send_error_json(400,"invalid_query","before must be a valid past Unix timestamp");return
            with open_db() as con:
                cur=con.execute("DELETE FROM events WHERE received_at<?",(before,))
                deleted=cur.rowcount
            audit("events.purge",str(before),"ok",self.ip);signal_stream()
            self.send_json(200,{"ok":True,"deleted":deleted,"before":before});return
        self.send_error_json(404,"not_found","API endpoint not found")

    def do_PATCH(self) -> None:
        path,p,q=self.route()
        if not self.preflight("PATCH", path): return
        if len(p)==4 and p[:3]==["api","v1","devices"]:
            if not self.require_admin():return
            body=self.read_json()
            if body is None:return
            if (body.get("revoke") is True or "pendingKeyId" in body or "pendingWrappedKey" in body) and not self.require_stepup():return
            self.patch_device(p[3],body); return
        self.send_error_json(404,"not_found","API endpoint not found")

    def create_admin_session(self,body:dict[str,Any]) -> None:
        if not auth_rate_allowed(self.ip):
            self.send_error_json(429,"rate_limited","Too many authentication failures"); return
        token=str(body.get("adminToken",""))
        username=str(body.get("username",ADMIN_USERNAME))
        totp=str(body.get("totp",""))
        if not ADMIN_TOKEN or not hmac.compare_digest(username,ADMIN_USERNAME) or not hmac.compare_digest(token,ADMIN_TOKEN) or not totp_valid(totp):
            auth_rate_fail(self.ip); audit("auth.session","","denied",self.ip)
            self.send_error_json(401,"unauthorized","Invalid admin token or TOTP"); return
        auth_rate_success(self.ip)
        session,exp=create_session(self.ip)
        audit("auth.session","","ok",self.ip)
        cookie=f"{SESSION_COOKIE}={session}; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age={SESSION_TTL}"
        self.send_json(201,{"ok":True,"username":ADMIN_USERNAME,"expiresAt":exp,"totpRequired":bool(TOTP_SECRET),"sessionIdleTtlSeconds":SESSION_IDLE_TTL,"csrfToken":csrf_for_session(session)},{"Set-Cookie":cookie})

    def passkey_action(self,path:str,body:dict[str,Any]) -> None:
        """Keep registration and step-up session-bound; login never receives Vault keys."""
        username=str(body.get("username",ADMIN_USERNAME))[:80]
        login_path=path in {"/api/v1/auth/passkeys/login/options","/api/v1/auth/passkeys/login/verify"}
        if login_path:
            if not auth_rate_allowed(self.ip):
                self.send_error_json(429,"rate_limited","Too many authentication attempts");return
        else:
            if not self.require_admin():return
            if path.startswith("/api/v1/auth/passkeys/register/") or path=="/api/v1/auth/passkeys/remove":
                if not self.require_stepup():return
            username=ADMIN_USERNAME
        session=_cookie_session(self.headers) if not login_path else ""
        fingerprint=sha256_text(session) if session else ""
        audit_action = ""
        audit_target = ""
        try:
            with open_db() as con:
                if path=="/api/v1/auth/passkeys/login/options":
                    data=passkeys.authentication_options(con,MANAGEMENT_ORIGIN,username,"","login")
                elif path=="/api/v1/auth/passkeys/login/verify":
                    if not hmac.compare_digest(username,ADMIN_USERNAME):
                        raise ValueError("Invalid administrator")
                    result=passkeys.authenticate(con,MANAGEMENT_ORIGIN,username,"","login",
                        str(body.get("challengeId","")),body.get("credential",{}))
                    data={"authenticated":True}
                elif path=="/api/v1/auth/passkeys/register/options":
                    if not fingerprint:raise ValueError("Browser session required")
                    data=passkeys.registration_options(con,MANAGEMENT_ORIGIN,username,fingerprint)
                elif path=="/api/v1/auth/passkeys/register/verify":
                    if not fingerprint:raise ValueError("Browser session required")
                    data=passkeys.register(con,MANAGEMENT_ORIGIN,username,fingerprint,
                        str(body.get("challengeId","")),body.get("credential",{}),
                        str(body.get("label","My passkey")).strip())
                    audit_action, audit_target = "auth.passkey.register", data["id"][:28]
                elif path=="/api/v1/auth/passkeys/elevate/options":
                    if not fingerprint:raise ValueError("Browser session required")
                    data=passkeys.authentication_options(con,MANAGEMENT_ORIGIN,username,fingerprint,"elevate")
                elif path=="/api/v1/auth/passkeys/elevate/verify":
                    if not fingerprint:raise ValueError("Browser session required")
                    data=passkeys.authenticate(con,MANAGEMENT_ORIGIN,username,fingerprint,"elevate",
                        str(body.get("challengeId","")),body.get("credential",{}))
                    expiry=now()+STEPUP_TTL
                    con.execute("UPDATE admin_sessions SET elevated_until=? WHERE token_hash=?",(expiry,fingerprint))
                    data={"ok":True,"elevatedUntil":expiry}
                    audit_action = "auth.passkey.elevate"
                elif path=="/api/v1/auth/passkeys/remove":
                    cid=str(body.get("id",""))
                    n=con.execute("DELETE FROM admin_passkeys WHERE credential_id=?",(cid,)).rowcount
                    if not n:raise ValueError("Passkey not found")
                    data={"ok":True}
                    audit_action, audit_target = "auth.passkey.remove", cid[:28]
                else:
                    self.send_error_json(404,"not_found","Passkey route not found");return
            if audit_action:
                audit(audit_action,audit_target,"ok",self.ip)
            if path=="/api/v1/auth/passkeys/login/verify":
                auth_rate_success(self.ip)
                session,exp=create_session(self.ip)
                cookie=f"{SESSION_COOKIE}={session}; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age={SESSION_TTL}"
                audit("auth.passkey.login","","ok",self.ip)
                self.send_json(201,{"ok":True,"username":ADMIN_USERNAME,"expiresAt":exp,
                    "totpRequired":bool(TOTP_SECRET),"sessionIdleTtlSeconds":SESSION_IDLE_TTL,"csrfToken":csrf_for_session(session)},{"Set-Cookie":cookie})
            else:
                self.send_json(200,data)
        except (ValueError,TypeError,KeyError) as exc:
            if login_path:
                auth_rate_fail(self.ip)
                audit("auth.passkey.login","","denied",self.ip)
            self.send_error_json(401 if login_path else 400,"invalid_passkey",str(exc)[:180])
        except Exception:
            if login_path:
                auth_rate_fail(self.ip)
                audit("auth.passkey.login","","denied",self.ip)
            log.exception("passkey_operation_failed")
            self.send_error_json(400,"invalid_passkey","WebAuthn verification failed")

    def create_enrollment(self,body:dict[str,Any]) -> None:
        eid=str(uuid.uuid4()); token=new_token(); ts=now()
        ttl=max(60,min(int(body.get("ttlSeconds",ENROLL_TTL)),3600))
        node_type=normalize_node_type(body.get("nodeType","android"))
        capabilities=normalize_capabilities(body.get("capabilities",[]))
        try:
            key_id,wrapped=normalize_wrapped_key(body.get("keyId",""),body.get("wrappedKey",{}))
        except ValueError as exc:
            self.send_error_json(400,"invalid_key",str(exc)); return
        bootstrap=body.get("bootstrapEnvelope",{})
        bootstrap_hash=str(body.get("bootstrapHash",""))
        if key_id and not validate_cipher(bootstrap):
            self.send_error_json(400,"invalid_bootstrap","A valid one-time bootstrap envelope is required for independent Node Key enrollment"); return
        if key_id and not BOOTSTRAP_PROOF_RE.fullmatch(bootstrap_hash):
            self.send_error_json(400,"invalid_bootstrap","A valid SHA-256 bootstrap proof is required"); return
        if not key_id:
            bootstrap={}; bootstrap_hash=""
        with open_db() as con:
            con.execute(
                "INSERT INTO enrollment_tokens(id,token_hash,created_at,expires_at,node_type,capabilities_json,key_id,wrapped_key_json,bootstrap_envelope_json,bootstrap_hash) VALUES(?,?,?,?,?,?,?,?,?,?)",
                (eid,sha256_text(token),ts,ts+ttl,node_type,json.dumps(capabilities,separators=(",",":")),key_id,json.dumps(wrapped,separators=(",",":")),json.dumps(bootstrap,separators=(",",":")),bootstrap_hash),
            )
        audit("enrollment.create",eid,"ok",self.ip)
        self.send_json(201,{"id":eid,"token":token,"expiresAt":ts+ttl,"server":PUBLIC_BASE_URL or None,"nodeType":node_type,"keyId":key_id or None})

    def enroll_device(self,body:dict[str,Any]) -> None:
        token=str(body.get("token",""))
        if not TOKEN_RE.match(token):
            self.send_error_json(400,"invalid_token","Enrollment token malformed"); return
        token_hash=sha256_text(token); ts=now()
        with open_db() as con:
            # Serialize enrollment claims at read time; avoids SQLite lock-upgrade
            # deadlocks between two concurrent consumers of the same token.
            con.execute("BEGIN IMMEDIATE")
            row=con.execute("SELECT * FROM enrollment_tokens WHERE token_hash=?",(token_hash,)).fetchone()
            if not row or row["used_at"] is not None or row["expires_at"]<ts:
                log.warning("Enrollment token rejected for %s",self.ip)
                self.send_error_json(401,"invalid_enrollment","Enrollment token invalid, expired, or already used"); return
            if row["key_id"]:
                proof=str(body.get("bootstrapProof",""))
                expected=str(row["bootstrap_hash"] or "")
                if not expected or not BOOTSTRAP_PROOF_RE.fullmatch(proof) or not hmac.compare_digest(expected,proof):
                    log.warning("Enrollment bootstrap proof rejected")
                    self.send_error_json(401,"invalid_bootstrap_proof","Bootstrap secret proof is invalid"); return
            # Atomic single-use claim: another request may have read the same row before us.
            # UPDATE obtains SQLite's writer lock; only one claimant can match used_at IS NULL.
            claimed=con.execute(
                "UPDATE enrollment_tokens SET used_at=?,bootstrap_envelope_json='{}',bootstrap_hash='' "
                "WHERE id=? AND used_at IS NULL AND expires_at>=?",
                (ts,row["id"],ts),
            )
            if claimed.rowcount!=1:
                self.send_error_json(401,"invalid_enrollment","Enrollment token already consumed"); return
            device_id=str(uuid.uuid4()); device_token=new_token(48)
            node_type=normalize_node_type(row["node_type"] or body.get("nodeType","android"))
            capabilities=normalize_capabilities(safe_json_loads(row["capabilities_json"],[]) or body.get("capabilities",[]))
            default_name="Android SIM Node" if node_type=="android" else "Modem SIM Node"
            name=str(body.get("name") or body.get("model") or default_name)[:80]
            con.execute(
                """INSERT INTO devices(id,token_hash,name,group_name,model,os_version,app_version,created_at,last_seen_at,node_type,capabilities_json,key_id,wrapped_key_json,token_issued_at)
                   VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (device_id,sha256_text(device_token),name,str(body.get("group",""))[:80],str(body.get("model",""))[:120],str(body.get("osVersion",""))[:40],str(body.get("appVersion",""))[:40],ts,ts,node_type,json.dumps(capabilities,separators=(",",":")),str(row["key_id"] or "")[:80],str(row["wrapped_key_json"] or "{}"),ts),
            )
            bootstrap=safe_json_loads(row["bootstrap_envelope_json"],{})

        audit("enrollment.consume",device_id,"ok",self.ip); signal_stream()
        self.send_json(201,{"deviceId":device_id,"deviceToken":device_token,"tokenIssuedAt":ts,"serverTime":ts,"nodeType":node_type,"keyId":str(row["key_id"] or "") or None,"bootstrapEnvelope":bootstrap or None})

    def get_devices(self) -> None:
        ts=now()
        with open_db() as con:
            rows=con.execute("SELECT d.*,s.state_json,s.updated_at AS state_updated_at FROM devices d LEFT JOIN device_state s ON s.device_id=d.id ORDER BY d.created_at DESC").fetchall()
        # No plaintext keys or key material are stored server-side. Read-only
        # Vault-wrapped envelopes permit offline decryption of pre-rotation SMS.
        history_by_device:dict[str,list[sqlite3.Row]]={}
        with open_db() as con:
            history=con.execute("SELECT device_id,key_id,wrapped_key_json FROM device_key_history ORDER BY archived_at DESC").fetchall()
            sms_counts={r["device_id"]:r["total"] for r in con.execute("SELECT device_id,COUNT(*) AS total FROM events WHERE kind LIKE 'sms.%' GROUP BY device_id")}
        for key in history:
            old=history_by_device.setdefault(key["device_id"],[])
            if len(old)<64: old.append(key)
        out=[]
        for r in rows:
            last=r["last_seen_at"] or 0
            out.append({
                "id":r["id"],"name":r["name"],"group":r["group_name"],"model":r["model"],"osVersion":r["os_version"],"appVersion":r["app_version"],
                "nodeType":r["node_type"],"capabilities":safe_json_loads(r["capabilities_json"],[]),"keyId":r["key_id"] or None,"tokenIssuedAt":r["token_issued_at"],
                "wrappedKey":safe_json_loads(r["wrapped_key_json"],{}) if r["key_id"] else None,
                "pendingKeyId":r["pending_key_id"] or None,"pendingWrappedKey":safe_json_loads(r["pending_wrapped_key_json"],{}) if r["pending_key_id"] else None,
                "createdAt":r["created_at"],"lastSeenAt":r["last_seen_at"],"online":bool(not r["revoked_at"] and not r["reset_requested_at"] and ts-last<=OFFLINE_AFTER),"revoked":bool(r["revoked_at"]),
                "resetRequestedAt":r["reset_requested_at"] or None,"resetSource":r["reset_source"],
                "smsCount":sms_counts.get(r["id"],0),"smsPurgeAt":r["sms_purged_before"],"smsEpoch":r["sms_epoch"],
                "state":safe_json_loads(r["state_json"],{}) if r["state_json"] else None,
                "historicalWrappedKeys": [
                    {"keyId": k["key_id"], "wrappedKey": safe_json_loads(k["wrapped_key_json"], {})}
                    for k in history_by_device.get(r["id"], [])
                ],
            })
        self.send_json(200,{"devices":out,"offlineAfterSeconds":OFFLINE_AFTER})

    def patch_device(self,device_id:str,body:dict[str,Any]) -> None:
        sets=[]; vals=[]
        if "name" in body: sets.append("name=?"); vals.append(str(body["name"])[:80])
        if "group" in body: sets.append("group_name=?"); vals.append(str(body["group"])[:80])
        if "pendingKeyId" in body or "pendingWrappedKey" in body:
            try:
                kid,wrapped=normalize_wrapped_key(body.get("pendingKeyId",""),body.get("pendingWrappedKey",{}))
            except ValueError as exc:
                self.send_error_json(400,"invalid_key",str(exc)); return
            if not kid:
                self.send_error_json(400,"invalid_key","pendingKeyId and pendingWrappedKey are required"); return
            with open_db() as check:
                current=check.execute("SELECT pending_key_id FROM devices WHERE id=?",(device_id,)).fetchone()
            if not current:
                self.send_error_json(404,"device_not_found","Device not found"); return
            if current["pending_key_id"] and current["pending_key_id"]!=kid:
                self.send_error_json(409,"key_rotation_in_progress","A different node-key rotation is already pending"); return
            sets.extend(["pending_key_id=?","pending_wrapped_key_json=?"])
            vals.extend([kid,json.dumps(wrapped,separators=(",",":"))])
        if body.get("revoke") is True: sets.append("revoked_at=?"); vals.append(now())
        if body.get("restore") is True:
            self.send_error_json(400,"reenroll_required","Revoked devices must be re-enrolled"); return
        if not sets:
            self.send_error_json(400,"no_changes","No supported fields supplied"); return
        vals.append(device_id)
        with open_db() as con:
            cur=con.execute(f"UPDATE devices SET {','.join(sets)} WHERE id=?",vals)
        if not cur.rowcount:
            self.send_error_json(404,"device_not_found","Device not found"); return
        audit("device.patch",device_id,"ok",self.ip); signal_stream()
        self.send_json(200,{"ok":True})

    def purge_device_sms(self, device_id: str) -> None:
        ts=now()
        with open_db() as con:
            row=con.execute("SELECT reset_requested_at FROM devices WHERE id=?",(device_id,)).fetchone()
            if not row:
                self.send_error_json(404,"device_not_found","Device not found"); return
            if row["reset_requested_at"]:
                self.send_error_json(409,"device_reset_pending","Device reset in progress"); return
            deleted=con.execute("DELETE FROM events WHERE device_id=? AND kind LIKE 'sms.%'",(device_id,)).rowcount
            cancelled=con.execute("DELETE FROM commands WHERE device_id=? AND type LIKE 'sms.%'",(device_id,)).rowcount
            con.execute("UPDATE devices SET sms_purged_before=MAX(sms_purged_before,?),sms_epoch=sms_epoch+1 WHERE id=?",(ts,device_id))
        audit("device.sms.purge",device_id,"ok",self.ip);signal_stream()
        self.send_json(200,{"ok":True,"deleted":deleted,"cancelledCommands":cancelled,"purgedBefore":ts})

    def admin_request_reset(self, device_id: str) -> None:
        ts=now()
        with open_db() as con:
            d=con.execute("SELECT reset_requested_at FROM devices WHERE id=?",(device_id,)).fetchone()
            if not d:
                self.send_error_json(404,"device_not_found","Device not found");return
            if not d["reset_requested_at"]:
                con.execute("UPDATE devices SET reset_requested_at=?,reset_source='admin' WHERE id=?",(ts,device_id))
                con.execute("UPDATE commands SET state='rejected',ack_at=? WHERE device_id=? AND state IN ('queued','dispatched')",(ts,device_id))
        audit("device.reset.request",device_id,"ok",self.ip);signal_stream()
        push_tickle_async(device_id,"reset_requested")
        self.send_json(202,{"ok":True,"status":"reset_pending"})

    def get_device_lifecycle(self, device_id: str) -> None:
        if not rate_allowed("device", device_id+":"+self.ip, 180):
            self.send_error_json(429,"rate_limited","Device request rate exceeded");return
        bearer=self.headers.get("Authorization","")
        if not bearer.startswith("Device ") or not TOKEN_RE.fullmatch(bearer[7:]):
            self.send_error_json(401,"unauthorized","Device token required");return
        digest=sha256_text(bearer[7:])
        with open_db() as con:
            row=con.execute("SELECT token_hash,pending_token_hash,pending_token_expires_at,reset_requested_at,revoked_at FROM devices WHERE id=?",(device_id,)).fetchone()
            dead=con.execute("SELECT 1 FROM device_reset_tombstones WHERE device_id=? AND token_hash=? AND expires_at>?",(device_id,digest,now())).fetchone()
        if dead:
            self.send_json(410,{"resetRequired":True,"status":"deleted"});return
        if row and (hmac.compare_digest(row["token_hash"],digest) or
                    (row["pending_token_hash"] and row["pending_token_expires_at"]>=now() and hmac.compare_digest(row["pending_token_hash"],digest))):
            pending=bool(row["reset_requested_at"] or row["revoked_at"])
            self.send_json(200,{"resetRequired":pending,"status":"reset_pending" if pending else "active"});return
        self.send_error_json(401,"unauthorized","Device credentials not recognized")

    def complete_device_reset(self, device_id: str) -> None:
        ts=now()
        with open_db() as con:
            if not purge_device_record(con,device_id,ts):
                self.send_error_json(404,"device_not_found","Device not found");return
        audit("device.reset.complete",device_id,"ok",self.ip);signal_stream()
        self.send_json(200,{"ok":True,"status":"deleted"})

    def force_delete_device(self, device_id: str) -> None:
        ts=now()
        with open_db() as con:
            if not purge_device_record(con,device_id,ts):
                self.send_error_json(404,"device_not_found","Device not found");return
        audit("device.reset.force",device_id,"ok",self.ip);signal_stream()
        self.send_json(200,{"ok":True,"status":"deleted","deviceMayBeOffline":True})

    def prepare_device_token(self,device_id:str) -> None:
        token=new_token(48);ts=now();expires=ts+3600
        with open_db() as con:
            cur=con.execute("UPDATE devices SET pending_token_hash=?,pending_token_expires_at=? WHERE id=? AND revoked_at IS NULL",(sha256_text(token),expires,device_id))
        if not cur.rowcount:
            self.send_error_json(404,"device_not_found","Active device not found"); return
        audit("device.token.prepare",device_id,"ok",self.ip)
        self.send_json(201,{"deviceToken":token,"expiresAt":expires})

    def commit_device_token(self,device_id:str,token:str) -> None:
        digest=sha256_text(token);ts=now()
        with open_db() as con:
            row=con.execute("SELECT token_hash,pending_token_hash,pending_token_expires_at FROM devices WHERE id=? AND revoked_at IS NULL",(device_id,)).fetchone()
            if not row:
                self.send_error_json(404,"device_not_found","Active device not found"); return
            if hmac.compare_digest(row["token_hash"],digest):
                self.send_json(200,{"ok":True,"alreadyCommitted":True,"tokenIssuedAt":ts}); return
            if not row["pending_token_hash"] or row["pending_token_expires_at"]<ts or not hmac.compare_digest(row["pending_token_hash"],digest):
                self.send_error_json(401,"invalid_pending_token","Pending token is invalid or expired"); return
            con.execute("UPDATE devices SET token_hash=?,token_issued_at=?,pending_token_hash='',pending_token_expires_at=0 WHERE id=?",(digest,ts,device_id))
        audit("device.token.commit",device_id,"ok",self.ip);signal_stream()
        self.send_json(200,{"ok":True,"tokenIssuedAt":ts})

    def heartbeat(self,device_id:str,body:dict[str,Any]) -> None:
        ts=now()
        with open_db() as con:
            con.execute("UPDATE devices SET last_seen_at=?,app_version=COALESCE(NULLIF(?,''),app_version),os_version=COALESCE(NULLIF(?,''),os_version) WHERE id=?",(ts,str(body.get("appVersion",""))[:40],str(body.get("osVersion",""))[:40],device_id))
        self.send_json(200,{"ok":True,"serverTime":ts})

    def ingest_event(self,device_id:str,body:dict[str,Any]) -> None:
        event_id=str(body.get("eventId","")); kind=str(body.get("kind",""))[:80]; cipher=body.get("ciphertext")
        if not event_id or len(event_id)>160 or not kind or not validate_cipher(cipher):
            self.send_error_json(400,"invalid_event","eventId, kind and valid ciphertext are required"); return
        try: occurred=int(body.get("occurredAt",now()))
        except Exception: occurred=now()
        if occurred>now()+300:
            self.send_error_json(400,"invalid_time","Event occurredAt is too far in the future"); return
        has_otp=bool(body.get("hasOtp",False)); sub=str(body.get("subscriptionId",""))[:80]
        metadata=sanitize_metadata(kind,body.get("metadata",{})); received=now()
        try:
            with open_db() as con:
                threshold=con.execute("SELECT sms_purged_before FROM devices WHERE id=?",(device_id,)).fetchone()["sms_purged_before"]
                if kind.startswith("sms.") and occurred<=threshold:
                    self.send_json(200,{"accepted":True,"suppressed":True,"eventId":event_id,"serverSequence":0});return
                cur=con.execute(
                    "INSERT INTO events(id,device_id,kind,occurred_at,received_at,subscription_id,has_otp,metadata_json,ciphertext_json) VALUES(?,?,?,?,?,?,?,?,?)",
                    (event_id,device_id,kind,occurred,received,sub,1 if has_otp else 0,json.dumps(metadata,separators=(",",":")),json.dumps(cipher,separators=(",",":"))),
                )
                seq=cur.lastrowid
                con.execute("UPDATE devices SET last_seen_at=? WHERE id=?",(received,device_id))
                d=con.execute("SELECT name FROM devices WHERE id=?",(device_id,)).fetchone()
        except sqlite3.IntegrityError:
            with open_db() as con:
                row=con.execute("SELECT seq FROM events WHERE id=? AND device_id=?",(event_id,device_id)).fetchone()
            if row:
                self.send_json(200,{"accepted":True,"duplicate":True,"eventId":event_id,"serverSequence":row["seq"]}); return
            self.send_error_json(409,"event_conflict","Event identity conflicts with existing data"); return
        notify_async(kind,device_id,d["name"] if d else "SIM Node",has_otp,occurred)
        signal_stream()
        self.send_json(201,{"accepted":True,"eventId":event_id,"serverSequence":seq})

    def ingest_events_batch(self, device_id: str, body: dict[str, Any]) -> None:
        """Authenticated, atomic, idempotent ingestion of up to 20 encrypted events."""
        items = body.get("events")
        if not isinstance(items,list) or not 1 <= len(items) <= 20:
            self.send_error_json(400,"invalid_batch","Batch must contain 1 to 20 events");return
        received=now(); prepared=[]; seen=set()
        for item in items:
            if not isinstance(item,dict):
                self.send_error_json(400,"invalid_event","Each event must be an object");return
            eid=item.get("eventId");kind=item.get("kind");cipher=item.get("ciphertext")
            if not isinstance(eid,str) or not 1<=len(eid)<=160 or not isinstance(kind,str) or not 1<=len(kind)<=80 or not validate_cipher(cipher) or eid in seen:
                self.send_error_json(400,"invalid_event","Invalid or duplicate event identity/ciphertext");return
            seen.add(eid)
            try: occurred=int(item.get("occurredAt",received))
            except (TypeError,ValueError):
                self.send_error_json(400,"invalid_time","Invalid occurredAt");return
            if occurred>received+300:
                self.send_error_json(400,"invalid_time","Event occurredAt is too far in the future");return
            sub=str(item.get("subscriptionId",""))[:80]
            meta=sanitize_metadata(kind,item.get("metadata",{}))
            prepared.append((eid,device_id,kind,occurred,received,sub,int(bool(item.get("hasOtp",False))),json.dumps(meta,separators=(",",":")),json.dumps(cipher,separators=(",",":"))))
        results=[];notifications=[]
        with open_db() as con:
            d=con.execute("SELECT name,sms_purged_before FROM devices WHERE id=?",(device_id,)).fetchone()
            for row in prepared:
                if row[2].startswith("sms.") and row[3]<=d["sms_purged_before"]:
                    results.append({"eventId":row[0],"accepted":True,"suppressed":True,"duplicate":False,"serverSequence":0})
                    continue
                cur=con.execute("INSERT OR IGNORE INTO events(id,device_id,kind,occurred_at,received_at,subscription_id,has_otp,metadata_json,ciphertext_json) VALUES(?,?,?,?,?,?,?,?,?)",row)
                duplicate=cur.rowcount==0
                seq=con.execute("SELECT seq FROM events WHERE device_id=? AND id=?",(device_id,row[0])).fetchone()["seq"]
                results.append({"eventId":row[0],"accepted":True,"duplicate":duplicate,"serverSequence":seq})
                if not duplicate and row[2]=="sms.received": notifications.append((bool(row[6]),row[3]))
            con.execute("UPDATE devices SET last_seen_at=? WHERE id=?",(received,device_id))
        for has_otp,occurred in notifications:
            notify_async("sms.received",device_id,d["name"] if d else "SIM Node",has_otp,occurred)
        if any(not x.get("duplicate") and not x.get("suppressed") for x in results):signal_stream()
        self.send_json(200,{"accepted":True,"results":results})

    def get_events_by_time(self,q:dict[str,list[str]],limit:int,device:str,kind:str) -> None:
        try:
            bt=int(q.get("beforeTime",["0"])[0]);bs=int(q.get("beforeSeq",["0"])[0])
            if bt<0 or bs<0 or bool(bt)!=bool(bs):raise ValueError()
        except (ValueError,TypeError):
            self.send_error_json(400,"invalid_query","Invalid time pagination cursor");return
        where=["1=1"];args:list[Any]=[]
        if bt:
            where.append("(occurred_at<? OR (occurred_at=? AND seq<?))");args.extend([bt,bt,bs])
        if device:where.append("device_id=?");args.append(device)
        if kind:where.append("kind=?");args.append(kind)
        args.append(limit+1)
        with open_db() as con:
            rows=con.execute(f"SELECT * FROM events WHERE {' AND '.join(where)} ORDER BY occurred_at DESC, seq DESC LIMIT ?",args).fetchall()
        has_more=len(rows)>limit;rows=rows[:limit]
        events=[{"seq":r["seq"],"eventId":r["id"],"deviceId":r["device_id"],"kind":r["kind"],"occurredAt":r["occurred_at"],"receivedAt":r["received_at"],"subscriptionId":r["subscription_id"],"hasOtp":bool(r["has_otp"]),"metadata":safe_json_loads(r["metadata_json"],{}),"ciphertext":safe_json_loads(r["ciphertext_json"],{})} for r in rows]
        last=rows[-1] if rows else None
        self.send_json(200,{"events":events,"hasMore":has_more,"nextBeforeTime":last["occurred_at"] if last else None,"nextBeforeSeq":last["seq"] if last else None})

    def get_events(self,q:dict[str,list[str]]) -> None:
        try:
            since=max(0,int(q.get("since",["0"])[0])); limit=max(1,min(int(q.get("limit",["200"])[0]),1000))
        except ValueError:
            self.send_error_json(400,"invalid_query","since/limit must be integers"); return
        device=q.get("device",[""])[0]; kind=q.get("kind",[""])[0]
        if q.get("order",[""])[0]=="occurred":
            self.get_events_by_time(q,limit,device,kind);return
        latest=q.get("latest",["0"])[0]=="1"
        try: before=max(0,int(q.get("before",["0"])[0]))
        except ValueError:
            self.send_error_json(400,"invalid_query","before must be a sequence number");return
        if latest and before:
            self.send_error_json(400,"invalid_query","latest and before cannot both be set");return
        where=["seq<?" if before else "seq>?" ]; args:list[Any]=[before if before else since]
        if device: where.append("device_id=?"); args.append(device)
        if kind: where.append("kind=?"); args.append(kind)
        args.append(limit)
        with open_db() as con:
            rows=con.execute(f"SELECT * FROM events WHERE {' AND '.join(where)} ORDER BY seq {'DESC' if latest or before else 'ASC'} LIMIT ?",args).fetchall()
        if latest or before:
            rows=list(reversed(rows))
        events=[{"seq":r["seq"],"eventId":r["id"],"deviceId":r["device_id"],"kind":r["kind"],"occurredAt":r["occurred_at"],"receivedAt":r["received_at"],"subscriptionId":r["subscription_id"],"hasOtp":bool(r["has_otp"]),"metadata":safe_json_loads(r["metadata_json"],{}),"ciphertext":safe_json_loads(r["ciphertext_json"],{})} for r in rows]
        self.send_json(200,{"events":events,"nextSince":events[-1]["seq"] if events else since})

    def put_device_state(self,device_id:str,body:dict[str,Any]) -> None:
        state=sanitize_state(body)
        raw=json.dumps(state,ensure_ascii=False,separators=(",",":"))
        if len(raw)>200_000:
            self.send_error_json(413,"state_too_large","State exceeds 200 KB"); return
        ts=now()
        node_type=normalize_node_type(state.get("nodeType","android"))
        capabilities=normalize_capabilities(state.get("capabilities",[]))
        active_key=str(state.get("cryptoKeyId") or "")[:80]
        with open_db() as con:
            con.execute("INSERT INTO device_state(device_id,state_json,updated_at) VALUES(?,?,?) ON CONFLICT(device_id) DO UPDATE SET state_json=excluded.state_json,updated_at=excluded.updated_at",(device_id,raw,ts))
            row=con.execute("SELECT pending_key_id,pending_wrapped_key_json FROM devices WHERE id=?",(device_id,)).fetchone()
            if row and active_key and row["pending_key_id"]==active_key:
                current=con.execute("SELECT key_id,wrapped_key_json FROM devices WHERE id=?",(device_id,)).fetchone()
                if current and current["key_id"] and current["key_id"]!=active_key and validate_cipher(safe_json_loads(current["wrapped_key_json"],{})):
                    con.execute("INSERT OR IGNORE INTO device_key_history(device_id,key_id,wrapped_key_json,archived_at) VALUES(?,?,?,?)",
                                (device_id,current["key_id"],current["wrapped_key_json"],ts))
                con.execute(
                    "UPDATE devices SET last_seen_at=?,node_type=?,capabilities_json=?,key_id=pending_key_id,wrapped_key_json=pending_wrapped_key_json,pending_key_id='',pending_wrapped_key_json='{}' WHERE id=?",
                    (ts,node_type,json.dumps(capabilities,separators=(",",":")),device_id),
                )
            else:
                con.execute("UPDATE devices SET last_seen_at=?,node_type=?,capabilities_json=? WHERE id=?",(ts,node_type,json.dumps(capabilities,separators=(",",":")),device_id))

            for item in state.get("subscriptions",[]):
                sub_id=str(item.get("subscriptionId",""))
                if not sub_id: continue
                sid=str(uuid.uuid5(uuid.NAMESPACE_URL,f"simhub:{device_id}:{sub_id}"))
                con.execute(
                    """INSERT INTO subscriptions(id,device_id,android_sub_id,slot_index,carrier_name,display_name,is_embedded,state_json,first_seen_at,last_seen_at)
                       VALUES(?,?,?,?,?,?,?,?,?,?)
                       ON CONFLICT(device_id,android_sub_id) DO UPDATE SET slot_index=excluded.slot_index,carrier_name=excluded.carrier_name,display_name=excluded.display_name,is_embedded=excluded.is_embedded,state_json=excluded.state_json,last_seen_at=excluded.last_seen_at""",
                    (sid,device_id,sub_id,int(item.get("slotIndex",-1)),str(item.get("carrierName",""))[:120],str(item.get("displayName",""))[:120],1 if item.get("isEmbedded") else 0,json.dumps(item,separators=(",",":")),ts,ts),
                )

            channels=state.get("channels",[])
            if not channels and state.get("subscriptions"):
                channels=[{
                    "id":str(x.get("channelId") or uuid.uuid5(uuid.NAMESPACE_URL,f"simhub:{device_id}:{x.get('subscriptionId','')}")),
                    "localId":str(x.get("subscriptionId","")),
                    "kind":"android-sim",
                    "revision":int(x.get("channelRevision",1) or 1),
                    **x,
                } for x in state["subscriptions"]]
            for item in channels:
                cid=str(item.get("id",""))[:160]
                if not cid: continue
                con.execute(
                    """INSERT INTO channels(id,device_id,local_id,kind,revision,slot_index,carrier_name,display_name,state_json,first_seen_at,last_seen_at)
                       VALUES(?,?,?,?,?,?,?,?,?,?,?)
                       ON CONFLICT(device_id,id) DO UPDATE SET local_id=excluded.local_id,kind=excluded.kind,revision=excluded.revision,slot_index=excluded.slot_index,carrier_name=excluded.carrier_name,display_name=excluded.display_name,state_json=excluded.state_json,last_seen_at=excluded.last_seen_at""",
                    (cid,device_id,str(item.get("localId",""))[:120],str(item.get("kind","sim"))[:40],max(1,int(item.get("revision",1) or 1)),int(item.get("slotIndex",-1) or -1),str(item.get("carrierName",""))[:120],str(item.get("displayName",""))[:120],json.dumps(item,separators=(",",":")),ts,ts),
                )
        signal_stream()
        self.send_json(200,{"ok":True,"updatedAt":ts})

    def get_device_state(self,device_id:str) -> None:
        with open_db() as con:
            r=con.execute("SELECT state_json,updated_at FROM device_state WHERE device_id=?",(device_id,)).fetchone()
        self.send_json(200,{"state":safe_json_loads(r["state_json"],{}) if r else None,"updatedAt":r["updated_at"] if r else None})

    def create_command(self,device_id:str,body:dict[str,Any]) -> None:
        ctype=str(body.get("type",""))
        if ctype.startswith(PHONE_COMMAND_PREFIXES) or ctype not in ALLOWED_COMMANDS:
            self.send_error_json(400,"command_not_allowed","Only SIM/SMS/device commands are supported; phone/call commands are intentionally disabled"); return
        cipher=body.get("ciphertext")
        if not validate_cipher(cipher):
            self.send_error_json(400,"invalid_ciphertext","Valid AES-GCM ciphertext envelope required"); return
        cid=str(body.get("commandId") or uuid.uuid4()); idem=str(body.get("idempotencyKey") or cid)
        if len(cid)>160 or len(idem)>160:
            self.send_error_json(400,"invalid_id","Command IDs too long"); return
        ts=now()
        try: created=int(body.get("createdAt",ts)); exp=int(body.get("expiresAt") or (ts+COMMAND_TTL))
        except Exception:
            self.send_error_json(400,"invalid_time","createdAt/expiresAt must be integers"); return
        if abs(created-ts)>300:
            self.send_error_json(400,"invalid_created_at","Command createdAt must be close to server time"); return
        if exp<=ts or exp>ts+86400:
            self.send_error_json(400,"invalid_expiry","Command expiry must be in the next 24h"); return
        with open_db() as con:
            d=con.execute("SELECT id,revoked_at,reset_requested_at FROM devices WHERE id=?",(device_id,)).fetchone()
            if not d or d["revoked_at"] or d["reset_requested_at"]:
                self.send_error_json(404,"device_not_found","Active device not found"); return
            try:
                cur=con.execute("INSERT INTO commands(id,device_id,type,created_at,expires_at,idempotency_key,ciphertext_json) VALUES(?,?,?,?,?,?,?)",(cid,device_id,ctype,created,exp,idem,json.dumps(cipher,separators=(",",":"))))
                seq=cur.lastrowid
            except sqlite3.IntegrityError:
                r=con.execute("SELECT seq,id,state FROM commands WHERE device_id=? AND idempotency_key=?",(device_id,idem)).fetchone()
                if not r:
                    self.send_error_json(409,"command_conflict","Command identity conflict"); return
                self.send_json(200,{"accepted":True,"duplicate":True,"commandId":r["id"],"serverSequence":r["seq"],"state":r["state"]}); return
        audit("command.create",f"{device_id}:{ctype}","ok",self.ip)
        push_tickle_async(device_id,"command_available"); signal_stream()
        self.send_json(201,{"accepted":True,"commandId":cid,"serverSequence":seq,"state":"queued"})

    def get_pending_commands(self,device_id:str,q:dict[str,list[str]]) -> None:
        ts=now()
        try: limit=max(1,min(int(q.get("limit",["50"])[0]),200))
        except ValueError: limit=50
        with open_db() as con:
            con.execute("UPDATE commands SET state='expired',ack_at=? WHERE device_id=? AND state IN ('queued','dispatched') AND expires_at<=?",(ts,device_id,ts))
            rows=con.execute("SELECT * FROM commands WHERE device_id=? AND state IN ('queued','dispatched') AND expires_at>? ORDER BY seq ASC LIMIT ?",(device_id,ts,limit)).fetchall()
            ids=[r["id"] for r in rows]
            if ids:
                con.executemany("UPDATE commands SET state='dispatched' WHERE id=? AND state='queued'",[(i,) for i in ids])
            con.execute("UPDATE devices SET last_seen_at=? WHERE id=?",(ts,device_id))
        out=[{"seq":r["seq"],"commandId":r["id"],"type":r["type"],"createdAt":r["created_at"],"expiresAt":r["expires_at"],"idempotencyKey":r["idempotency_key"],"ciphertext":safe_json_loads(r["ciphertext_json"],{})} for r in rows]
        self.send_json(200,{"commands":out,"serverTime":ts})

    def ack_command(self,device_id:str,command_id:str,body:dict[str,Any]) -> None:
        state=str(body.get("state",""))
        allowed={"submitted","sent","delivered","succeeded","failed","rejected","expired"}
        if state not in allowed:
            self.send_error_json(400,"invalid_state","Invalid command state"); return
        result=body.get("result",{})
        if not isinstance(result,dict): result={}
        # Positive allowlist: a compromised node must not smuggle SMS text into
        # command status/audit metadata via arbitrary result keys.
        safe_result_keys={"reason","queued","scanned","submitted","subscriptionId","status","enabled","refreshed","rotated","keyId","checked","duplicate"}
        result={k:(v[:120] if isinstance(v,str) else v) for k,v in result.items()
                if k in safe_result_keys and isinstance(v,(str,int,float,bool,type(None)))}
        rank={"queued":0,"dispatched":1,"submitted":2,"sent":3,"delivered":4,"succeeded":4,"failed":4,"rejected":4,"expired":4}
        with open_db() as con:
            row=con.execute("SELECT state FROM commands WHERE id=? AND device_id=?",(command_id,device_id)).fetchone()
            if not row:
                self.send_error_json(404,"command_not_found","Command not found"); return
            current=row["state"]
            if rank.get(state,9) >= rank.get(current,0):
                con.execute("UPDATE commands SET state=?,ack_at=?,result_json=? WHERE id=? AND device_id=?",(state,now(),json.dumps(result,separators=(",",":")),command_id,device_id))
        signal_stream()
        self.send_json(200,{"ok":True})

    def get_recent_commands(self,q:dict[str,list[str]]) -> None:
        """Safe controller progress read: state/result metadata, never ciphertext."""
        try: limit=max(1,min(int(q.get("limit",["20"])[0]),50))
        except ValueError: limit=20
        with open_db() as con:
            rows=con.execute("SELECT id,device_id,type,created_at,expires_at,ack_at,state,result_json FROM commands ORDER BY seq DESC LIMIT ?",(limit,)).fetchall()
        self.send_json(200,{"commands":[{"commandId":r["id"],"deviceId":r["device_id"],"type":r["type"],"createdAt":r["created_at"],"expiresAt":r["expires_at"],"ackAt":r["ack_at"],"state":r["state"],"result":safe_json_loads(r["result_json"],{})} for r in rows]})

    def get_audit(self,q:dict[str,list[str]]) -> None:
        try: limit=max(1,min(int(q.get("limit",["200"])[0]),1000))
        except ValueError: limit=200
        with open_db() as con:
            rows=con.execute("SELECT * FROM audit ORDER BY seq DESC LIMIT ?",(limit,)).fetchall()
        self.send_json(200,{"audit":[dict(r) for r in rows]})

    def get_metrics(self) -> None:
        ts=now()
        with open_db() as con:
            devices=con.execute("SELECT COUNT(*) c FROM devices WHERE revoked_at IS NULL").fetchone()["c"]
            online=con.execute("SELECT COUNT(*) c FROM devices WHERE revoked_at IS NULL AND last_seen_at>=?",(ts-OFFLINE_AFTER,)).fetchone()["c"]
            pending=con.execute("SELECT COUNT(*) c FROM commands WHERE state IN ('queued','dispatched')").fetchone()["c"]
            events24=con.execute("SELECT COUNT(*) c FROM events WHERE received_at>=?",(ts-86400,)).fetchone()["c"]
            oldest=con.execute("SELECT MIN(created_at) v FROM commands WHERE state IN ('queued','dispatched')").fetchone()["v"]
        self.send_json(200,{"devices":devices,"onlineDevices":online,"pendingCommands":pending,"events24h":events24,"oldestPendingCommandSeconds":(ts-oldest) if oldest else 0})

    def stream_events(self) -> None:
        global _stream_epoch
        session_key=sha256_text(_cookie_session(self.headers) or self.ip)
        with _sse_lock:
            if _sse_clients.get(session_key,0) >= MAX_SSE_PER_SESSION:
                self.send_error_json(429,"sse_limit","Too many live streams"); return
            _sse_clients[session_key]=_sse_clients.get(session_key,0)+1
        self.send_response(200)
        self.send_header("Content-Type","text/event-stream; charset=utf-8")
        self.send_header("Cache-Control","no-cache")
        self.send_header("Connection","keep-alive")
        self.send_header("X-Accel-Buffering","no")
        self.security_headers()
        self.end_headers()
        deadline=time.monotonic()+55
        last=-1
        try:
            while time.monotonic()<deadline:
                with _stream_condition:
                    epoch=_stream_epoch
                    if epoch==last:
                        _stream_condition.wait(timeout=15)
                        epoch=_stream_epoch
                if epoch!=last:
                    payload=json.dumps({"epoch":epoch,"time":now()},separators=(",",":"))
                    self.wfile.write(f"event: change\ndata: {payload}\n\n".encode())
                    last=epoch
                else:
                    self.wfile.write(b": ping\n\n")
                self.wfile.flush()
        except (BrokenPipeError,ConnectionResetError,TimeoutError,OSError):
            return
        finally:
            with _sse_lock:
                remaining = _sse_clients.get(session_key,1)-1
                if remaining: _sse_clients[session_key]=remaining
                else: _sse_clients.pop(session_key,None)

    def get_ota(self) -> None:
        if not OTA_FILE.exists():
            self.send_json(200,{"available":False}); return
        try:
            data=json.loads(OTA_FILE.read_text("utf-8"))
            if not isinstance(data,dict): raise ValueError()
            self.send_json(200,{"available":True,**data})
        except Exception:
            self.send_error_json(500,"ota_invalid","OTA metadata file is invalid")

    def serve_static(self,path:str) -> None:
        rel="index.html" if path in ("","/") else path.lstrip("/")
        rel=rel.split("?",1)[0]; candidate=(WEB_ROOT/rel).resolve()
        try: candidate.relative_to(WEB_ROOT.resolve())
        except ValueError:
            self.send_error(403); return
        if not candidate.is_file():
            if "." not in Path(rel).name: candidate=WEB_ROOT/"index.html"
            else: self.send_error(404); return
        data=candidate.read_bytes(); ctype=mimetypes.guess_type(str(candidate))[0] or "application/octet-stream"
        self.send_response(200)
        self.send_header("Content-Type",ctype+("; charset=utf-8" if ctype.startswith("text/") or ctype in {"application/javascript","application/json"} else ""))
        self.send_header("Content-Length",str(len(data)))
        self.send_header("Cache-Control","no-cache" if candidate.name in {"sw.js","manifest.webmanifest"} else "no-store")
        self.security_headers(); self.end_headers(); self.wfile.write(data)


def main() -> None:
    if not re.fullmatch(r"[A-Za-z0-9_.-]{2,64}", ADMIN_USERNAME):
        raise SystemExit("SIMHUB_ADMIN_USERNAME must contain 2-64 safe characters")
    if len(ADMIN_TOKEN)<32 or ADMIN_TOKEN.startswith("change-me"):
        raise SystemExit("SIMHUB_ADMIN_TOKEN must be set to a strong >=32 character random token")
    if REQUIRE_TOTP and not TOTP_SECRET:
        raise SystemExit("SIMHUB_REQUIRE_TOTP is enabled but SIMHUB_TOTP_SECRET is empty")
    if not TOTP_SECRET:
        log.warning("TOTP is disabled; enable SIMHUB_REQUIRE_TOTP=true and configure SIMHUB_TOTP_SECRET for internet-facing deployments")
    if TOTP_SECRET:
        try:
            padded=TOTP_SECRET.upper()+"="*((8-len(TOTP_SECRET)%8)%8)
            base64.b32decode(padded,casefold=True)
        except Exception as exc:
            raise SystemExit("SIMHUB_TOTP_SECRET must be valid Base32") from exc
    init_db()
    threading.Thread(target=maintenance_loop,name="simhub-maintenance",daemon=True).start()
    if SEPARATE_SURFACES and (not MANAGEMENT_ORIGIN.startswith("https://") or not PUBLIC_BASE_URL.startswith("https://") or MANAGEMENT_ORIGIN == PUBLIC_BASE_URL):
        raise SystemExit("Separated origins require distinct HTTPS management and node URLs")
    server=BoundedHTTPServer((BIND,PORT),SimHubHandler)
    log.info("SIM Hub relay %s listening on %s:%s, db=%s",APP_VERSION,BIND,PORT,DB_PATH)
    try: server.serve_forever()
    except KeyboardInterrupt: pass
    finally: server.server_close()


if __name__=="__main__":
    main()
