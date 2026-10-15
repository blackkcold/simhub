#!/usr/bin/env python3
"""Contract tests for the center-auxiliary signaling store (unit level, no HTTP).

The transcripts below are implemented independently from server/peer_signaling.py
on purpose: the module must agree with the documented length-prefixed spec, so a
drift in either side fails these tests. Nothing here is a production endpoint.
"""
import base64
import hashlib
import hmac
import json
import os
import secrets
import sqlite3
import tempfile
import unittest
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

import peer_signaling as sig

VERSION = "center-signaling-v1"
DOMAIN = "simhub-peer-signal-v1"
DEVICES_SQL = """CREATE TABLE devices(
  id TEXT PRIMARY KEY, token_hash TEXT NOT NULL DEFAULT '', name TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL DEFAULT 0, revoked_at INTEGER, reset_requested_at INTEGER NOT NULL DEFAULT 0);"""


def b64(value):
    return base64.urlsafe_b64encode(value).decode().rstrip("=")


def lp(value):
    value = str(value)
    return f"{len(value.encode('utf-8'))}:{value}"


def der(pair):
    return pair.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)


def pub(pair):
    return b64(der(pair))


def identity(pair):
    return b64(hashlib.sha256(der(pair)).digest())


def sign(pair, transcript):
    return b64(pair.sign(transcript.encode("utf-8"), ec.ECDSA(hashes.SHA256())))


def binding_transcript(*, protocolVersion, bindingId, nodeId, nodePublicKey, controllerPeerId,
                       controllerPublicKey, revision, allowed, challengeId, expires):
    allowed = "1" if allowed else "0"
    return DOMAIN + "|binding|" + "".join(lp(v) for v in (
        protocolVersion, bindingId, nodeId, nodePublicKey, controllerPeerId,
        controllerPublicKey, revision, allowed, challengeId, expires))


def session_transcript(*, protocolVersion, role, centerId, sessionId, bindingId, nodeId,
                       nodePublicKey, controllerPeerId, controllerPublicKey, revision,
                       challengeId, expires):
    return DOMAIN + "|session|" + "".join(lp(v) for v in (
        protocolVersion, role, centerId, sessionId, bindingId, nodeId, nodePublicKey,
        controllerPeerId, controllerPublicKey, revision, challengeId, expires))


def frame(store, session_id, direction, seq, *, revision=1, expires=None, body=b"ciphertext-bytes-000",
          message_id=None, **overrides):
    value = {
        "v": 1, "domain": DOMAIN, "session": session_id,
        "id": message_id or b64(os.urandom(16)), "dir": direction, "seq": seq,
        "expires": store.now + 60 if expires is None else expires, "revision": revision,
        "nonce": b64(bytes(12)), "ct": b64(body),
    }
    value.update(overrides)
    return value


