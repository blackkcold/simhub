"""Opaque, revisioned controller preferences bound to the existing Global Vault.

Only clients holding the Vault key can interpret aliases or phone overrides.
The Relay validates the envelope and performs optimistic concurrency control.
"""
from __future__ import annotations

import base64
import json
import re
import sqlite3
import time

import global_vault

MAX_CIPHERTEXT_CHARS = 60_000


class SettingsConflict(ValueError):
    """Refresh and merge encrypted settings before retrying this revision."""


def init(con: sqlite3.Connection) -> None:
    con.execute("""CREATE TABLE IF NOT EXISTS vault_settings(
        singleton INTEGER PRIMARY KEY CHECK(singleton=1),
        vault_id TEXT NOT NULL,
        key_id TEXT NOT NULL,
        revision INTEGER NOT NULL CHECK(revision>0),
        ciphertext_json TEXT NOT NULL,
        updated_at INTEGER NOT NULL,
        FOREIGN KEY(vault_id) REFERENCES global_vault(vault_id) ON DELETE CASCADE
    )""")


def get(con: sqlite3.Connection) -> dict:
    vault=global_vault.status(con)
    row=con.execute("SELECT * FROM vault_settings WHERE singleton=1").fetchone()
    if row and (row["vault_id"]!=vault["vaultId"] or row["key_id"]!=vault["keyId"]):
        raise SettingsConflict("Vault identity changed; refresh before reading settings")
    return {"initialized":vault["initialized"],"vaultId":vault["vaultId"],"keyId":vault["keyId"],
            "revision":row["revision"] if row else 0,"updatedAt":row["updated_at"] if row else None,
            "ciphertext":json.loads(row["ciphertext_json"]) if row else None}


def _decoded(value: object, minimum: int, maximum: int) -> bytes:
    if not isinstance(value,str) or not 1<=len(value)<=MAX_CIPHERTEXT_CHARS or not re.fullmatch(r"[A-Za-z0-9_-]+",value):
        raise ValueError("Invalid settings envelope encoding")
    try:
        decoded=base64.b64decode(value+"="*(-len(value)%4),altchars=b"-_",validate=True)
    except (ValueError,base64.binascii.Error) as error:
        raise ValueError("Invalid settings envelope encoding") from error
    if not minimum<=len(decoded)<=maximum:
        raise ValueError("Invalid settings envelope length")
    return decoded


def put(con: sqlite3.Connection, body: dict) -> dict:
    if not isinstance(body,dict) or set(body)!={"vaultId","keyId","expectedRevision","ciphertext"}:
        raise ValueError("Only Vault identity, expectedRevision and ciphertext are accepted")
    expected=body["expectedRevision"]
    if type(expected) is not int or not 0<=expected<2**31-1:
        raise ValueError("Invalid expected settings revision")
    envelope=body["ciphertext"]
    if (not isinstance(envelope,dict) or set(envelope)!={"v","alg","kid","iv","ct"}
            or type(envelope["v"]) is not int or envelope["v"]!=2 or envelope["alg"]!="A256GCM"):
        raise ValueError("Invalid settings ciphertext envelope")
    _decoded(body["keyId"],16,16)
    if envelope["kid"]!=body["keyId"]:
        raise ValueError("Settings ciphertext must use the Global Vault key identity")
    _decoded(envelope["iv"],12,12)
    _decoded(envelope["ct"],16,45_000)
    vault=global_vault.status(con)
    if (not vault["initialized"] or body["vaultId"]!=vault["vaultId"] or body["keyId"]!=vault["keyId"]):
        raise SettingsConflict("Vault identity changed or uninitialized; refresh before updating settings")
    encoded=json.dumps(envelope,separators=(",",":"))
    stamp=int(time.time())
    if expected==0:
        changed=con.execute("""INSERT OR IGNORE INTO vault_settings
            (singleton,vault_id,key_id,revision,ciphertext_json,updated_at) VALUES(1,?,?,1,?,?)""",
            (body["vaultId"],body["keyId"],encoded,stamp)).rowcount
    else:
        changed=con.execute("""UPDATE vault_settings SET ciphertext_json=?,revision=revision+1,updated_at=?
            WHERE singleton=1 AND vault_id=? AND key_id=? AND revision=?""",
            (encoded,stamp,body["vaultId"],body["keyId"],expected)).rowcount
    if changed!=1:
        raise SettingsConflict("Concurrent settings update; refresh and merge before retry")
    return get(con)
