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
    def request(self,path,body,headers=None):
        req=urllib.request.Request(f"http://127.0.0.1:{self.port}{path}",
            data=json.dumps(body).encode(),headers={"Content-Type":"application/json",**(headers or {})},method="POST")
        try:
            with urllib.request.urlopen(req,timeout=5) as response:
                self.last_headers=dict(response.headers)
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

    def test_valid_session_does_not_replenish_stepup_budget(self):
        status,login=self.request("/api/v1/auth/session",{
            "username":"admin","adminToken":TOKEN,"totp":otp()})
        self.assertEqual(status,201)
        headers={"Cookie":self.last_headers["Set-Cookie"].split(";",1)[0],
                 "X-SimHub-CSRF":login["csrfToken"]}
        for _ in range(5):
            status,_=self.request("/api/v1/auth/elevate",{"totp":"invalid"},headers)
            self.assertEqual(status,401)
            # An ordinary successful session request used to reset this budget.
            req=urllib.request.Request(f"http://127.0.0.1:{self.port}/api/v1/devices",headers=headers)
            with urllib.request.urlopen(req,timeout=5) as response:
                self.assertEqual(response.status,200)
        status,_=self.request("/api/v1/auth/elevate",{"totp":otp()},headers)
        self.assertEqual(status,429)
        # A fresh login and a different session cannot replenish the IP budget.
        status,second=self.request("/api/v1/auth/session",{
            "username":"admin","adminToken":TOKEN,"totp":otp()})
        self.assertEqual(status,201)
        newer={"Cookie":self.last_headers["Set-Cookie"].split(";",1)[0],
               "X-SimHub-CSRF":second["csrfToken"]}
        self.assertEqual(self.request("/api/v1/auth/elevate",{"totp":otp()},newer)[0],429)
if __name__=="__main__":unittest.main()
