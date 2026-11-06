#!/usr/bin/env node
import assert from 'node:assert/strict';
import {HistoryBackfill,HISTORY_PAGE_SIZE,HISTORY_PAGE_INTERVAL_MS,waitForHistoryPage,validateHistoryPage} from './history-backfill.js';
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return {promise,resolve};};
const event=(seq,time=seq)=>({seq,occurredAt:time,eventId:'e'+seq,deviceId:'node-A',kind:'sms.history'});
const page=(events,hasMore)=>({events,hasMore,nextBeforeTime:events.at(-1)?.occurredAt??null,nextBeforeSeq:events.at(-1)?.seq??null});
assert.ok(HISTORY_PAGE_SIZE<=1000);
{
  const originalSet=globalThis.setTimeout,originalClear=globalThis.clearTimeout,signal=new AbortController();
  let delay,cleared;
  try{
    globalThis.setTimeout=(callback,ms)=>{delay=ms;return 'scheduled-page';};
    globalThis.clearTimeout=id=>cleared=id;
    const waiting=waitForHistoryPage(signal.signal);assert.equal(delay,HISTORY_PAGE_INTERVAL_MS);
    signal.abort();await waiting;assert.equal(cleared,'scheduled-page','Stop cancels the pacing timer immediately');
  }finally{globalThis.setTimeout=originalSet;globalThis.clearTimeout=originalClear;}
}
assert.deepEqual(validateHistoryPage(page([event(3,7),event(2,7),event(1,6)],true)).cursor,{time:6,seq:1});
assert.equal(validateHistoryPage(page([],false),{time:6,seq:1}).hasMore,false);
for(const invalid of [page([],true),page([event(2),event(3)],false),{events:[],hasMore:undefined},
  {...page([event(3)],true),nextBeforeSeq:4},page([event(3)],true)]){
  assert.throws(()=>validateHistoryPage(invalid,{time:3,seq:3}),/history|History/);
}
assert.throws(()=>validateHistoryPage(page([event(3),event(2)],true),null,1),/Invalid/);
{
  // A full explicit scan is paged, sequential and finite; no hidden row cutoff.
  const source=Array.from({length:1251},(_,i)=>event(1251-i));let offset=0,inFlight=0,maxInFlight=0;
  const controller=new HistoryBackfill({hasMore:()=>offset<source.length,yieldTask:()=>Promise.resolve(),loadPage:async signal=>{
    inFlight++;maxInFlight=Math.max(maxInFlight,inFlight);assert.equal(signal.aborted,false);
    const batch=source.slice(offset,offset+HISTORY_PAGE_SIZE);
    validateHistoryPage(page(batch,offset+batch.length<source.length));
    await Promise.resolve();offset+=batch.length;inFlight--;return {loaded:batch.length};
  }});
  await controller.start();assert.equal(controller.status,'complete');assert.equal(controller.pages,3);
  assert.equal(controller.rows,1251);assert.equal(maxInFlight,1);
}
{
  const entered=deferred(),resume=deferred();let offset=0,yields=0;
  const controller=new HistoryBackfill({hasMore:()=>offset<3,loadPage:async()=>{offset++;return {loaded:10};},
    yieldTask:async()=>{if(++yields===1){entered.resolve();await resume.promise;}}});
  const first=controller.start();assert.equal(controller.start(),first,'Repeated clicks join one worker');
  await entered.promise;controller.cancel();resume.resolve();await first;
  assert.equal(controller.status,'paused');assert.equal(offset,1);assert.equal(controller.pages,1);
  await controller.start();assert.equal(offset,3);assert.equal(controller.pages,3);assert.equal(controller.rows,30);
}
{
  let calls=0,offset=0;
  const controller=new HistoryBackfill({hasMore:()=>offset<1,loadPage:signal=>{
    calls++;
    if(calls===1)return new Promise((resolve,reject)=>signal.addEventListener('abort',()=>reject(signal.reason),{once:true}));
    offset++;return Promise.resolve({loaded:2});
  }});
  const pending=controller.start();controller.cancel();await pending;
  assert.equal(controller.status,'paused');assert.equal(offset,0);assert.equal(controller.pages,0);
  await controller.start();assert.equal(controller.status,'complete');assert.equal(controller.rows,2);
}
{
  const old=deferred();let calls=0,more=true;
  const controller=new HistoryBackfill({hasMore:()=>more,loadPage:async()=>{
    if(++calls===1)return old.promise;
    more=false;return {loaded:5};
  }});
  const abandoned=controller.start();controller.reset();await controller.start();
  old.resolve({loaded:900});await abandoned;
  assert.equal(controller.rows,5,'A pre-lock worker cannot update a new session');
  assert.equal(controller.pages,1);assert.equal(controller.status,'complete');
}
{
  let calls=0,more=true;
  const controller=new HistoryBackfill({hasMore:()=>more,loadPage:async()=>{
    if(++calls===1)throw Error('Offline');more=false;return {loaded:1};
  }});
  await assert.rejects(controller.start(),/Offline/);assert.equal(controller.status,'error');
  assert.equal(calls,1,'Failures require a user-controlled resume');assert.equal(controller.pages,0);
  await controller.start();assert.equal(controller.status,'complete');
}
{
  const controller=new HistoryBackfill({hasMore:()=>true,loadPage:async()=>{
    throw Object.assign(Error('Partial coverage'),{code:'history_memory_limit'});
  }});
  await assert.rejects(controller.start(),/Partial coverage/);
  assert.equal(controller.status,'limited');assert.equal(controller.pages,0);
}
console.log('PASS: bounded complete paging, cursor validation, cancellation/resume and stale-worker isolation');
