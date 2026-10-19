"""Telemetry inputs are bounded and legacy Relay clients remain supported."""
import time
import unittest
import simhub_server as server

class DeviceTelemetrySecurityTest(unittest.TestCase):
    def test_valid_compact_health_fields(self):
        ts=int(time.time())
        state=server.sanitize_telemetry({
            "sampledAt":ts, "batteryPct":76,"charging":True,
            "wifiConnected":True,"wifiSignalLevel":3,"wifiRssiDbm":-59,
            "cellularConnected":False,"internetValidated":True,
            "ssid":"HOME-PRIVATE","bssid":"AA:BB:CC:DD:EE:FF","latitude":39.9,
        })
        self.assertEqual(state["sampledAt"],ts)
        self.assertEqual(state["wifiSignalLevel"],3)
        self.assertEqual(state["wifiRssiDbm"],-59)
        for forbidden in ("ssid","bssid","latitude"):
            self.assertNotIn(forbidden,state)

    def test_invalid_and_missing_values_are_unknown_not_zero(self):
        now=int(time.time())
        bad=server.sanitize_telemetry({"sampledAt":now,"batteryPct":101,"wifiSignalLevel":5,
            "wifiRssiDbm":-190,"charging":1,"wifiConnected":"true"})
        self.assertEqual(bad,{"sampledAt":now})
        self.assertEqual(server.sanitize_telemetry({"batteryPct":60}),{})
        self.assertEqual(server.sanitize_telemetry({"sampledAt":now+600,"batteryPct":60}),{})
        self.assertEqual(server.sanitize_telemetry({"sampledAt":now,"batteryPct":True}),
                         {"sampledAt":now})

    def test_legacy_full_inventory_is_not_regressed(self):
        legacy=server.sanitize_state({"batteryPct":85,"charging":True,"wifiConnected":True})
        self.assertEqual(legacy["batteryPct"],85)
        self.assertTrue(legacy["charging"])
        self.assertNotIn("sampledAt",legacy)

    def test_manual_binding_never_accepts_arbitrary_strings(self):
        stamp=int(time.time())
        state=server.sanitize_state({"sampledAt":stamp,"manualRequestId":"request_safe-02"})
        self.assertEqual(state["manualRequestId"],"request_safe-02")
        unsafe=server.sanitize_state({"sampledAt":stamp,"manualRequestId":"<svg onload=alert(1)>"})
        self.assertNotIn("manualRequestId",unsafe)

if __name__=="__main__":
    unittest.main()
