"""Optional single-owner encrypted SMS sharing pool.

Relay never handles plaintext SMS, Node Keys, or Pool Keys. A member must explicitly
opt in on its Android node and an authenticated administrator must provide an
AES-GCM key envelope wrapped independently for that Node Key. SQL statements
are parameterized and all reads enforce membership within the transaction.
"""
import json
import sqlite3
import time

POOL = "default"
MAX_BATCH = 20
MAX_PAGE = 100


def init(con: sqlite3.Connection) -> None:
    con.executescript("""
    CREATE TABLE IF NOT EXISTS sms_pool_keys (
        epoch INTEGER PRIMARY KEY,
        key_id TEXT NOT NULL,
        vault_envelope TEXT NOT NULL,
        created_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS sms_pool_security (
        pool_id TEXT PRIMARY KEY,
        rotation_required INTEGER NOT NULL DEFAULT 0,
        requested_at INTEGER NOT NULL DEFAULT 0
    );
    INSERT OR IGNORE INTO sms_pool_security(pool_id) VALUES('default');
    CREATE TABLE IF NOT EXISTS sms_pool_members (
        device_id TEXT PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
        requested INTEGER NOT NULL DEFAULT 0,
        approved INTEGER NOT NULL DEFAULT 0,
        updated_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS sms_pool_member_keys (
        device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
        epoch INTEGER NOT NULL REFERENCES sms_pool_keys(epoch),
        wrapped_envelope TEXT NOT NULL,
        PRIMARY KEY(device_id,epoch)
    );
    CREATE TABLE IF NOT EXISTS sms_pool_messages (
        seq INTEGER PRIMARY KEY AUTOINCREMENT,
        device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
        origin_event_id TEXT NOT NULL,
        occurred_at INTEGER NOT NULL,
        epoch INTEGER NOT NULL REFERENCES sms_pool_keys(epoch),
        channel_id TEXT NOT NULL,
        ciphertext TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        UNIQUE(device_id,origin_event_id)
    );
    CREATE INDEX IF NOT EXISTS idx_sms_pool_occurred
      ON sms_pool_messages(occurred_at DESC,seq DESC);
    CREATE INDEX IF NOT EXISTS idx_sms_pool_seq ON sms_pool_messages(seq);
    """)


def valid_cipher(obj) -> bool:
    if not isinstance(obj,dict) or obj.get("alg")!="A256GCM":
        return False
    if obj.get("v") not in (1,2): return False
    return (isinstance(obj.get("iv"),str) and 12<=len(obj["iv"])<=64
        and isinstance(obj.get("ct"),str) and 20<=len(obj["ct"])<=18000)


def current_key(con):
    return con.execute("SELECT * FROM sms_pool_keys ORDER BY epoch DESC LIMIT 1").fetchone()


def member(con, device):
    return con.execute(
        "SELECT m.* FROM sms_pool_members m JOIN devices d ON d.id=m.device_id "
        "WHERE m.device_id=? AND d.revoked_at IS NULL AND d.reset_requested_at=0",
        (device,)).fetchone()


def rotation_required(con) -> bool:
    row=con.execute("SELECT rotation_required FROM sms_pool_security WHERE pool_id=?",(POOL,)).fetchone()
    return bool(row and row[0])


def require_rotation(con):
    if current_key(con):
        con.execute("UPDATE sms_pool_security SET rotation_required=1,requested_at=? WHERE pool_id=?",
                    (int(time.time()),POOL))


def member_departure(con,device):
    """Invalidate membership inside an enclosing device lifecycle transaction."""
    row=con.execute("SELECT approved FROM sms_pool_members WHERE device_id=?",(device,)).fetchone()
    if row and row[0]:
        require_rotation(con)
    con.execute("UPDATE sms_pool_members SET approved=0,requested=0,updated_at=? WHERE device_id=?",
                (int(time.time()),device))
    con.execute("DELETE FROM sms_pool_member_keys WHERE device_id=?",(device,))
    return bool(row and row[0])


