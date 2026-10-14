#!/usr/bin/env python3
"""Isolated anonymous P2P signaling/mailbox service. Never imports Global Vault.

TLS must terminate at a configured reverse proxy. TURN runs separately in coturn.
No content keys or plaintext SMS are accepted. This is a preview service, not an
operated public endpoint or an app-store approval claim.
"""
import base64
import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import threading
import time
from collections import defaultdict, deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit, parse_qs
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

DOMAIN = "simhub-peer-v1"
IDENTIFIER = re.compile(r"^[A-Za-z0-9_-]{16,80}$")
POLICIES = {"P2P_ONLY", "P2P_RELAY_FALLBACK"}
MAX_FRAME = 600_000


def b64(data):
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def unb64(value):
    if not isinstance(value, str) or len(value) > 800_000:
        raise ValueError("invalid_encoding")
    return base64.b64decode(value + "=" * (-len(value) % 4), altchars=b"-_", validate=True)


class Rejected(Exception):
    def __init__(self, reason, status=403):
        super().__init__(reason)
        self.status = status


class PeerConnect:
    def __init__(self, path, clock=time.time, turn_secret=None, stun_urls=(), turn_urls=()):
        self.path, self.clock = path, clock
        self.turn_secret = turn_secret
        self.stun_urls, self.turn_urls = tuple(stun_urls), tuple(turn_urls)
        self.lock = threading.RLock()
        self.limits = defaultdict(deque)
        with self.db() as db:
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript('''
                CREATE TABLE IF NOT EXISTS challenges(id TEXT PRIMARY KEY,ip TEXT NOT NULL,expires INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS registrations(identity TEXT NOT NULL,peer TEXT NOT NULL,public_key TEXT NOT NULL,policy TEXT NOT NULL,consent INTEGER NOT NULL,token_hash TEXT NOT NULL,expires INTEGER NOT NULL,route TEXT NOT NULL DEFAULT 'OFFLINE',PRIMARY KEY(identity,peer));
                CREATE TABLE IF NOT EXISTS frames(seq INTEGER PRIMARY KEY AUTOINCREMENT,src TEXT NOT NULL,dst TEXT NOT NULL,id TEXT NOT NULL,kind TEXT NOT NULL,frame TEXT NOT NULL,expires INTEGER NOT NULL,UNIQUE(src,dst,id,kind));
                CREATE INDEX IF NOT EXISTS frame_dst ON frames(dst,src,seq);
            ''')

    def db(self):
        db = sqlite3.connect(self.path, timeout=5)
        db.row_factory = sqlite3.Row
        return db

    def rate(self, ip, action, maximum=120):
        with self.lock:
            now = int(self.clock())
            # Hard cap attacker-created IP keys; expire idle entries.
            if len(self.limits) > 5000:
                for key in list(self.limits):
                    if not self.limits[key] or self.limits[key][-1] < now - 60:
                        self.limits.pop(key, None)
                if len(self.limits) > 5000:
                    raise Rejected("service_busy", 429)
            queue = self.limits[(ip, action)]
            while queue and queue[0] < now - 60:
                queue.popleft()
            if len(queue) >= maximum:
                raise Rejected("rate_limited", 429)
            queue.append(now)

    def challenge(self, ip):
        self.rate(ip, "challenge", 10)
        now = int(self.clock())
        identifier = b64(secrets.token_bytes(24))
        with self.db() as db:
            db.execute("DELETE FROM challenges WHERE expires<?", (now,))
            if db.execute("SELECT count(*) FROM challenges").fetchone()[0] >= 10000:
                raise Rejected("service_busy", 429)
            db.execute("INSERT INTO challenges VALUES(?,?,?)", (identifier, ip, now + 60))
        return {"challenge": identifier, "expires": now + 60}

    def register(self, ip, data):
        self.rate(ip, "register", 10)
        if data.get("policy") not in POLICIES or not isinstance(data.get("fallbackConsent"), bool):
            raise Rejected("invalid_policy", 400)
        consent = data["fallbackConsent"]
        if consent != (data["policy"] == "P2P_RELAY_FALLBACK"):
            raise Rejected("policy_consent_mismatch", 400)
        peer = data.get("peer", "")
        if not IDENTIFIER.fullmatch(peer):
            raise Rejected("invalid_peer", 400)
        try:
            der = unb64(data["identity"])
            if len(der) > 256:
                raise ValueError()
            key = serialization.load_der_public_key(der)
            if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
                raise ValueError()
            identity = b64(hashlib.sha256(der).digest())
            if peer == identity:
                raise ValueError()
            transcript = f'{DOMAIN}|register|{data["challenge"]}|{peer}|{data["policy"]}|{str(consent).lower()}|{identity}'
            key.verify(unb64(data["signature"]), transcript.encode(), ec.ECDSA(hashes.SHA256()))
        except Exception as error:
            raise Rejected("identity_proof_rejected") from error
        now = int(self.clock())
        token = b64(secrets.token_bytes(32))
        with self.db() as db:
            challenge = db.execute("SELECT * FROM challenges WHERE id=?", (data["challenge"],)).fetchone()
            if not challenge or challenge["ip"] != ip or challenge["expires"] < now:
                raise Rejected("challenge_expired_or_reused")
            db.execute("DELETE FROM challenges WHERE id=?", (data["challenge"],))
            db.execute("DELETE FROM registrations WHERE expires<?", (now,))
            if db.execute("SELECT count(*) FROM registrations").fetchone()[0] >= 10000:
                raise Rejected("registration_capacity", 429)
            db.execute("INSERT OR REPLACE INTO registrations(identity,peer,public_key,policy,consent,token_hash,expires) VALUES(?,?,?,?,?,?,?)", (identity, peer, data["identity"], data["policy"], int(consent), hashlib.sha256(token.encode()).hexdigest(), now + 3600))
        ice = [{"urls": list(self.stun_urls)}] if self.stun_urls else []
        if consent and self.turn_secret and self.turn_urls:
            username = f'{now + 300}:{identity[:16]}'
            password = base64.b64encode(hmac.new(self.turn_secret.encode(), username.encode(), hashlib.sha1).digest()).decode()
            ice.append({"urls": list(self.turn_urls), "username": username, "credential": password})
        return {"token": token, "expires": now + 3600, "iceServers": ice, "relayBindingState": "ACTIVE"}

    def auth(self, bearer):
        if not bearer or len(bearer) > 120:
            raise Rejected("authentication_required", 401)
        with self.db() as db:
            row = db.execute("SELECT * FROM registrations WHERE token_hash=? AND expires>=?", (hashlib.sha256(bearer.encode()).hexdigest(), int(self.clock()))).fetchone()
        if not row:
            raise Rejected("authentication_expired", 401)
        return dict(row)

    def message(self, actor, data):
        self.rate(actor["identity"], "message", 180)
        kind = data.get("kind")
        if kind not in {"signal", "mailbox"} or data.get("peer") != actor["peer"]:
            raise Rejected("pair_binding_rejected")
        frame = data.get("frame")
        if not isinstance(frame, dict) or frame.get("v") != 1 or frame.get("domain") != DOMAIN:
            raise Rejected("invalid_domain", 400)
        now = int(self.clock())
        if frame.get("from") != actor["identity"] or frame.get("to") != actor["peer"] or not IDENTIFIER.fullmatch(frame.get("id", "")):
            raise Rejected("frame_binding_rejected")
        expires = frame.get("expires")
        if not isinstance(expires, int) or isinstance(expires, bool) or not now <= expires <= now + 300:
            raise Rejected("invalid_expiry", 400)
        try:
            if len(unb64(frame.get("iv"))) != 12 or len(unb64(frame.get("ct"))) < 16:
                raise ValueError()
        except Exception as error:
            raise Rejected("invalid_ciphertext", 400) from error
        packed = json.dumps(frame, separators=(",", ":"))
        if len(packed.encode()) > MAX_FRAME:
            raise Rejected("frame_too_large", 413)
        with self.db() as db:
            recipient = db.execute("SELECT * FROM registrations WHERE identity=? AND peer=? AND expires>=?", (actor["peer"], actor["identity"], now)).fetchone()
            if not recipient:
                raise Rejected("peer_not_registered", 409)
            if kind == "mailbox" and not (actor["consent"] and recipient["consent"] and actor["policy"] == recipient["policy"] == "P2P_RELAY_FALLBACK"):
                raise Rejected("both_peers_must_consent")
            db.execute("DELETE FROM frames WHERE expires<?", (now,))
            existing = db.execute("SELECT seq FROM frames WHERE src=? AND dst=? AND id=? AND kind=?", (actor["identity"], actor["peer"], frame["id"], kind)).fetchone()
            if existing:
                return {"seq": existing["seq"], "duplicate": True}
            amount = db.execute("SELECT count(*),coalesce(sum(length(frame)),0) FROM frames WHERE src=?", (actor["identity"],)).fetchone()
            total = db.execute("SELECT coalesce(sum(length(frame)),0) FROM frames").fetchone()[0]
            if amount[0] >= 128 or amount[1] + len(packed) > 4_000_000 or total + len(packed) > 128_000_000:
                raise Rejected("mailbox_quota", 429)
            result = db.execute("INSERT INTO frames(src,dst,id,kind,frame,expires) VALUES(?,?,?,?,?,?)", (actor["identity"], actor["peer"], frame["id"], kind, packed, expires))
            return {"seq": result.lastrowid, "state": "queued_ciphertext"}

    def poll(self, actor, after=0):
        self.rate(actor["identity"], "poll", 80)
        with self.db() as db:
            db.execute("DELETE FROM frames WHERE expires<?", (int(self.clock()),))
            rows = db.execute("SELECT * FROM frames WHERE dst=? AND src=? AND seq>? ORDER BY seq LIMIT 8", (actor["identity"], actor["peer"], max(0, int(after)))).fetchall()
        return {"messages": [{"seq": row["seq"], "kind": row["kind"], "frame": json.loads(row["frame"])} for row in rows]}

    def ack(self, actor, seq):
        with self.db() as db:
            db.execute("DELETE FROM frames WHERE seq=? AND dst=? AND src=?", (int(seq), actor["identity"], actor["peer"]))
        return {"state": "acknowledged"}

    def revoke(self, actor):
        with self.db() as db:
            db.execute("DELETE FROM registrations WHERE identity=? AND peer=?", (actor["identity"], actor["peer"]))
            db.execute("DELETE FROM frames WHERE (src=? AND dst=?) OR (src=? AND dst=?)", (actor["identity"], actor["peer"], actor["peer"], actor["identity"]))
        return {"state": "revoked"}

    def directory(self):
        now = int(self.clock())
        with self.db() as db:
            rows = db.execute("SELECT identity,peer,policy,consent,route FROM registrations WHERE expires>=? ORDER BY identity,peer LIMIT 10000", (now,)).fetchall()
        identities = {(r["identity"], r["peer"]) for r in rows}
        return {"devices": [{"identity": row["identity"], "peer": row["peer"], "connectionPolicy": row["policy"], "p2pPairState": "ACTIVE" if (row["peer"], row["identity"]) in identities else "PENDING", "relayBindingState": "ACTIVE", "fallbackConsent": bool(row["consent"]), "activeTransport": row["route"], "legacyEnrollment": False} for row in rows]}


