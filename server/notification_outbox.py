"""Durable, metadata-only, at-least-once notification delivery journal.

Provider credentials and endpoints stay in process configuration, never in
SQLite. A successful HTTP response means provider acceptance, not a confirmed
device display. Replays may repeat an external notification after a crash.
"""
from __future__ import annotations
import json
import sqlite3
from typing import Any

CHANNELS = frozenset({"webhook", "bark", "ntfy"})
MAX_ROWS = 5000
MAX_ATTEMPTS = 8

def init(con: sqlite3.Connection) -> None:
    con.executescript("""
        CREATE TABLE IF NOT EXISTS notification_outbox(
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
          event_id TEXT NOT NULL,
          channel TEXT NOT NULL,
          payload_json TEXT NOT NULL,
          state TEXT NOT NULL DEFAULT 'pending',
          attempts INTEGER NOT NULL DEFAULT 0,
          created_at INTEGER NOT NULL,
          next_attempt_at INTEGER NOT NULL,
          lease_until INTEGER NOT NULL DEFAULT 0,
          expires_at INTEGER NOT NULL,
          last_error TEXT NOT NULL DEFAULT '',
          UNIQUE(device_id,event_id,channel)
        );
        CREATE INDEX IF NOT EXISTS idx_notification_outbox_due
          ON notification_outbox(state,next_attempt_at,lease_until);
    """)

def enqueue(con: sqlite3.Connection, device_id: str, event_id: str,
            channel: str, payload: dict[str,Any], ts: int, expires_at: int) -> bool:
    if channel not in CHANNELS or not 1 <= len(event_id) <= 160 or expires_at <= ts:
        return False
    # Only explicit, bounded generic metadata is eligible. This hardens the
    # persistence boundary even if an upstream caller supplies an SMS body.
    generic = {
        "source": "simhub",
        "kind": "sms.received",
        "message": "New OTP received" if payload.get("hasOtp") is True else "New SMS received",
        "device": str(payload.get("device","SIM Node"))[:80],
        "occurredAt": int(payload.get("occurredAt",ts)),
        "hasOtp": payload.get("hasOtp") is True,
    }
    con.execute("DELETE FROM notification_outbox WHERE state IN ('delivered','failed','expired') AND created_at<?",
                (ts-86400,))
    existing = con.execute("SELECT id FROM notification_outbox WHERE device_id=? AND event_id=? AND channel=?",
                           (device_id,event_id,channel)).fetchone()
    if existing:
        return False
    count = con.execute("SELECT COUNT(*) FROM notification_outbox").fetchone()[0]
    if count >= MAX_ROWS:
        return False
    cur=con.execute(
        """INSERT OR IGNORE INTO notification_outbox
           (device_id,event_id,channel,payload_json,created_at,next_attempt_at,expires_at)
           VALUES(?,?,?,?,?,?,?)""",
        (device_id,event_id,channel,json.dumps(generic,ensure_ascii=False,separators=(",",":")),
         ts,ts,expires_at))
    return cur.rowcount == 1

def claim(con: sqlite3.Connection, ts: int, limit: int = 8) -> list[dict[str,Any]]:
    # Caller acquires BEGIN IMMEDIATE before this SELECT/UPDATE lease pair.
    con.execute("UPDATE notification_outbox SET state='expired' WHERE expires_at<=? AND state IN ('pending','retry','inflight')",(ts,))
    # A worker crash after the last claim must not create infinite retries.
    con.execute("UPDATE notification_outbox SET state='failed',lease_until=0 WHERE state='inflight' AND attempts>=? AND lease_until<=?",
                (MAX_ATTEMPTS,ts))
    rows=con.execute(
        """SELECT * FROM notification_outbox
           WHERE expires_at>? AND
             ((state IN ('pending','retry') AND next_attempt_at<=?)
                 OR (state='inflight' AND lease_until<=?))
           ORDER BY next_attempt_at,id LIMIT ?""",
        (ts,ts,ts,max(1,min(int(limit),32)))).fetchall()
    result=[]
    for row in rows:
        con.execute("UPDATE notification_outbox SET state='inflight',attempts=attempts+1,lease_until=? WHERE id=?",
                    (ts+30,row["id"]))
        result.append({"id":row["id"],"channel":row["channel"],
                       "payload":json.loads(row["payload_json"]),"attempts":row["attempts"]+1})
    return result

def finish(con: sqlite3.Connection, delivery_id: int, success: bool,
           ts: int, error: str = "", expected_attempt: int | None = None) -> None:
    row=con.execute("SELECT attempts,expires_at,state FROM notification_outbox WHERE id=?",(delivery_id,)).fetchone()
    # Leased work may be reclaimed while an older HTTP response arrives. Only
    # the current lease owner may transition the outbox state.
    if not row or row["state"]!="inflight" or (expected_attempt is not None
                                                and row["attempts"]!=expected_attempt):
        return
    if success:
        con.execute("UPDATE notification_outbox SET state='delivered',lease_until=0,last_error='' WHERE id=?",
                    (delivery_id,))
        return
    delay=min(900, 5 * (2**min(int(row["attempts"]),8)))
    state="failed" if row["attempts"]>=MAX_ATTEMPTS or ts+delay>=row["expires_at"] else "retry"
    con.execute("UPDATE notification_outbox SET state=?,lease_until=0,next_attempt_at=?,last_error=? WHERE id=?",
                (state,ts+delay,str(error)[:60],delivery_id))

def maintenance(con: sqlite3.Connection, ts: int) -> int:
    # Keep tombstones long enough to prevent repeat notification of a delayed
    # duplicate event, then reclaim storage in the regular maintenance cycle.
    return con.execute("DELETE FROM notification_outbox WHERE state IN ('delivered','failed','expired') AND created_at<?",
                       (ts-86400,)).rowcount

def summary(con: sqlite3.Connection) -> dict[str,int]:
    counts={"pending":0,"retry":0,"inflight":0,"delivered":0,"failed":0,"expired":0}
    for state,count in con.execute("SELECT state,COUNT(*) FROM notification_outbox GROUP BY state"):
        if state in counts:counts[state]=count
    return counts
