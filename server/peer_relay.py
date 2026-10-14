"""Optional Legacy Relay carrier for opaque P2P replies, never P2P authority."""
import base64
import json
import re

IDENTITY = re.compile(r"[A-Za-z0-9_-]{16,80}\Z")

def validate(frame, timestamp):
    if not isinstance(frame, dict) or set(frame) != {"v","domain","from","to","id","expires","iv","ct"} or type(frame.get("v")) is not int or frame.get("v") != 1 or frame.get("domain") != "simhub-peer-v1":
        raise ValueError("invalid_peer_frame")
    if any(not isinstance(frame.get(k), str) or not IDENTITY.fullmatch(frame[k]) for k in ("from", "to", "id")):
        raise ValueError("invalid_peer_identity")
    if type(frame.get("expires")) is not int or not timestamp <= frame["expires"] <= timestamp + 300:
        raise ValueError("expired_peer_frame")
    if len(json.dumps(frame)) > 600_000:
        raise ValueError("peer_frame_too_large")
    for field, size in (("iv", 12), ("ct", None)):
        encoded = frame.get(field)
        if not isinstance(encoded, str) or not re.fullmatch(r"[A-Za-z0-9_-]+", encoded):
            raise ValueError("invalid_peer_cipher")
        decoded = base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4))
        if (size and len(decoded) != size) or (field == "ct" and len(decoded) < 16):
            raise ValueError("invalid_peer_cipher")

def store_response(con, device, command, frame, timestamp):
    validate(frame, timestamp)
    con.execute("DELETE FROM peer_rpc_responses WHERE expires_at<?", (timestamp,))
    row = con.execute("SELECT type,expires_at FROM commands WHERE device_id=? AND id=?", (device, command)).fetchone()
    if not row or row["type"] != "peer.rpc" or row["expires_at"] < timestamp:
        raise ValueError("peer_command_unavailable")
    existing = con.execute("SELECT 1 FROM peer_rpc_responses WHERE device_id=? AND command_id=?", (device, command)).fetchone()
    if not existing:
        if con.execute("SELECT COUNT(*) FROM peer_rpc_responses WHERE device_id=?", (device,)).fetchone()[0] >= 32:
            raise ValueError("peer_response_capacity")
        con.execute("INSERT INTO peer_rpc_responses(device_id,command_id,frame_json,expires_at) VALUES(?,?,?,?)",
                    (device, command, json.dumps(frame, separators=(",", ":")), min(frame["expires"], row["expires_at"])))

def response(con, device, command, timestamp):
    row = con.execute("""SELECT r.frame_json FROM peer_rpc_responses r JOIN devices d ON d.id=r.device_id
        WHERE r.device_id=? AND r.command_id=? AND r.expires_at>=? AND COALESCE(d.revoked_at,0)=0
        AND COALESCE(d.reset_requested_at,0)=0""", (device, command, timestamp)).fetchone()
    return json.loads(row[0]) if row else None
