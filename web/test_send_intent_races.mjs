#!/usr/bin/env node
// A reconciliation in another tab must never turn "resume" into a new SMS.
import assert from 'node:assert/strict';
import {webcrypto} from 'node:crypto';
import {IntentJournal,SendCoordinator} from './send-intents.js';
if(!globalThis.crypto)Object.defineProperty(globalThis,'crypto',{value:webcrypto});
const key=await webcrypto.subtle.generateKey({name:'AES-GCM',length:256},false,['encrypt','decrypt']);
const payload={channelId:'sim-A',channelRevision:2,to:'+12025550123',body:'synthetic first message'};
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return {promise,resolve};};
function setup(){
  const values=new Map(),tails=new Map();let ids=0,posts=0,lose=true;
  const storage={get length(){return values.size;},key:i=>[...values.keys()][i],
    getItem:k=>values.get(k)||null,setItem:(k,v)=>values.set(k,v),removeItem:k=>values.delete(k)};
  const locks={request:async(name,fn)=>{const prior=tails.get(name)||Promise.resolve();
    let release;const current=new Promise(r=>release=r);tails.set(name,current);
    await prior;try{return await fn();}finally{release();if(tails.get(name)===current)tails.delete(name);}}};
  const commands=new Map();
  const api=async(path,options)=>{
    if(options?.method==='POST'){
      posts++;const commandId=options.body.commandId;
      commands.set(commandId,{commandId,deviceId:'node-A',state:'sent'});
      if(lose){lose=false;throw Error('Response lost');}
      return {accepted:true,commandId,state:'sent'};
    }
    const command=commands.get(path.split('/').pop());
    if(!command)throw Object.assign(Error('Missing'),{status:404,code:'command_not_found'});
    return {command};
  };
  const create=()=>{
    const journal=new IntentJournal({scope:'race-test',key,storage,locks});
    const coordinator=new SendCoordinator({journal,api,encrypt:async()=>({v:2,ct:'test'}),
      now:()=>1000,newId:()=>('intent-'+ ++ids)});
    return {journal,coordinator};
  };
  return {create,commands,get posts(){return posts;},get ids(){return ids;}};
}
{
  const test=setup(),a=test.create(),b=test.create();
  await assert.rejects(a.coordinator.submit({draftId:'compose',deviceId:'node-A',payload,ttl:300}),/lost/);
  const entered=deferred(),resume=deferred(),read=a.journal.read.bind(a.journal);let intercept=true;
  a.journal.read=async id=>{
    const item=await read(id);
    if(id==='intent:intent-1'&&intercept){intercept=false;entered.resolve();await resume.promise;}
    return item;
  };
  const retry=a.coordinator.retry('intent-1');await entered.promise;
  // Another tab learns that the original send succeeded and starts a new draft.
  await b.coordinator.reconcile('intent-1');
  const second={...payload,body:'synthetic second message',deviceId:'node-A'};
  await b.journal.saveDraft('compose',second);
  resume.resolve();const result=await retry;
  assert.equal(result.commandId,'intent-1','Resume stays attached to the chosen record');
  assert.equal(test.posts,1,'No new network submission after concurrent acceptance');
  assert.equal(test.ids,1,'Resume never generates another command identity');
  assert.equal((await b.journal.draft('compose')).body,second.body,'A new draft is not cleared by old reconciliation');
}
{
  const test=setup(),a=test.create(),b=test.create();
  await assert.rejects(a.coordinator.submit({draftId:'compose',deviceId:'node-A',payload,ttl:300}),/lost/);
  const entered=deferred(),resume=deferred(),exclusive=a.journal.exclusive.bind(a.journal);let intercept=true;
  a.journal.exclusive=async(name,action)=>{
    if(name==='intent:intent-1'&&intercept){intercept=false;entered.resolve();await resume.promise;}
    return exclusive(name,action);
  };
  const submit=a.coordinator.submit({draftId:'compose',deviceId:'node-A',payload,ttl:300});await entered.promise;
  await b.coordinator.reconcile('intent-1');
  test.commands.delete('intent-1'); // Retention/deletion cannot undo known acceptance.
  resume.resolve();await assert.rejects(submit,/previously accepted/);
  assert.equal(test.posts,1,'Reload the latest observed state after acquiring the record lock');
}
{
  const test=setup(),a=test.create();
  const remember=a.coordinator.remember.bind(a.coordinator);
  a.coordinator.remember=async item=>{
    const result=await remember(item);
    if(item.phase==='submitting')a.journal.close();
    return result;
  };
  await assert.rejects(a.coordinator.submit({draftId:'compose',deviceId:'node-A',payload,ttl:300}),/locked/);
  assert.equal(test.posts,0,'A lock after journal commit still cancels the network side effect');
}
{
  const test=setup(),a=test.create();
  await assert.rejects(a.coordinator.submit({draftId:'compose',deviceId:'node-A',payload,ttl:300}),/lost/);
  await a.coordinator.reconcile('intent-1');await a.journal.archive('intent-1');
  await assert.rejects(a.coordinator.submit({draftId:'compose',deviceId:'node-A',payload,ttl:300}),/record.*missing/i);
  assert.equal(test.posts,1,'A stale tab cannot recreate an archived intent from its old draft link');
}
console.log('PASS: exact-ID resume, concurrent reconciliation, lock-before-POST and missing-journal barriers');
