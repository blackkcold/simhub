"""Exercise the controller pairing protocol through HTTP and the existing auth gate."""
import base64
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
