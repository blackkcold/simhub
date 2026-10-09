"""Integration gates for rate-safe device command status and historical E2EE keys."""
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
TOKEN="T"*48
DEVICE_TOKEN="D"*48
OLD_CIPHER={"v":1,"alg":"A256GCM","iv":"A"*16,"ct":"B"*32}


class HardeningIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        s=socket.socket();s.bind(("127.0.0.1",0));cls.port=s.getsockname()[1];s.close()
        cls.base="http://127.0.0.1:"+str(cls.port)
        cls.temp=tempfile.TemporaryDirectory();cls.db=Path(cls.temp.name)/"simhub.db"
        env={**os.environ,"SIMHUB_ADMIN_TOKEN":TOKEN,"SIMHUB_REQUIRE_TOTP":"false",
             "SIMHUB_BIND":"127.0.0.1","SIMHUB_PORT":str(cls.port),"SIMHUB_DB":str(cls.db),
             "SIMHUB_WEB_ROOT":str(ROOT/"web"),"SIMHUB_TRUST_DOCKER_GATEWAY":"false"}
        cls.proc=subprocess.Popen([sys.executable,str(ROOT/"server"/"simhub_server.py")],
                                  env=env,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
        for _ in range(100):
            try:
                urllib.request.urlopen(cls.base+"/healthz",timeout=.25).read();break
            except Exception:time.sleep(.04)
        else:raise RuntimeError("Relay failed startup")
        cls.dbcon=sqlite3.connect(cls.db)
        cls.dbcon.execute("""INSERT INTO devices(id,token_hash,name,created_at,last_seen_at,node_type,key_id,wrapped_key_json,pending_key_id,pending_wrapped_key_json)
                             VALUES(?,?,?,?,?,?,?,?,?,?)""",
                          ("node-hardening",hashlib.sha256(DEVICE_TOKEN.encode()).hexdigest(),"Test Node",
                           int(time.time()),int(time.time()),"android","retired-key-0001",
                           json.dumps(OLD_CIPHER),"fresh-key-000002",json.dumps(OLD_CIPHER)))
        cls.dbcon.execute("""INSERT INTO commands(id,device_id,type,created_at,expires_at,idempotency_key,ciphertext_json)
                             VALUES(?,?,?,?,?,?,?)""",
                          ("cmd-hardening","node-hardening","sms.sync_recent",int(time.time()),
                           int(time.time())+600,"idem-hardening",json.dumps(OLD_CIPHER)))
        cls.dbcon.commit()

    @classmethod
    def tearDownClass(cls):
        cls.dbcon.close();cls.proc.terminate();cls.proc.wait(timeout=5)
        cls.temp.cleanup()

    def request(self,path,method="GET",body=None,device=False):
        h={"Authorization":("Device "+DEVICE_TOKEN) if device else ("Bearer "+TOKEN)}
        if device:h["X-SimHub-Device-Id"]="node-hardening"
        data=None if body is None else json.dumps(body).encode()
        if body is not None:h["Content-Type"]="application/json"
        req=urllib.request.Request(self.base+path,method=method,data=data,headers=h)
        with urllib.request.urlopen(req,timeout=4) as response:
            return json.load(response)

    def test_01_preserve_retired_wrapped_keys_on_state_activation(self):
        self.request("/api/v1/devices/node-hardening/state",method="POST",device=True,
                     body={"nodeType":"android","cryptoKeyId":"fresh-key-000002","capabilities":["sms.receive"]})
        devices=self.request("/api/v1/devices")["devices"]
        row=next(x for x in devices if x["id"]=="node-hardening")
        self.assertEqual(row["keyId"],"fresh-key-000002")
        self.assertEqual(len(row["historicalWrappedKeys"]),1)
        self.assertEqual(row["historicalWrappedKeys"][0]["keyId"],"retired-key-0001")
        self.assertEqual(row["historicalWrappedKeys"][0]["wrappedKey"],OLD_CIPHER)
        self.assertNotIn("rawKey",json.dumps(row))
        again=self.request("/api/v1/devices")["devices"][0]["historicalWrappedKeys"]
        self.assertEqual(len(again),1)

    def test_02_command_results_allowlist_and_progress(self):
        self.request("/api/v1/devices/node-hardening/commands/cmd-hardening/ack",
                     method="POST",device=True,body={"state":"succeeded",
                     "result":{"scanned":100,"queued":6,"reason":"done","body":"SECRET-BODY",
                               "recipient":"+8613800000000","arbitrarySecret":"SECRET"}})
        recent=self.request("/api/v1/commands/recent?limit=20")["commands"]
        self.assertEqual(len(recent),1)
        one=recent[0]
        self.assertEqual(one["state"],"succeeded")
        self.assertEqual(one["result"]["scanned"],100)
        self.assertEqual(one["result"]["queued"],6)
        self.assertNotIn("ciphertext",one)
        self.assertNotIn("SECRET",json.dumps(one))
        self.assertNotIn("recipient",json.dumps(one))


if __name__=="__main__":
    unittest.main(verbosity=2)
