#!/usr/bin/env python3
"""Exercise generated deployment ports without touching DNS or Docker."""
import contextlib
import importlib.util
import io
import socket
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("deployment_setup", Path(__file__).with_name("setup.py"))
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)


class SetupTests(unittest.TestCase):
    def test_active_tcp_tables_include_other_interfaces_and_ipv6(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            root.joinpath("tcp").write_text(
                "sl local_address rem_address st\n"
                "0: 050200C0:0050 00000000:0000 0A\n"  # 192.0.2.5:80 listener
                "1: 0100007F:2253 050200C0:01BB 01\n"  # remote 443 is not local 443
                "2: 0100007F:01BB 050200C0:1234 06\n")  # reusable TIME_WAIT
            root.joinpath("tcp6").write_text(
                "sl local_address remote_address st\n"
                "0: 00000000000000000000000000000000:01BB 00000000000000000000000000000000:0000 0A\n")
            self.assertEqual(setup.active_tcp_ports(root), {80, 443, 8787})
            root.joinpath("tcp6").unlink()
            self.assertEqual(setup.active_tcp_ports(root), {80, 8787}, "IPv6-disabled hosts remain supported")

    def test_missing_or_malformed_socket_tables_stop_the_check(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            with self.assertRaisesRegex(RuntimeError, "Cannot inspect"):
                setup.active_tcp_ports(root)
            for malformed in ("", "unexpected header\n", "sl local_address rem_address st\n0: incomplete\n"):
                root.joinpath("tcp").write_text(malformed)
                with self.assertRaisesRegex(RuntimeError, "Cannot inspect"):
                    setup.active_tcp_ports(root)

    @unittest.skipUnless(setup.sys.platform == "linux", "Linux socket-table preflight")
    def test_listener_on_another_local_address_is_detected(self):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            listener.bind(("127.0.0.2", 0))
            listener.listen(1)
            port = listener.getsockname()[1]
            # This would fool a preflight that only tried 127.0.0.1.
            with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
                probe.bind(("127.0.0.1", port))
            with self.assertRaisesRegex(RuntimeError, f"Port {port} not available"):
                setup.ensure_ports((port,))
        setup.ensure_ports((port,))

    @unittest.skipUnless(setup.sys.platform == "linux", "Linux socket-table preflight")
    def test_loopback_reservation_is_detected_before_listen(self):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as reserved:
            reserved.bind(("127.0.0.1", 0))
            port = reserved.getsockname()[1]
            with self.assertRaisesRegex(RuntimeError, f"Port {port} not available"):
                setup.ensure_ports((port,))

    @unittest.skipUnless(setup.sys.platform == "linux" and socket.has_ipv6, "IPv6 socket-table preflight")
    def test_ipv6_only_listener_is_detected(self):
        with socket.socket(socket.AF_INET6, socket.SOCK_STREAM) as listener:
            try:
                listener.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 1)
                listener.bind(("::1", 0))
            except OSError as exc:
                self.skipTest(f"IPv6 loopback is disabled: {exc}")
            listener.listen(1)
            port = listener.getsockname()[1]
            with self.assertRaisesRegex(RuntimeError, f"Port {port} not available"):
                setup.ensure_ports((port,))

    def test_available_port_probes_are_loopback_only_and_never_listen(self):
        with patch.object(setup, "active_tcp_ports", return_value=set()), \
                patch.object(setup.socket, "has_ipv6", True), patch.object(setup.socket, "socket") as create:
            setup.ensure_ports((18443,))
            sock = create.return_value.__enter__.return_value
            self.assertEqual([call.args[0] for call in sock.bind.call_args_list],
                             [("127.0.0.1", 18443), ("::1", 18443)])
            sock.listen.assert_not_called()
            sock.connect.assert_not_called()
        for invalid in (0, 65536, True, "80"):
            with self.assertRaises(ValueError):
                setup.ensure_ports((invalid,))

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
