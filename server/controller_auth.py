"""Zero-knowledge, explicit approval for independent Android management controllers.

Controller credentials authenticate to the existing administrator session API.
The Relay cannot decrypt the sealed Vault; grants are made by an unlocked Web client.
"""
from __future__ import annotations
import base64
import json
import secrets
import sqlite3
import time
import uuid
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.exceptions import InvalidSignature
import global_vault

PAIR_TTL = 600
LOGIN_TTL = 90
# Explicit, time-limited bridge for pre-v0.15.7 Android pairing clients.
# Existing approved controllers continue to use their signed session protocol.
LEGACY_PAIR_SUNSET = 1802304000  # 2027-02-11 UTC

def b64decode(value: str, maximum: int = 2048) -> bytes:
    if not isinstance(value, str) or not value or len(value) > maximum:
        raise ValueError("Invalid encoded key")
    if any(c not in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_" for c in value):
        raise ValueError("Invalid base64url")
    return base64.urlsafe_b64decode(value + "=" * ((-len(value)) % 4))

def public_key(encoded: str):
    key = serialization.load_der_public_key(b64decode(encoded, 500))
    if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
        raise ValueError("Expected P-256 SPKI public key")
    return key

def init(con: sqlite3.Connection) -> None:
    con.executescript("""
    CREATE TABLE IF NOT EXISTS controller_requests(
      id TEXT PRIMARY KEY,
      label TEXT NOT NULL,
      client_ip TEXT NOT NULL,
      identity_public TEXT NOT NULL,
      exchange_public TEXT NOT NULL,
      code_hash TEXT NOT NULL,
      poll_token_hash TEXT NOT NULL DEFAULT '',
      created_at INTEGER NOT NULL,
      expires_at INTEGER NOT NULL,
      approved_at INTEGER,
      envelope_json TEXT
    );
    CREATE INDEX IF NOT EXISTS idx_controller_requests_expiry ON controller_requests(expires_at);
    CREATE TABLE IF NOT EXISTS controllers(
      id TEXT PRIMARY KEY,
      label TEXT NOT NULL,
      identity_public TEXT NOT NULL,
      approved_at INTEGER NOT NULL,
      revoked_at INTEGER,
      admin_fingerprint TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS controller_challenges(
      digest TEXT PRIMARY KEY,
      controller_id TEXT NOT NULL,
      expires_at INTEGER NOT NULL
    );
    """)
    # Existing installations require an additive migration for in-flight pairings.
    cols = {r[1] for r in con.execute("PRAGMA table_info(controller_requests)")}
    if "poll_token_hash" not in cols:
        con.execute("ALTER TABLE controller_requests ADD COLUMN poll_token_hash TEXT NOT NULL DEFAULT ''")
    global_vault.init(con)

def maintenance(con: sqlite3.Connection, now: int) -> None:
    con.execute("DELETE FROM controller_requests WHERE expires_at < ?", (now - 86400,))
    con.execute("DELETE FROM controller_challenges WHERE expires_at <= ?", (now,))

def start(con: sqlite3.Connection, body: dict, ip: str, now: int) -> dict:
    label = str(body.get("label", "")).strip()[:64]
    identity = body.get("identityPublic")
    exchange = body.get("exchangePublic")
    public_key(identity)
    public_key(exchange)
    if not label:
        raise ValueError("Controller label required")
    con.execute("DELETE FROM controller_requests WHERE expires_at < ?", (now,))
    n = con.execute("SELECT COUNT(*) FROM controller_requests WHERE client_ip=? AND approved_at IS NULL",
                    (ip,)).fetchone()[0]
    if n >= 3:
        raise ValueError("Too many pending controller pairings")
    # Legacy Android v0.15.6 sends no protocol field and cannot return a poll
    # token. Keep it bound to the initiating IP until its short expiry; v2
    # requests always require an independently generated high-entropy token.
    version = body.get("pairingProtocolVersion", 1)
    if type(version) is not int or version not in (1, 2):
        raise ValueError("Unsupported controller pairing protocol")
    if version == 1 and now >= LEGACY_PAIR_SUNSET:
        raise ValueError("Legacy pairing retired; update the Android client")
    request_id = str(uuid.uuid4())
    code = f"{secrets.randbelow(1000000):06d}"
    poll_token = secrets.token_urlsafe(32) if version == 2 else ""
    import hashlib
    digest = hashlib.sha256((request_id + "|" + code).encode()).hexdigest()
    con.execute("""INSERT INTO controller_requests(id,label,client_ip,identity_public,exchange_public,
                  code_hash,poll_token_hash,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?)""",
                (request_id,label,ip,identity,exchange,digest,hashlib.sha256(poll_token.encode()).hexdigest() if poll_token else "",now,now+PAIR_TTL))
    response = {"requestId":request_id,"pairingCode":code,"expiresIn":PAIR_TTL,
                "pairingProtocolVersion":version}
    if poll_token:
        response["pollToken"] = poll_token
    return response

def pending(con: sqlite3.Connection, now: int) -> list[dict]:
    rows = con.execute("""SELECT id,label,identity_public,exchange_public,created_at,expires_at
                          FROM controller_requests WHERE expires_at>? AND approved_at IS NULL
                          ORDER BY created_at DESC LIMIT 15""",(now,)).fetchall()
    import hashlib
    return [{"requestId":r["id"],"label":r["label"],
             "fingerprint":hashlib.sha256(b64decode(r["identity_public"])).hexdigest()[:16],
             "identityPublic":r["identity_public"],"exchangePublic":r["exchange_public"],
             "createdAt":r["created_at"],"expiresAt":r["expires_at"]} for r in rows]

def approve(con: sqlite3.Connection, body: dict, now: int, admin_fingerprint: str) -> dict:
    request_id=str(body.get("requestId",""))
    code=str(body.get("pairingCode",""))
    envelope=body.get("vaultEnvelope")
    if not isinstance(envelope,dict) or envelope.get("v") != 1 or not isinstance(code,str) or len(code)!=6:
        raise ValueError("Invalid controller grant")
    for key,limit in (("epk",500),("salt",100),("iv",100),("ct",200)):
        b64decode(envelope.get(key),limit)
    if len(b64decode(envelope["salt"]))!=16 or len(b64decode(envelope["iv"]))!=12 or len(b64decode(envelope["ct"]))!=48:
        raise ValueError("Invalid sealed Vault envelope")
    public_key(envelope["epk"])
    # Never approve a newly paired controller for a different local Master Key.
    # The Relay checks the public identifier, not the key material.
    root=global_vault.status(con)
    if root["initialized"] and (body.get("vaultId")!=root["vaultId"] or body.get("vaultKeyId")!=root["keyId"]):
        raise ValueError("Controller grant does not match the unique Global Vault")
    row=con.execute("""SELECT * FROM controller_requests WHERE id=? AND expires_at>?
                       AND approved_at IS NULL""",(request_id,now)).fetchone()
    import hashlib, hmac
    if row is None or not hmac.compare_digest(row["code_hash"],
                hashlib.sha256((request_id+"|"+code).encode()).hexdigest()):
        raise ValueError("Pairing code is incorrect or expired")
    controller_id=str(uuid.uuid4())
    con.execute("""INSERT INTO controllers(id,label,identity_public,approved_at,admin_fingerprint)
                   VALUES(?,?,?,?,?)""",(controller_id,row["label"],row["identity_public"],
                                          now,admin_fingerprint))
    con.execute("UPDATE controller_requests SET approved_at=?,envelope_json=? WHERE id=?",
                (now,json.dumps({"controllerId":controller_id,"vaultEnvelope":envelope,"grantVaultId":root["vaultId"],"grantVaultKeyId":root["keyId"]}),request_id))
    return {"ok":True,"controllerId":controller_id}

def poll(con: sqlite3.Connection, request_id: str, ip: str, now: int, poll_token: str = "") -> dict:
    row=con.execute("SELECT * FROM controller_requests WHERE id=?",(request_id,)).fetchone()
    # A separate high-entropy polling credential survives Wi-Fi / mobile data handover.
    # Explicit legacy-v1 or pre-migration requests retain their original IP restriction.
    import hashlib, hmac
    if not row or (row["poll_token_hash"] and not (poll_token and hmac.compare_digest(
            row["poll_token_hash"],hashlib.sha256(poll_token.encode()).hexdigest()))) or (
            not row["poll_token_hash"] and row["client_ip"]!=ip):
        raise ValueError("Unknown pairing request")
    if row["expires_at"]<=now:
        return {"state":"expired"}
    if row["approved_at"] is None:
        return {"state":"pending","expiresAt":row["expires_at"]}
    return {"state":"approved",**json.loads(row["envelope_json"])}

def challenge(con: sqlite3.Connection, controller_id: str, now: int, fingerprint: str) -> dict:
    row=con.execute("""SELECT id FROM controllers WHERE id=? AND revoked_at IS NULL
                       AND admin_fingerprint=?""",(controller_id,fingerprint)).fetchone()
    if not row:
        raise ValueError("Controller not authorized")
    nonce=base64.urlsafe_b64encode(secrets.token_bytes(32)).decode().rstrip("=")
    import hashlib
    con.execute("INSERT INTO controller_challenges(digest,controller_id,expires_at) VALUES(?,?,?)",
                (hashlib.sha256(nonce.encode()).hexdigest(),controller_id,now+LOGIN_TTL))
    return {"challenge":nonce,"expiresIn":LOGIN_TTL}

def verify_login(con: sqlite3.Connection, body: dict, now: int, fingerprint: str) -> str:
    controller_id=str(body.get("controllerId",""))
    nonce=str(body.get("challenge",""))
    signature=b64decode(body.get("signature"),300)
    if len(nonce)!=43 or len(controller_id)!=36: raise ValueError("Invalid challenge")
    import hashlib
    digest=hashlib.sha256(nonce.encode()).hexdigest()
    row=con.execute("""SELECT c.identity_public FROM controllers c JOIN controller_challenges q
                       ON q.controller_id=c.id WHERE c.id=? AND c.revoked_at IS NULL
                       AND c.admin_fingerprint=? AND q.digest=? AND q.expires_at>?""",
                       (controller_id,fingerprint,digest,now)).fetchone()
    # Consume even a failed signature to prevent online replay.
    con.execute("DELETE FROM controller_challenges WHERE digest=?", (digest,))
    if not row: raise ValueError("Controller challenge expired or revoked")
    signed=f"simhub-controller-login-v1|{controller_id}|{nonce}".encode()
    try:
        public_key(row["identity_public"]).verify(signature,signed,ec.ECDSA(hashes.SHA256()))
    except InvalidSignature as exc:
        raise ValueError("Controller signature invalid") from exc
    return controller_id

def list_controllers(con: sqlite3.Connection) -> list[dict]:
    return [{"id":r["id"],"label":r["label"],"approvedAt":r["approved_at"],
             "revokedAt":r["revoked_at"]} for r in con.execute(
             "SELECT id,label,approved_at,revoked_at FROM controllers ORDER BY approved_at DESC")]

def revoke(con: sqlite3.Connection, controller_id: str, now: int) -> bool:
    changed=con.execute("UPDATE controllers SET revoked_at=? WHERE id=? AND revoked_at IS NULL",
                        (now,controller_id)).rowcount
    if changed:
        con.execute("DELETE FROM controller_challenges WHERE controller_id=?",(controller_id,))
        con.execute("DELETE FROM admin_sessions WHERE controller_id=?",(controller_id,))
    return bool(changed)
