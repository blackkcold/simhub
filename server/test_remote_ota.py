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

if __name__=="__main__":unittest.main()
