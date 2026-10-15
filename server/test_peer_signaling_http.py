#!/usr/bin/env python3
"""HTTP surface regression tests for the center-auxiliary peer signaling routes.

Runs an isolated relay process. No real domains, tokens or TURN secrets are used.
"""
import hashlib
import json
import os
import secrets
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

from cryptography.hazmat.primitives.asymmetric import ec

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_peer_signaling import VERSION, identity, pub, sign, binding_transcript, session_transcript  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
DOMAIN = "simhub-peer-signal-v1"
ADMIN_TOKEN = "A" * 48


def b64(value):
    import base64
    return base64.urlsafe_b64encode(value).decode().rstrip("=")


def wire(now, session_id, direction, seq, *, revision=1, expires=None, body=b"ciphertext-bytes-000",
         message_id=None, **overrides):
    value = {
        "v": 1, "domain": DOMAIN, "session": session_id,
        "id": message_id or b64(os.urandom(16)), "dir": direction, "seq": seq,
        "expires": now + 60 if expires is None else expires, "revision": revision,
        "nonce": b64(bytes(12)), "ct": b64(body),
    }
    value.update(overrides)
    return value


class SignalingHttpTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            cls.port = sock.getsockname()[1]
        cls.db = Path(cls.tmp.name) / "relay.db"
        cls.base = f"http://127.0.0.1:{cls.port}"
        env = os.environ.copy()
        env.update({"SIMHUB_ADMIN_TOKEN": ADMIN_TOKEN, "SIMHUB_BIND": "127.0.0.1",
                    "SIMHUB_PORT": str(cls.port), "SIMHUB_DB": str(cls.db),
                    "SIMHUB_REQUIRE_TOTP": "false"})
        cls.proc = subprocess.Popen([sys.executable, str(ROOT / "server" / "simhub_server.py")],
                                    env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(100):
            try:
                urllib.request.urlopen(cls.base + "/readyz", timeout=.2).read()
                return
            except Exception:
                time.sleep(.05)
        raise RuntimeError("signaling HTTP relay did not start")

    @classmethod
    def tearDownClass(cls):
        cls.proc.terminate()
        cls.proc.wait(timeout=5)
        cls.tmp.cleanup()

    def req(self, method, path, body=None, *, admin=True, device_token=None, cookie=None,
            csrf=None, signal_token=None, headers=None):
        sent = {"Content-Type": "application/json"}
        if headers:
            sent.update(headers)
        if admin:
            sent["Authorization"] = "Bearer " + ADMIN_TOKEN
        if device_token:
            sent["Authorization"] = "Device " + device_token
        if cookie:
            sent["Cookie"] = cookie
        if csrf:
            sent["X-SimHub-CSRF"] = csrf
        if signal_token:
            sent["X-SimHub-Signal-Token"] = signal_token
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.base + path, method=method, data=data, headers=sent)
        try:
            with urllib.request.urlopen(request, timeout=4) as response:
                raw = response.read()
                return response.status, (json.loads(raw) if raw else {}), dict(response.headers)
        except urllib.error.HTTPError as exc:
            raw = exc.read()
            return exc.code, (json.loads(raw) if raw else {}), dict(exc.headers)

    def enroll(self, name="Signal node"):
        status, body, _ = self.req("POST", "/api/v1/enrollments", {})
        self.assertEqual(status, 201)
        status, device, _ = self.req("POST", "/api/v1/enroll",
                                     {"token": body["token"], "name": name}, admin=False)
        self.assertEqual(status, 201)
        return device

    def pair_fixture(self, name="Signal node"):
        """Device + P2P keys + registered binding. Returns a namespace-ish dict."""
        device = self.enroll(name)
        node, ctrl = ec.generate_private_key(ec.SECP256R1()), ec.generate_private_key(ec.SECP256R1())
        node_id, ctrl_id = identity(node), identity(ctrl)
        device_id, device_token = device["deviceId"], device["deviceToken"]
        base = f"/api/v1/devices/{device_id}/peer-signaling"
        status, challenge, _ = self.req("POST", base + "/challenge", {}, admin=False,
                                        device_token=device_token)
        self.assertEqual(status, 200)
        expires = int(time.time()) + 86400
        transcript = dict(protocolVersion=VERSION, bindingId="", nodeId=node_id, nodePublicKey=pub(node),
                          controllerPeerId=ctrl_id, controllerPublicKey=pub(ctrl), revision=1,
                          allowed=True, challengeId=challenge["challengeId"], expires=expires)
        body = {"protocolVersion": VERSION, "bindingId": "", "nodeId": node_id,
                "nodePublicKey": pub(node), "allowedControllerPeerId": ctrl_id,
                "allowedControllerPublicKey": pub(ctrl), "consentRevision": 1, "allowed": True,
                "challengeId": challenge["challengeId"], "expires": expires,
                "signature": sign(node, binding_transcript(**transcript))}
        status, binding, _ = self.req("POST", base + "/binding", body, admin=False,
                                      device_token=device_token)
        self.assertEqual(status, 201)
        self.assertTrue(binding["created"])
        return {"device_id": device_id, "device_token": device_token, "node": node, "ctrl": ctrl,
                "node_id": node_id, "ctrl_id": ctrl_id, "binding_id": binding["bindingId"], "base": base}

    def open_session(self, fixture, *, center_id="center.test"):
        """Controller leg + node join; returns {sessionId, sessionToken}."""
        now = int(time.time())
        status, snapshot, _ = self.req("POST", "/api/v1/controllers/peer-signaling/challenge",
                                       {"bindingId": fixture["binding_id"]})
        self.assertEqual(status, 200)
        self.assertEqual(snapshot["revision"], 1)
        self.assertEqual(snapshot["nodeId"], fixture["node_id"])
        session_id = "session-" + secrets.token_hex(12)
        expires = now + 300
        transcript = dict(protocolVersion=VERSION, sessionId=session_id,
                          bindingId=fixture["binding_id"], nodeId=fixture["node_id"],
                          nodePublicKey=pub(fixture["node"]), controllerPeerId=fixture["ctrl_id"],
                          controllerPublicKey=pub(fixture["ctrl"]), centerId=center_id, revision=1,
                          challengeId=snapshot["challengeId"], expires=expires)
        status, created, _ = self.req("POST", "/api/v1/controllers/peer-signaling/sessions",
                                      {**transcript, "signature": sign(fixture["ctrl"],
                                                                       session_transcript(role="controller",
                                                                                          **transcript))})
        self.assertEqual(status, 201)
        status, challenge, _ = self.req("POST", fixture["base"] + "/challenge", {}, admin=False,
                                        device_token=fixture["device_token"])
        self.assertEqual(status, 200)
        join = dict(protocolVersion=VERSION, sessionId=session_id, bindingId=fixture["binding_id"],
                    centerId=center_id, revision=1, challengeId=challenge["challengeId"], expires=expires)
        status, joined, _ = self.req("POST", fixture["base"] + f"/sessions/{session_id}/join",
                                     {**join, "signature": sign(fixture["node"],
                                                                session_transcript(role="node",
                                                                                    nodeId=fixture["node_id"],
                                                                                    nodePublicKey=pub(fixture["node"]),
                                                                                    controllerPeerId=fixture["ctrl_id"],
                                                                                    controllerPublicKey=pub(fixture["ctrl"]),
                                                                                    **join))},
                                     admin=False, device_token=fixture["device_token"])
        self.assertEqual(status, 200)
        self.assertEqual(joined["state"], "active")
        return {"session_id": session_id, "session_token": created["sessionToken"]}

    def test_end_to_end_binding_session_and_frame_relay(self):
        fixture = self.pair_fixture()
        session = self.open_session(fixture)
        now = int(time.time())
        node_wire = wire(now, session["session_id"], "node", 1)
        status, sent, _ = self.req("POST", fixture["base"] + f"/sessions/{session['session_id']}/signal",
                                   {"frame": node_wire}, admin=False, device_token=fixture["device_token"])
        self.assertEqual(status, 201)
        self.assertGreaterEqual(sent["seq"], 1)
        status, polled, _ = self.req("GET", "/api/v1/controllers/peer-signaling/sessions/"
                                           f"{session['session_id']}/poll?after=0",
                                     signal_token=session["session_token"])
        self.assertEqual(status, 200)
        self.assertEqual([item["frame"] for item in polled["messages"]], [node_wire])
        controller_wire = wire(now, session["session_id"], "controller", 1)
        status, ack, _ = self.req("POST", "/api/v1/controllers/peer-signaling/sessions/"
                                        f"{session['session_id']}/ack", {"seq": sent["seq"]},
                                  signal_token=session["session_token"])
        self.assertEqual((status, ack["acknowledged"]), (200, True))
        status, sent2, _ = self.req("POST", "/api/v1/controllers/peer-signaling/sessions/"
                                          f"{session['session_id']}/signal", {"frame": controller_wire},
                                    signal_token=session["session_token"])
        self.assertEqual(status, 201)
        self.assertGreater(sent2["seq"], sent["seq"])
        status, polled2, _ = self.req("GET", fixture["base"] + f"/sessions/{session['session_id']}/poll?after=0",
                                      admin=False, device_token=fixture["device_token"])
        self.assertEqual(status, 200)
        self.assertEqual([item["frame"] for item in polled2["messages"]], [controller_wire])
        status, closed, _ = self.req("POST", fixture["base"] + f"/sessions/{session['session_id']}/close", {},
                                     admin=False, device_token=fixture["device_token"])
        self.assertEqual((status, closed["closed"]), (200, True))
        status, page, _ = self.req("GET", "/api/v1/commands/recent?limit=10")
        self.assertEqual(status, 200)
        self.assertNotIn(node_wire["ct"], json.dumps(page))

    def test_route_auth_separation_and_csrf(self):
        fixture = self.pair_fixture()
        status, _, _ = self.req("POST", fixture["base"] + "/challenge", {}, admin=False)
        self.assertEqual(status, 401)
        status, _, _ = self.req("POST", fixture["base"] + "/challenge", {})
        self.assertEqual(status, 401)
        status, _, _ = self.req("GET", fixture["base"] + "/bindings", admin=False)
        self.assertEqual(status, 401)
        status, _, _ = self.req("POST", "/api/v1/controllers/peer-signaling/challenge",
                                {"bindingId": fixture["binding_id"]}, admin=False)
        self.assertEqual(status, 401)
        status, _, _ = self.req("POST", "/api/v1/controllers/peer-signaling/challenge",
                                {"bindingId": fixture["binding_id"]}, admin=True,
                                device_token=fixture["device_token"])
        self.assertEqual(status, 401)
        # A browser session without CSRF must not drive controller signaling.
        status, login, headers = self.req("POST", "/api/v1/auth/session", {"adminToken": ADMIN_TOKEN},
                                          admin=False)
        self.assertEqual(status, 201)
        cookie = headers["Set-Cookie"].split(";", 1)[0]
        status, _, _ = self.req("POST", "/api/v1/controllers/peer-signaling/challenge",
                                {"bindingId": fixture["binding_id"]}, admin=False, cookie=cookie)
        self.assertEqual(status, 403)
        status, snapshot, _ = self.req("POST", "/api/v1/controllers/peer-signaling/challenge",
                                       {"bindingId": fixture["binding_id"]}, admin=False, cookie=cookie,
                                       csrf=login["csrfToken"])
        self.assertEqual(status, 200)
        # A device token never opens the management surface.
        status, _, _ = self.req("GET", "/api/v1/devices", admin=False, device_token=fixture["device_token"])
        self.assertEqual(status, 401)

    def test_binding_mismatch_cross_device_and_shape_rejections(self):
        fixture = self.pair_fixture("Signal node A")
        other = self.pair_fixture("Signal node B")
        status, _, _ = self.req("POST", "/api/v1/controllers/peer-signaling/challenge",
                                {"bindingId": "f" * 36})
        self.assertEqual(status, 404)
        # Node B cannot register a binding under node A's device identity.
        now = int(time.time())
        status, challenge, _ = self.req("POST", other["base"] + "/challenge", {}, admin=False,
                                        device_token=other["device_token"])
        self.assertEqual(status, 200)
        transcript = dict(protocolVersion=VERSION, bindingId="", nodeId=other["node_id"],
                          nodePublicKey=pub(other["node"]), controllerPeerId=other["ctrl_id"],
                          controllerPublicKey=pub(other["ctrl"]), revision=1, allowed=True,
                          challengeId=challenge["challengeId"], expires=now + 86400)
        body = {"protocolVersion": VERSION, "bindingId": "", "nodeId": fixture["node_id"],
                "nodePublicKey": pub(fixture["node"]), "allowedControllerPeerId": other["ctrl_id"],
                "allowedControllerPublicKey": pub(other["ctrl"]), "consentRevision": 1, "allowed": True,
                "challengeId": challenge["challengeId"], "expires": now + 86400,
                "signature": sign(other["node"], binding_transcript(**transcript))}
        status, error, _ = self.req("POST", other["base"] + "/binding", body, admin=False,
                                    device_token=other["device_token"])
        self.assertEqual((status, error["error"]), (403, "binding_mismatch"))
        session = self.open_session(fixture)
        # Node B probing node A's session sees nothing.
        status, error, _ = self.req("GET", other["base"] + f"/sessions/{session['session_id']}/poll?after=0",
                                    admin=False, device_token=other["device_token"])
        self.assertEqual((status, error["error"]), (410, "session_unavailable"))
        # A business PeerProtocol frame is not valid signaling.
        business = {"v": 1, "domain": "simhub-peer-v1", "from": fixture["node_id"],
                    "to": fixture["ctrl_id"], "id": "x" * 24, "expires": now + 60,
                    "iv": b64(bytes(12)), "ct": b64(b"y" * 32)}
        status, error, _ = self.req("POST", fixture["base"] + f"/sessions/{session['session_id']}/signal",
                                    {"frame": business}, admin=False, device_token=fixture["device_token"])
        self.assertEqual((status, error["error"]), (400, "frame_rejected"))
        status, challenge, _ = self.req("POST", fixture["base"] + "/challenge", {}, admin=False,
                                        device_token=fixture["device_token"])
        join = dict(protocolVersion=VERSION, sessionId=session["session_id"], bindingId=fixture["binding_id"],
                    centerId="center.test", revision=1, challengeId=challenge["challengeId"],
                    expires=self.session_expiry(session["session_id"]))
        status, error, _ = self.req("POST", fixture["base"] + f"/sessions/{session['session_id']}/join",
                                    {**join, "signature": sign(fixture["node"], "x")},
                                    admin=False, device_token=fixture["device_token"])
        self.assertEqual((status, error["error"]), (403, "signature_rejected"))
        status, error, _ = self.req("POST", fixture["base"] + "/binding",
                                    {**body, "bindingId": fixture["binding_id"]}, admin=False,
                                    device_token=other["device_token"])
        self.assertEqual(status, 401)  # device token does not match the path device

    def session_expiry(self, session_id):
        with sqlite3.connect(self.db) as con:
            return con.execute("SELECT expires_at FROM peer_signal_sessions WHERE session_id=?",
                               (session_id,)).fetchone()[0]

    def test_device_revocation_purges_signaling_state(self):
        fixture = self.pair_fixture()
        session = self.open_session(fixture)
        now = int(time.time())
        self.req("POST", fixture["base"] + f"/sessions/{session['session_id']}/signal",
                 {"frame": wire(now, session["session_id"], "node", 1)}, admin=False,
                 device_token=fixture["device_token"])
        status, _, _ = self.req("PATCH", f"/api/v1/devices/{fixture['device_id']}", {"revoke": True})
        self.assertEqual(status, 200)
        with sqlite3.connect(self.db) as con:
            for table in ("peer_signal_bindings", "peer_signal_sessions", "peer_signal_frames"):
                rows = con.execute(f"SELECT COUNT(*) FROM {table} WHERE "
                                   + ("device_id=?" if table != "peer_signal_frames" else
                                      "session_id=?") , (fixture["device_id"] if table != "peer_signal_frames"
                                                         else session["session_id"],)).fetchone()[0]
                self.assertEqual(rows, 0, table)
        status, _, _ = self.req("GET", fixture["base"] + f"/sessions/{session['session_id']}/poll?after=0",
                                admin=False, device_token=fixture["device_token"])
        self.assertEqual(status, 401)

    def test_frame_quota_and_size_bounds_over_http(self):
        fixture = self.pair_fixture()
        session = self.open_session(fixture)
        now = int(time.time())
        for seq in range(1, 33):
            status, _, _ = self.req("POST", fixture["base"] + f"/sessions/{session['session_id']}/signal",
                                    {"frame": wire(now, session["session_id"], "node", seq)},
                                    admin=False, device_token=fixture["device_token"])
            self.assertEqual(status, 201)
        status, error, _ = self.req("POST", fixture["base"] + f"/sessions/{session['session_id']}/signal",
                                    {"frame": wire(now, session["session_id"], "node", 33)},
                                    admin=False, device_token=fixture["device_token"])
        self.assertEqual((status, error["error"]), (429, "frame_quota"))
        status, error, _ = self.req("POST", fixture["base"] + f"/sessions/{session['session_id']}/signal",
                                    {"frame": wire(now, session["session_id"], "node", 34,
                                                   body=b"x" * 70000)},
                                    admin=False, device_token=fixture["device_token"])
        self.assertEqual((status, error["error"]), (413, "frame_too_large"))


