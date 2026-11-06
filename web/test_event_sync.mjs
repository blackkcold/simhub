#!/usr/bin/env node
// Query ordering changes while initial history is loading: no sequence gaps.
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createContext,runInContext} from 'node:vm';
const source=readFileSync(new URL('./app.js',import.meta.url),'utf8');
const code=source.slice(source.indexOf('async function ingestEvents('),source.indexOf('async function loadOlder('));
const row=(seq,time=seq)=>({seq,deviceId:'synthetic-node',eventId:'event-'+seq,
  kind:'sms.received',occurredAt:time,ciphertext:{v:2}});
const first=Array.from({length:100},(_,i)=>row(i+1,10000+i));
const late=Array.from({length:200},(_,i)=>row(i+101,1));
const calls=[];
let notify=0;
const ctx=createContext({securityEpoch:0,eventDataGeneration:0,vaultKey:{},lastSeq:0,
  oldestSeq:0,initialEventsLoaded:false,events:[],decryptedEvents:[],eventIds:new Set(),
  olderCursor:null,historyHasMore:true,renderInbox:()=>{},
  decryptEvent:async e=>({body:'synthetic '+e.seq}),maybeNotify:()=>notify++,
  decryptFailureCategory:()=>'',api:async path=>{
    calls.push(path);
    if(path.includes('latest=1&limit=1')&&!path.endsWith('100'))return {events:[row(100)]};
    if(path.includes('order=occurred'))return {events:first,hasMore:true,nextBeforeTime:10000,nextBeforeSeq:1};
    if(path.includes('latest=1'))return {events:late.slice(100).reverse()};
    const since=Number(new URL(path,'https://test.invalid').searchParams.get('since'));
    return {events:late.filter(e=>e.seq>since).slice(0,100)};
  }});
runInContext(code,ctx);
await runInContext('loadEvents()',ctx);
assert.equal(ctx.lastSeq,300);assert.equal(ctx.eventIds.size,300);
assert.ok(calls.includes('/api/v1/events?since=100&limit=100'));
assert.equal(notify,0,'Startup backfill does not announce old messages as new');
// Overlapping historical pages must not move the incremental checkpoint.
await runInContext('ingestEvents([{seq:900,deviceId:"other",eventId:"historical",kind:"sms.received"}],false)',ctx);
assert.equal(ctx.lastSeq,300);
// A lock in the middle of decrypting a page must not commit its checkpoint.
ctx.decryptEvent=async()=>{ctx.securityEpoch++;ctx.vaultKey=null;return {};};
ctx.api=async()=>({events:[row(301)]});
await runInContext('loadEvents()',ctx);
assert.equal(ctx.lastSeq,300);
// A purge/reset during decryption cannot repopulate the new cache, including
// the decryption-error branch. The Vault can remain unlocked during a reset.
for(const fails of [false,true]){
  let enter,finish;
  const entered=new Promise(r=>enter=r),barrier=new Promise((resolve,reject)=>finish=()=>fails?reject(Error('old key')):resolve({body:'old private body'}));
  ctx.vaultKey={};ctx.initialEventsLoaded=true;ctx.lastSeq=300;
  ctx.decryptEvent=async()=>{enter();return barrier;};
  ctx.api=async()=>({events:[row(301)]});
  const pending=runInContext('loadEvents()',ctx);await entered;
  ctx.eventDataGeneration++;ctx.events=[];ctx.decryptedEvents=[];ctx.eventIds.clear();ctx.lastSeq=0;
  finish();await pending;
  assert.equal(ctx.decryptedEvents.length,0,'Reset invalidates successful and failed old decryption');
  assert.equal(ctx.events.length,0);assert.equal(ctx.eventIds.size,0);assert.equal(ctx.lastSeq,0);
}
// Cancellation leaves the same event retryable; no premature dedup checkpoint.
ctx.vaultKey={};ctx.decryptEvent=async()=>({body:'current'});
const signal={aborted:true};ctx.signal=signal;
await runInContext('ingestEvents([{seq:301,deviceId:"synthetic-node",eventId:"event-301",kind:"sms.received"}],false,signal)',ctx);
assert.equal(ctx.eventIds.size,0);
signal.aborted=false;
await runInContext('ingestEvents([{seq:301,deviceId:"synthetic-node",eventId:"event-301",kind:"sms.received"}],false,signal)',ctx);
assert.equal(ctx.eventIds.size,1);
console.log('PASS: concurrent old uploads, independent cursors and interrupted ingestion');
