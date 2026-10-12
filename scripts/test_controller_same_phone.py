"""Guard same-phone controller approval and shared Android Compose shell.

CI's Android compilation and browser tests cover runtime compatibility; these
source-level checks protect architectural and secret-handling invariants.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parent.parent
ANDROID = ROOT / "android/app/src/main/java/com/blackkcold/simhub"


class NativeControllerContract(unittest.TestCase):
    def test_single_navigation_owner(self):
        hub = (ANDROID / "HubUi.kt").read_text()
        workspace = (ANDROID / "ControllerWorkspace.kt").read_text()
        self.assertIn("NavigationBar(", hub)
        self.assertIn("NavigationRail(", hub)
        self.assertIn("ControllerWorkspace(", hub)
        for nested in ("NavigationBar(", "NavigationRail(", "TopAppBar(", "WebView("):
            self.assertNotIn(nested, workspace)
        self.assertIn("onSelectTab", workspace)

    def test_browser_return_contains_no_authorization_secret(self):
        web = (ROOT / "web/app.js").read_text()
        client = (ANDROID / "ControllerWorkspace.kt").read_text()
        activity = (ANDROID / "HubActivity.kt").read_text()
        self.assertIn("samePhoneControllerApproval", web)
        self.assertIn("simhub://controller?resume=1", web)
        self.assertIn("controller=local", client)
        self.assertIn('uri.getQueryParameter("resume")=="1"', activity)
        self.assertNotIn("simhub://controller?code=", web)
        self.assertNotIn("simhub://controller?vault=", web)

    def test_pending_private_key_is_encrypted(self):
        client = (ANDROID / "ControllerSecureClient.kt").read_text()
        self.assertIn('putString("pendingPairing",saved.toString())', client)
        self.assertIn('cipher.init(Cipher.ENCRYPT_MODE,localWrappingKey())', client)
        self.assertIn('cipher.init(Cipher.DECRYPT_MODE,localWrappingKey()', client)
        self.assertIn("pendingAad()", client)
        self.assertIn('private fun clearPending()', client)
        self.assertIn("System.currentTimeMillis()+600_000L", client)

    def test_approval_still_requires_current_global_vault(self):
        web = (ROOT / "web/app.js").read_text()
        server = (ROOT / "server/controller_auth.py").read_text()
        self.assertIn("await ensureStepUp();", web)
        self.assertIn("vaultId:globalVault?.vaultId", web)
        self.assertIn('body.get("vaultKeyId")!=root["keyId"]', server)


if __name__ == "__main__":
    unittest.main()