class SignalingSurfaceTest(unittest.TestCase):
    """The same store keeps its two authentication surfaces separated."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            cls.port = sock.getsockname()[1]
        cls.db = Path(cls.tmp.name) / "relay.db"
        cls.base = f"http://127.0.0.1:{cls.port}"
        cls.admin_host = "admin.simhub.example.com"
        cls.node_host = "node.simhub.example.com"
        env = os.environ.copy()
        env.update({"SIMHUB_ADMIN_TOKEN": ADMIN_TOKEN, "SIMHUB_BIND": "127.0.0.1",
                    "SIMHUB_PORT": str(cls.port), "SIMHUB_DB": str(cls.db),
                    "SIMHUB_REQUIRE_TOTP": "false",
                    "SIMHUB_MANAGEMENT_ORIGIN": "https://" + cls.admin_host,
                    "SIMHUB_PUBLIC_BASE_URL": "https://" + cls.node_host,
                    "SIMHUB_SEPARATE_SURFACES": "true"})
        cls.proc = subprocess.Popen([sys.executable, str(ROOT / "server" / "simhub_server.py")],
                                    env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        for _ in range(100):
            try:
                urllib.request.urlopen(cls.base + "/readyz", timeout=.2).read()
                return
            except Exception:
                time.sleep(.05)
        raise RuntimeError("signaling surface relay did not start")

    @classmethod
    def tearDownClass(cls):
        cls.proc.terminate()
        cls.proc.wait(timeout=5)
        cls.tmp.cleanup()

    def call(self, method, path, host, body=None, device_token=None):
        headers = {"Host": host, "Content-Type": "application/json"}
        if device_token:
            headers["Authorization"] = "Device " + device_token
        else:
            headers["Authorization"] = "Bearer " + ADMIN_TOKEN
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.base + path, method=method, data=data, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=4) as response:
                return response.status, json.loads(response.read())
        except urllib.error.HTTPError as exc:
            return exc.code, json.loads(exc.read())

    def test_signaling_routes_follow_host_separation(self):
        device = "surface-node"
        surface_token = "S" * 48
        with sqlite3.connect(self.db) as con:
            con.execute("INSERT OR IGNORE INTO devices(id,token_hash,name,created_at) VALUES(?,?,?,?)",
                        (device, hashlib.sha256(surface_token.encode()).hexdigest(), "Surface", int(time.time())))
        node_path = f"/api/v1/devices/{device}/peer-signaling/challenge"
        controller_path = "/api/v1/controllers/peer-signaling/challenge"
        self.assertEqual(self.call("POST", node_path, self.admin_host, {},
                                   device_token=surface_token)[0], 404)
        self.assertEqual(self.call("POST", controller_path, self.node_host, {"bindingId": "x" * 36})[0], 404)
        self.assertEqual(self.call("POST", node_path, self.node_host, {},
                                   device_token=surface_token)[0], 200)
        self.assertEqual(self.call("POST", controller_path, self.admin_host,
                                   {"bindingId": "x" * 36})[0], 404)
        self.assertEqual(self.call("GET", node_path, self.node_host)[0], 404)


if __name__ == "__main__":
    unittest.main(verbosity=2)
