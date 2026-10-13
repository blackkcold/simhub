"""An in-flight long poll must surface an administrative reset without waiting for another poll."""
import sqlite3
import unittest
from types import SimpleNamespace
from unittest.mock import patch
import simhub_server as server

class ResetLongPollTests(unittest.TestCase):
    def test_reset_detected_after_wait(self):
        db=sqlite3.connect(":memory:")
        db.row_factory=sqlite3.Row
        db.executescript("""
            CREATE TABLE devices(id TEXT PRIMARY KEY,reset_requested_at INTEGER);
            CREATE TABLE commands(id TEXT,device_id TEXT,state TEXT,expires_at INTEGER);
            INSERT INTO devices VALUES ('test-node',0);
        """)
        captured=[]
        handler=SimpleNamespace(
            send_json=lambda code,body: captured.append((code,body)),
            send_error_json=lambda code,key,message:captured.append((code,{"error":key})))
        class WakeForReset:
            def __enter__(self):return self
            def __exit__(self,*args):return False
            def wait_for(self,predicate,timeout):
                db.execute("UPDATE devices SET reset_requested_at=123 WHERE id='test-node'")
                db.commit()
                return True
        try:
            with patch.object(server,"open_db",lambda:db),patch.object(server,"_stream_condition",WakeForReset()):
                server.SimHubHandler.get_pending_commands(handler,"test-node",{"wait":["15"]})
            self.assertEqual(captured,[(409,{"error":"device_reset_pending"})])
        finally:db.close()

    def test_no_reset_keeps_command_poll_successful(self):
        db=sqlite3.connect(":memory:")
        db.row_factory=sqlite3.Row
        db.executescript("""
            CREATE TABLE devices(id TEXT PRIMARY KEY,reset_requested_at INTEGER,last_seen_at INTEGER);
            CREATE TABLE commands(id TEXT,device_id TEXT,state TEXT,expires_at INTEGER,ack_at INTEGER,seq INTEGER,type TEXT,created_at INTEGER,idempotency_key TEXT,ciphertext_json TEXT);
            INSERT INTO devices VALUES ('test-node',0,0);
        """)
        captured=[]
        handler=SimpleNamespace(send_json=lambda code,body:captured.append((code,body)),send_error_json=lambda *args:self.fail(str(args)))
        try:
            with patch.object(server,"open_db",lambda:db):
                server.SimHubHandler.get_pending_commands(handler,"test-node",{"wait":["0"]})
            self.assertEqual(captured[0][0],200)
            self.assertEqual(captured[0][1]["commands"],[])
        finally:db.close()

if __name__=="__main__":unittest.main()
