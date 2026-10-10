"""Security regression tests for the rootless, signed-image updater."""
import importlib.util
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "simhub_updater", Path(__file__).with_name("simhub_updater.py"))
updater = importlib.util.module_from_spec(spec)
spec.loader.exec_module(updater)

IMAGE = "ghcr.io/blackkcold/simhub-relay@sha256:" + "a" * 64


class UpdaterTests(unittest.TestCase):
    def test_version(self):
        self.assertEqual(updater.parse_version("v0.11.1"), (0, 11, 1))
        self.assertEqual(updater.parse_version("v0.11.0"), (0, 11, 0))
        self.assertEqual(updater.parse_version("v0.11.0-rc1"), (0, 0, 0))

    def test_require_non_root_and_rootless_daemon(self):
        with patch.object(updater.os, "geteuid", return_value=0):
            with self.assertRaises(PermissionError):
                updater.require_rootless()
        with patch.object(updater.os, "geteuid", return_value=1000), patch.object(
                updater, "run", return_value=json.dumps(["name=seccomp"])):
            with self.assertRaises(PermissionError):
                updater.require_rootless()

    def test_reject_legacy_release_without_signed_digest(self):
        info = {"version": "0.11.0", "assets": {"update-manifest.json": "https://example.test"}}
        bad = {"schemaVersion": 1, "release": "0.11.0", "channel": "stable",
               "server": {"dbSchema": 11, "asset": "source.zip", "sha256": "a" * 64}}
        with patch.object(updater, "fetch_json", return_value=bad):
            with self.assertRaises(ValueError):
                updater.release_image(info)
        bad["server"]["image"] = "evil.tld/repo@sha256:" + "a" * 64
        with patch.object(updater, "fetch_json", return_value=bad):
            with self.assertRaises(ValueError):
                updater.release_image(info)

    def test_only_fixed_image_and_strict_signature_identity(self):
        with patch.object(updater, "run", return_value="[]") as invoke:
            updater.verify_image(IMAGE)
        self.assertEqual(invoke.call_args.args[0:2], ("cosign", "verify"))
        self.assertIn("--certificate-identity=" + updater.CERT_IDENTITY,
                      invoke.call_args.args)
        self.assertIn("--certificate-oidc-issuer=" + updater.CERT_ISSUER,
                      invoke.call_args.args)
        with self.assertRaises(ValueError):
            updater.verify_image("ghcr.io/blackkcold/simhub-relay:latest")

    def test_fail_closed_when_signature_invalid(self):
        with patch.object(updater, "verify_image", side_effect=RuntimeError("sig invalid")), patch.object(
                updater, "run") as invoke:
            with self.assertRaises(RuntimeError):
                updater.pull_verified_image(IMAGE)
            invoke.assert_not_called()

    def test_write_digest_reference_and_restore_local_image(self):
        with tempfile.TemporaryDirectory() as tmp:
            f = Path(tmp) / ".env"
            f.write_text("ADMIN_TOKEN=fake\nSIMHUB_IMAGE_TAG=v0.10.0\n", encoding="utf-8")
            with patch.object(updater, "ENV_FILE", f), patch.object(updater, "ROOT", Path(tmp)):
                updater.update_env(IMAGE, "0.11.0")
                self.assertEqual(updater.current_version(), "0.11.0")
                self.assertIn("SIMHUB_IMAGE_REF=" + IMAGE, f.read_text())
                self.assertIn("ADMIN_TOKEN=fake", f.read_text())
                updater.update_env("simhub-relay:rollback-123", "0.10.0")
                self.assertEqual(updater.current_version(), "0.10.0")
                with self.assertRaises(ValueError):
                    updater.update_env("evil/relay:latest", "0.10.0")

    def test_fetch_manifest_rejects_invalid_metadata(self):
        with patch.object(updater, "fetch_json", return_value={
                "tag_name": "v0.11.0", "draft": False, "prerelease": True,
                "published_at": "now"}):
            with self.assertRaises(ValueError):
                updater.latest()

    def test_user_systemd_unit_does_not_create_user_namespace(self):
        """PrivateTmp/ProtectSystem under systemd --user can remap Docker socket GIDs."""
        installer = Path(__file__).with_name("install-updater.sh").read_text("utf-8")
        self.assertIn('cat > "$UNIT" <<EOF', installer)
        unit = installer.split('cat > "$UNIT" <<EOF', 1)[1].split("\nEOF", 1)[0]
        for setting in ("PrivateTmp", "ProtectSystem", "PrivateUsers",
                        "PrivateMounts", "PrivateDevices", "ProtectHome",
                        "ReadOnlyPaths", "ReadWritePaths", "InaccessiblePaths",
                        "BindPaths", "TemporaryFileSystem"):
            self.assertNotRegex(unit, r"(?m)^" + setting + r"\s*=")
        # Preserve non-namespace controls and the non-root Docker architecture.
        self.assertIn("NoNewPrivileges=true", unit)
        self.assertIn("RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6", unit)
        self.assertIn("SystemCallArchitectures=native", unit)
        self.assertIn("simhub_updater.py --serve", unit)

    def test_installer_fails_closed_on_namespace_or_ipc_failure(self):
        installer = Path(__file__).with_name("install-updater.sh").read_text("utf-8")
        self.assertIn('readlink "/proc/$PID/ns/user"', installer)
        self.assertIn('readlink /proc/self/ns/user', installer)
        self.assertIn('s.connect(sys.argv[1])', installer)
        self.assertIn('result.get("mode") == "rootless-verified"', installer)
        self.assertIn('if [[ "$UPDATER_READY" != "1" ]]', installer)
        self.assertNotIn("chmod 666", installer)
        self.assertIn("if [[ \"$EUID\" -eq 0 ]]", installer)

    def test_readiness_needs_two_consecutive_successes(self):
        import io
        ready = json.dumps({"ok": True, "version": "0.13.2"}).encode()
        with patch.object(updater.urllib.request, "urlopen", side_effect=[
                io.BytesIO(ready), OSError("upstream restart"),
                io.BytesIO(ready), io.BytesIO(ready)]) as fetch, patch.object(
                updater.time, "sleep", return_value=None):
            self.assertTrue(updater.health("0.13.2"))
            self.assertEqual(fetch.call_count, 4)

    def test_caddy_retries_only_safe_reads(self):
        caddy = Path(__file__).resolve().parent.parent.joinpath("Caddyfile.example").read_text("utf-8")
        self.assertEqual(caddy.count("lb_try_duration 8s"), 2)
        self.assertEqual(caddy.count("method GET HEAD"), 2)
        self.assertNotIn("method POST", caddy)

    def test_ipc_control_restricts_actions(self):
        self.assertFalse(hasattr(updater, "extract"))
        self.assertFalse(hasattr(updater, "download"))
        self.assertTrue(updater.DIGEST_IMAGE.fullmatch(IMAGE))
        self.assertFalse(updater.DIGEST_IMAGE.fullmatch(IMAGE + "\n"))


if __name__ == "__main__":
    unittest.main()
