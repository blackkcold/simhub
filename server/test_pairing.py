#!/usr/bin/env python3
"""Device-initiated pairing security and lifecycle regression tests."""
import base64
import hashlib
import json
import os
import socket
import sqlite3
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
ADMIN="P"*48
PREFIX=bytes.fromhex("3059301306072a8648ce3d020106082a8648ce3d03010703420004")
PUB=base64.urlsafe_b64encode(PREFIX+b"X"*64).decode().rstrip("=")
CIPHER={"v":1,"alg":"A256GCM","iv":"A"*16,"ct":"B"*32}

class PairingTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        sock=socket.socket();sock.bind(("127.0.0.1",0));cls.port=sock.getsockname()[1];sock.close()
        cls.temp=tempfile.TemporaryDirectory()
        cls.db=Path(cls.temp.name)/"simhub.sqlite"
        cls.base=f"http://127.0.0.1:{cls.port}"
        env={**os.environ,"SIMHUB_ADMIN_TOKEN":ADMIN,"SIMHUB_REQUIRE_TOTP":"false",
             "SIMHUB_BIND":"127.0.0.1","SIMHUB_PORT":str(cls.port),
             "SIMHUB_DB":str(cls.db),"SIMHUB_PUBLIC_BASE_URL":cls.base,
             "SIMHUB_MANAGEMENT_ORIGIN":cls.base}
        cls.proc=subprocess.Popen([sys.executable,str(ROOT/"server"/"simhub_server.py")],env=env,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
        for _ in range(120):
            try:
                with urllib.request.urlopen(cls.base+"/readyz",timeout=.3) as response:
                    if response.status==200:break
            except (urllib.error.URLError,TimeoutError):time.sleep(.05)
        else:raise RuntimeError("Relay failed to start")
    @classmethod
    def tearDownClass(cls):
        cls.proc.terminate();cls.proc.wait(timeout=5);cls.proc.stderr.close();cls.temp.cleanup()

    def req(self,method,path,body=None,auth=None):
        headers={}
        if auth:headers["Authorization"]=auth
        payload=json.dumps(body).encode() if body is not None else None
        if payload is not None:headers["Content-Type"]="application/json"
        request=urllib.request.Request(self.base+path,data=payload,headers=headers,method=method)
        try:
            with urllib.request.urlopen(request,timeout=3) as response:
                return response.status,json.load(response)
        except urllib.error.HTTPError as error:
            return error.code,json.load(error)

    def test_pairing_requires_admin_and_device_secret(self):
        code,started=self.req("POST","/api/v1/pairings/start",{"publicKey":PUB,"name":"Test handset","model":"Android"})
        self.assertEqual(code,201)
        self.assertRegex(started["code"],r"^\d{8}$")
        pair_id=started["requestId"]
        poll="Pair "+started["pollToken"]
        status,_=self.req("GET",f"/api/v1/pairings/{pair_id}/status")
        self.assertEqual(status,401)
        status,pending=self.req("GET",f"/api/v1/pairings/{pair_id}/status",auth=poll)
        self.assertEqual((status,pending["status"]),(200,"pending"))
        status,_=self.req("GET","/api/v1/pairings/lookup?code="+started["code"])
        self.assertEqual(status,401)
        status,info=self.req("GET","/api/v1/pairings/lookup?code="+started["code"],auth="Bearer "+ADMIN)
        self.assertEqual(status,200)
        self.assertEqual(info["publicKey"],PUB)
        self.assertEqual(info["requestId"],pair_id)
        approval={"code":started["code"],"keyId":"exampleKey123",
                  "wrappedKey":CIPHER,"envelope":{**CIPHER,"publicKey":PUB},
                  "deviceTokenHash":hashlib.sha256(b"device-secret").hexdigest(),
                  "completeProofHash":"f"*64}
        status,_=self.req("POST",f"/api/v1/pairings/{pair_id}/approve",approval)
        self.assertEqual(status,401)
        status,approved=self.req("POST",f"/api/v1/pairings/{pair_id}/approve",approval,auth="Bearer "+ADMIN)
        self.assertEqual(status,200)
        status,res=self.req("GET",f"/api/v1/pairings/{pair_id}/status",auth=poll)
        self.assertEqual(status,200)
        self.assertEqual(res["status"],"approved")
        self.assertEqual(res["envelope"]["ct"],CIPHER["ct"])
        self.assertNotIn("deviceTokenHash",res)
        status,_=self.req("POST",f"/api/v1/pairings/{pair_id}/complete",{"proof":"0"*64},auth=poll)
        self.assertEqual(status,403)
        status,completed=self.req("POST",f"/api/v1/pairings/{pair_id}/complete",{"proof":"f"*64},auth=poll)
        self.assertEqual(status,200)
        self.assertEqual(completed["deviceId"],approved["deviceId"])
        status,_=self.req("POST",f"/api/v1/pairings/{pair_id}/complete",{"proof":"f"*64},auth=poll)
        self.assertEqual(status,200)
        with sqlite3.connect(self.db) as con:
            self.assertEqual(con.execute("SELECT COUNT(*) FROM devices WHERE id=?",(approved["deviceId"],)).fetchone()[0],1)
            self.assertEqual(con.execute("SELECT token_hash FROM devices WHERE id=?",(approved["deviceId"],)).fetchone()[0],approval["deviceTokenHash"])
        status,_=self.req("GET","/api/v1/pairings/lookup?code="+started["code"],auth="Bearer "+ADMIN)
        self.assertEqual(status,404)

    def test_invalid_public_keys_and_missing_code(self):
        status,_=self.req("POST","/api/v1/pairings/start",{"publicKey":"invalid"})
        self.assertEqual(status,400)
        status,_=self.req("GET","/api/v1/pairings/lookup?code=123",auth="Bearer "+ADMIN)
        self.assertEqual(status,400)

if __name__=="__main__":
    unittest.main()
