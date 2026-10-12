"""Singleton zero-knowledge Master Vault registry.

The Relay only persists a password-wrapped random 32-byte Master Vault Key.
It never sees the password, plaintext Master Key, or per-node keys.
"""
import base64
import json
import re
import sqlite3
import time
import uuid

def init(con):
    con.execute("""CREATE TABLE IF NOT EXISTS global_vault (
        singleton INTEGER PRIMARY KEY CHECK(singleton=1),
        vault_id TEXT NOT NULL UNIQUE,
        key_id TEXT NOT NULL,
        envelope_json TEXT NOT NULL,
        generation INTEGER NOT NULL DEFAULT 1,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL
    )""")

def _b64(value, length):
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]+", value):
        raise ValueError("Invalid Vault envelope encoding")
    try:
        decoded = base64.b64decode(value + "=" * (-len(value) % 4), altchars=b"-_", validate=True)
    except (ValueError, base64.binascii.Error) as exc:
        raise ValueError("Invalid Vault envelope encoding") from exc
    if len(decoded) != length:
        raise ValueError("Invalid Vault envelope length")
    return decoded

def _validated(body):
    if not isinstance(body, dict):
        raise ValueError("Invalid Vault request")
    vault_id = str(body.get("vaultId", ""))
    key_id = str(body.get("keyId", ""))
    try:
        uuid.UUID(vault_id)
    except (ValueError, AttributeError) as exc:
        raise ValueError("Invalid Vault ID") from exc
    _b64(key_id, 16)
    e = body.get("envelope")
    if not isinstance(e, dict) or set(e) != {"v", "kdf", "iterations", "salt", "iv", "ct"}:
        raise ValueError("Invalid Vault envelope fields")
    if e["v"] != 1 or e["kdf"] != "PBKDF2-SHA256" or type(e["iterations"]) is not int or not 310000 <= e["iterations"] <= 2000000:
        raise ValueError("Invalid Vault KDF parameters")
    _b64(e["salt"], 16)
    _b64(e["iv"], 12)
    _b64(e["ct"], 48)
    return vault_id, key_id, json.dumps(e, separators=(",", ":"))

def status(con):
    r = con.execute("SELECT vault_id,key_id,generation,created_at FROM global_vault WHERE singleton=1").fetchone()
    return {"initialized": bool(r), "vaultId": r["vault_id"] if r else None,
            "keyId": r["key_id"] if r else None, "generation": r["generation"] if r else 0,
            "createdAt": r["created_at"] if r else None}

def get(con):
    r = con.execute("SELECT * FROM global_vault WHERE singleton=1").fetchone()
    if r is None:
        return {"initialized": False}
    return {**status(con), "envelope": json.loads(r["envelope_json"])}

def initialize(con, body):
    vault_id, key_id, envelope = _validated(body)
    ts = int(time.time())
    try:
        con.execute("INSERT INTO global_vault(singleton,vault_id,key_id,envelope_json,created_at,updated_at) VALUES(1,?,?,?,?,?)",
                    (vault_id,key_id,envelope,ts,ts))
    except sqlite3.IntegrityError as exc:
        raise ValueError("Vault already initialized; use the existing Vault") from exc
    return status(con)

def rewrap(con, body):
    vault_id,key_id,envelope = _validated(body)
    if type(body.get("expectedGeneration")) is not int:
        raise ValueError("Expected Vault generation required")
    result = con.execute("""UPDATE global_vault SET envelope_json=?,generation=generation+1,updated_at=?
        WHERE singleton=1 AND vault_id=? AND key_id=? AND generation=?""",
        (envelope,int(time.time()),vault_id,key_id,body["expectedGeneration"]))
    if result.rowcount != 1:
        raise ValueError("Vault identity changed or concurrent update; refresh before retry")
    return status(con)
