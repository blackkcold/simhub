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
    def test_default_mode_remains_supported(self):
        state=server.sanitize_state({"smsMode":"default","smsRoleHeld":True})
        self.assertEqual(state["smsMode"],"default")
        self.assertTrue(state["smsRoleHeld"])

if __name__=="__main__":
    unittest.main()