def enabled(con,device) -> bool:
    row=member(con,device)
    return bool(row and row["requested"] and row["approved"])


def json_key(obj) -> str:
    return json.dumps(obj,ensure_ascii=False,separators=(",",":"))


def admin_status(con):
    key=current_key(con)
    members=con.execute(
        "SELECT d.id,d.name,m.requested,m.approved,m.updated_at "
        "FROM devices d LEFT JOIN sms_pool_members m ON m.device_id=d.id "
        "WHERE d.revoked_at IS NULL AND d.reset_requested_at=0 "
        "ORDER BY d.created_at DESC").fetchall()
    return {
        "poolId":POOL,
        "rotationRequired":rotation_required(con),
        "epoch":key["epoch"] if key else 0,
        "keyId":key["key_id"] if key else "",
        "vaultEnvelope":json.loads(key["vault_envelope"]) if key else None,
        "keys":[{"epoch":k["epoch"],"keyId":k["key_id"],
                 "vaultEnvelope":json.loads(k["vault_envelope"])}
                for k in con.execute("SELECT * FROM sms_pool_keys ORDER BY epoch DESC LIMIT 32")],
        "members":[dict(row) for row in members],
    }


def request(con,device,on):
    ts=int(time.time())
    previous=enabled(con,device)
    con.execute(
        "INSERT INTO sms_pool_members(device_id,requested,approved,updated_at) "
        "VALUES(?,?,0,?) ON CONFLICT(device_id) DO UPDATE SET "
        "requested=excluded.requested,approved=CASE WHEN excluded.requested=0 "
        "THEN 0 ELSE sms_pool_members.approved END,updated_at=excluded.updated_at",
        (device,1 if on else 0,ts))
    if not on:
        con.execute("DELETE FROM sms_pool_member_keys WHERE device_id=?",(device,))
        if previous:require_rotation(con)
    return {"ok":True,"requested":bool(on),"approved":bool(on and enabled(con,device))}


def provision(con, body):
    device=body.get("deviceId")
    if not isinstance(device,str) or not 1<=len(device)<=120:
        raise ValueError("Valid deviceId required")
    row=member(con,device)
    if not row or not row["requested"]:
        raise ValueError("Node must first opt in to sharing")
    if rotation_required(con):
        raise ValueError("Pool Key rotation required before new grants")
    envelope=body.get("memberEnvelope")
    if not valid_cipher(envelope):
        raise ValueError("Valid per-node wrapped Pool Key required")
    key=current_key(con)
    if not key:
        vault=body.get("vaultEnvelope")
        kid=body.get("keyId")
        if not valid_cipher(vault) or not isinstance(kid,str) or not 4<len(kid)<100:
            raise ValueError("Initial vault envelope and key ID required")
        con.execute("INSERT INTO sms_pool_keys VALUES(?,?,?,?)",
                    (1,kid,json_key(vault),int(time.time())))
        key=current_key(con)
    elif body.get("keyId")!=key["key_id"]:
        raise ValueError("Existing pool key ID mismatch; use atomic rotation")
    if int(body.get("epoch",key["epoch"]))!=key["epoch"]:
        raise ValueError("Pool epoch mismatch")
    con.execute("INSERT INTO sms_pool_member_keys(device_id,epoch,wrapped_envelope)"
                " VALUES(?,?,?) ON CONFLICT(device_id,epoch) DO UPDATE SET "
                "wrapped_envelope=excluded.wrapped_envelope",
                (device,key["epoch"],json_key(envelope)))
    history=body.get("historyEnvelopes",{})
    if not isinstance(history,dict):raise ValueError("Historical key envelopes must be an object")
    allowed={str(k["epoch"]) for k in con.execute(
        "SELECT epoch FROM sms_pool_keys WHERE epoch<? ORDER BY epoch DESC LIMIT 31",(key["epoch"],))}
    if not set(history).issubset(allowed) or not all(valid_cipher(v) for v in history.values()):
        raise ValueError("Invalid historical key envelopes")
    for old_epoch,old_envelope in history.items():
        con.execute("INSERT INTO sms_pool_member_keys(device_id,epoch,wrapped_envelope) "
                    "VALUES(?,?,?) ON CONFLICT(device_id,epoch) DO UPDATE SET "
                    "wrapped_envelope=excluded.wrapped_envelope",
                    (device,int(old_epoch),json_key(old_envelope)))
    con.execute("UPDATE sms_pool_members SET approved=1,updated_at=? WHERE device_id=?",
                (int(time.time()),device))
    return {"ok":True,"deviceId":device,"epoch":key["epoch"]}


