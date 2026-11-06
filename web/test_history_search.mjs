#!/usr/bin/env node
import assert from 'node:assert/strict';
import {LocalHistoryIndex,HistoryLoadBudget,normalizeSearch,summarizeHistoryCoverage} from './history-search.js';
const row=(id,body,extra={})=>({eventId:id,deviceId:'node-A',kind:'sms.received',payload:{body,...extra}});
// Storage access is forbidden: the local search cache is RAM-only.
for(const name of ['localStorage','sessionStorage','indexedDB'])
  Object.defineProperty(globalThis,name,{get(){throw Error('Search accessed persistent storage');},configurable:true});
const index=new LocalHistoryIndex({maxPostings:90,maxGramsPerRow:40}),rows=[];
for(let i=0;i<600;i++){
  const event=row('e'+i,`Record ${i}：验证码 ${600000+i} café`,{sender:'+12025550123',contactName:i%2?'Alice':'张三'});
  rows.push(event);index.addEvent(event);
}
const extended=row('long','prefix-'+ 'x'.repeat(10000)+'-END-TAIL', {otp:{value:1234}});
rows.push(extended);index.addEvent(extended);
for(const query of ['Record 5','验证码','６０００１２','CafÉ','张','ALICE','2555','END-TAIL','1234','不存在','']){
  const expected=rows.filter(e=>[e.payload.body,e.payload.sender,e.payload.contactName,e.payload.otp?.value]
    .some(v=>normalizeSearch(v).includes(normalizeSearch(query)))).map(e=>e.deviceId+':'+e.eventId).sort();
  assert.deepEqual([...index.search(query)].sort(),expected,'Exact matches remain complete beyond the posting budget: '+query);
}
assert.ok(index.postingCount<=90);assert.ok(index.fallback.size>0);
index.add('separate-fields',['abc','def']);
assert.equal(index.search('cde').has('separate-fields'),false,'Fields are not concatenated into invented matches');
index.add('replace',['old body']);index.add('replace',['new body']);
assert.equal(index.search('old body').has('replace'),false);assert.ok(index.search('new body').has('replace'));
index.remove('replace');assert.equal(index.search('new body').size,0);
index.addEvent({...row('unreadable','secret'),payload:null,decryptError:true});
index.addEvent({...row('location','coordinates'),kind:'device.location'});
assert.equal(index.search('coordinates').size,0);
assert.equal(index.search('secret').size,0);

const devices=[{id:'node-A',smsCount:100,state:{pendingEvents:7}},{id:'node-B',state:{}}];
const events=[row('1','readable'),{...row('2','not readable'),payload:null,decryptError:true},
  {...row('3','node-B body'),deviceId:'node-B'},{...row('4','coordinate'),kind:'device.location'}];
const all=summarizeHistoryCoverage(events,devices);
assert.equal(all.loaded,3);assert.equal(all.searchable,2);assert.equal(all.unreadable,1);
assert.equal(all.retained,null,'Unknown counts are not silently reported as zero');
assert.equal(all.pending,7);assert.equal(all.pendingUnknownNodes,1);
const one=summarizeHistoryCoverage(events,devices,'node-A');
assert.equal(one.loaded,2);assert.equal(one.retained,100);assert.equal(one.pending,7);
assert.equal(summarizeHistoryCoverage([],[]).retained,null);
assert.equal(summarizeHistoryCoverage([],[{id:'node-A',smsCount:0,state:{pendingEvents:0}}]).pending,0);
const budget=new HistoryLoadBudget({maxRows:1,maxEstimatedBytes:1024*1024});
budget.addEvent(row('a','first'),true);const bytes=budget.estimatedBytes;
assert.throws(()=>budget.addEvent(row('b','second'),true),error=>error.code==='history_memory_limit');
assert.equal(budget.rows,1);assert.equal(budget.estimatedBytes,bytes);assert.equal(budget.limited,true);
budget.addEvent(row('live','live messages retain priority'));
assert.equal(budget.rows,2,'This is a backfill admission budget, not an inaccurate total-heap guarantee');
budget.clear();assert.equal(budget.rows,0);assert.equal(budget.estimatedBytes,0);assert.equal(budget.limited,false);
const textBudget=new HistoryLoadBudget({maxRows:100,maxEstimatedBytes:512});
assert.throws(()=>textBudget.addEvent(row('large','x'.repeat(2000)),true),error=>error.code==='history_memory_limit');
assert.equal(textBudget.rows,0,'An oversized plaintext row is rejected before it enters the retained cache');
index.clear();assert.equal(index.size,0);assert.equal(index.postingCount,0);
assert.equal(index.postings.size,0);assert.equal(index.fallback.size,0);assert.equal(index.search('record').size,0);
console.log('PASS: complete bounded RAM index, Unicode/substring search, coverage uncertainty and clear-on-lock primitive');
