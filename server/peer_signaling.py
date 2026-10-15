"""Center-auxiliary P2P signaling store: sessions, bindings, bounded queues.

Contract authority: docs/PROTOCOL.md + design v0.1 sections 4-5. The Relay only
stores opaque, pair-encrypted OFFER/ANSWER/ICE envelopes and never decrypts them.
This module deliberately does NOT enter PeerRpc.dispatch, the Legacy Relay
response store or the SMS command pipeline, and it keeps its own quotas instead
of sharing peer_connect.py's mailbox budgets.

Failure semantics are distinct and never reveal other pairings:
authentication (401), binding_mismatch / signature_rejected / authorization_revoked /
binding_not_authorized / session_token_rejected (403), binding_unavailable (404),
session_unavailable (410), challenge_expired_or_reused / revision_stale /
session_conflict / session_not_ready / message_id_conflict / sequence_reused (409),
protocol_unsupported / invalid_request / frame_rejected (400), frame_too_large (413),
rate_limited / binding_limit / signaling_session_limit / frame_quota (429).
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import re
import secrets
import sqlite3
import threading
import uuid
from collections import deque

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

PROTOCOL_VERSION = "center-signaling-v1"
SIGNAL_DOMAIN = "simhub-peer-signal-v1"

CHALLENGE_TTL = 60
SESSION_TTL = 300
MESSAGE_TTL = 120
BINDING_TTL = 30 * 86400
MAX_FRAME = 65536
UNACKED_MAX = 32
DIRECTION_BYTES = 2 * 1024 * 1024
GLOBAL_BYTES = 64 * 1024 * 1024
MAX_BINDINGS_PER_DEVICE = 8
MAX_ACTIVE_SESSIONS = 2
POLL_LIMIT = 8

CHALLENGE_RATE = 60
BINDING_RATE = 10
SESSION_RATE = 10
FRAME_RATE = 60
RATE_WINDOW = 60

IDENTITY = re.compile(r"[A-Za-z0-9_-]{16,80}\Z")
BASE64URL_ALPHABET = frozenset("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")
DIRECTIONS = {"node", "controller"}

_BINDING_FIELDS = {"protocolVersion", "bindingId", "nodeId", "nodePublicKey", "allowedControllerPeerId",
                   "allowedControllerPublicKey", "consentRevision", "allowed", "challengeId", "expires",
                   "signature"}
_SESSION_FIELDS = {"protocolVersion", "sessionId", "bindingId", "nodeId", "nodePublicKey",
                   "controllerPeerId", "controllerPublicKey", "centerId", "revision", "challengeId",
                   "expires", "signature"}
_JOIN_FIELDS = {"protocolVersion", "sessionId", "bindingId", "centerId", "revision", "challengeId",
                "expires", "signature"}
_FRAME_FIELDS = {"v", "domain", "session", "id", "dir", "seq", "expires", "revision", "nonce", "ct"}

_rates: dict[tuple[str, str], deque] = {}
_rates_lock = threading.RLock()


class SignalingRejected(Exception):
    def __init__(self, code: str, status: int):
        super().__init__(code)
        self.code = code
        self.status = status


def init(con: sqlite3.Connection) -> None:
    con.executescript("""
    CREATE TABLE IF NOT EXISTS peer_signal_challenges(
      id TEXT PRIMARY KEY,
      role TEXT NOT NULL,
      device_id TEXT NOT NULL,
      binding_id TEXT NOT NULL DEFAULT '',
      created_at INTEGER NOT NULL,
      expires_at INTEGER NOT NULL
    );
    CREATE INDEX IF NOT EXISTS idx_peer_signal_challenges_expiry ON peer_signal_challenges(expires_at);
    CREATE TABLE IF NOT EXISTS peer_signal_bindings(
      binding_id TEXT PRIMARY KEY,
      device_id TEXT NOT NULL,
      node_id TEXT NOT NULL,
      node_public_key TEXT NOT NULL,
      controller_peer_id TEXT NOT NULL,
      controller_public_key TEXT NOT NULL,
      consent_revision INTEGER NOT NULL,
      allowed INTEGER NOT NULL,
      created_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL,
      expires_at INTEGER NOT NULL,
      UNIQUE(device_id, controller_peer_id)
    );
    CREATE INDEX IF NOT EXISTS idx_peer_signal_bindings_device ON peer_signal_bindings(device_id);
    CREATE INDEX IF NOT EXISTS idx_peer_signal_bindings_expiry ON peer_signal_bindings(expires_at);
    CREATE TABLE IF NOT EXISTS peer_signal_sessions(
      session_id TEXT PRIMARY KEY,
      binding_id TEXT NOT NULL,
      device_id TEXT NOT NULL,
      controller_peer_id TEXT NOT NULL,
      center_id TEXT NOT NULL DEFAULT '',
      revision INTEGER NOT NULL,
      controller_token_hash TEXT NOT NULL DEFAULT '',
      created_at INTEGER NOT NULL,
      expires_at INTEGER NOT NULL,
      controller_joined_at INTEGER NOT NULL DEFAULT 0,
      node_joined_at INTEGER NOT NULL DEFAULT 0,
      closed_at INTEGER NOT NULL DEFAULT 0,
      last_seq_node INTEGER NOT NULL DEFAULT 0,
      last_seq_controller INTEGER NOT NULL DEFAULT 0
    );
    CREATE INDEX IF NOT EXISTS idx_peer_signal_sessions_device ON peer_signal_sessions(device_id, expires_at);
    CREATE INDEX IF NOT EXISTS idx_peer_signal_sessions_expiry ON peer_signal_sessions(expires_at);
    CREATE TABLE IF NOT EXISTS peer_signal_frames(
      seq INTEGER PRIMARY KEY AUTOINCREMENT,
      session_id TEXT NOT NULL,
      direction TEXT NOT NULL,
      message_id TEXT NOT NULL,
      digest TEXT NOT NULL,
      frame_json TEXT NOT NULL,
      size INTEGER NOT NULL,
      created_at INTEGER NOT NULL,
      expires_at INTEGER NOT NULL,
      acked INTEGER NOT NULL DEFAULT 0,
      UNIQUE(session_id, direction, message_id)
    );
    CREATE INDEX IF NOT EXISTS idx_peer_signal_frames_session ON peer_signal_frames(session_id, direction, acked, seq);
    CREATE INDEX IF NOT EXISTS idx_peer_signal_frames_expiry ON peer_signal_frames(expires_at);
    """)


def maintenance(con: sqlite3.Connection, now: int) -> int:
    removed = 0
    removed += con.execute(
        "DELETE FROM peer_signal_frames WHERE expires_at<=? OR session_id IN "
        "(SELECT session_id FROM peer_signal_sessions WHERE expires_at<=? OR closed_at>0)",
        (now, now)).rowcount
    removed += con.execute(
        "DELETE FROM peer_signal_sessions WHERE expires_at<=? OR closed_at>0 OR binding_id IN "
        "(SELECT binding_id FROM peer_signal_bindings WHERE expires_at<=?)", (now, now)).rowcount
    removed += con.execute("DELETE FROM peer_signal_bindings WHERE expires_at<=?", (now,)).rowcount
    removed += con.execute("DELETE FROM peer_signal_challenges WHERE expires_at<=?", (now,)).rowcount
    return removed


def forget_device(con: sqlite3.Connection, device_id: str) -> int:
    """Immediate invalidation: sessions, queued frames and bindings of one node."""
    removed = con.execute(
        "DELETE FROM peer_signal_frames WHERE session_id IN "
        "(SELECT session_id FROM peer_signal_sessions WHERE device_id=?)", (device_id,)).rowcount
    removed += con.execute("DELETE FROM peer_signal_sessions WHERE device_id=?", (device_id,)).rowcount
    removed += con.execute("DELETE FROM peer_signal_bindings WHERE device_id=?", (device_id,)).rowcount
    removed += con.execute("DELETE FROM peer_signal_challenges WHERE device_id=?", (device_id,)).rowcount
    return removed


# -- canonical transcripts (length-prefixed, purpose labelled) --------------

def _lp(value) -> str:
    value = str(value)
    return f"{len(value.encode('utf-8'))}:{value}"


def binding_transcript(*, protocolVersion, bindingId, nodeId, nodePublicKey, controllerPeerId,
                       controllerPublicKey, revision, allowed, challengeId, expires) -> str:
    allowed = "1" if allowed else "0"
    return SIGNAL_DOMAIN + "|binding|" + "".join(_lp(v) for v in (
        protocolVersion, bindingId, nodeId, nodePublicKey, controllerPeerId, controllerPublicKey,
        revision, allowed, challengeId, expires))


def session_transcript(*, protocolVersion, role, centerId, sessionId, bindingId, nodeId, nodePublicKey,
                       controllerPeerId, controllerPublicKey, revision, challengeId, expires) -> str:
    return SIGNAL_DOMAIN + "|session|" + "".join(_lp(v) for v in (
        protocolVersion, role, centerId, sessionId, bindingId, nodeId, nodePublicKey,
        controllerPeerId, controllerPublicKey, revision, challengeId, expires))


# -- primitives -------------------------------------------------------------

def _encode(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def _decode(value, maximum: int) -> bytes:
    if not isinstance(value, str) or not value or len(value) > maximum or any(ch not in BASE64URL_ALPHABET for ch in value):
        raise ValueError("invalid_encoding")
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


def _identity(encoded: str) -> str:
    der = _decode(encoded, 500)
    if len(der) > 256:
        raise ValueError("invalid_key")
    return _encode(hashlib.sha256(der).digest())


def _public_key(encoded):
    key = serialization.load_der_public_key(_decode(encoded, 500))
    if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
        raise ValueError("expected P-256 SPKI key")
    return key


def _verify(encoded: str, transcript: str, signature) -> None:
    try:
        _public_key(encoded).verify(_decode(signature, 300), transcript.encode("utf-8"),
                                    ec.ECDSA(hashes.SHA256()))
    except (InvalidSignature, ValueError, TypeError) as error:
        raise SignalingRejected("signature_rejected", 403) from error


def _rate(bucket: str, key: str, maximum: int, now: int) -> None:
    with _rates_lock:
        if len(_rates) > 4096:
            for name in [k for k, queue in _rates.items() if not queue or queue[-1] < now - RATE_WINDOW]:
                _rates.pop(name, None)
            if len(_rates) > 4096:
                raise SignalingRejected("rate_limited", 429)
        queue = _rates.setdefault((bucket, key), deque())
        while queue and queue[0] < now - RATE_WINDOW:
            queue.popleft()
        if len(queue) >= maximum:
            raise SignalingRejected("rate_limited", 429)
        queue.append(now)


def _request_int(value, minimum=None, maximum=None):
    if type(value) is not int:
        raise SignalingRejected("invalid_request", 400)
    if minimum is not None and value < minimum:
        raise SignalingRejected("invalid_request", 400)
    if maximum is not None and value > maximum:
        raise SignalingRejected("invalid_request", 400)
    return value


def _request_str(value, minimum=1, maximum=500):
    if not isinstance(value, str) or not minimum <= len(value) <= maximum:
        raise SignalingRejected("invalid_request", 400)
    return value


def _device_row(con: sqlite3.Connection, device_id: str, now: int):
    row = con.execute("SELECT id,revoked_at,reset_requested_at FROM devices WHERE id=?",
                      (device_id,)).fetchone()
    if row is None or row["revoked_at"] is not None or row["reset_requested_at"]:
        raise SignalingRejected("authorization_revoked", 403)
    return row


def _check_protocol(value) -> None:
    if value != PROTOCOL_VERSION:
        raise SignalingRejected("protocol_unsupported", 400)


def challenge(con: sqlite3.Connection, role: str, device_id: str, now: int,
              binding_id: str = "") -> dict:
    if role not in DIRECTIONS:
        raise SignalingRejected("invalid_request", 400)
    if role == "node":
        _device_row(con, device_id, now)
    _rate("challenge", f"{role}:{device_id or binding_id}", CHALLENGE_RATE, now)
    identifier = _encode(secrets.token_bytes(24))
    con.execute("DELETE FROM peer_signal_challenges WHERE expires_at<=?", (now,))
    con.execute("INSERT INTO peer_signal_challenges(id,role,device_id,binding_id,created_at,expires_at) "
                "VALUES(?,?,?,?,?,?)",
                (identifier, role, device_id or binding_id, binding_id if role == "controller" else "",
                 now, now + CHALLENGE_TTL))
    return {"challengeId": identifier, "expires": now + CHALLENGE_TTL}


def _consume_challenge(con: sqlite3.Connection, challenge_id, *, role: str, device_id: str,
                       binding_id: str, now: int) -> None:
    row = None
    if isinstance(challenge_id, str) and 16 <= len(challenge_id) <= 80:
        row = con.execute("SELECT * FROM peer_signal_challenges WHERE id=?", (challenge_id,)).fetchone()
        if row is not None:
            con.execute("DELETE FROM peer_signal_challenges WHERE id=?", (challenge_id,))
    if (row is None or row["role"] != role or row["device_id"] != device_id
            or row["binding_id"] != binding_id or row["expires_at"] <= now):
        raise SignalingRejected("challenge_expired_or_reused", 409)


def bind(con: sqlite3.Connection, device_id: str, body, now: int) -> dict:
    if not isinstance(body, dict) or set(body) != _BINDING_FIELDS:
        raise SignalingRejected("invalid_request", 400)
    _rate("binding", device_id, BINDING_RATE, now)
    _check_protocol(body["protocolVersion"])
    binding_id = _request_str(body["bindingId"], 0, 80)
    node_id = _request_str(body["nodeId"], 16, 80)
    node_public_key = _request_str(body["nodePublicKey"])
    controller_peer_id = _request_str(body["allowedControllerPeerId"], 16, 80)
    controller_public_key = _request_str(body["allowedControllerPublicKey"])
    consent_revision = _request_int(body["consentRevision"], 1, 2 ** 31)
    if type(body["allowed"]) is not bool:
        raise SignalingRejected("invalid_request", 400)
    allowed = body["allowed"]
    challenge_id = _request_str(body["challengeId"], 16, 80)
    expires = _request_int(body["expires"], now + 1, now + BINDING_TTL)
    signature = _request_str(body["signature"], 1, 300)
    _device_row(con, device_id, now)
    if _identity(node_public_key) != node_id or _identity(controller_public_key) != controller_peer_id:
        raise SignalingRejected("binding_mismatch", 403)
    if con.execute("SELECT 1 FROM peer_signal_bindings WHERE node_id=? AND device_id!=?",
                   (node_id, device_id)).fetchone():
        raise SignalingRejected("binding_mismatch", 403)
    recorded = con.execute("SELECT node_id FROM peer_signal_bindings WHERE device_id=? LIMIT 1",
                           (device_id,)).fetchone()
    if recorded is not None and recorded["node_id"] != node_id:
        raise SignalingRejected("binding_mismatch", 403)
    transcript = binding_transcript(protocolVersion=body["protocolVersion"], bindingId=binding_id,
                                    nodeId=node_id, nodePublicKey=node_public_key,
                                    controllerPeerId=controller_peer_id,
                                    controllerPublicKey=controller_public_key,
                                    revision=consent_revision, allowed=allowed,
                                    challengeId=challenge_id, expires=expires)
    # The one-time challenge is consumed before any decision so a replayed,
    # expired or forged request can never be reused with different content.
    _consume_challenge(con, challenge_id, role="node", device_id=device_id, binding_id="", now=now)
    _verify(node_public_key, transcript, signature)
    created = False
    existing = None
    if binding_id:
        existing = con.execute("SELECT * FROM peer_signal_bindings WHERE binding_id=?",
                               (binding_id,)).fetchone()
        if (existing is None or existing["device_id"] != device_id or existing["node_id"] != node_id
                or existing["node_public_key"] != node_public_key
                or existing["controller_peer_id"] != controller_peer_id
                or existing["controller_public_key"] != controller_public_key):
            raise SignalingRejected("binding_mismatch", 403)
        if consent_revision <= existing["consent_revision"]:
            raise SignalingRejected("revision_stale", 409)
    else:
        if not allowed:
            raise SignalingRejected("binding_mismatch", 403)
        if con.execute("SELECT 1 FROM peer_signal_bindings WHERE device_id=? AND controller_peer_id=?",
                       (device_id, controller_peer_id)).fetchone():
            raise SignalingRejected("binding_mismatch", 403)
        if con.execute("SELECT COUNT(*) FROM peer_signal_bindings WHERE device_id=?",
                       (device_id,)).fetchone()[0] >= MAX_BINDINGS_PER_DEVICE:
            raise SignalingRejected("binding_limit", 429)
    if existing is None:
        binding_id = str(uuid.uuid4())
        created = True
        con.execute(
            "INSERT INTO peer_signal_bindings(binding_id,device_id,node_id,node_public_key,"
            "controller_peer_id,controller_public_key,consent_revision,allowed,created_at,updated_at,"
            "expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            (binding_id, device_id, node_id, node_public_key, controller_peer_id, controller_public_key,
             consent_revision, int(allowed), now, now, expires))
    else:
        con.execute("UPDATE peer_signal_bindings SET consent_revision=?,allowed=?,updated_at=?,"
                    "expires_at=? WHERE binding_id=?",
                    (consent_revision, int(allowed), now, expires, binding_id))
        # An authorization change invalidates its sessions and queued signaling.
        con.execute("DELETE FROM peer_signal_frames WHERE session_id IN "
                    "(SELECT session_id FROM peer_signal_sessions WHERE binding_id=?)", (binding_id,))
        con.execute("DELETE FROM peer_signal_sessions WHERE binding_id=?", (binding_id,))
    return {"bindingId": binding_id, "created": created, "acceptedRevision": consent_revision,
            "allowed": allowed, "expires": expires, "protocolVersion": PROTOCOL_VERSION}


def list_bindings(con: sqlite3.Connection, device_id: str, now: int) -> dict:
    rows = con.execute("SELECT binding_id,controller_peer_id,allowed,consent_revision,expires_at "
                       "FROM peer_signal_bindings WHERE device_id=? AND expires_at>? "
                       "ORDER BY created_at,binding_id", (device_id, now)).fetchall()
    return {"bindings": [{"bindingId": row["binding_id"], "controllerPeerId": row["controller_peer_id"],
                          "allowed": bool(row["allowed"]), "consentRevision": row["consent_revision"],
                          "expires": row["expires_at"]} for row in rows]}


def controller_challenge(con: sqlite3.Connection, binding_id: str, now: int) -> dict:
    row = con.execute("SELECT * FROM peer_signal_bindings WHERE binding_id=?", (binding_id,)).fetchone()
    if row is None or row["expires_at"] <= now:
        raise SignalingRejected("binding_unavailable", 404)
    if not row["allowed"]:
        raise SignalingRejected("binding_not_authorized", 403)
    _rate("challenge", f"controller:{binding_id}", CHALLENGE_RATE, now)
    identifier = _encode(secrets.token_bytes(24))
    con.execute("DELETE FROM peer_signal_challenges WHERE expires_at<=?", (now,))
    con.execute("INSERT INTO peer_signal_challenges(id,role,device_id,binding_id,created_at,expires_at) "
                "VALUES(?,?,?,?,?,?)",
                (identifier, "controller", row["device_id"], binding_id, now, now + CHALLENGE_TTL))
    return {"challengeId": identifier, "expires": now + CHALLENGE_TTL, "bindingId": binding_id,
            "nodeId": row["node_id"], "nodePublicKey": row["node_public_key"],
            "controllerPeerId": row["controller_peer_id"], "controllerPublicKey": row["controller_public_key"],
            "revision": row["consent_revision"]}


def _load_session(con: sqlite3.Connection, session_id: str, now: int, device_id=None):
    session = con.execute("SELECT * FROM peer_signal_sessions WHERE session_id=?", (session_id,)).fetchone()
    if session is None or session["closed_at"] > 0 or session["expires_at"] <= now:
        raise SignalingRejected("session_unavailable", 410)
    if device_id is not None and session["device_id"] != device_id:
        raise SignalingRejected("session_unavailable", 410)
    _device_row(con, session["device_id"], now)
    binding = con.execute("SELECT * FROM peer_signal_bindings WHERE binding_id=?",
                          (session["binding_id"],)).fetchone()
    if (binding is None or not binding["allowed"] or binding["expires_at"] <= now
            or binding["consent_revision"] != session["revision"]):
        raise SignalingRejected("authorization_revoked", 403)
    return session, binding


def _require_ready(session) -> None:
    if not (session["controller_joined_at"] and session["node_joined_at"]):
        raise SignalingRejected("session_not_ready", 409)


def create_session(con: sqlite3.Connection, body, now: int) -> dict:
    if not isinstance(body, dict) or set(body) != _SESSION_FIELDS:
        raise SignalingRejected("invalid_request", 400)
    _check_protocol(body["protocolVersion"])
    session_id = _request_str(body["sessionId"], 0, 80)
    binding_id = _request_str(body["bindingId"], 1, 80)
    node_id = _request_str(body["nodeId"], 16, 80)
    node_public_key = _request_str(body["nodePublicKey"])
    controller_peer_id = _request_str(body["controllerPeerId"], 16, 80)
    controller_public_key = _request_str(body["controllerPublicKey"])
    center_id = _request_str(body["centerId"], 1, 120)
    revision = _request_int(body["revision"], 1, 2 ** 31)
    challenge_id = _request_str(body["challengeId"], 16, 80)
    expires = _request_int(body["expires"], now + 1, now + SESSION_TTL)
    signature = _request_str(body["signature"], 1, 300)
    binding = con.execute("SELECT * FROM peer_signal_bindings WHERE binding_id=?", (binding_id,)).fetchone()
    if binding is None or binding["expires_at"] <= now:
        raise SignalingRejected("binding_unavailable", 404)
    if not binding["allowed"]:
        raise SignalingRejected("binding_not_authorized", 403)
    device_id = binding["device_id"]
    _device_row(con, device_id, now)
    _rate("session", device_id, SESSION_RATE, now)
    if (binding["node_id"] != node_id or binding["node_public_key"] != node_public_key
            or binding["controller_peer_id"] != controller_peer_id
            or binding["controller_public_key"] != controller_public_key):
        raise SignalingRejected("binding_mismatch", 403)
    if revision != binding["consent_revision"]:
        raise SignalingRejected("revision_stale", 409)
    _consume_challenge(con, challenge_id, role="controller", device_id=device_id,
                       binding_id=binding_id, now=now)
    _verify(controller_public_key, session_transcript(
        protocolVersion=body["protocolVersion"], role="controller", centerId=center_id,
        sessionId=session_id, bindingId=binding_id, nodeId=node_id, nodePublicKey=node_public_key,
        controllerPeerId=controller_peer_id, controllerPublicKey=controller_public_key, revision=revision,
        challengeId=challenge_id, expires=expires), signature)
    if not IDENTITY.fullmatch(session_id):
        raise SignalingRejected("invalid_request", 400)
    if con.execute("SELECT 1 FROM peer_signal_sessions WHERE session_id=?", (session_id,)).fetchone():
        raise SignalingRejected("session_conflict", 409)
    if con.execute("SELECT COUNT(*) FROM peer_signal_sessions WHERE device_id=? AND closed_at=0 "
                   "AND expires_at>?", (device_id, now)).fetchone()[0] >= MAX_ACTIVE_SESSIONS:
        raise SignalingRejected("signaling_session_limit", 429)
    token = secrets.token_urlsafe(32)
    con.execute(
        "INSERT INTO peer_signal_sessions(session_id,binding_id,device_id,controller_peer_id,center_id,"
        "revision,controller_token_hash,created_at,expires_at,controller_joined_at) "
        "VALUES(?,?,?,?,?,?,?,?,?,?)",
        (session_id, binding_id, device_id, controller_peer_id, center_id, revision,
         hashlib.sha256(token.encode()).hexdigest(), now, expires, now))
    return {"sessionId": session_id, "state": "awaiting_node", "expires": expires, "sessionToken": token}


def join_session(con: sqlite3.Connection, device_id: str, session_id: str, body, now: int) -> dict:
    if not isinstance(body, dict) or set(body) != _JOIN_FIELDS:
        raise SignalingRejected("invalid_request", 400)
    _check_protocol(body["protocolVersion"])
    signed_session = _request_str(body["sessionId"], 16, 80)
    binding_id = _request_str(body["bindingId"], 1, 80)
    center_id = _request_str(body["centerId"], 1, 120)
    revision = _request_int(body["revision"], 1, 2 ** 31)
    challenge_id = _request_str(body["challengeId"], 16, 80)
    expires = _request_int(body["expires"], 1, 2 ** 31)
    signature = _request_str(body["signature"], 1, 300)
    session, binding = _load_session(con, session_id, now)
    if session["device_id"] != device_id:
        raise SignalingRejected("session_unavailable", 410)
    if (signed_session != session["session_id"] or binding_id != session["binding_id"]
            or center_id != session["center_id"] or revision != session["revision"]
            or expires != session["expires_at"]):
        raise SignalingRejected("binding_mismatch", 403)
    _consume_challenge(con, challenge_id, role="node", device_id=device_id, binding_id="", now=now)
    _verify(binding["node_public_key"], session_transcript(
        protocolVersion=body["protocolVersion"], role="node", centerId=center_id, sessionId=session_id,
        bindingId=session["binding_id"], nodeId=binding["node_id"], nodePublicKey=binding["node_public_key"],
        controllerPeerId=binding["controller_peer_id"],
        controllerPublicKey=binding["controller_public_key"], revision=revision,
        challengeId=challenge_id, expires=expires), signature)
    con.execute("UPDATE peer_signal_sessions SET node_joined_at=? WHERE session_id=? AND node_joined_at=0",
                (now, session_id))
    return {"sessionId": session_id, "state": "active", "expires": session["expires_at"]}


def list_sessions(con: sqlite3.Connection, device_id: str, now: int) -> dict:
    rows = con.execute("SELECT * FROM peer_signal_sessions WHERE device_id=? AND closed_at=0 AND expires_at>? "
                       "ORDER BY created_at,session_id", (device_id, now)).fetchall()
    return {"sessions": [{"sessionId": row["session_id"], "bindingId": row["binding_id"],
                          "controllerPeerId": row["controller_peer_id"], "centerId": row["center_id"],
                          "revision": row["revision"], "expires": row["expires_at"],
                          "state": "active" if row["node_joined_at"] else "awaiting_node"} for row in rows]}


def _check_actor(con: sqlite3.Connection, session, direction: str, token, device_id=None) -> None:
    if direction not in DIRECTIONS:
        raise SignalingRejected("invalid_request", 400)
    if direction == "controller":
        if not isinstance(token, str) or not token or not hmac.compare_digest(
                hashlib.sha256(token.encode()).hexdigest(), session["controller_token_hash"]):
            raise SignalingRejected("session_token_rejected", 403)
    elif not device_id or device_id != session["device_id"]:
        # Another node never learns whether this session exists.
        raise SignalingRejected("session_unavailable", 410)


def _frame_digest(packed: str) -> str:
    return hashlib.sha256(packed.encode()).hexdigest()


def send_frame(con: sqlite3.Connection, session_id: str, direction: str, frame, now: int,
               token=None, device_id=None) -> dict:
    _rate("frame", f"{session_id}:{direction}", FRAME_RATE, now)
    session, binding = _load_session(con, session_id, now, device_id)
    _check_actor(con, session, direction, token, device_id)
    _require_ready(session)
    if not isinstance(frame, dict) or set(frame) != _FRAME_FIELDS:
        raise SignalingRejected("frame_rejected", 400)
    if type(frame["v"]) is not int or frame["v"] != 1 or frame["domain"] != SIGNAL_DOMAIN:
        raise SignalingRejected("frame_rejected", 400)
    if frame["session"] != session_id or frame["dir"] != direction:
        raise SignalingRejected("frame_rejected", 400)
    if not isinstance(frame["id"], str) or not IDENTITY.fullmatch(frame["id"]):
        raise SignalingRejected("frame_rejected", 400)
    if type(frame["seq"]) is not int or frame["seq"] < 1:
        raise SignalingRejected("frame_rejected", 400)
    if type(frame["revision"]) is not int or frame["revision"] != session["revision"]:
        raise SignalingRejected("frame_rejected", 400)
    if type(frame["expires"]) is not int or not now <= frame["expires"] <= min(now + MESSAGE_TTL,
                                                                              session["expires_at"]):
        raise SignalingRejected("frame_rejected", 400)
    packed = json.dumps(frame, separators=(",", ":"))
    size = len(packed.encode())
    if size > MAX_FRAME:
        raise SignalingRejected("frame_too_large", 413)
    try:
        if len(_decode(frame["nonce"], 40)) != 12 or len(_decode(frame["ct"], MAX_FRAME)) < 16:
            raise ValueError("invalid_cipher")
    except ValueError as error:
        raise SignalingRejected("frame_rejected", 400) from error
    con.execute("DELETE FROM peer_signal_frames WHERE expires_at<=?", (now,))
    digest = _frame_digest(packed)
    existing = con.execute("SELECT seq,digest FROM peer_signal_frames WHERE session_id=? AND direction=? "
                           "AND message_id=?", (session_id, direction, frame["id"])).fetchone()
    if existing is not None:
        if existing["digest"] == digest:
            return {"seq": existing["seq"]}
        raise SignalingRejected("message_id_conflict", 409)
    column = "last_seq_node" if direction == "node" else "last_seq_controller"
    if frame["seq"] <= session[column]:
        raise SignalingRejected("sequence_reused", 409)
    # One retransmission keeps the same message id, so ack state never counts duplicates.
    pending = con.execute("SELECT COUNT(*),COALESCE(SUM(size),0) FROM peer_signal_frames "
                          "WHERE session_id=? AND direction=? AND acked=0", (session_id, direction)).fetchone()
    total = con.execute("SELECT COALESCE(SUM(size),0) FROM peer_signal_frames").fetchone()[0]
    if pending[0] >= UNACKED_MAX or pending[1] + size > DIRECTION_BYTES or total + size > GLOBAL_BYTES:
        raise SignalingRejected("frame_quota", 429)
    result = con.execute(
        "INSERT INTO peer_signal_frames(session_id,direction,message_id,digest,frame_json,size,"
        "created_at,expires_at) VALUES(?,?,?,?,?,?,?,?)",
        (session_id, direction, frame["id"], digest, packed, size, now, frame["expires"]))
    con.execute(f"UPDATE peer_signal_sessions SET {column}=? WHERE session_id=?", (frame["seq"], session_id))
    return {"seq": result.lastrowid}


def poll(con: sqlite3.Connection, session_id: str, direction: str, after, now: int, token=None,
         device_id=None) -> dict:
    session, _binding = _load_session(con, session_id, now, device_id)
    _check_actor(con, session, direction, token, device_id)
    _require_ready(session)
    if type(after) is not int or after < 0:
        raise SignalingRejected("invalid_request", 400)
    con.execute("DELETE FROM peer_signal_frames WHERE expires_at<=?", (now,))
    opposite = "controller" if direction == "node" else "node"
    rows = con.execute("SELECT seq,frame_json FROM peer_signal_frames WHERE session_id=? AND direction=? "
                       "AND seq>? ORDER BY seq LIMIT ?",
                       (session_id, opposite, after, POLL_LIMIT)).fetchall()
    return {"messages": [{"seq": row["seq"], "frame": json.loads(row["frame_json"])} for row in rows]}


def ack(con: sqlite3.Connection, session_id: str, direction: str, seq, now: int, token=None,
        device_id=None) -> dict:
    session, _binding = _load_session(con, session_id, now, device_id)
    _check_actor(con, session, direction, token, device_id)
    _require_ready(session)
    if type(seq) is not int:
        raise SignalingRejected("invalid_request", 400)
    opposite = "controller" if direction == "node" else "node"
    con.execute("UPDATE peer_signal_frames SET acked=1 WHERE session_id=? AND direction=? AND seq=?",
                (session_id, opposite, seq))
    return {"acknowledged": True}


def close_session(con: sqlite3.Connection, session_id: str, now: int, token=None,
                  device_id=None) -> dict:
    session, _binding = _load_session(con, session_id, now, device_id)
    if token is not None:
        if not hmac.compare_digest(hashlib.sha256(str(token).encode()).hexdigest(),
                                   session["controller_token_hash"]):
            raise SignalingRejected("session_token_rejected", 403)
    elif device_id is None:
        # Module-internal calls only; HTTP routes always carry device auth or a token.
        pass
    con.execute("DELETE FROM peer_signal_frames WHERE session_id=?", (session_id,))
    con.execute("UPDATE peer_signal_sessions SET closed_at=?,expires_at=? WHERE session_id=?",
                (now, now, session_id))
    return {"closed": True}
