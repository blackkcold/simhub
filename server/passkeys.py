"""WebAuthn relying-party operations. A passkey authenticates the administrator;
it NEVER unwraps the client-side Vault or moves SMS plaintext to the relay.
"""
from __future__ import annotations
import base64
import hashlib
import json
import secrets
import time
from urllib.parse import urlsplit

from webauthn import (generate_authentication_options, generate_registration_options,
                      options_to_json, verify_authentication_response,
                      verify_registration_response)
from webauthn.helpers.structs import (AuthenticatorSelectionCriteria,
                                     ResidentKeyRequirement, UserVerificationRequirement)

CHALLENGE_TTL = 180
MAX_KEYS = 12


def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).decode().rstrip("=")


def decode(data: str) -> bytes:
    if not isinstance(data, str) or len(data) > 16384:
        raise ValueError("Invalid WebAuthn field")
    return base64.urlsafe_b64decode(data + "=" * (-len(data) % 4))


def rp_id(origin: str) -> str:
    parsed = urlsplit(origin)
    if parsed.scheme != "https" or not parsed.hostname:
        raise ValueError("WebAuthn requires an HTTPS management origin")
    return parsed.hostname


def purge(con) -> None:
    con.execute("DELETE FROM passkey_challenges WHERE expires_at < ?", (int(time.time()),))


def create_challenge(con, kind: str, username: str, session_hash: str, challenge: bytes) -> str:
    purge(con)
    cid = secrets.token_urlsafe(24)
    con.execute("INSERT INTO passkey_challenges(id,challenge,kind,username,session_hash,expires_at) VALUES(?,?,?,?,?,?)",
                (cid, b64(challenge), kind, username, session_hash, int(time.time()) + CHALLENGE_TTL))
    return cid


def consume_challenge(con, challenge_id: str, kind: str, username: str, session_hash: str) -> bytes:
    row = con.execute("SELECT challenge FROM passkey_challenges WHERE id=? AND kind=? AND username=? AND session_hash=? AND expires_at>? ",
                      (challenge_id, kind, username, session_hash, int(time.time()))).fetchone()
    if not row:
        raise ValueError("Challenge expired or already consumed")
    con.execute("DELETE FROM passkey_challenges WHERE id=?", (challenge_id,))
    return decode(row["challenge"])


def registration_options(con, origin: str, username: str, session_hash: str):
    count = con.execute("SELECT COUNT(*) FROM admin_passkeys").fetchone()[0]
    if count >= MAX_KEYS:
        raise ValueError("Maximum number of passkeys reached")
    options = generate_registration_options(
        rp_id=rp_id(origin),
        rp_name="SIM Hub",
        user_id=hashlib.sha256(b"simhub-admin-user-v1").digest(),
        user_name=username,
        authenticator_selection=AuthenticatorSelectionCriteria(
            resident_key=ResidentKeyRequirement.REQUIRED,
            user_verification=UserVerificationRequirement.REQUIRED))
    id = create_challenge(con, "register", username, session_hash, options.challenge)
    return {"challengeId": id, "options": json.loads(options_to_json(options))}


def register(con, origin: str, username: str, session_hash: str, challenge_id: str, credential: dict, label: str):
    expected = consume_challenge(con, challenge_id, "register", username, session_hash)
    result = verify_registration_response(
        credential=credential, expected_challenge=expected,
        expected_origin=origin, expected_rp_id=rp_id(origin), require_user_verification=True)
    cid = b64(result.credential_id)
    if con.execute("SELECT 1 FROM admin_passkeys WHERE credential_id=?", (cid,)).fetchone():
        raise ValueError("Passkey already registered")
    if not label or len(label) > 64:
        raise ValueError("Passkey label must contain 1-64 characters")
    con.execute("INSERT INTO admin_passkeys(credential_id,public_key,sign_count,label,created_at) VALUES(?,?,?,?,?)",
                (cid, result.credential_public_key, result.sign_count, label, int(time.time())))
    return {"id": cid, "label": label}


def authentication_options(con, origin: str, username: str, session_hash: str, kind: str):
    options = generate_authentication_options(
        rp_id=rp_id(origin), user_verification=UserVerificationRequirement.REQUIRED)
    cid = create_challenge(con, kind, username, session_hash, options.challenge)
    return {"challengeId": cid, "options": json.loads(options_to_json(options))}


def authenticate(con, origin: str, username: str, session_hash: str, kind: str,
                 challenge_id: str, credential: dict):
    challenge = consume_challenge(con, challenge_id, kind, username, session_hash)
    cid = str(credential.get("id", ""))
    row = con.execute("SELECT * FROM admin_passkeys WHERE credential_id=?", (cid,)).fetchone()
    if not row:
        raise ValueError("Unknown passkey")
    verified = verify_authentication_response(
        credential=credential, expected_challenge=challenge,
        expected_rp_id=rp_id(origin), expected_origin=origin,
        credential_public_key=bytes(row["public_key"]),
        credential_current_sign_count=row["sign_count"],
        require_user_verification=True)
    con.execute("UPDATE admin_passkeys SET sign_count=?,last_used_at=? WHERE credential_id=?",
                (verified.new_sign_count, int(time.time()), cid))
    return {"id": cid, "label": row["label"]}


def list_keys(con):
    return [{"id": r["credential_id"], "label": r["label"],
             "createdAt": r["created_at"], "lastUsedAt": r["last_used_at"]}
            for r in con.execute("SELECT credential_id,label,created_at,last_used_at FROM admin_passkeys ORDER BY created_at")]
