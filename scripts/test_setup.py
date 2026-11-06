#!/usr/bin/env python3
"""Exercise generated deployment ports without touching DNS or Docker."""
import contextlib
import importlib.util
import io
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("deployment_setup", Path(__file__).with_name("setup.py"))
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)


class SetupTests(unittest.TestCase):
    def test_both_origins_use_the_selected_private_port(self):
        for port in (8787, 8788, 18443):
            with self.subTest(port=port):
                text = setup.caddy_config("admin.example.test", "node.example.test", port)
                self.assertEqual(text.count(f"reverse_proxy 127.0.0.1:{port} {{"), 2)
                self.assertEqual(text.count("respond @internal_health 404"), 2)
                self.assertEqual(text.count("header_up X-Forwarded-For {remote_host}"), 2)
        for port in (0, 65536, -1, True, "8787\ninject"):
            with self.assertRaises(ValueError):
                setup.caddy_config("admin.example.test", "node.example.test", port)

    def test_generated_env_proxy_and_instructions_share_one_port(self):
        for channel, port, override in (("stable", 8787, []), ("dev", 8788, []),
                                        ("dev", 18443, ["--host-port", "18443"])):
            for mode in ("external", "managed"):
                with self.subTest(channel=channel, mode=mode, port=port), tempfile.TemporaryDirectory() as tmp:
                    root = Path(tmp)
                    root.joinpath(".env.example").write_text((setup.ROOT / ".env.example").read_text())
                    argv = ["setup.py", "--admin-domain", "admin.example.test", "--node-domain", "node.example.test",
                            "--channel", channel, "--mode", mode, "--no-start", "--skip-dns-check", *override]
                    stdout = io.StringIO()
                    with patch.object(setup, "ROOT", root), patch.object(setup, "ENV", root / ".env"), \
                            patch.object(setup, "CADDY", root / "Caddyfile"), patch.object(setup, "check_docker"), \
                            patch.object(setup, "ensure_ports"), patch.object(setup, "public_ipv4", return_value=None), \
                            patch.object(setup, "dns_addresses", return_value=set()), \
                            patch.object(setup.sys, "argv", argv), contextlib.redirect_stdout(stdout):
                        self.assertEqual(setup.main(), 0)
                    env = setup.parse_env(root.joinpath(".env").read_text())
                    self.assertEqual(env["SIMHUB_HOST_PORT"], str(port))
                    self.assertEqual(env["COMPOSE_PROJECT_NAME"], "simhub-" + channel)
                    self.assertEqual(root.joinpath(".env").stat().st_mode & 0o777, 0o600)
                    if mode == "managed":
                        self.assertEqual(root.joinpath("Caddyfile").read_text().count(f"127.0.0.1:{port}"), 2)
                    else:
                        self.assertIn(f"reverse proxy to 127.0.0.1:{port}", stdout.getvalue())
                        self.assertIn(f"never expose port {port}", stdout.getvalue())


if __name__ == "__main__":
    unittest.main()
