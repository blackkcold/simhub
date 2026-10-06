#!/usr/bin/env python3
import sqlite3, tempfile, time, unittest
from pathlib import Path
import simhub_server as srv

class MaintenanceTest(unittest.TestCase):
    def test_retention_cleans_terminal_data_but_keeps_pending(self):
        with tempfile.TemporaryDirectory() as td:
            db=Path(td)/"maintenance.db"
            old_db=srv.DB_PATH
            old_event=srv.EVENT_RETENTION_DAYS
            old_audit=srv.AUDIT_RETENTION_DAYS
            old_command=srv.COMMAND_RETENTION_DAYS
            try:
                srv.DB_PATH=db
                srv.EVENT_RETENTION_DAYS=30
                srv.AUDIT_RETENTION_DAYS=180
                srv.COMMAND_RETENTION_DAYS=30
                srv.init_db()
                cutoff=int(time.time())-400*86400
                with sqlite3.connect(db) as con:
                    con.execute("INSERT INTO devices(id,token_hash,name,created_at,pending_token_hash,pending_token_expires_at) VALUES('d','h','D',?,'pending',?)",(cutoff,int(time.time())-1))
                    con.execute("INSERT INTO events(id,device_id,kind,occurred_at,received_at,ciphertext_json) VALUES('old','d','sms.received',?,?,?)",(cutoff,cutoff,'{"v":1,"alg":"A256GCM","iv":"AAAAAAAAAAAAAAAA","ct":"AAAAAAAAAAAAAAAA"}'))
                    con.execute("INSERT INTO audit(occurred_at,action,result) VALUES(?,?,?)",(cutoff,'old','ok'))
                    con.execute("INSERT INTO commands(id,device_id,type,created_at,expires_at,idempotency_key,ciphertext_json,state) VALUES('done','d','sms.send',?,?,?,?,'sent')",(cutoff,cutoff+10,'done','{"v":1,"alg":"A256GCM","iv":"AAAAAAAAAAAAAAAA","ct":"AAAAAAAAAAAAAAAA"}'))
                    con.execute("INSERT INTO commands(id,device_id,type,created_at,expires_at,idempotency_key,ciphertext_json,state) VALUES('pending','d','sms.send',?,?,?,?,'queued')",(cutoff,cutoff+10,'pending','{"v":1,"alg":"A256GCM","iv":"AAAAAAAAAAAAAAAA","ct":"AAAAAAAAAAAAAAAA"}'))
                removed=srv.run_maintenance()
                self.assertGreaterEqual(removed['events'],1)
                self.assertGreaterEqual(removed['audit'],1)
                self.assertGreaterEqual(removed['commands'],1)
                with sqlite3.connect(db) as con:
                    self.assertEqual(con.execute("SELECT COUNT(*) FROM events WHERE id='old'").fetchone()[0],0)
                    self.assertEqual(con.execute("SELECT COUNT(*) FROM commands WHERE id='done'").fetchone()[0],0)
                    self.assertEqual(con.execute("SELECT COUNT(*) FROM commands WHERE id='pending'").fetchone()[0],1)
                    token=con.execute("SELECT token_hash,pending_token_hash,pending_token_expires_at FROM devices WHERE id='d'").fetchone()
                    self.assertEqual(token[0],'h')
                    self.assertEqual(token[1],'')
                    self.assertEqual(token[2],0)
                self.assertGreaterEqual(removed['pendingTokens'],1)
            finally:
                srv.DB_PATH=old_db
                srv.EVENT_RETENTION_DAYS=old_event
                srv.AUDIT_RETENTION_DAYS=old_audit
                srv.COMMAND_RETENTION_DAYS=old_command

if __name__=='__main__':
    unittest.main(verbosity=2)
