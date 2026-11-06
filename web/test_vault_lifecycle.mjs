#!/usr/bin/env node
// Actual async browser functions, real AES-GCM and controlled lock races.
import assert from 'node:assert/strict';
import {webcrypto} from 'node:crypto';
import {readFileSync} from 'node:fs';
import {createContext,runInContext} from 'node:vm';

const source=readFileSync(new URL('./app.js',import.meta.url),'utf8');
function implementation(name){
  const start=source.search(new RegExp('(?:async )?function '+name+'\\('));
  assert.ok(start>=0,name);
  const tail=source.slice(start),next=tail.slice(1).search(/\n(?:async )?function \w+\(/);
  return next<0?tail:tail.slice(0,next+1);
}
const storage=()=>{const rows=new Map();return {getItem:k=>rows.get(k)||null,
  setItem:(k,v)=>rows.set(k,v),removeItem:k=>rows.delete(k)};};
function context(extra={}){
  return createContext({console,Uint8Array,TextEncoder,TextDecoder,Date,
    crypto:webcrypto,enc:new TextEncoder(),dec:new TextDecoder(),
    b64u:b=>Buffer.from(b).toString('base64url'),unb64u:s=>new Uint8Array(Buffer.from(s,'base64url')),
    securityEpoch:0,vaultKey:{},vaultRaw:new Uint8Array(32).fill(23),authenticated:true,
    sessionIdleMs:28800000,lastActivity:Date.now(),globalVault:null,
    TRUSTED_VAULT_CACHE:'trusted',SESSION_VAULT_CACHE:'tab',
    localStorage:storage(),sessionStorage:storage(),
    trustedOptions:()=>({enabled:true,duration:3600}),deviceKeyCache:new Map(),
    resetAutoLock:()=>{},cacheTabVault:async()=>{},...extra});
}
function deferred(){let resolve;const promise=new Promise(r=>resolve=r);return {promise,resolve};}
const browserKey=await webcrypto.subtle.generateKey({name:'AES-GCM',length:256},false,['encrypt','decrypt']);
for(const name of ['storeTrustedVault','cacheTabVault']){
  const barrier=deferred(),ctx=context({tabVaultKey:()=>barrier.promise});
  runInContext(implementation(name),ctx);
  const pending=runInContext(name+'()',ctx);
  // Explicit or cross-tab lock invalidates an already copied raw key.
  ctx.securityEpoch++;ctx.vaultKey=null;ctx.vaultRaw.fill(0);ctx.vaultRaw=null;
  ctx.localStorage.removeItem('trusted');ctx.sessionStorage.removeItem('tab');
  barrier.resolve(browserKey);await pending;
  assert.equal(ctx.localStorage.getItem('trusted'),null,name+' must not restore trusted cache');
  assert.equal(ctx.sessionStorage.getItem('tab'),null,name+' must not restore tab cache');
}
for(const name of ['restoreTrustedVault','restoreTabVault']){
  const barrier=deferred(),iv=webcrypto.getRandomValues(new Uint8Array(12));
  const trusted=name==='restoreTrustedVault',aad=trusted?'simhub-trusted-vault-v1':'simhub-session-tab-v1';
  const ct=await webcrypto.subtle.encrypt({name:'AES-GCM',iv,additionalData:new TextEncoder().encode(aad)},browserKey,new Uint8Array(32).fill(8));
  let imported=0;
  const ctx=context({tabVaultKey:()=>barrier.promise,importVault:async()=>{imported++;},
    clearTrustedVault:()=>{},clearTabVault:()=>{}});
  const row={v:1,iv:Buffer.from(iv).toString('base64url'),ct:Buffer.from(ct).toString('base64url'),expiresAt:Date.now()+60000,lastActivity:Date.now()};
  (trusted?ctx.localStorage:ctx.sessionStorage).setItem(trusted?'trusted':'tab',JSON.stringify(row));
  runInContext(implementation(name),ctx);
  const pending=runInContext(name+'()',ctx);
  ctx.securityEpoch++;ctx.vaultKey=null;barrier.resolve(browserKey);
  assert.equal(await pending,false);assert.equal(imported,0,'A lock cancels an in-flight restore');
}
{
  const barrier=deferred(),ctx=context({crypto:{subtle:{importKey:()=>barrier.promise}}});
  runInContext(implementation('importVault'),ctx);
  const pending=runInContext('importVault(new Uint8Array(32).fill(19))',ctx);
  ctx.securityEpoch++;ctx.vaultKey=null;ctx.vaultRaw=null;barrier.resolve(browserKey);
  await assert.rejects(pending,/cancelled/);
  assert.equal(ctx.vaultKey,null);assert.equal(ctx.vaultRaw,null);
}
{
  // Sensitive DOM values and child nodes are removed, including hidden dialogs.
  const nodes=new Map();
  const $=id=>{if(!nodes.has(id))nodes.set(id,{value:'private',textContent:'private',open:true,
    childNodes:[],replaceChildren(){this.textContent='';},removeAttribute(){},close(){this.open=false;},dispatchEvent(){}});return nodes.get(id);};
  const ctx=context({$,expandedDeviceDetails:new Set(),enrollStartingDevices:new Set(),
    offlineTimers:new Map(),offlineNotified:new Set(),knownCommandStates:new Map(),
    clearTimeout,Event:class{}});
  runInContext(implementation('purgeSensitiveUI')+'\npurgeSensitiveUI()',ctx);
  for(const id of ['newVaultPassphrase','confirmVaultPassphrase','conversationTitle','conversationMeta','controllerPending','controllerAuthorized'])
    assert.equal($(id).value,'',id);
  assert.equal($('controllerConnectDialog').open,false);
  assert.equal($('simFilter').textContent,'');
}
console.log('PASS: Vault write, restore, import and sensitive UI lock barriers');
