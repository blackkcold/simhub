"""Command expiry must never disguise a delayed authenticated SMS receipt."""
import json
import sqlite3
import unittest
from types import SimpleNamespace
from unittest.mock import patch

import simhub_server as server


class CommandExpiryTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.row_factory = sqlite3.Row
        self.db.executescript("""
            CREATE TABLE devices(id TEXT PRIMARY KEY,reset_requested_at INTEGER,last_seen_at INTEGER);
            INSERT INTO devices VALUES ('node',0,0);
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
        self.responses = []
        self.handler = SimpleNamespace(
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


if __name__ == "__main__":
    unittest.main()
