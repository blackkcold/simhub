#!/usr/bin/env python3
"""Host/route separation regression tests against an isolated relay process."""
import json
import os
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
TOKEN="A"*48

class SurfaceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        sock=socket.socket()
        sock.bind(("127.0.0.1",0))
        cls.port=sock.getsockname()[1]
        sock.close()
        cls.tmp=tempfile.TemporaryDirectory()
        cls.base=f"http://127.0.0.1:{cls.port}"
        env=os.environ.copy()
        env.update({
            "SIMHUB_ADMIN_TOKEN":TOKEN,
            "SIMHUB_BIND":"127.0.0.1",
            "SIMHUB_PORT":str(cls.port),
            "SIMHUB_DB":str(Path(cls.tmp.name)/"simhub.db"),
            "SIMHUB_WEB_ROOT":str(ROOT/"web"),
            "SIMHUB_REQUIRE_TOTP":"false",
            "SIMHUB_MANAGEMENT_ORIGIN":"https://admin.simhub.example.com",
            "SIMHUB_PUBLIC_BASE_URL":"https://node.simhub.example.com",
            "SIMHUB_SEPARATE_SURFACES":"true",
        })
        cls.proc=subprocess.Popen([sys.executable,str(ROOT/"server"/"simhub_server.py")],env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        for _ in range(100):
            try:
                urllib.request.urlopen(cls.base+"/readyz",timeout=.2).read()
                break
            except Exception:
                time.sleep(.04)
        else:
            raise RuntimeError("isolated server did not start")
    @classmethod
    def tearDownClass(cls):
        cls.proc.terminate()
        cls.proc.wait(timeout=3)
        cls.tmp.cleanup()

    def request(self,method,path,host,body=None,device=False):
        data=None if body is None else json.dumps(body).encode()
        headers={"Host":host,"Content-Type":"application/json"}
        if not device:
            headers["Authorization"]="Bearer "+TOKEN
        request=urllib.request.Request(self.base+path,method=method,data=data,headers=headers)
        try:
            with urllib.request.urlopen(request,timeout=3) as response:
                raw=response.read()
                return response.status, json.loads(raw) if response.headers.get('Content-Type','').startswith('application/json') else {}
        except urllib.error.HTTPError as exc:
            return exc.code, json.loads(exc.read())

    def test_separated_hosts(self):
        admin="admin.simhub.example.com"
        node="node.simhub.example.com"
        self.assertEqual(self.request("GET","/",admin)[0],200)
        self.assertEqual(self.request("GET","/",node)[0],404)
        self.assertEqual(self.request("GET","/api/v1/devices",node)[0],404)
        self.assertEqual(self.request("GET","/api/v1/devices",admin)[0],200)
        self.assertEqual(self.request("POST","/api/v1/enroll",node,{"token":"short"},device=True)[0],400)
        self.assertEqual(self.request("POST","/api/v1/enroll",admin,{"token":"short"},device=True)[0],404)
        self.assertEqual(self.request("GET","/", "unknown.invalid")[0],421)
        self.assertEqual(self.request("GET","/healthz",node)[0],200)  # local health probe remains available
        self.assertEqual(self.request("GET","/readyz","127.0.0.1:"+str(self.port))[0],200)

if __name__=="__main__":
    unittest.main(verbosity=2)