def revoke(con,device):
    if not member(con,device):raise ValueError("Unknown member")
    member_departure(con,device)
    return {"ok":True,"revoked":device,"rotationRequired":rotation_required(con)}


def rotate(con,body):
    old=current_key(con)
    if not old:raise ValueError("Pool not initialized")
    if "expectedEpoch" in body and body["expectedEpoch"]!=old["epoch"]:
        raise ValueError("Pool epoch advanced, reload before rotating")
    key_id=body.get("keyId")
    vault=body.get("vaultEnvelope")
    envelopes=body.get("members")
    if not isinstance(key_id,str) or not 5<len(key_id)<100 or key_id==old["key_id"]:
        raise ValueError("A different Pool Key ID is required")
    if not valid_cipher(vault) or not isinstance(envelopes,dict):
        raise ValueError("Vault envelope and members required")
    devices=[r["device_id"] for r in con.execute(
        "SELECT m.device_id FROM sms_pool_members m JOIN devices d ON d.id=m.device_id "
        "WHERE m.requested=1 AND m.approved=1 AND d.revoked_at IS NULL "
        "AND d.reset_requested_at=0")]
    if set(envelopes)!=set(devices) or not all(valid_cipher(v) for v in envelopes.values()):
        raise ValueError("All currently approved members require new envelopes")
    epoch=old["epoch"]+1
    con.execute("INSERT INTO sms_pool_keys VALUES(?,?,?,?)",
                (epoch,key_id,json_key(vault),int(time.time())))
    for did,enc in envelopes.items():
        con.execute("INSERT INTO sms_pool_member_keys VALUES(?,?,?)",
                    (did,epoch,json_key(enc)))
    con.execute("UPDATE sms_pool_security SET rotation_required=0,requested_at=0 WHERE pool_id=?",(POOL,))
    return {"ok":True,"epoch":epoch}


def node_status(con,device):
    key=current_key(con)
    row=member(con,device)
    on=enabled(con,device)
    keys=[]
    if on:
        for k in con.execute(
                "SELECT k.epoch,k.key_id,m.wrapped_envelope FROM sms_pool_member_keys m "
                "JOIN sms_pool_keys k ON k.epoch=m.epoch "
                "WHERE m.device_id=? ORDER BY k.epoch DESC LIMIT 32",(device,)):
            keys.append({"epoch":k["epoch"],"keyId":k["key_id"],"wrappedKey":json.loads(k["wrapped_envelope"])})
    return {"poolId":POOL,"requested":bool(row and row["requested"]),
            "approved":on,"rotationRequired":rotation_required(con),
            "epoch":key["epoch"] if key else 0,
            "keys":keys}


