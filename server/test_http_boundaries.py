"""Untrusted request targets cannot select files, inject headers, or forge logs."""
import http.client
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import unittest

import simhub_server as server


class HttpLoggingTests(unittest.TestCase):
    def message(self, path, method="GET", fmt='"%s" %s %s', args=("request", "200", "12")):
        handler=server.SimHubHandler.__new__(server.SimHubHandler)
        handler.path=path
        handler.command=method
        with self.assertLogs(server.log,level="INFO") as captured:
            handler.log_message(fmt,*args)
        self.assertEqual(len(captured.records),1)
        return captured.records[0].getMessage()

    def test_access_logs_preserve_method_path_status_without_query_or_userinfo(self):
        message=self.message("https://user:secret@relay.invalid/api/v1/events?token=private#private-fragment")
        self.assertEqual(message,"http GET /api/v1/events -> 200")

    def test_all_request_fields_cannot_create_log_lines_or_terminal_controls(self):
        message=self.message("/path\x00\x85\u2028\u202e?token=private",
                             "GET\r\nforged\x1b[31m",args=("unused","200\r\nforged","12"))
        self.assertEqual(len(message.splitlines()),1)
        self.assertTrue(all(char.isprintable() for char in message))
        self.assertIn("\\r\\n",message)
        self.assertIn("\\u001b",message)
        self.assertNotIn("private",message)
        self.assertTrue(message.endswith(" -> 0"))

    def test_error_logs_do_not_copy_the_untrusted_request_error(self):
        message=self.message("/bad?token=private",fmt="code %d, message %s",
                             args=(400,"Bad request syntax: secret\r\nforged"))
        self.assertEqual(message,"http GET /bad -> 400")

    def test_malformed_target_and_incomplete_request_still_log_safely(self):
        self.assertEqual(self.message("//[invalid"),"http GET <invalid-target> -> 200")
        handler=server.SimHubHandler.__new__(server.SimHubHandler)
        with self.assertLogs(server.log,level="INFO") as captured:
            handler.log_message("unexpected request")
        self.assertEqual(captured.records[0].getMessage(),"http   -> 0")

    def test_logged_fields_are_bounded(self):
        message=self.message("/"+"p"*10000,"X"*10000)
        self.assertEqual(message.count("X"),32)
        self.assertEqual(message.count("p"),2048)  # 2047 path chars plus the "http" prefix.
        self.assertLess(len(message),2100)


class StaticAssetHttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory()
        cls.root=Path(cls.temp.name)
        cls.web=cls.root/"web"
        cls.web.mkdir()
        cls.index=b"sandbox-index"
        for name,data in {"index.html":cls.index,"bundle.js":b"sandbox-js",
                          "style.css":b"sandbox-css","manifest.webmanifest":b"{}",
                          "payload.unknown":b"sandbox-data","vanishes.txt":b"remove-me"}.items():
            (cls.web/name).write_bytes(data)
        (cls.web/"nested").mkdir()
        (cls.web/"nested"/"asset.txt").write_bytes(b"nested-asset")
        (cls.root/"outside.txt").write_bytes(b"must-never-be-served")
        (cls.web/"outside-link.txt").symlink_to(cls.root/"outside.txt")
        with socket.socket() as sock:
            sock.bind(("127.0.0.1",0))
            cls.port=sock.getsockname()[1]
        cls.log=tempfile.TemporaryFile()
        env={**os.environ,"SIMHUB_ADMIN_TOKEN":"T"*48,"SIMHUB_REQUIRE_TOTP":"false",
             "SIMHUB_BIND":"127.0.0.1","SIMHUB_PORT":str(cls.port),
             "SIMHUB_DB":str(cls.root/"relay.db"),"SIMHUB_WEB_ROOT":str(cls.web),
             "SIMHUB_SEPARATE_SURFACES":"false","SIMHUB_CHANNEL":"stable"}
        cls.proc=subprocess.Popen([sys.executable,str(Path(server.__file__))],
                                  env=env,stdout=cls.log,stderr=cls.log)
        for _ in range(100):
            try:
                if cls.fetch("/healthz")[0]==200:
                    break
            except OSError:
                pass
            time.sleep(.05)
        else:
            cls.proc.terminate();cls.proc.wait(timeout=5)
            cls.log.seek(0)
            raise RuntimeError("Static test server failed: "+cls.log.read().decode(errors="replace"))

    @classmethod
    def tearDownClass(cls):
        cls.proc.terminate();cls.proc.wait(timeout=5)
        cls.log.close();cls.temp.cleanup()

    @classmethod
    def fetch(cls,path):
        connection=http.client.HTTPConnection("127.0.0.1",cls.port,timeout=3)
        try:
            connection.request("GET",path)
            response=connection.getresponse()
            return response.status,dict(response.getheaders()),response.read()
        finally:
            connection.close()

    def test_installed_assets_and_extensionless_routes_still_work(self):
        for path,body in (("/",self.index),("/messages/thread",self.index),
                          ("/bundle.js?version=1",b"sandbox-js"),
                          ("/nested/asset.txt",b"nested-asset")):
            with self.subTest(path=path):
                status,_,data=self.fetch(path)
                self.assertEqual((status,data),(200,body))

    def test_traversal_symlinks_and_missing_files_cannot_read_outside_inventory(self):
        for path in ("/../outside.txt","/%2e%2e/outside.txt","/..%2foutside.txt",
                     "/nested/../bundle.js","/nested//asset.txt","/outside-link.txt","/missing.js"):
            with self.subTest(path=path):
                status,_,body=self.fetch(path)
                self.assertEqual(status,404)
                self.assertNotIn(b"must-never-be-served",body)

    def test_files_added_after_start_are_not_request_addressable(self):
        (self.web/"late.txt").write_bytes(b"not-part-of-deployment")
        self.assertEqual(self.fetch("/late.txt")[0],404)

    def test_missing_installed_asset_fails_without_a_partial_success(self):
        self.assertEqual(self.fetch("/vanishes.txt")[0],200)
        (self.web/"vanishes.txt").unlink()
        self.assertEqual(self.fetch("/vanishes.txt")[0],404)

    def test_mime_headers_are_fixed_for_assets_and_hostile_request_suffixes(self):
        for path,expected in (("/bundle.js","application/javascript; charset=utf-8"),
                              ("/style.css","text/css; charset=utf-8"),
                              ("/manifest.webmanifest","application/manifest+json; charset=utf-8"),
                              ("/payload.unknown","application/octet-stream"),
                              ("/page%0d%0aX-Injected:%20yes","text/html; charset=utf-8")):
            with self.subTest(path=path):
                status,headers,_=self.fetch(path)
                self.assertEqual(status,200)
                self.assertEqual(headers["Content-Type"],expected)
                self.assertNotIn("x-injected",{key.lower() for key in headers})
                self.assertTrue(all("\r" not in value and "\n" not in value for value in headers.values()))


if __name__=="__main__":
    unittest.main()
