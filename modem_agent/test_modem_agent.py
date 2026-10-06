#!/usr/bin/env python3
import tempfile, unittest
from pathlib import Path

from simhub_modem_agent import (
    Store, DjiAtAdapter, command_aad, decrypt_payload, encrypt_payload, event_aad, key_id, decode_ucs2, decode_message_body
)

class ModemAgentTest(unittest.TestCase):
    def test_shared_crypto_vector(self):
        import json
        vector=json.loads((Path(__file__).resolve().parents[1]/'test_vectors'/'crypto-v2.json').read_text('utf-8'))
        key=__import__('base64').urlsafe_b64decode(vector['keyBase64Url']+'==')
        self.assertEqual(key_id(key),vector['kid'])
        aad=event_aad(vector['deviceId'],vector['eventId'],vector['kind'],vector['occurredAt'],vector['subscriptionId'],vector['hasOtp'])
        self.assertEqual(aad.decode(),vector['aad'])
        envelope=encrypt_payload(key,vector['kid'],json.loads(vector['plaintext']),aad)
        # encrypt_payload uses a random IV, so verify the shared vector by decrypting its fixed envelope.
        fixed={'v':2,'alg':'A256GCM','kid':vector['kid'],'iv':vector['ivBase64Url'],'ct':vector['ciphertextBase64Url']}
        self.assertEqual(json.dumps(decrypt_payload(key,vector['kid'],fixed,aad),separators=(',',':')),vector['plaintext'])

    def test_v2_crypto_roundtrip_and_metadata_binding(self):
        key=bytes(range(32));kid=key_id(key)
        payload={'action':'sms.send','commandId':'cmd-1','expiresAt':200,'channelId':'ch-1','body':'hello'}
        aad=command_aad('dev-1','cmd-1','sms.send',100,200,'idem-1')
        envelope=encrypt_payload(key,kid,payload,aad)
        self.assertEqual(decrypt_payload(key,kid,envelope,aad),payload)
        with self.assertRaises(Exception):
            decrypt_payload(key,kid,envelope,command_aad('dev-2','cmd-1','sms.send',100,200,'idem-1'))

    def test_event_aad_is_channel_bound(self):
        self.assertNotEqual(event_aad('d','e','sms.received',1,'channel-a',False),event_aad('d','e','sms.received',1,'channel-b',False))

    def test_store_is_idempotent(self):
        with tempfile.TemporaryDirectory() as td:
            store=Store(Path(td)/'agent.db')
            cipher={'v':2,'alg':'A256GCM','kid':'abcdefgh1234','iv':'a','ct':'b'}
            self.assertTrue(store.queue_event('evt','sms.received',1,'ch',False,{},cipher))
            self.assertTrue(store.queue_event('evt','sms.received',1,'ch',False,{},cipher))
            self.assertEqual(len(store.pending_events()),1)
            self.assertTrue(store.claim_command('cmd'))
            self.assertFalse(store.claim_command('cmd'))

    def test_ucs2_decode(self):
        self.assertEqual(decode_ucs2('4F60597D'),'你好')
        self.assertEqual(decode_ucs2('hello'),'hello')

    def test_numeric_otp_is_not_misdecoded_as_ucs2(self):
        self.assertEqual(decode_ucs2('12345678'),'12345678')
        self.assertEqual(decode_message_body('12345678',0),'12345678')
        self.assertEqual(decode_message_body('9A8C8BC17801',8),'验证码')

    def test_dji_pdu_supports_unicode_and_multipart(self):
        single=DjiAtAdapter._submit_pdus('+8613800138000','你好123')
        self.assertEqual(len(single),1)
        self.assertTrue(single[0].startswith('00'))
        self.assertIn('4F60597D',single[0])

        parts=DjiAtAdapter._submit_pdus('+8613800138000','验'*160)
        self.assertGreater(len(parts),1)
        total=len(parts)
        for index,pdu in enumerate(parts,start=1):
            raw=bytes.fromhex(pdu)
            self.assertLessEqual(len(raw)-1,160)
            self.assertIn(bytes([0x05,0x00,0x03]),raw)
            self.assertIn(bytes([total,index]),raw)

if __name__=='__main__':
    unittest.main(verbosity=2)