class Store:
    """Temporary database, clock and fixtures shared by one test."""

    def __init__(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / "signal.db"
        self.now = 1_760_000_000
        with self.con() as con:
            con.executescript(DEVICES_SQL)
            sig.init(con)

    def con(self):
        con = sqlite3.connect(self.path, isolation_level=None)
        con.row_factory = sqlite3.Row
        return con

    def device(self, name=None, *, revoked=None, reset=0):
        name = name or "node-" + secrets.token_hex(8)
        with self.con() as con:
            con.execute("INSERT INTO devices(id,token_hash,name,created_at,revoked_at,reset_requested_at) "
                        "VALUES(?,?,?,?,?,?)", (name, "h", name, self.now, revoked, reset))
        return name

    def tick(self, seconds=1):
        self.now += seconds
        return self.now


class Pair:
    """A node/controller P2P pair with independent transcript builders."""

    def __init__(self, store, device, node=None):
        self.store, self.device = store, device
        self.node = node or ec.generate_private_key(ec.SECP256R1())
        self.ctrl = ec.generate_private_key(ec.SECP256R1())
        self.node_id, self.ctrl_id = identity(self.node), identity(self.ctrl)

    def challenge(self, role="node", binding_id="", device=None):
        result = sig.challenge(self.store.con(), role, device or self.device, self.store.now,
                               binding_id=binding_id)
        return result["challengeId"]

    def binding_body(self, *, challenge, binding_id="", revision=1, allowed=True, expires=None,
                     node_key=None, ctrl_key=None, node_id=None, version=VERSION, signature=None):
        expires = self.store.now + 86400 if expires is None else expires
        transcript = dict(
            protocolVersion=version, bindingId=binding_id, nodeId=node_id or self.node_id,
            nodePublicKey=node_key if node_key is not None else pub(self.node),
            controllerPeerId=self.ctrl_id, controllerPublicKey=ctrl_key if ctrl_key is not None else pub(self.ctrl),
            revision=revision, allowed=allowed, challengeId=challenge, expires=expires)
        return {
            "protocolVersion": version, "bindingId": binding_id, "nodeId": transcript["nodeId"],
            "nodePublicKey": transcript["nodePublicKey"],
            "allowedControllerPeerId": transcript["controllerPeerId"],
            "allowedControllerPublicKey": transcript["controllerPublicKey"],
            "consentRevision": revision, "allowed": allowed, "challengeId": challenge,
            "expires": expires,
            "signature": signature or sign(self.node, binding_transcript(**transcript)),
        }

    def register(self, **kwargs):
        return sig.bind(self.store.con(), self.device, self.binding_body(**kwargs), self.store.now)

    def create_body(self, *, session_id, binding_id, challenge, center_id="center.test", revision=1,
                    expires=None, version=VERSION, node_key=None, signature=None):
        expires = self.store.now + 300 if expires is None else expires
        fields = dict(
            protocolVersion=version, sessionId=session_id, bindingId=binding_id, nodeId=self.node_id,
            nodePublicKey=node_key if node_key is not None else pub(self.node),
            controllerPeerId=self.ctrl_id, controllerPublicKey=pub(self.ctrl),
            centerId=center_id, revision=revision, challengeId=challenge, expires=expires)
        fields["signature"] = signature or sign(self.ctrl, session_transcript(role="controller", **fields))
        return fields

    def new_challenge(self, binding_id):
        return sig.controller_challenge(self.store.con(), binding_id, self.store.now)["challengeId"]

    def create_session(self, session_id=None, binding_id=None, **kwargs):
        session_id = session_id or "session-" + secrets.token_hex(12)
        binding_id = binding_id or kwargs.pop("binding", None) or self.binding["bindingId"]
        challenge = kwargs.pop("challenge", None) or self.new_challenge(binding_id)
        return sig.create_session(self.store.con(), self.create_body(
            session_id=session_id, binding_id=binding_id, challenge=challenge, **kwargs), self.store.now)

    def join_body(self, *, session_row, challenge=None, center_id=None, revision=None, expires=None,
                  version=VERSION, signature=None):
        center_id = session_row["center_id"] if center_id is None else center_id
        revision = session_row["revision"] if revision is None else revision
        expires = session_row["expires_at"] if expires is None else expires
        fields = dict(
            protocolVersion=version, sessionId=session_row["session_id"], bindingId=session_row["binding_id"],
            centerId=center_id, revision=revision, challengeId=challenge or self.challenge(), expires=expires)
        fields["signature"] = signature or sign(self.node, session_transcript(
            role="node", nodeId=self.node_id, nodePublicKey=pub(self.node),
            controllerPeerId=self.ctrl_id, controllerPublicKey=pub(self.ctrl), **fields))
        return fields

    def join(self, session_row, **kwargs):
        return sig.join_session(self.store.con(), self.device, session_row["session_id"],
                                self.join_body(session_row=session_row, **kwargs), self.store.now)


def session_row(store, session_id):
    with store.con() as con:
        return con.execute("SELECT * FROM peer_signal_sessions WHERE session_id=?", (session_id,)).fetchone()


class SignalingStoreTest(unittest.TestCase):
    def setUp(self):
        self.store = Store()
        self.device = self.store.device()
        self.pair = Pair(self.store, self.device)
        self.pair.binding = self.pair.register(challenge=self.pair.challenge())

    def assert_rejected(self, code, status, function, *args, **kwargs):
        with self.assertRaises(sig.SignalingRejected) as caught:
            function(*args, **kwargs)
        self.assertEqual((caught.exception.code, caught.exception.status), (code, status))
        return caught.exception

    def active_session(self, **kwargs):
        session = self.pair.create_session(**kwargs)
        self.pair.join(session_row(self.store, session["sessionId"]))
        return session

    # -- transcripts -------------------------------------------------------

    def test_cross_language_signaling_vectors(self):
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        vector = json.loads((Path(__file__).resolve().parents[1] / "test_vectors" / "signaling-v1.json")
                            .read_text(encoding="utf-8"))
        root = base64.urlsafe_b64decode(vector["rootKeyBase64Url"] + "=" * (-len(vector["rootKeyBase64Url"]) % 4))
        prk = hmac.new(b"simhub-peer-signal-v1", root, hashlib.sha256).digest()
        derived = hmac.new(prk, b"simhub-peer-signal-v1|aead" + b"\x01", hashlib.sha256).digest()
        self.assertEqual(b64(derived), vector["derivedKeyBase64Url"])
        aad = "simhub-peer-signal-v1|" + "".join(lp(value) for value in (
            vector["version"], vector["domain"], vector["session"], vector["id"], vector["direction"],
            vector["seq"], vector["expires"], vector["revision"], vector["nonceBase64Url"]))
        self.assertEqual(aad, vector["aad"])
        nonce = base64.urlsafe_b64decode(vector["nonceBase64Url"] + "=" * (-len(vector["nonceBase64Url"]) % 4))
        plaintext = json.dumps(vector["payload"], separators=(",", ":")).encode("utf-8")
        sealed = AESGCM(derived).encrypt(nonce, plaintext, aad.encode("utf-8"))
        self.assertEqual(b64(sealed), vector["ciphertextBase64Url"])
        self.assertEqual(sig.binding_transcript(
            protocolVersion="center-signaling-v1", bindingId="binding-vector-0000000000000",
            nodeId="node-vector-0000000000000000000000000000000", nodePublicKey="node-key-vector",
            controllerPeerId="ctrl-vector-0000000000000000000000000000000",
            controllerPublicKey="ctrl-key-vector", revision=3, allowed=True,
            challengeId="challenge-vector-00000000000000", expires=1791264000),
            vector["bindingTranscriptExample"])
        self.assertEqual(sig.session_transcript(
            protocolVersion="center-signaling-v1", role="node", centerId="center.example.invalid",
            sessionId="session-vector-000000000000", bindingId="binding-vector-0000000000000",
            nodeId="node-vector-0000000000000000000000000000000", nodePublicKey="node-key-vector",
            controllerPeerId="ctrl-vector-0000000000000000000000000000000",
            controllerPublicKey="ctrl-key-vector", revision=3,
            challengeId="challenge-vector-00000000000000", expires=1791264060),
            vector["sessionTranscriptExample"])

    def test_transcript_helpers_match_length_prefixed_spec(self):
        fields = dict(protocolVersion=VERSION, bindingId="b" * 36, nodeId=self.pair.node_id,
                      nodePublicKey=pub(self.pair.node), controllerPeerId=self.pair.ctrl_id,
                      controllerPublicKey=pub(self.pair.ctrl), revision=7, allowed=True,
                      challengeId="c" * 32, expires=1_760_000_123)
        self.assertEqual(sig.binding_transcript(**fields), binding_transcript(**fields))
        session = dict(protocolVersion=VERSION, role="node", centerId="center.test",
                       sessionId="s" * 40, bindingId="b" * 36, nodeId=self.pair.node_id,
                       nodePublicKey=pub(self.pair.node), controllerPeerId=self.pair.ctrl_id,
                       controllerPublicKey=pub(self.pair.ctrl), revision=7, challengeId="c" * 32,
                       expires=1_760_000_123)
        self.assertEqual(sig.session_transcript(**session), session_transcript(**session))
        # Length prefixes make field-boundary ambiguity impossible.
        left = binding_transcript(**{**fields, "nodeId": "a:b", "nodePublicKey": "c"})
        right = binding_transcript(**{**fields, "nodeId": "a", "nodePublicKey": "b:c"})
        self.assertNotEqual(left, right)

    # -- binding registration ---------------------------------------------

    def test_binding_create_records_snapshot_and_returns_binding_id(self):
        result = self.pair.binding
        self.assertEqual(set(result), {"bindingId", "created", "acceptedRevision", "allowed",
                                       "expires", "protocolVersion"})
        self.assertTrue(result["created"])
        self.assertEqual(result["protocolVersion"], VERSION)
        self.assertEqual((result["acceptedRevision"], result["allowed"]), (1, True))
        listed = sig.list_bindings(self.store.con(), self.device, self.store.now)["bindings"]
        self.assertEqual(len(listed), 1)
        self.assertEqual(set(listed[0]), {"bindingId", "controllerPeerId", "allowed", "consentRevision", "expires"})
        self.assertEqual(listed[0], {"bindingId": result["bindingId"], "controllerPeerId": self.pair.ctrl_id,
                                     "allowed": True, "consentRevision": 1,
                                     "expires": self.store.now + 86400})

    def test_binding_requires_valid_single_use_challenge(self):
        used = self.pair.challenge()
        second = Pair(self.store, self.device, node=self.pair.node)
        self.assertTrue(second.register(challenge=used)["created"])
        replay = second.binding_body(challenge=used)
        self.assert_rejected("challenge_expired_or_reused", 409,
                             sig.bind, self.store.con(), self.device, replay, self.store.now)
        stale = self.pair.challenge()
        self.store.tick(61)
        self.assert_rejected("challenge_expired_or_reused", 409, sig.bind, self.store.con(), self.device,
                             self.pair.binding_body(challenge=stale), self.store.now)

    def test_binding_rejects_identity_device_and_signature_mismatch(self):
        stranger = ec.generate_private_key(ec.SECP256R1())
        self.assert_rejected("binding_mismatch", 403, sig.bind, self.store.con(), self.device,
                             self.pair.binding_body(challenge=self.pair.challenge(),
                                                    node_key=pub(stranger)), self.store.now)
        self.assert_rejected("binding_mismatch", 403, sig.bind, self.store.con(), self.device,
                             self.pair.binding_body(challenge=self.pair.challenge(),
                                                    ctrl_key=pub(stranger)), self.store.now)
        self.store.device("other-device")
        self.assert_rejected("binding_mismatch", 403, sig.bind, self.store.con(), "other-device",
                             self.pair.binding_body(challenge=self.pair.challenge()), self.store.now)
        self.assert_rejected("signature_rejected", 403, sig.bind, self.store.con(), self.device,
                             self.pair.binding_body(challenge=self.pair.challenge(),
                                                    binding_id="1" * 36, revision=2, signature="A" * 86),
                             self.store.now)
        self.assert_rejected("binding_mismatch", 403, sig.bind, self.store.con(), self.device,
                             self.pair.binding_body(challenge=self.pair.challenge(),
                                                    node_id=identity(stranger)), self.store.now)

    def test_binding_protocol_expiry_and_shape_bounds(self):
        self.assert_rejected("protocol_unsupported", 400, sig.bind, self.store.con(), self.device,
                             self.pair.binding_body(challenge=self.pair.challenge(), version="peer-v0"),
                             self.store.now)
        self.assert_rejected("invalid_request", 400, sig.bind, self.store.con(), self.device,
                             self.pair.binding_body(challenge=self.pair.challenge(), expires=self.store.now),
                             self.store.now)
        self.assert_rejected("invalid_request", 400, sig.bind, self.store.con(), self.device,
                             self.pair.binding_body(challenge=self.pair.challenge(),
                                                    expires=self.store.now + 31 * 86400), self.store.now)
        self.assert_rejected("invalid_request", 400, sig.bind, self.store.con(), self.device,
                             self.pair.binding_body(challenge=self.pair.challenge(), revision="7"),
                             self.store.now)
        self.assert_rejected("binding_mismatch", 403, sig.bind, self.store.con(), self.device,
                             self.pair.binding_body(challenge=self.pair.challenge(), allowed=False),
                             self.store.now)

    def test_binding_update_requires_same_pair_and_increasing_revision(self):
        binding_id = self.pair.binding["bindingId"]
        self.assert_rejected("revision_stale", 409, sig.bind, self.store.con(), self.device,
                             self.pair.binding_body(challenge=self.pair.challenge(), binding_id=binding_id,
                                                    revision=1), self.store.now)
        updated = sig.bind(self.store.con(), self.device, self.pair.binding_body(
            challenge=self.pair.challenge(), binding_id=binding_id, revision=2), self.store.now)
        self.assertEqual((updated["created"], updated["acceptedRevision"]), (False, 2))
        # A second create for the same pair must carry the existing bindingId.
        self.assert_rejected("binding_mismatch", 403, sig.bind, self.store.con(), self.device,
                             self.pair.binding_body(challenge=self.pair.challenge()), self.store.now)
        # Another pair's bindingId does not accept this pair's identity.
        other = Pair(self.store, self.store.device("node-b"))
        other.binding = other.register(challenge=other.challenge())
        self.assert_rejected("binding_mismatch", 403, sig.bind, self.store.con(), self.device,
                             self.pair.binding_body(challenge=self.pair.challenge(),
                                                    binding_id=other.binding["bindingId"], revision=2),
                             self.store.now)

    def test_binding_write_invalidates_sessions_and_queued_frames(self):
        binding_id = self.pair.binding["bindingId"]
        session = self.active_session()
        sid = session["sessionId"]
        sig.send_frame(self.store.con(), sid, "node", frame(self.store, sid, "node", 1), self.store.now,
                       device_id=self.device)
        updated = sig.bind(self.store.con(), self.device, self.pair.binding_body(
            challenge=self.pair.challenge(), binding_id=binding_id, revision=2), self.store.now)
        self.assertEqual((updated["created"], updated["allowed"]), (False, True))
        self.assertEqual(sig.list_sessions(self.store.con(), self.device, self.store.now)["sessions"], [])
        self.assert_rejected("session_unavailable", 410, sig.send_frame, self.store.con(), sid, "node",
                             frame(self.store, sid, "node", 2), self.store.now)
        revoked = sig.bind(self.store.con(), self.device, self.pair.binding_body(
            challenge=self.pair.challenge(), binding_id=binding_id, revision=3, allowed=False), self.store.now)
        self.assertEqual((revoked["acceptedRevision"], revoked["allowed"]), (3, False))
        self.assert_rejected("revision_stale", 409, sig.bind, self.store.con(), self.device,
                             self.pair.binding_body(challenge=self.pair.challenge(), binding_id=binding_id,
                                                    revision=3, allowed=False), self.store.now)
        listed = sig.list_bindings(self.store.con(), self.device, self.store.now)["bindings"]
        self.assertEqual(listed[0]["allowed"], False)
        with self.store.con() as con:
            self.assertEqual(con.execute("SELECT COUNT(*) FROM peer_signal_frames").fetchone()[0], 0)

    def test_binding_limit_and_foreign_device_bindings_are_isolated(self):
        for _ in range(7):
            extra = Pair(self.store, self.device, node=self.pair.node)
            extra.register(challenge=extra.challenge())
        overflow = Pair(self.store, self.device, node=self.pair.node)
        self.assert_rejected("binding_limit", 429, overflow.register, challenge=overflow.challenge())
        other = Pair(self.store, self.store.device("node-b"))
        other.register(challenge=other.challenge())
        listed = sig.list_bindings(self.store.con(), self.device, self.store.now)["bindings"]
        self.assertEqual(len(listed), 8)
        self.assertTrue(all(item["controllerPeerId"] != other.ctrl_id for item in listed))

    # -- sessions ----------------------------------------------------------

    def test_controller_challenge_snapshot_requires_allowed_binding(self):
        snapshot = sig.controller_challenge(self.store.con(), self.pair.binding["bindingId"], self.store.now)
        self.assertEqual(set(snapshot), {"challengeId", "expires", "bindingId", "nodeId", "nodePublicKey",
                                         "controllerPeerId", "controllerPublicKey", "revision"})
        self.assertEqual((snapshot["bindingId"], snapshot["nodeId"], snapshot["nodePublicKey"]),
                         (self.pair.binding["bindingId"], self.pair.node_id, pub(self.pair.node)))
        self.assertEqual((snapshot["controllerPeerId"], snapshot["controllerPublicKey"], snapshot["revision"]),
                         (self.pair.ctrl_id, pub(self.pair.ctrl), 1))
        self.assert_rejected("binding_unavailable", 404, sig.controller_challenge, self.store.con(),
                             "f" * 36, self.store.now)
        binding_id = self.pair.binding["bindingId"]
        sig.bind(self.store.con(), self.device, self.pair.binding_body(
            challenge=self.pair.challenge(), binding_id=binding_id, revision=2, allowed=False), self.store.now)
        self.assert_rejected("binding_not_authorized", 403, sig.controller_challenge, self.store.con(),
                             binding_id, self.store.now)

    def test_session_create_validates_controller_signature_and_binding_fields(self):
        binding_id = self.pair.binding["bindingId"]
        body = self.pair.create_body(session_id="session-" + "t" * 24, binding_id=binding_id,
                                     challenge=self.pair.new_challenge(binding_id), signature="A" * 86)
        self.assert_rejected("signature_rejected", 403, sig.create_session, self.store.con(), body, self.store.now)
        self.assert_rejected("binding_mismatch", 403, sig.create_session, self.store.con(),
                             self.pair.create_body(session_id="session-" + "t" * 24, binding_id=binding_id,
                                                   challenge=self.pair.new_challenge(binding_id),
                                                   node_key=pub(ec.generate_private_key(ec.SECP256R1()))),
                             self.store.now)
        self.assert_rejected("revision_stale", 409, sig.create_session, self.store.con(),
                             self.pair.create_body(session_id="session-" + "t" * 24, binding_id=binding_id,
                                                   challenge=self.pair.new_challenge(binding_id), revision=2),
                             self.store.now)
        self.assert_rejected("protocol_unsupported", 400, sig.create_session, self.store.con(),
                             self.pair.create_body(session_id="session-" + "t" * 24, binding_id=binding_id,
                                                   challenge=self.pair.new_challenge(binding_id),
                                                   version="center-signaling-v2"), self.store.now)
        created = self.pair.create_session(session_id="session-" + "t" * 24)
        self.assertEqual(set(created), {"sessionId", "state", "expires", "sessionToken"})
        self.assertEqual((created["state"], len(created["sessionToken"])), ("awaiting_node", 43))
        self.assertEqual(created["expires"], self.store.now + 300)
        self.assert_rejected("session_conflict", 409, sig.create_session, self.store.con(),
                             self.pair.create_body(session_id="session-" + "t" * 24, binding_id=binding_id,
                                                   challenge=self.pair.new_challenge(binding_id)),
                             self.store.now)
        self.assert_rejected("invalid_request", 400, sig.create_session, self.store.con(),
                             self.pair.create_body(session_id="short", binding_id=binding_id,
                                                   challenge=self.pair.new_challenge(binding_id)),
                             self.store.now)

    def test_session_create_bounds_concurrency_and_rate(self):
        first = self.pair.create_session(session_id="session-" + "a" * 24)
        second = self.pair.create_session(session_id="session-" + "b" * 24)
        self.assert_rejected("signaling_session_limit", 429, sig.create_session, self.store.con(),
                             self.pair.create_body(session_id="session-" + "c" * 24,
                                                   binding_id=self.pair.binding["bindingId"],
                                                   challenge=self.pair.new_challenge(self.pair.binding["bindingId"])),
                             self.store.now)
        sig.close_session(self.store.con(), first["sessionId"], self.store.now)
        sig.close_session(self.store.con(), second["sessionId"], self.store.now)
        for index in range(7):
            created = self.pair.create_session(session_id="session-%s" % (chr(ord("d") + index) * 24))
            sig.close_session(self.store.con(), created["sessionId"], self.store.now)
        self.assert_rejected("rate_limited", 429, sig.create_session, self.store.con(),
                             self.pair.create_body(session_id="session-" + "z" * 24,
                                                   binding_id=self.pair.binding["bindingId"],
                                                   challenge=self.pair.new_challenge(self.pair.binding["bindingId"])),
                             self.store.now)
        self.assert_rejected("session_unavailable", 410, sig.poll, self.store.con(),
                             first["sessionId"], "controller", 0, self.store.now, first["sessionToken"])

    def test_session_join_requires_matching_transcript_and_own_challenge(self):
        session = self.pair.create_session()
        sid = session["sessionId"]
        row = session_row(self.store, sid)
        self.assert_rejected("session_not_ready", 409, sig.send_frame, self.store.con(), sid, "controller",
                             frame(self.store, sid, "controller", 1), self.store.now, session["sessionToken"])
        self.assert_rejected("binding_mismatch", 403, sig.join_session, self.store.con(), self.device, sid,
                             self.pair.join_body(session_row=row, center_id="center.other"), self.store.now)
        self.assert_rejected("binding_mismatch", 403, sig.join_session, self.store.con(), self.device, sid,
                             self.pair.join_body(session_row=row, revision=2), self.store.now)
        self.assert_rejected("binding_mismatch", 403, sig.join_session, self.store.con(), self.device, sid,
                             self.pair.join_body(session_row=row, expires=row["expires_at"] + 1), self.store.now)
        self.assert_rejected("challenge_expired_or_reused", 409, sig.join_session, self.store.con(), self.device,
                             sid, self.pair.join_body(session_row=row, challenge="y" * 43), self.store.now)
        foreign = Pair(self.store, self.store.device("node-f"))
        foreign.register(challenge=foreign.challenge())
        self.assert_rejected("signature_rejected", 403, sig.join_session, self.store.con(), self.device, sid,
                             self.pair.join_body(session_row=row, signature=sign(foreign.node, "x")),
                             self.store.now)
        joined = self.pair.join(row)
        self.assertEqual((joined["state"], set(joined)), ("active", {"sessionId", "state", "expires"}))
        listed = sig.list_sessions(self.store.con(), self.device, self.store.now)["sessions"]
        self.assertEqual(listed[0]["state"], "active")
        self.assertEqual(listed[0]["centerId"], "center.test")

    # -- frames ------------------------------------------------------------

    def test_frames_reject_non_signaling_shapes(self):
        session = self.active_session()
        sid = session["sessionId"]
        send = lambda frame_value, token=None: sig.send_frame(  # noqa: E731
            self.store.con(), sid, "node", frame_value, self.store.now, token, self.device)
        self.assert_rejected("frame_rejected", 400, send,
                             frame(self.store, sid, "node", 1, ct=b64(b"short")))
        self.assert_rejected("frame_rejected", 400, send,
                             {**frame(self.store, sid, "node", 1), "extra": "field"})
        self.assert_rejected("frame_rejected", 400, send,
                             {key: value for key, value in frame(self.store, sid, "node", 1).items()
                              if key != "ct"})
        self.assert_rejected("frame_rejected", 400, send, frame(self.store, sid, "node", 1,
                                                                 domain="simhub-peer-v1"))
        self.assert_rejected("frame_rejected", 400, send, frame(self.store, sid, "node", 1,
                                                                 session="s" * 40))
        self.assert_rejected("frame_rejected", 400, send, frame(self.store, sid, "node", 1,
                                                                 dir="controller"))
        self.assert_rejected("frame_rejected", 400, send, frame(self.store, sid, "node", 0))
        self.assert_rejected("frame_rejected", 400, send, frame(self.store, sid, "node", True))
        self.assert_rejected("frame_rejected", 400, send, frame(self.store, sid, "node", 1,
                                                                 revision=2))
        self.assert_rejected("frame_rejected", 400, send, frame(self.store, sid, "node", 1,
                                                                 nonce=b64(bytes(11))))
        self.assert_rejected("frame_rejected", 400, send, frame(self.store, sid, "node", 1,
                                                                 expires=self.store.now - 1))
        self.assert_rejected("frame_rejected", 400, send, frame(self.store, sid, "node", 1,
                                                                 expires=self.store.now + 121))
        self.assert_rejected("frame_too_large", 413, send, frame(self.store, sid, "node", 1,
                                                                  body=b"x" * 70_000))
        self.assert_rejected("frame_rejected", 400, send, frame(self.store, sid, "node", 1, id="short"))
        sent = send(frame(self.store, sid, "node", 1))
        self.assertEqual(sent, {"seq": 1})

    def test_frame_dedup_and_sequence_rules(self):
        session = self.active_session()
        sid = session["sessionId"]
        first = frame(self.store, sid, "node", 1, message_id="m" * 24)
        self.assertEqual(sig.send_frame(self.store.con(), sid, "node", first, self.store.now,
                                        device_id=self.device)["seq"], 1)
        self.assertEqual(sig.send_frame(self.store.con(), sid, "node", first, self.store.now,
                                        device_id=self.device)["seq"], 1)
        conflict = frame(self.store, sid, "node", 2, message_id="m" * 24, body=b"different-content-0001")
        self.assert_rejected("message_id_conflict", 409, sig.send_frame, self.store.con(), sid,
                             "node", conflict, self.store.now, None, self.device)
        self.assert_rejected("sequence_reused", 409, sig.send_frame, self.store.con(), sid, "node",
                             frame(self.store, sid, "node", 1, message_id="n" * 24), self.store.now,
                             None, self.device)
        self.assertEqual(sig.send_frame(self.store.con(), sid, "node",
                                        frame(self.store, sid, "node", 2, message_id="o" * 24),
                                        self.store.now, None, self.device)["seq"], 2)
        # The opposite direction keeps its own sequence space.
        self.assertEqual(sig.send_frame(self.store.con(), sid, "controller",
                                        frame(self.store, sid, "controller", 1), self.store.now,
                                        session["sessionToken"])["seq"], 3)

    def test_frame_quota_poll_ack_and_own_direction_isolation(self):
        session = self.active_session()
        sid = session["sessionId"]
        sent_frames = [frame(self.store, sid, "node", seq) for seq in range(1, 33)]
        for value in sent_frames:
            sig.send_frame(self.store.con(), sid, "node", value, self.store.now, device_id=self.device)
        self.assert_rejected("frame_quota", 429, sig.send_frame, self.store.con(), sid, "node",
                             frame(self.store, sid, "node", 33), self.store.now, None, self.device)
        collected, after = [], 0
        while True:
            page = sig.poll(self.store.con(), sid, "controller", after, self.store.now,
                            session["sessionToken"])
            if not page["messages"]:
                break
            collected.extend(page["messages"])
            after = collected[-1]["seq"]
        self.assertEqual([item["seq"] for item in collected], list(range(1, 33)))
        self.assertEqual(set(collected[0]), {"seq", "frame"})
        self.assertEqual(collected[0]["frame"], sent_frames[0])
        sig.ack(self.store.con(), sid, "controller", 5, self.store.now, session["sessionToken"])
        self.assertEqual(sig.send_frame(self.store.con(), sid, "node",
                                        frame(self.store, sid, "node", 33, message_id="q" * 24),
                                        self.store.now, device_id=self.device)["seq"], 33)
        self.assertEqual(sig.send_frame(self.store.con(), sid, "controller",
                                        frame(self.store, sid, "controller", 1), self.store.now,
                                        session["sessionToken"])["seq"] > 0, True)
        own = sig.poll(self.store.con(), sid, "node", 0, self.store.now, device_id=self.device)
        self.assertTrue(all(item["frame"]["dir"] == "controller" for item in own["messages"]))

    def test_frame_rate_limit_is_per_pair_and_direction(self):
        session = self.active_session()
        sid = session["sessionId"]
        for seq in range(1, 33):
            sig.send_frame(self.store.con(), sid, "node",
                           frame(self.store, sid, "node", seq, message_id=f"a{seq}".ljust(24, "a")),
                           self.store.now, device_id=self.device)
        for seq in range(1, 33):
            sig.ack(self.store.con(), sid, "controller", seq, self.store.now, session["sessionToken"])
        for seq in range(33, 61):
            sig.send_frame(self.store.con(), sid, "node",
                           frame(self.store, sid, "node", seq, message_id=f"b{seq}".ljust(24, "b")),
                           self.store.now, device_id=self.device)
        self.assert_rejected("rate_limited", 429, sig.send_frame, self.store.con(), sid, "node",
                             frame(self.store, sid, "node", 61, message_id="c" * 24), self.store.now,
                             None, self.device)
        # The controller direction and the binding pair both keep their own budget.
        self.assertEqual(sig.send_frame(self.store.con(), sid, "controller",
                                        frame(self.store, sid, "controller", 1), self.store.now,
                                        session["sessionToken"])["seq"] > 0, True)
        other = Pair(self.store, self.store.device("node-rate"))
        other.binding = other.register(challenge=other.challenge())
        other_session = other.create_session()
        other.join(session_row(self.store, other_session["sessionId"]))
        sig.send_frame(self.store.con(), other_session["sessionId"], "node",
                       frame(self.store, other_session["sessionId"], "node", 1), self.store.now,
                       device_id=other.device)
        self.store.tick(61)
        self.assertEqual(sig.send_frame(self.store.con(), sid, "node",
                                        frame(self.store, sid, "node", 61, message_id="d" * 24),
                                        self.store.now, device_id=self.device)["seq"] > 0, True)

    def test_close_expiry_and_maintenance_cleanup(self):
        session = self.active_session()
        sid = session["sessionId"]
        sig.send_frame(self.store.con(), sid, "node", frame(self.store, sid, "node", 1), self.store.now,
                       device_id=self.device)
        self.store.tick(301)
        self.assert_rejected("session_unavailable", 410, sig.poll, self.store.con(), sid, "controller",
                             0, self.store.now, session["sessionToken"])
        self.assertGreaterEqual(sig.maintenance(self.store.con(), self.store.now), 1)
        with self.store.con() as con:
            for table in ("peer_signal_sessions", "peer_signal_frames", "peer_signal_challenges"):
                self.assertEqual(con.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0], 0, table)
        fresh = self.active_session(session_id="session-" + "k" * 24)
        self.assertEqual(sig.close_session(self.store.con(), fresh["sessionId"], self.store.now), {"closed": True})
        self.assert_rejected("session_unavailable", 410, sig.poll, self.store.con(), fresh["sessionId"],
                             "controller", 0, self.store.now, fresh["sessionToken"])

    def test_revoked_or_reset_device_blocks_ops_and_forget_purges(self):
        session = self.active_session()
        sid = session["sessionId"]
        sig.send_frame(self.store.con(), sid, "node", frame(self.store, sid, "node", 1), self.store.now,
                       device_id=self.device)
        with self.store.con() as con:
            con.execute("UPDATE devices SET revoked_at=? WHERE id=?", (self.store.now, self.device))
        self.assert_rejected("authorization_revoked", 403, sig.poll, self.store.con(), sid, "controller",
                             0, self.store.now, session["sessionToken"])
        sig.forget_device(self.store.con(), self.device)
        with self.store.con() as con:
            for table in ("peer_signal_bindings", "peer_signal_sessions", "peer_signal_frames"):
                self.assertEqual(con.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0], 0, table)
        self.assert_rejected("session_unavailable", 410, sig.poll, self.store.con(), sid, "controller",
                             0, self.store.now, session["sessionToken"])

    def test_controller_token_is_required_for_controller_operations(self):
        session = self.active_session()
        sid = session["sessionId"]
        self.assert_rejected("session_token_rejected", 403, sig.send_frame, self.store.con(), sid,
                             "controller", frame(self.store, sid, "controller", 1), self.store.now)
        sent = sig.send_frame(self.store.con(), sid, "controller",
                              frame(self.store, sid, "controller", 1), self.store.now, session["sessionToken"])
        self.assert_rejected("session_token_rejected", 403, sig.poll, self.store.con(), sid, "controller",
                             0, self.store.now, "A" * 43)
        self.assert_rejected("session_token_rejected", 403, sig.poll, self.store.con(), sid, "controller",
                             0, self.store.now, None)
        # The node side authenticates by device token and never needs the controller token.
        node_frame = sig.send_frame(self.store.con(), sid, "node",
                                    frame(self.store, sid, "node", 1), self.store.now,
                                    device_id=self.device)
        polled = sig.poll(self.store.con(), sid, "controller", 0, self.store.now, session["sessionToken"])
        self.assertEqual([item["seq"] for item in polled["messages"]], [node_frame["seq"]])
        self.assertEqual([item["seq"] for item in sig.poll(self.store.con(), sid, "node", 0,
                                                           self.store.now, device_id=self.device)["messages"]],
                         [sent["seq"]])

    def test_session_lists_and_binding_lookup_stay_scoped(self):
        session = self.active_session()
        listed = sig.list_sessions(self.store.con(), self.device, self.store.now)["sessions"]
        self.assertEqual([item["sessionId"] for item in listed], [session["sessionId"]])
        self.assertEqual(set(listed[0]), {"sessionId", "bindingId", "controllerPeerId", "centerId",
                                          "revision", "expires", "state"})
        self.assertEqual(listed[0]["bindingId"], self.pair.binding["bindingId"])
        other = Pair(self.store, self.store.device("node-z"))
        other.binding = other.register(challenge=other.challenge())
        self.assertEqual(sig.list_sessions(self.store.con(), "node-z", self.store.now)["sessions"], [])
        self.assertEqual(sig.list_bindings(self.store.con(), "node-z", self.store.now)["bindings"][0]["bindingId"],
                         other.binding["bindingId"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
