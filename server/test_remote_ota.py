"""Remote OTA compatibility policy and metadata sanitization regressions."""
import unittest
from simhub_server import remote_ota_capable, sanitize_state, ALLOWED_COMMANDS

class RemoteOtaTests(unittest.TestCase):
    def test_command_registered(self):
        self.assertIn("ota.install",ALLOWED_COMMANDS)
    def test_requires_local_consent_and_reported_platform(self):
        state={"sdk":36,"remoteOtaSupported":True,
               "remoteOtaAuthorized":True,"remoteOtaInstallPermission":True}
        self.assertTrue(remote_ota_capable("0.13.3",state))
        self.assertTrue(remote_ota_capable("0.14.0",{**state,"sdk":37}))
        self.assertFalse(remote_ota_capable("0.13.2",state))
        self.assertFalse(remote_ota_capable("0.13.3",{**state,"sdk":35}))
        self.assertTrue(remote_ota_capable("0.13.3",{**state,"sdk":35,
            "remoteOtaDeveloperOverride":True}))
        self.assertFalse(remote_ota_capable("0.13.3",{**state,"remoteOtaAuthorized":False}))
        self.assertFalse(remote_ota_capable("0.13.3",{**state,"remoteOtaInstallPermission":False}))
        self.assertFalse(remote_ota_capable("0.13.3",{}))
    def test_sanitized_no_arbitrary_metadata(self):
        state=sanitize_state({"remoteOtaStage":"awaiting_confirmation",
            "remoteOtaAuthorized":True,"remoteOtaTarget":"0.13.3",
            "secretToken":"do_not_echo"})
        self.assertEqual(state.get("remoteOtaStage"),"awaiting_confirmation")
        self.assertNotIn("secretToken",state)


class DeviceVersionReconciliationTests(unittest.TestCase):
    def test_full_inventory_and_heartbeat_share_authoritative_version(self):
        import json
        import sqlite3
        from types import SimpleNamespace
        from unittest.mock import patch
        import simhub_server as server

        con=sqlite3.connect(":memory:")
        con.row_factory=sqlite3.Row
        con.executescript("""
        CREATE TABLE devices(
            id TEXT PRIMARY KEY, app_version TEXT, os_version TEXT,
            last_seen_at INTEGER, node_type TEXT, capabilities_json TEXT,
            key_id TEXT, pending_key_id TEXT, pending_wrapped_key_json TEXT
        );
        CREATE TABLE device_state(
            device_id TEXT PRIMARY KEY, state_json TEXT, updated_at INTEGER
        );
        INSERT INTO devices VALUES
            ('node-1','0.16.0','16',0,'android','[]','','','');
        """)
        responses=[]
        handler=SimpleNamespace(send_json=lambda status,result:responses.append((status,result)))
        try:
            with patch.object(server,"open_db",lambda:con), patch.object(server,"signal_stream",lambda:None):
                server.SimHubHandler.put_device_state(handler,"node-1",{
                    "appVersion":"0.16.1","appVersionCode":42,"androidVersion":"16",
                    "remoteOtaStage":"installing","remoteOtaTargetCode":42})
                row=con.execute("SELECT app_version FROM devices WHERE id='node-1'").fetchone()
                self.assertEqual(row["app_version"],"0.16.1")
                state_row=con.execute("SELECT state_json,updated_at FROM device_state WHERE device_id='node-1'").fetchone()
                self.assertEqual(json.loads(state_row["state_json"])["appVersionCode"],42)
                old_inventory_at=state_row["updated_at"]
                server.SimHubHandler.heartbeat(handler,"node-1",{
                    "appVersion":"0.16.2","appVersionCode":43,"osVersion":"16"})
                row=con.execute("SELECT app_version FROM devices WHERE id='node-1'").fetchone()
                state_row=con.execute("SELECT state_json,updated_at FROM device_state WHERE device_id='node-1'").fetchone()
                merged=json.loads(state_row["state_json"])
                self.assertEqual(row["app_version"],"0.16.2")
                self.assertEqual(merged["appVersionCode"],43)
                self.assertEqual(merged["remoteOtaStage"],"installing")
                self.assertEqual(merged["remoteOtaTargetCode"],42)
                self.assertEqual(state_row["updated_at"],old_inventory_at)
                self.assertEqual([status for status,_ in responses],[200,200])
        finally:
            con.close()

    def test_state_sanitizer_accepts_only_version_metadata_not_secrets(self):
        from simhub_server import sanitize_state
        state=sanitize_state({"appVersion":"0.16.1","appVersionCode":42,
            "remoteOtaTargetCode":42,"privateKey":"forbidden"})
        self.assertEqual(state["appVersionCode"],42)
        self.assertEqual(state["remoteOtaTargetCode"],42)
        self.assertNotIn("privateKey",state)

if __name__=="__main__":unittest.main()