def handler(service):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        def log_message(self, *_):
            pass
        def do_GET(self):
            self.handle_request()
        def do_POST(self):
            self.handle_request()
        def handle_request(self):
            try:
                self.connection.settimeout(15)
                service.rate(self.client_address[0], "http", 240)
                parsed = urlsplit(self.path)
                base = "/api/v2/p2p/"
                if not parsed.path.startswith(base):
                    raise Rejected("not_found", 404)
                route = parsed.path[len(base):]
                body = {}
                if self.command == "POST":
                    if self.headers.get("Transfer-Encoding") or self.headers.get("Content-Type", "").split(";")[0] != "application/json":
                        raise Rejected("json_required", 415)
                    length = int(self.headers.get("Content-Length", "0"))
                    if not 1 <= length <= 800_000:
                        raise Rejected("invalid_content_length", 413)
                    body = json.loads(self.rfile.read(length))
                    if not isinstance(body, dict):
                        raise Rejected("object_required", 400)
                if route == "challenge" and self.command == "GET":
                    result = service.challenge(self.client_address[0])
                elif route == "register" and self.command == "POST":
                    result = service.register(self.client_address[0], body)
                elif route == "directory" and self.command == "GET":
                    secret = os.environ.get("P2P_DIRECTORY_ADMIN_TOKEN", "")
                    if len(secret) < 32 or not hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + secret):
                        raise Rejected("directory_admin_required", 401)
                    result = service.directory()
                else:
                    actor = service.auth(self.headers.get("Authorization", "").removeprefix("Bearer "))
                    if route == "poll" and self.command == "GET":
                        result = service.poll(actor, parse_qs(parsed.query).get("after", [0])[0])
                    elif route == "message" and self.command == "POST":
                        result = service.message(actor, body)
                    elif route == "ack" and self.command == "POST":
                        result = service.ack(actor, body["seq"])
                    elif route == "revoke" and self.command == "POST":
                        result = service.revoke(actor)
                    elif route == "status" and self.command == "POST":
                        route = body.get("activeTransport")
                        if route not in {"LAN", "DIRECT", "TURN", "MAILBOX", "OFFLINE"} or (route in {"TURN", "MAILBOX"} and not actor["consent"]):
                            raise Rejected("invalid_transport", 400)
                        with service.db() as db:
                            db.execute("UPDATE registrations SET route=? WHERE identity=? AND peer=?", (route, actor["identity"], actor["peer"]))
                        result = {"state": "recorded_client_report"}
                    else:
                        raise Rejected("not_found", 404)
                self.reply(200, result)
            except Rejected as error:
                self.reply(error.status, {"error": str(error)})
            except (ValueError, TypeError, KeyError, json.JSONDecodeError):
                self.reply(400, {"error": "invalid_request"})
            except Exception:
                self.reply(503, {"error": "service_unavailable"})
        def reply(self, status, data):
            encoded = json.dumps(data, separators=(",", ":")).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(encoded)
            self.close_connection = True
    return Handler


class BoundedServer(ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, *args, **kwargs):
        self.slots = threading.BoundedSemaphore(32)
        super().__init__(*args, **kwargs)
    def process_request(self, request, client_address):
        if not self.slots.acquire(False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self.slots.release()
            raise
    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()


if __name__ == "__main__":
    path = os.environ.get("P2P_DB", "peer-connect.db")
    service = PeerConnect(path, turn_secret=os.environ.get("P2P_TURN_SECRET"), stun_urls=os.environ.get("P2P_STUN_URLS", "").split(), turn_urls=os.environ.get("P2P_TURN_URLS", "").split())
    BoundedServer((os.environ.get("P2P_BIND", "127.0.0.1"), int(os.environ.get("P2P_PORT", "8787"))), handler(service)).serve_forever()
