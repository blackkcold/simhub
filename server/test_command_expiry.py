"""Command expiry must never disguise a delayed authenticated SMS receipt."""
import json
import sqlite3
import tempfile
import threading
import unittest
from contextlib import contextmanager
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import simhub_server as server


class CommandExpiryTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.row_factory = sqlite3.Row
        self.db.executescript("""
            CREATE TABLE devices(id TEXT PRIMARY KEY,reset_requested_at INTEGER,last_seen_at INTEGER,token_hash TEXT,revoked_at INTEGER);
            CREATE TABLE commands(
              seq INTEGER PRIMARY KEY,id TEXT,device_id TEXT,type TEXT,created_at INTEGER,
              expires_at INTEGER,ack_at INTEGER,state TEXT,result_json TEXT,
              idempotency_key TEXT,ciphertext_json TEXT
            );
            INSERT INTO commands VALUES
              (1,'queued','node','sms.send',1,10,NULL,'queued','{}','queued','{}'),
              (2,'dispatched','node','sms.send',1,10,NULL,'dispatched','{}','dispatched','{}'),
              (3,'legacy','node','sms.send',1,10,10,'expired','{}','legacy','{}');
        """)
        self.db.execute("INSERT INTO devices VALUES ('node',0,0,?,NULL)",(server.sha256_text("D"*48),))
        self.db.commit()
        self.responses = []
        self.handler = SimpleNamespace(
            headers={"Authorization":"Device "+"D"*48},
            send_json=lambda status, body: self.responses.append((status, body)),
            send_error_json=lambda status, code, message: self.responses.append((status, {"error": code})),
        )
        self.patches = [patch.object(server, "open_db", lambda: self.db),
                        patch.object(server, "now", lambda: 20),
                        patch.object(server, "signal_stream", lambda: None)]
        for p in self.patches:
            p.start()

    def tearDown(self):
        for p in reversed(self.patches):
            p.stop()
        self.db.close()

    def state(self, command):
        return self.db.execute("SELECT state FROM commands WHERE id=?", (command,)).fetchone()[0]

    def test_recent_separates_expired_from_uncertain_and_reconciles_late_ack(self):
        server.SimHubHandler.get_recent_commands(self.handler, {})
        self.assertEqual(self.state("queued"), "expired")
        self.assertEqual(self.state("dispatched"), "outcome_unknown")
        server.SimHubHandler.ack_command(self.handler, "node", "dispatched", {"state": "sent"})
        self.assertEqual(self.state("dispatched"), "sent")
        self.assertEqual(self.responses[-1][1]["currentState"],"sent")
        self.assertEqual(self.responses[-1][1]["ackAt"],20)
        server.SimHubHandler.ack_command(self.handler, "node", "dispatched", {"state": "submitted"})
        self.assertEqual(self.state("dispatched"), "sent")
        server.SimHubHandler.ack_command(self.handler, "node", "legacy", {"state": "delivered"})
        self.assertEqual(self.state("legacy"), "delivered")

    def test_pending_poll_never_redelivers_unknown_and_ack_is_device_scoped(self):
        server.SimHubHandler.get_pending_commands(self.handler, "node", {"wait": ["0"]})
        self.assertEqual(self.responses[-1][1]["commands"], [])
        self.assertEqual(self.state("dispatched"), "outcome_unknown")
        server.SimHubHandler.ack_command(self.handler, "wrong-node", "dispatched", {"state": "sent"})
        self.assertEqual(self.responses[-1][0], 404)
        self.assertEqual(self.state("dispatched"), "outcome_unknown")
        server.SimHubHandler.ack_command(self.handler, "node", "dispatched", {"state": "expired"})
        self.assertEqual(self.state("dispatched"), "expired")


class ConcurrentAcknowledgementTests(unittest.TestCase):
    def test_delayed_submitted_receipt_cannot_swallow_concurrent_sent_ack(self):
        """Overlap the old read/CAS window on two real SQLite connections."""
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/"ack.db"
            with sqlite3.connect(path) as con:
                con.executescript("""
                    PRAGMA journal_mode=WAL;
                    CREATE TABLE commands(id TEXT,device_id TEXT,state TEXT,ack_at INTEGER,result_json TEXT);
                    INSERT INTO commands VALUES ('command','node','dispatched',NULL,'{}');
                """)
            first_read=threading.Event();later_read=threading.Event()
            allow_first=threading.Event();first_committed=threading.Event()
            @contextmanager
            def connections():
                con=sqlite3.connect(path,timeout=5)
                con.row_factory=sqlite3.Row
                class Connection:
                    def execute(self,sql,args=()):
                        cursor=con.execute(sql,args)
                        if sql.startswith("SELECT state"):
                            if threading.current_thread().name.endswith("_0"):
                                first_read.set()
                                if not allow_first.wait(timeout=5):
                                    raise TimeoutError("First ACK was not released")
                            else:
                                later_read.set()
                                if not first_committed.wait(timeout=5):
                                    raise TimeoutError("Earlier ACK did not commit")
                        return cursor
                try:
                    yield Connection()
                    con.commit()
                    if threading.current_thread().name.endswith("_0"):
                        first_committed.set()
                except Exception:
                    con.rollback()
                    raise
                finally:
                    con.close()
            responses={}
            def acknowledge(state):
                handler=SimpleNamespace(send_json=lambda status,body:responses.update({state:(status,body)}),
                    send_error_json=lambda *args:self.fail(str(args)))
                server.SimHubHandler.ack_command(handler,"node","command",{"state":state})
            with patch.object(server,"open_db",connections),patch.object(server,"signal_stream",lambda:None):
                with ThreadPoolExecutor(max_workers=2,thread_name_prefix="ack") as pool:
                    first=pool.submit(acknowledge,"submitted")
                    try:
                        self.assertTrue(first_read.wait(timeout=5))
                        later=pool.submit(acknowledge,"sent")
                        # Old code can read stale 'dispatched' here; fixed code
                        # waits on the earlier write transaction instead.
                        later_read.wait(timeout=.2)
                    finally:
                        allow_first.set()
                    first.result(timeout=5);later.result(timeout=5)
            with sqlite3.connect(path) as con:
                self.assertEqual(con.execute("SELECT state FROM commands").fetchone()[0],"sent")
            self.assertEqual(responses["sent"][1]["currentState"],"sent")
            self.assertEqual(responses["submitted"][1]["currentState"],"submitted")


if __name__ == "__main__":
    unittest.main()
