"""Companion-mode state is visible without relaxing trusted state sanitization."""
import unittest
import simhub_server as server

class CompanionStateTest(unittest.TestCase):
    def test_permission_and_mode_diagnostics_survive_sanitization(self):
        state=server.sanitize_state({
            "smsMode":"companion","smsRoleHeld":False,
            "smsReadPermission":True,"smsReceivePermission":True,
            "smsSendPermission":True,"smsOperational":True,
            "unknownCredential":"never-forward"
        })
        self.assertEqual(state["smsMode"],"companion")
        self.assertFalse(state["smsRoleHeld"])
        self.assertTrue(state["smsOperational"])
        self.assertTrue(state["smsReadPermission"])
        self.assertNotIn("unknownCredential",state)
    def test_queue_upload_and_sync_observability_are_sanitized(self):
        state=server.sanitize_state({
            "pendingEvents":0,"pendingCommandAcks":2,
            "uploadedEventReceipts":120,"uploadedEventTotal":155,
            "lastEventUploadAt":1790000000,"lastEventUploadCount":3,
            "lastUploadAttemptAt":1790000001,
            "lastUploadError":"HTTP_429_rate_limited",
            "lastEventQueueError":"database_insert_rejected",
            "lastSmsProviderChangeAt":1789999000,
            "lastSmsBroadcastAt":0,"lastReconcileAt":1790000000,
            "lastReconcileCount":100,
            "pendingEventTasks":[{"kind":"sms.history","queuedAt":1790000000,"body":"DO_NOT_SEND","id":"sensitive-queue-id"},{"kind":"sms.received","queuedAt":1790000001}],
            "smsBody":"secret",
            "token":"do_not_expose"
        })
        self.assertEqual(state["pendingEvents"],0)
        self.assertEqual(state["uploadedEventReceipts"],120)
        self.assertEqual(state["lastUploadError"],"HTTP_429_rate_limited")
        self.assertEqual(state["lastReconcileCount"],100)
        self.assertNotIn("smsBody",state)
        self.assertEqual(len(state["pendingEventTasks"]),2)
        self.assertEqual(state["pendingEventTasks"][0],{"kind":"sms.history","queuedAt":1790000000})
        self.assertNotIn("body",state["pendingEventTasks"][0])
        self.assertNotIn("id",state["pendingEventTasks"][0])
        self.assertNotIn("token",state)

    def test_default_mode_remains_supported(self):
        state=server.sanitize_state({"smsMode":"default","smsRoleHeld":True})
        self.assertEqual(state["smsMode"],"default")
        self.assertTrue(state["smsRoleHeld"])

if __name__=="__main__":
    unittest.main()
