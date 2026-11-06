#!/usr/bin/env node
import assert from 'node:assert/strict';
import {webcrypto} from 'node:crypto';
import {IntentJournal,SendCoordinator} from './send-intents.js';
if(!globalThis.crypto)Object.defineProperty(globalThis,'crypto',{value:webcrypto});
function storage(){const map=new Map();return {get length(){return map.size;},key:i=>[...map.keys()][i],
  getItem:k=>map.get(k)||null,setItem:(k,v)=>map.set(k,v),removeItem:k=>map.delete(k),map};}
function mutex(){const tails=new Map();return {request:async(name,fn)=>{
  const old=tails.get(name)||Promise.resolve();let release;
  const next=new Promise(r=>release=r);tails.set(name,next);
  await old;try{return await fn();}finally{release();if(tails.get(name)===next)tails.delete(name);}
}};}
const disk=storage(),locks=mutex(),key=await webcrypto.subtle.generateKey({name:'AES-GCM',length:256},false,['encrypt','decrypt']);
const journal=new IntentJournal({scope:'synthetic-vault',key,storage:disk,locks});
const payload={channelId:'sim-A',channelRevision:3,to:'+12025550123',body:'synthetic-private-text'};
const accepted=new Map(),posts=[];let lose=true,idCount=0;
const api=async(path,options)=>{
  if(options?.method==='POST'){
    const command=options.body;posts.push(JSON.stringify(command));
    accepted.set(command.commandId,{commandId:command.commandId,deviceId:'node-A',state:'queued'});
    if(lose){lose=false;throw Error('Response lost');}
    return {accepted:true,commandId:command.commandId,state:'queued'};
  }
  const command=accepted.get(path.split('/').pop());
  if(!command)throw Object.assign(Error('Missing'),{status:404,code:'command_not_found'});
  return {command};
};
const opts={journal,api,encrypt:async()=>({v:2,ct:'synthetic-ciphertext'}),now:()=>1000,newId:()=>('intent-'+ ++idCount)};
let coordinator=new SendCoordinator(opts);
await journal.saveDraft('compose',{deviceId:'node-A',...payload});
await assert.rejects(coordinator.submit({draftId:'compose',deviceId:'node-A',payload,ttl:180}),/lost/);
assert.equal(posts.length,1);assert.equal(idCount,1);
assert.ok([...disk.map.values()].every(x=>!x.includes(payload.body)&&!x.includes(payload.to)),
  'No plaintext SMS or recipient in browser storage');
// Reload: the original intent is recovered from authenticated encrypted storage.
const reloaded=new IntentJournal({scope:'synthetic-vault',key,storage:disk,locks});
coordinator=new SendCoordinator({...opts,journal:reloaded});
const recovered=await coordinator.submit({draftId:'compose',deviceId:'node-A',payload,ttl:180});
assert.equal(recovered.commandId,'intent-1');assert.equal(posts.length,1);
assert.equal((await reloaded.draft('compose')).body,'');
// An old second tab clicking send cannot create another ID after acknowledgement.
await new SendCoordinator(opts).submit({draftId:'compose',deviceId:'node-A',payload,ttl:180});
assert.equal(idCount,1);
// New typing is an explicit new draft after an observed response.
await reloaded.saveDraft('compose',{deviceId:'node-A',...payload,body:'second message'});
const second={...payload,body:'second message'};
await coordinator.submit({draftId:'compose',deviceId:'node-A',payload:second,ttl:180});
assert.equal(idCount,2);
// Pre-acceptance loss: 404 permits only the same immutable envelope, never a new ID.
const j2=new IntentJournal({scope:'second-vault',key,storage:disk,locks});let first=true;
const requests=[];
const c2=new SendCoordinator({...opts,journal:j2,api:async(path,options)=>{
  if(!options)throw Object.assign(Error('Missing'),{status:404,code:'command_not_found'});
  requests.push(JSON.stringify(options.body));if(first){first=false;throw Error('Offline');}
  return {accepted:true,commandId:options.body.commandId,state:'queued'};
}});
await assert.rejects(c2.submit({draftId:'draft',deviceId:'node-A',payload,ttl:180}),/Offline/);
await assert.rejects(c2.submit({draftId:'draft',deviceId:'node-B',payload,ttl:180}),/previous send/);
await c2.submit({draftId:'draft',deviceId:'node-A',payload,ttl:180});
assert.equal(requests.length,2);assert.equal(requests[0],requests[1]);
// Unknown delivery is status-only; an observed radio-side ambiguity is not retried.
accepted.get('intent-1').state='outcome_unknown';
await coordinator.retry('intent-1');assert.equal(posts.length,2);
assert.equal((await reloaded.read('intent:intent-1')).state,'outcome_unknown');
await assert.rejects(reloaded.archive('intent-1'),/Resolve/);
// Tampering is detected; locking destroys journal access.
const name=[...disk.map.keys()].find(k=>k.includes('second-vault')&&k.includes('intent:'));
const envelope=JSON.parse(disk.getItem(name)),tampered=Buffer.from(envelope.ct,'base64');tampered[0]^=1;envelope.ct=tampered.toString('base64');disk.setItem(name,JSON.stringify(envelope));
await assert.rejects(j2.list());
reloaded.close();await assert.rejects(reloaded.draft('compose'),/locked/);
console.log('PASS: encrypted durable drafts, response-loss reconciliation, route freeze and safe same-ID retry');