def put_messages(con,device,body):
    if not enabled(con,device):raise PermissionError("Pool access is not enabled")
    if rotation_required(con):raise PermissionError("Pool Key rotation required before new uploads")
    key=current_key(con)
    items=body.get("messages")
    if not isinstance(items,list) or not 1<=len(items)<=MAX_BATCH:
        raise ValueError("Expected 1 to 20 encrypted SMS")
    inserted=0;results=[];seen=set();now=int(time.time())
    device_record=con.execute("SELECT sms_purged_before FROM devices WHERE id=?",(device,)).fetchone()
    purged_before=device_record["sms_purged_before"] if device_record else 0
    for x in items:
        if not isinstance(x,dict):raise ValueError("Invalid message")
        event_id=x.get("originEventId")
        if not isinstance(event_id,str) or not 1<=len(event_id)<=160 or event_id in seen:
            raise ValueError("Invalid or repeated event identity")
        seen.add(event_id)
        channel=x.get("channelId","")
        if not isinstance(channel,str) or len(channel)>120:raise ValueError("Invalid channel")
        epoch=x.get("epoch")
        if epoch!=key["epoch"] or not valid_cipher(x.get("ciphertext")):
            raise ValueError("Stale pool epoch or invalid ciphertext")
        occurred=x.get("occurredAt")
        if not isinstance(occurred,int) or occurred<=0 or occurred>now+300:
            raise ValueError("Invalid timestamp")
        if occurred<=purged_before:
            results.append({"originEventId":event_id,"accepted":True,
                            "duplicate":False,"suppressed":True})
            continue
        cur=con.execute("INSERT OR IGNORE INTO sms_pool_messages"
            "(device_id,origin_event_id,occurred_at,epoch,channel_id,ciphertext,created_at) "
            "VALUES(?,?,?,?,?,?,?)",
            (device,event_id,occurred,epoch,channel,json_key(x["ciphertext"]),now))
        inserted+=int(cur.rowcount)
        results.append({"originEventId":event_id,"accepted":True,"duplicate":not bool(cur.rowcount)})
    return {"accepted":True,"inserted":inserted,"results":results}


def get_messages(con,device,query):
    if not enabled(con,device):raise PermissionError("Pool access is not enabled")
    try:
        limit=max(1,min(int(query.get("limit",["50"])[0]),MAX_PAGE))
        before=max(0,int(query.get("before",["0"])[0]))
        since=max(0,int(query.get("since",["0"])[0]))
        before_time=max(0,int(query.get("beforeTime",["0"])[0]))
        before_seq=max(0,int(query.get("beforeSeq",["0"])[0]))
    except (TypeError,ValueError):raise ValueError("Invalid cursor")
    if bool(before_time)!=bool(before_seq) or (since and (before or before_time)):
        raise ValueError("Invalid conflicting pagination cursor")
    if since:
        rows=con.execute(
            "SELECT * FROM sms_pool_messages WHERE seq>? ORDER BY seq ASC LIMIT ?",
            (since,limit+1)).fetchall()
    elif before_time:
        rows=con.execute(
            "SELECT * FROM sms_pool_messages WHERE occurred_at<? OR "
            "(occurred_at=? AND seq<?) ORDER BY occurred_at DESC,seq DESC LIMIT ?",
            (before_time,before_time,before_seq,limit+1)).fetchall()
    elif before:
        # Backward-compatibility cursor for clients prior to date-keyset pagination.
        anchor=con.execute("SELECT occurred_at,seq FROM sms_pool_messages WHERE seq=?",(before,)).fetchone()
        if anchor:
            rows=con.execute(
                "SELECT * FROM sms_pool_messages WHERE occurred_at<? OR "
                "(occurred_at=? AND seq<?) ORDER BY occurred_at DESC,seq DESC LIMIT ?",
                (anchor["occurred_at"],anchor["occurred_at"],anchor["seq"],limit+1)).fetchall()
        else:rows=[]
    else:
        rows=con.execute(
            "SELECT * FROM sms_pool_messages ORDER BY occurred_at DESC,seq DESC LIMIT ?",
            (limit+1,)).fetchall()
    has_more=len(rows)>limit
    rows=rows[:limit]
    items=[{"seq":r["seq"],"deviceId":r["device_id"],"originEventId":r["origin_event_id"],
        "occurredAt":r["occurred_at"],"epoch":r["epoch"],"channelId":r["channel_id"],
        "ciphertext":json.loads(r["ciphertext"])} for r in rows]
    last=items[-1] if items else None
    return {"messages":items,"hasMore":has_more,
            "nextBeforeTime":last["occurredAt"] if last and not since else None,
            "nextBeforeSeq":last["seq"] if last and not since else None,
            "nextBefore":last["seq"] if last and not since else None,
            "nextSince":items[-1]["seq"] if items and since else since,
            "epoch":current_key(con)["epoch"] if current_key(con) else 0}
