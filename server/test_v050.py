"""v0.5.0 regression gates for private SIM inventory and command capabilities."""
import unittest
import simhub_server as relay

class SimInventoryPrivacyTests(unittest.TestCase):
    def test_sensitive_phone_number_not_exposed_in_relay_state(self):
        cipher={"v":2,"alg":"A256GCM","kid":"k"*16,"iv":"a"*16,"ct":"b"*32}
        raw={
            "nodeType":"android","network":"WIFI","wifiConnected":True,
            "dataFallbackEnabled":True,"dataFallbackStatus":"wifi_primary_data_sim_ready",
            "subscriptions":[{"subscriptionId":3,"phoneNumber":"+1234567890","carrierName":"Demo"}],
            "channels":[{"id":"cid","phoneNumber":"+1234567890","kind":"android-sim"}],
            "encryptedSimNumbers":{"eventId":"inventory-1","occurredAt":1730000000,"ciphertext":cipher},
            "phoneNumber":"+1234567890"
        }
        clean=relay.sanitize_state(raw)
        self.assertNotIn("phoneNumber",clean)
        self.assertNotIn("phoneNumber",clean["subscriptions"][0])
        self.assertNotIn("phoneNumber",clean["channels"][0])
        self.assertEqual(clean["encryptedSimNumbers"]["ciphertext"],cipher)
        self.assertTrue(clean["wifiConnected"])
        self.assertEqual(clean["dataFallbackStatus"],"wifi_primary_data_sim_ready")

    def test_invalid_inventory_cipher_rejected(self):
        clean=relay.sanitize_state({"encryptedSimNumbers":{"eventId":"x","occurredAt":1,"ciphertext":{"ct":"plaintext"}}})
        self.assertNotIn("encryptedSimNumbers",clean)

    def test_command_whitelist(self):
        self.assertTrue({"sms.sync_recent","sms.sync_older","device.network_policy","diagnostics.request"}<=relay.ALLOWED_COMMANDS)
        self.assertFalse(any(name.startswith("call.") for name in relay.ALLOWED_COMMANDS))

if __name__=="__main__":
    unittest.main()
