#!/usr/bin/env python3
"""v0.4.0 configuration and trusted-proxy regression tests."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import simhub_server as server
import passkeys
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location("simhub_setup",ROOT/"scripts"/"setup.py")
setup=importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)

class ProxyTests(unittest.TestCase):
    def test_exact_gateway_only(self):
        with patch.object(server,"DOCKER_GATEWAY_IP","172.21.0.1"):
            self.assertTrue(server.trusted_proxy("172.21.0.1"))
            self.assertFalse(server.trusted_proxy("172.21.0.2"))
            self.assertEqual(server.forwarded_client_ip("172.21.0.1",{"X-Forwarded-For":"203.0.113.8"}),"203.0.113.8")
            self.assertEqual(server.forwarded_client_ip("172.21.0.2",{"X-Forwarded-For":"203.0.113.8"}),"172.21.0.2")

class SetupTests(unittest.TestCase):
    def test_fqdns(self):
        self.assertEqual(setup.validate_domain("Admin.Example.COM."),"admin.example.com")
        for value in ("","https://example.com","localhost"):
            with self.assertRaises(ValueError): setup.validate_domain(value)
    def test_env(self):
        content=setup.set_vars("KEEP=one\nSIMHUB_ADMIN_TOKEN=old\n",{"SIMHUB_ADMIN_TOKEN":"new"})
        self.assertIn("KEEP=one",content)
        self.assertIn("SIMHUB_ADMIN_TOKEN=new",content)
    def test_secure_create(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/".env"
            setup.secure_create(path,"A=B\n")
            self.assertEqual(path.stat().st_mode&0o777,0o600)
            with self.assertRaises(FileExistsError): setup.secure_create(path,"C=D")
    def test_caddy(self):
        config=setup.caddy_config("admin.example.com","node.example.com")
        self.assertIn("header_up X-Forwarded-For {remote_host}",config)

class WebauthnTests(unittest.TestCase):
    def test_origin(self):
        self.assertEqual(passkeys.rp_id("https://admin.example.com"),"admin.example.com")
        with self.assertRaises(ValueError): passkeys.rp_id("http://example.com")
    def test_b64(self):
        self.assertEqual(passkeys.decode(passkeys.b64(b"abc")) ,b"abc")

if __name__=="__main__":
    unittest.main(verbosity=2)
