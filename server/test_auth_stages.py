#!/usr/bin/env python3
"""Two-stage authentication must not mint a session before OTP verification."""
import base64
import hashlib
import hmac
import json
import os
import socket
import struct
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path

from argon2 import PasswordHasher

ROOT=Path(__file__).resolve().parents[1]
SECRET=base64.b32encode(b"simhub-test-second-factor").decode().rstrip("=")
TOKEN="A"*48
PASSWORD="a-fairly-strong-test-password"

def otp():
    padded=SECRET+"="*((8-len(SECRET)%8)%8)
    key=base64.b32decode(padded)
    counter=int(time.time()//30)
    digest=hmac.new(key,struct.pack(">Q",counter),hashlib.sha1).digest()
    offset=digest[-1]&15
    return f"{(struct.unpack('>I',digest[offset:offset+4])[0]&0x7fffffff)%1000000:06d}"

class StagedLoginTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        with socket.socket() as s:
            s.bind(("127.0.0.1",0))
            self.port=s.getsockname()[1]
        env={**os.environ,"SIMHUB_ADMIN_TOKEN":TOKEN,
            "SIMHUB_ADMIN_PASSWORD_HASH":PasswordHasher().hash(PASSWORD),
            "SIMHUB_ADMIN_USERNAME":"admin",
            "SIMHUB_REQUIRE_TOTP":"true","SIMHUB_TOTP_SECRET":SECRET,
            "SIMHUB_DB":str(Path(self.temp.name)/"test.db"),
            "SIMHUB_BIND":"127.0.0.1","SIMHUB_PORT":str(self.port),
            "SIMHUB_MANAGEMENT_ORIGIN":f"http://127.0.0.1:{self.port}",
            "SIMHUB_PUBLIC_BASE_URL":f"http://127.0.0.1:{self.port}"}
        self.proc=subprocess.Popen([sys.executable,str(ROOT/"server/simhub_server.py")],
            cwd=ROOT,env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        for _ in range(120):
            try:
                urllib.request.urlopen(f"http://127.0.0.1:{self.port}/readyz",timeout=.5).close()
                break
            except Exception:time.sleep(.08)
        else:self.fail("Server not ready")
    def tearDown(self):
        self.proc.terminate()
        try:self.proc.wait(timeout=5)
        except subprocess.TimeoutExpired:self.proc.kill()
        self.temp.cleanup()
    def request(self,path,body):
        req=urllib.request.Request(f"http://127.0.0.1:{self.port}{path}",
            data=json.dumps(body).encode(),headers={"Content-Type":"application/json"},method="POST")
        try:
            with urllib.request.urlopen(req,timeout=5) as response:
                return response.status,json.load(response)
        except urllib.error.HTTPError as error:
            return error.code,json.loads(error.read())
    def test_password_then_otp_single_use(self):
        status,_=self.request("/api/v1/auth/start",{"username":"admin","password":"wrong"})
        self.assertEqual(status,401)
        status,data=self.request("/api/v1/auth/start",{"username":"admin","password":PASSWORD})
        self.assertEqual(status,202)
        self.assertTrue(data["requiresTotp"])
        code=data["challengeId"]
        status,_=self.request("/api/v1/auth/session",{"challengeId":code,"totp":"000000"})
        self.assertEqual(status,401)
        status,_=self.request("/api/v1/auth/session",{"challengeId":code,"totp":otp()})
        self.assertEqual(status,201)
        status,_=self.request("/api/v1/auth/session",{"challengeId":code,"totp":otp()})
        self.assertEqual(status,401)
    def test_emergency_token_legacy_session(self):
        status,data=self.request("/api/v1/auth/session",{
            "username":"admin","adminToken":TOKEN,"totp":otp()})
        self.assertEqual(status,201)
        self.assertTrue(data["ok"])
if __name__=="__main__":unittest.main()
