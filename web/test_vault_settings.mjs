#!/usr/bin/env node
import assert from 'node:assert/strict';
import {webcrypto} from 'node:crypto';
import {VaultSettingsClient} from './vault-settings.js';
const key=await webcrypto.subtle.generateKey({name:'AES-GCM',length:256},false,['encrypt','decrypt']);
const vaultId='fixture-vault',keyId='fixture-key';
let state={vaultId,keyId,revision:0,ciphertext:null},injected=false,other;
const bodies=[];
const api=async(path,options)=>{
  assert.equal(path,'/api/v1/vault/settings');
  if(!options)return structuredClone(state);
  const body=options.body;bodies.push(body);
  if(!injected){injected=true;await other.update('channelAliases','node|simB|2','Work SIM');}
  if(body.expectedRevision!==state.revision)throw Object.assign(Error('conflict'),{status:409,code:'settings_conflict'});
  state={vaultId,keyId,revision:state.revision+1,ciphertext:body.ciphertext};
  return structuredClone(state);
};
const client=new VaultSettingsClient({vaultId,keyId,key,api});
other=new VaultSettingsClient({vaultId,keyId,key,api});
const changed=await client.update('phoneOverrides','node|simA|1','+12025550123');
assert.equal(changed.settings.phoneOverrides['node|simA|1'],'+12025550123');
assert.equal(changed.settings.channelAliases['node|simB|2'],'Work SIM');
assert.ok(bodies.length>=3,'Concurrent CAS forces a fresh merge');
assert.ok(bodies.every(b=>!JSON.stringify(b).includes('+12025550123')&&!JSON.stringify(b).includes('Work SIM')),
  'Only opaque settings reach the Relay');
const removed=await other.update('phoneOverrides','node|simA|1','');
assert.equal(removed.settings.phoneOverrides['node|simA|1'],undefined);
assert.equal(removed.settings.channelAliases['node|simB|2'],'Work SIM');
await assert.rejects(new VaultSettingsClient({vaultId:'different',keyId,key,api}).load(),/another Vault/);
state.ciphertext={...state.ciphertext,kid:'different'};
await assert.rejects(client.load(),/envelope/);
client.close();await assert.rejects(client.load(),/locked/);
console.log('PASS: encrypted cross-controller labels, CAS merge, deletion and identity fences');
