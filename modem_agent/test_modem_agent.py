#!/usr/bin/env python3
import tempfile, unittest
from pathlib import Path

from simhub_modem_agent import (
    Store, DjiAtAdapter, MmcliAdapter, parse_otp, command_aad, decrypt_payload, encrypt_payload, event_aad, key_id, bootstrap_proof, decode_ucs2, decode_message_body, decrypt_bootstrap_node_key, parse_concat_udh
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

    def test_bootstrap_proof_vector(self):
        self.assertEqual(bootstrap_proof(bytes(range(32))),'Yw3NKWbEM2aRElRIu7JbT_QSpJxzLbLIq8G4WBvXEN0')

    def test_shared_bootstrap_vector(self):
        import json
        vector=json.loads((Path(__file__).resolve().parents[1]/'test_vectors'/'bootstrap-v1.json').read_text('utf-8'))
        bootstrap=__import__('base64').urlsafe_b64decode(vector['bootstrapBase64Url']+'==')
        envelope={'v':1,'alg':'A256GCM','iv':vector['ivBase64Url'],'ct':vector['ciphertextBase64Url']}
        raw=decrypt_bootstrap_node_key(bootstrap,envelope,vector['kid'])
        self.assertEqual(__import__('base64').urlsafe_b64encode(raw).decode().rstrip('='),vector['nodeKeyBase64Url'])

    def test_concat_udh_parser_and_store(self):
        raw=bytes([0x00,0x40,0x02,0x91,0x21,0x00,0x08,0,0,0,0,0,0,0,0x08,0x05,0x00,0x03,0x07,0x02,0x01,0x4F,0x60])
        self.assertEqual(parse_concat_udh(raw.hex()),('8:7',2,1))
        with tempfile.TemporaryDirectory() as td:
            store=Store(Path(td)/'agent.db')
            cipher={'v':1,'alg':'A256GCM','iv':'a','ct':'b'}
            self.assertTrue(store.queue_multipart_part('g',1,2,cipher))
            self.assertTrue(store.queue_multipart_part('g',2,2,cipher))
            rows=store.multipart_group('g')
            self.assertEqual([r['part_no'] for r in rows],[1,2])
            store.delete_multipart_group('g')
            self.assertEqual(store.multipart_group('g'),[])

    def test_bootstrap_node_key_roundtrip(self):
        import os
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        bootstrap=os.urandom(32);node=os.urandom(32);kid=key_id(node);iv=os.urandom(12)
        aad=('simhub-bootstrap-node-key-v1|'+kid).encode()
        envelope={'v':1,'alg':'A256GCM','iv':__import__('base64').urlsafe_b64encode(iv).decode().rstrip('='),'ct':__import__('base64').urlsafe_b64encode(AESGCM(bootstrap).encrypt(iv,node,aad)).decode().rstrip('=')}
        self.assertEqual(decrypt_bootstrap_node_key(bootstrap,envelope,kid),node)
        with self.assertRaises(Exception):
            decrypt_bootstrap_node_key(os.urandom(32),envelope,kid)

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

    def test_durable_sms_budget(self):
        with tempfile.TemporaryDirectory() as td:
            path=Path(td)/'agent.db'
            store=Store(path)
            for i in range(10):
                self.assertTrue(store.claim_sms_budget('sms-'+str(i)))
            self.assertTrue(store.claim_sms_budget('sms-9'))  # same command is idempotent
            self.assertFalse(store.claim_sms_budget('sms-blocked'))
            store.db.close()
            restarted=Store(path)
            self.assertFalse(restarted.claim_sms_budget('sms-after-restart'))
            restarted.db.close()

    def test_store_is_idempotent(self):
        with tempfile.TemporaryDirectory() as td:
            store=Store(Path(td)/'agent.db')
            cipher={'v':2,'alg':'A256GCM','kid':'abcdefgh1234','iv':'a','ct':'b'}
            self.assertTrue(store.queue_event('evt','sms.received',1,'ch',False,{},cipher))
            self.assertTrue(store.queue_event('evt','sms.received',1,'ch',False,{},cipher))
            self.assertEqual(len(store.pending_events()),1)
            self.assertTrue(store.claim_command('cmd'))
            self.assertFalse(store.claim_command('cmd'))

    def test_modem_otp_is_content_only(self):
        self.assertEqual(parse_otp("【服务】您的验证码为 638271，请勿告知他人")["value"],"638271")
        self.assertEqual(parse_otp("Your verification code is 7A82B9")["value"],"7A82B9")
        self.assertIsNone(parse_otp("Hello, this is not a code."))

    def test_mmcli_fingerprint_uses_sim_not_hardware_imei(self):
        from unittest.mock import patch
        modem=MmcliAdapter.__new__(MmcliAdapter)
        modem.modem="any"
        modem_data='{"modem":{"generic":{"sim":"/org/freedesktop/ModemManager1/SIM/3","equipment-identifier":"861234567890123"}}}'
        sim_a='{"sim":{"properties":{"iccid":"89860123456789012345","imsi":"460011234567890"}}}'
        sim_b='{"sim":{"properties":{"iccid":"89860123456789012346","imsi":"460011234567891"}}}'
        with patch("simhub_modem_agent.run_command",side_effect=[modem_data,sim_a,modem_data,sim_b,modem_data,'{}']):
            fp1=modem.fingerprint()
            fp2=modem.fingerprint()
            self.assertNotEqual(fp1,fp2)
            with self.assertRaisesRegex(RuntimeError,"ICCID/IMSI"):
                modem.fingerprint()

    def test_ucs2_decode(self):
        self.assertEqual(decode_ucs2('4F60597D'),'你好')
        self.assertEqual(decode_ucs2('hello'),'hello')

    def test_numeric_otp_is_not_misdecoded_as_ucs2(self):
        self.assertEqual(decode_ucs2('12345678'),'12345678')
        self.assertEqual(decode_message_body('12345678',0),'12345678')
        self.assertEqual(decode_message_body('9A8C8BC17801',8),'验证码')

    def test_rejects_service_dialing_symbols(self):
        with self.assertRaises(ValueError):
            DjiAtAdapter._submit_pdus('*123#','hello')

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
