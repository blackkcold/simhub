"""Durable notification outbox: dedup, leases, privacy, retries and TTL."""
import sqlite3
import unittest
import notification_outbox as outbox

class NotificationOutboxTest(unittest.TestCase):
    def setUp(self):
        self.db=sqlite3.connect(":memory:")
        self.db.row_factory=sqlite3.Row
        self.db.execute("PRAGMA foreign_keys=ON")
        self.db.execute("CREATE TABLE devices(id TEXT PRIMARY KEY)")
        self.db.execute("INSERT INTO devices(id) VALUES('node-a')")
        outbox.init(self.db)

    def tearDown(self):self.db.close()

    def test_claim_retry_and_provider_success_survive_worker_restart(self):
        metadata={"device":"Node 1","hasOtp":True,"occurredAt":1000,
                  "body":"confidential body should never persist",
                  "recipient":"+123456789"}
        self.assertTrue(outbox.enqueue(self.db,"node-a","event-a","webhook",metadata,1001,1300))
        self.assertFalse(outbox.enqueue(self.db,"node-a","event-a","webhook",metadata,1001,1300))
        raw=self.db.execute("SELECT payload_json FROM notification_outbox").fetchone()[0]
        self.assertNotIn("confidential",raw)
        self.assertNotIn("+123456789",raw)
        self.assertIn("New OTP received",raw)
        self.db.execute("BEGIN IMMEDIATE") if not self.db.in_transaction else None
        first=outbox.claim(self.db,1002)
        self.assertEqual(len(first),1)
        self.assertEqual(outbox.claim(self.db,1003),[])
        outbox.finish(self.db,first[0]["id"],False,1003,"http_503")
        self.assertEqual(outbox.claim(self.db,1004),[])
        second=outbox.claim(self.db,1020)
        self.assertEqual(len(second),1)
        outbox.finish(self.db,second[0]["id"],True,1021)
        self.assertEqual(outbox.summary(self.db)["delivered"],1)
        self.assertEqual(outbox.claim(self.db,1030),[])

    def test_expiry_and_device_deletion(self):
        self.assertTrue(outbox.enqueue(self.db,"node-a","event-b","bark",{"hasOtp":False},1000,1004))
        self.assertEqual(outbox.claim(self.db,1005),[])
        self.assertEqual(outbox.summary(self.db)["expired"],1)
        self.db.execute("DELETE FROM devices WHERE id='node-a'")
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM notification_outbox").fetchone()[0],0)

    def test_leases_recover_after_crash_and_bound_retry_attempts(self):
        outbox.enqueue(self.db,"node-a","event-c","ntfy",{},1000,100000)
        current=1001
        for iteration in range(outbox.MAX_ATTEMPTS):
            claimed=outbox.claim(self.db,current)
            self.assertEqual(len(claimed),1)
            outbox.finish(self.db,claimed[0]["id"],False,current,"timeout")
            current+=1000
        self.assertEqual(outbox.summary(self.db)["failed"],1)

if __name__=="__main__":unittest.main()
