#!/usr/bin/env python3
import sqlite3, tempfile, time, unittest
from pathlib import Path
import simhub_server as srv

LEGACY_SCHEMA="""
PRAGMA foreign_keys=ON;
CREATE TABLE devices(
  id TEXT PRIMARY KEY, token_hash TEXT NOT NULL UNIQUE, name TEXT NOT NULL,
  group_name TEXT NOT NULL DEFAULT '', model TEXT NOT NULL DEFAULT '', os_version TEXT NOT NULL DEFAULT '',
  app_version TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, last_seen_at INTEGER, revoked_at INTEGER
);
CREATE TABLE events(
  seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, device_id TEXT NOT NULL,
  kind TEXT NOT NULL, occurred_at INTEGER NOT NULL, received_at INTEGER NOT NULL,
  subscription_id TEXT NOT NULL DEFAULT '', has_otp INTEGER NOT NULL DEFAULT 0,
  metadata_json TEXT NOT NULL DEFAULT '{}', ciphertext_json TEXT NOT NULL,
  FOREIGN KEY(device_id) REFERENCES devices(id)
);
CREATE INDEX idx_events_device_seq ON events(device_id,seq);
CREATE INDEX idx_events_kind_seq ON events(kind,seq);
CREATE TABLE commands(
  seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, device_id TEXT NOT NULL,
  type TEXT NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL,
  idempotency_key TEXT NOT NULL UNIQUE, ciphertext_json TEXT NOT NULL,
  state TEXT NOT NULL DEFAULT 'queued', ack_at INTEGER, result_json TEXT NOT NULL DEFAULT '{}',
  FOREIGN KEY(device_id) REFERENCES devices(id)
);
CREATE INDEX idx_commands_device_state ON commands(device_id,state,seq);
CREATE TABLE device_state(device_id TEXT PRIMARY KEY,state_json TEXT NOT NULL,updated_at INTEGER NOT NULL,FOREIGN KEY(device_id) REFERENCES devices(id));
CREATE TABLE enrollment_tokens(id TEXT PRIMARY KEY,token_hash TEXT NOT NULL UNIQUE,created_at INTEGER NOT NULL,expires_at INTEGER NOT NULL,used_at INTEGER);
CREATE TABLE audit(seq INTEGER PRIMARY KEY AUTOINCREMENT,occurred_at INTEGER NOT NULL,action TEXT NOT NULL,target TEXT NOT NULL DEFAULT '',result TEXT NOT NULL,ip TEXT NOT NULL DEFAULT '');
"""

class MigrationTest(unittest.TestCase):
    def test_v1_to_v7_preserves_data_and_adds_session_hardening(self):
        with tempfile.TemporaryDirectory() as td:
            db=Path(td)/"legacy.db"
            with sqlite3.connect(db) as con:
                con.executescript(LEGACY_SCHEMA)
                for d in ("dev-a","dev-b"):
                    con.execute("INSERT INTO devices(id,token_hash,name,created_at) VALUES(?,?,?,?)",(d,"hash-"+d,d,1))
                ts=int(time.time())
                con.execute("INSERT INTO events(id,device_id,kind,occurred_at,received_at,ciphertext_json) VALUES(?,?,?,?,?,?)",("sms-provider-42","dev-a","sms.received",ts,ts,'{"v":1,"alg":"A256GCM","iv":"AAAAAAAAAAAAAAAA","ct":"AAAAAAAAAAAAAAAA"}'))
                con.execute("INSERT INTO commands(id,device_id,type,created_at,expires_at,idempotency_key,ciphertext_json) VALUES(?,?,?,?,?,?,?)",("cmd-a","dev-a","sms.send",1,9999999999,"same-idem",'{"v":1,"alg":"A256GCM","iv":"AAAAAAAAAAAAAAAA","ct":"AAAAAAAAAAAAAAAA"}'))
            old=srv.DB_PATH
            try:
                srv.DB_PATH=db
                srv.init_db()
            finally:
                srv.DB_PATH=old
            with sqlite3.connect(db) as con:
                self.assertEqual(con.execute("PRAGMA user_version").fetchone()[0],10)
                self.assertEqual(con.execute("SELECT COUNT(*) FROM events").fetchone()[0],1)
                device_cols={r[1] for r in con.execute("PRAGMA table_info(devices)")}
                token_cols={r[1] for r in con.execute("PRAGMA table_info(enrollment_tokens)")}
                self.assertTrue({"node_type","capabilities_json","key_id","wrapped_key_json","pending_key_id","pending_wrapped_key_json","token_issued_at","pending_token_hash","pending_token_expires_at"} <= device_cols)
                self.assertTrue({"node_type","capabilities_json","key_id","wrapped_key_json","bootstrap_envelope_json","bootstrap_hash"} <= token_cols)
                self.assertIsNotNone(con.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='channels'").fetchone())
                con.execute("INSERT INTO events(id,device_id,kind,occurred_at,received_at,ciphertext_json) VALUES(?,?,?,?,?,?)",("sms-provider-42","dev-b","sms.received",2,2,'{"v":2,"alg":"A256GCM","kid":"abcdefgh1234","iv":"AAAAAAAAAAAAAAAA","ct":"AAAAAAAAAAAAAAAA"}'))
                con.execute("INSERT INTO commands(id,device_id,type,created_at,expires_at,idempotency_key,ciphertext_json) VALUES(?,?,?,?,?,?,?)",("cmd-b","dev-b","sms.send",1,9999999999,"same-idem",'{"v":2,"alg":"A256GCM","kid":"abcdefgh1234","iv":"AAAAAAAAAAAAAAAA","ct":"AAAAAAAAAAAAAAAA"}'))
                self.assertEqual(con.execute("SELECT COUNT(*) FROM events WHERE id='sms-provider-42'").fetchone()[0],2)
                self.assertEqual(con.execute("SELECT COUNT(*) FROM commands WHERE idempotency_key='same-idem'").fetchone()[0],2)

if __name__=="__main__":
    unittest.main(verbosity=2)
