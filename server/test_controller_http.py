"""Exercise the controller pairing protocol through HTTP and the existing auth gate."""
import base64
import json
import sqlite3
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
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

ROOT=Path(__file__).resolve().parents[1]
ADMIN_TOKEN="A"*48

def b64(x):
    return base64.urlsafe_b64encode(x).decode().rstrip("=")

def pub(pair):
    return b64(pair.public_key().public_bytes(serialization.Encoding.DER,
                                            serialization.PublicFormat.SubjectPublicKeyInfo))

class ControllerHttpTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.work=tempfile.TemporaryDirectory()
        with socket.socket() as s:
            s.bind(("127.0.0.1",0));cls.port=s.getsockname()[1]
        cls.url=f"http://127.0.0.1:{cls.port}"
        env=os.environ.copy()
        env.update(SIMHUB_ADMIN_TOKEN=ADMIN_TOKEN,SIMHUB_BIND="127.0.0.1",
                   SIMHUB_PORT=str(cls.port),SIMHUB_DB=str(Path(cls.work.name)/"relay.db"),
                   SIMHUB_REQUIRE_TOTP="false")
        cls.proc=subprocess.Popen([sys.executable,str(ROOT/"server"/"simhub_server.py")],
            env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        for _ in range(100):
            try:
                urllib.request.urlopen(cls.url+"/readyz",timeout=.2).read()
                return
            except Exception: time.sleep(.05)
        raise RuntimeError("Controller HTTP relay did not start")

    @classmethod
    def tearDownClass(cls):
        cls.proc.terminate()
        cls.proc.wait(timeout=5)
        cls.work.cleanup()

    def call(self,method,path,obj=None,cookie=None,admin=False,csrf=None,poll_token=None):
        headers={"Content-Type":"application/json"}
        if admin:headers["Authorization"]="Bearer "+ADMIN_TOKEN
        if cookie:headers["Cookie"]=cookie
        if csrf:headers["X-SimHub-CSRF"]=csrf
        if poll_token:headers["X-SimHub-Pair-Token"]=poll_token
        data=json.dumps(obj).encode() if obj is not None else None
        req=urllib.request.Request(self.url+path,method=method,data=data,headers=headers)
        try:
            with urllib.request.urlopen(req,timeout=4) as response:
                return response.status,json.loads(response.read()),dict(response.headers)
        except urllib.error.HTTPError as exc:
            return exc.code,json.loads(exc.read()),dict(exc.headers)

    def test_native_device_management_post_requires_admin_and_validates_actions(self):
        db=str(Path(self.work.name)/"relay.db")
        with sqlite3.connect(db) as con:
            con.execute("INSERT OR IGNORE INTO devices(id,token_hash,name,created_at) VALUES(?,?,?,?)",
                        ("native-manage-test","native-manage-token","Original label",1760000000))
        path="/api/v1/devices/native-manage-test/manage"
        status,_,_=self.call("POST",path,{"action":"rename","name":"New name"})
        self.assertEqual(status,401)
        status,_,_=self.call("POST",path,{"action":"unsupported"},admin=True)
        self.assertEqual(status,400)
        status,_,_=self.call("POST",path,{"action":"rename","name":""},admin=True)
        self.assertEqual(status,400)
        status,body,_=self.call("POST",path,{"action":"rename","name":"工作设备"},admin=True)
        self.assertEqual((status,body.get("ok")),(200,True))
        with sqlite3.connect(db) as con:
            name=con.execute("SELECT name FROM devices WHERE id=?",
                             ("native-manage-test",)).fetchone()[0]
        self.assertEqual(name,"工作设备")

    def test_two_thousand_event_delta_cursor_and_historical_pages(self):
        # Encryptions are opaque to Relay; paging must preserve *all* sequence
        # IDs across bounded batches before the Android client ACKs progress.
        db=str(Path(self.work.name)/"relay.db")
        with sqlite3.connect(db) as con:
            con.execute("INSERT OR IGNORE INTO devices(id,token_hash,name,created_at) VALUES(?,?,?,?)",
                        ("bulk-test-node","bulk-test-token","Fixture node",1760000000))
            con.executemany(
                """INSERT OR IGNORE INTO events(id,device_id,kind,occurred_at,received_at,ciphertext_json)
                   VALUES(?,?,?,?,?,?)""",
                [(f"bulk-{i}","bulk-test-node","sms.received",1760000000+i,
                  1760000000+i,"{}") for i in range(2050)])
        cursor=0
        observed=[]
        for _ in range(100):
            status,page,_=self.call("GET",f"/api/v1/events?since={cursor}&limit=50",admin=True)
            self.assertEqual(status,200)
            events=page["events"]
            if not events:break
            seqs=[e["seq"] for e in events]
            self.assertTrue(all(a<b for a,b in zip(seqs,seqs[1:])))
            self.assertGreater(seqs[0],cursor)
            observed.extend(e["eventId"] for e in events if e["deviceId"]=="bulk-test-node")
            # This update models an ACK *after* accepting a complete page.
            cursor=seqs[-1]
            if len(events)<50:break
        self.assertEqual(observed,[f"bulk-{i}" for i in range(2050)])
        before_time=before_seq=0
        history=[]
        for _ in range(60):
            suffix=f"&beforeTime={before_time}&beforeSeq={before_seq}" if before_time else ""
            status,page,_=self.call("GET","/api/v1/events?order=occurred&limit=50"+suffix,admin=True)
            self.assertEqual(status,200)
            history.extend(e["eventId"] for e in page["events"] if e["deviceId"]=="bulk-test-node")
            if not page["hasMore"]:break
            before_time=page["nextBeforeTime"]
            before_seq=page["nextBeforeSeq"]
        self.assertEqual(history,[f"bulk-{i}" for i in reversed(range(2050))])

    def test_first_use_approval_session_and_server_revocation(self):
        identity=ec.generate_private_key(ec.SECP256R1())
        exchange=ec.generate_private_key(ec.SECP256R1())
        s,pair,_=self.call("POST","/api/v1/controllers/pair",
            {"label":"Test Pixel","identityPublic":pub(identity),"exchangePublic":pub(exchange),
             "pairingProtocolVersion":2})
        self.assertEqual(s,201)
        pair_id=pair["requestId"]
        s,pending,_=self.call("GET","/api/v1/controllers/pending",admin=True)
        self.assertEqual(s,200)
        self.assertTrue(any(p["requestId"]==pair_id for p in pending["pending"]))
        s,_,_=self.call("POST","/api/v1/controllers/approve",
            {"requestId":pair_id,"pairingCode":"000000"},admin=False)
        self.assertEqual(s,401)
        envelope={"v":1,"epk":pub(ec.generate_private_key(ec.SECP256R1())),
                  "salt":b64(bytes(16)),"iv":b64(bytes(12)),"ct":b64(bytes(48))}
        s,grant,_=self.call("POST","/api/v1/controllers/approve",
            {"requestId":pair_id,"pairingCode":pair["pairingCode"],"vaultEnvelope":envelope},
            admin=True)
        self.assertEqual(s,200)
        self.assertEqual(grant["ok"],True)
        cid=grant["controllerId"]
        s,poll,_=self.call("GET","/api/v1/controllers/pair/"+pair_id,poll_token=pair["pollToken"])
        self.assertEqual(s,200)
        self.assertEqual(poll["vaultEnvelope"],envelope)
        s,response,_=self.call("POST","/api/v1/controllers/login/challenge",{"controllerId":cid})
        self.assertEqual(s,200)
        nonce=response["challenge"]
        signature=identity.sign(f"simhub-controller-login-v1|{cid}|{nonce}".encode(),
                                ec.ECDSA(hashes.SHA256()))
        body={"controllerId":cid,"challenge":nonce,"signature":b64(signature)}
        s,login,headers=self.call("POST","/api/v1/controllers/login/verify",body)
        self.assertEqual(s,201)
        cookie=headers["Set-Cookie"].split(";",1)[0]
        s,_,_=self.call("GET","/api/v1/devices",cookie=cookie)
        self.assertEqual(s,200)
        s,_,_=self.call("POST","/api/v1/controllers/login/verify",body)
        self.assertEqual(s,401)
        s,_,_=self.call("POST","/api/v1/controllers/revoke",{"controllerId":cid},admin=True)
        self.assertEqual(s,200)
        s,_,_=self.call("GET","/api/v1/devices",cookie=cookie)
        self.assertEqual(s,401)
        s,_,_=self.call("POST","/api/v1/controllers/login/challenge",{"controllerId":cid})
        self.assertEqual(s,401)

if __name__=="__main__":
    unittest.main()
