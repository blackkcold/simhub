#!/usr/bin/env node
// Exercise the actual app's async page and index wiring without a browser shim.
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {createContext,runInContext} from 'node:vm';
import {LocalHistoryIndex,HistoryLoadBudget,normalizeSearch,eventSearchId,summarizeHistoryCoverage} from './history-search.js';
import {validateHistoryPage} from './history-backfill.js';
const source=readFileSync(new URL('./app.js',import.meta.url),'utf8');
function implementation(name){
  const start=source.search(new RegExp('(?:async )?function '+name+'\\('));assert.ok(start>=0,name);
  const tail=source.slice(start),next=tail.slice(1).search(/\n(?:async )?function \w+\(/);
  return next<0?tail:tail.slice(0,next+1);
}
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return {promise,resolve};};
const row=seq=>({seq,occurredAt:seq,eventId:'event-'+seq,deviceId:'node-A',kind:'sms.received'});
const page=(events,hasMore)=>({events,hasMore,nextBeforeTime:events.at(-1)?.occurredAt??null,nextBeforeSeq:events.at(-1)?.seq??null});
function context(extra={}){
  const elements=new Map();
  const $=id=>{if(!elements.has(id))elements.set(id,{value:'',textContent:'',disabled:false,hidden:false,classList:{remove(){}}});return elements.get(id);};
  const ctx=createContext({console,AbortController,DOMException,Set,Map,$,
    securityEpoch:0,eventDataGeneration:0,vaultKey:{},events:[],decryptedEvents:[],eventIds:new Set(),
    historyIndex:new LocalHistoryIndex(),historyBudget:new HistoryLoadBudget(),validateHistoryPage,historyRequestController:null,historyFetchInFlight:null,
    initialEventsLoaded:true,olderCursor:{time:100,seq:100},historyHasMore:true,lastSeq:5000,oldestSeq:1,
    historyBackfill:{running:false,status:'idle',reset(){}},incrementalCaughtUp:true,
    simProfileIndex:new Map(),simProfileIndexVersion:'',visibleCount:40,lastAutoScroll:-1,
    activeConversationKey:null,renderInbox:()=>{},renderHistoryCoverage:()=>{},
    decryptEvent:async e=>({body:'private-'+e.seq,sender:'sender-'+e.seq}),maybeNotify:()=>{},decryptFailureCategory:()=>'',
    normalizeSearch,eventSearchId,summarizeHistoryCoverage,devices:[{id:'node-A',name:'Node Alpha',smsCount:100,state:{pendingEvents:2}}],
    deviceName:id=>'Node Alpha',getLocale:()=>'en',...extra});
  for(const name of ['ingestEvents','fetchHistoryPage','clearHistoryState','resetEventCache','filteredEvents','collapseMessageEvents','eventSimKey','messageAddress','eventIsInbound','canonicalAddress'])
    runInContext(implementation(name),ctx);
  return ctx;
}
{
  const entered=deferred(),resume=deferred(),signal=new AbortController(),requests=[];
  const response=page([row(99),row(98),row(97)],true);let blocked=false;
  const ctx=context({signal:signal.signal,api:async(path,options)=>{requests.push(path);assert.ok(options.signal);return response;},
    decryptEvent:async e=>{if(e.seq===98&&!blocked){blocked=true;entered.resolve();await resume.promise;}return {body:'private-'+e.seq};}});
  const pending=runInContext('fetchHistoryPage({signal})',ctx);await entered.promise;
  signal.abort();resume.resolve();await pending;
  assert.equal(ctx.olderCursor.seq,100,'Do not commit a partially decrypted page');
  assert.equal(ctx.lastSeq,5000,'History cannot advance the independent incremental cursor');
  assert.equal(ctx.decryptedEvents.length,1);assert.equal(ctx.historyIndex.size,1);
  assert.deepEqual([...ctx.historyIndex.search('private-99')],['node-A:event-99']);
  await runInContext('fetchHistoryPage()',ctx);
  assert.equal(ctx.olderCursor.seq,97);assert.equal(ctx.decryptedEvents.length,3);assert.equal(ctx.eventIds.size,3);
  assert.equal(ctx.historyIndex.size,3);assert.equal(requests[0],requests[1],'Resume requests the original checkpoint');
  ctx.api=async()=>page([],false);await runInContext('fetchHistoryPage()',ctx);
  assert.equal(ctx.historyHasMore,false);assert.equal(ctx.olderCursor,null);
  ctx.$('search').value='PRIVATE-98';
  assert.equal(runInContext('filteredEvents().length',ctx),1,'Search sees successfully committed backfill rows');
  ctx.$('search').value='node alpha';assert.equal(runInContext('filteredEvents().length',ctx),3,'Device labels remain searchable');
  ctx.$('deviceFilter').value='node-B';assert.equal(runInContext('filteredEvents().length',ctx),0,'Source filters still apply');
  runInContext('clearHistoryState()',ctx);assert.equal(ctx.historyIndex.size,0);
}
{
  const response=deferred();let decrypted=0,renders=0;
  const ctx=context({api:()=>response.promise,decryptEvent:async()=>{decrypted++;return {body:'stale'};},renderInbox:()=>renders++});
  const pending=runInContext('fetchHistoryPage()',ctx);
  runInContext('resetEventCache()',ctx);
  const replacement=Promise.resolve();ctx.historyFetchInFlight=replacement;
  response.resolve(page([row(99)],false));await pending;
  assert.equal(decrypted,0);assert.equal(ctx.historyIndex.size,0);assert.equal(ctx.decryptedEvents.length,0);
  assert.equal(ctx.historyFetchInFlight,replacement,'A stale finally cannot clear a new request');
  assert.equal(renders,0);assert.equal(ctx.lastSeq,0);
}
{
  const ctx=context({api:async()=>page([row(100)],true)});
  await assert.rejects(runInContext('fetchHistoryPage()',ctx),/did not advance/);
  assert.equal(ctx.olderCursor.seq,100);assert.equal(ctx.historyIndex.size,0);assert.equal(ctx.historyHasMore,true);
}
{
  const ctx=context({historyBudget:new HistoryLoadBudget({maxRows:1}),api:async()=>page([row(99),row(98)],true)});
  await assert.rejects(runInContext('fetchHistoryPage()',ctx),error=>error.code==='history_memory_limit');
  assert.equal(ctx.olderCursor.seq,100);assert.equal(ctx.historyHasMore,true);assert.equal(ctx.lastSeq,5000);
  assert.equal(ctx.decryptedEvents.length,1);assert.equal(ctx.historyIndex.size,1);
  runInContext(implementation('renderHistoryCoverage')+'\nrenderHistoryCoverage()',ctx);
  assert.match(ctx.$('historyCoverage').textContent,/some history remains unsearched/);
  assert.equal(ctx.$('historyLoadAll').disabled,true);assert.equal(ctx.$('historyClear').hidden,false);
  runInContext('resetEventCache()',ctx);assert.equal(ctx.historyBudget.limited,false);assert.equal(ctx.historyBudget.rows,0);
}
{
  const ctx=context();
  ctx.decryptedEvents=[{...row(1),kind:'sms.history',payload:{body:'historical body',direction:'out',providerId:7}},
    {...row(2),kind:'sms.delivered',payload:{direction:'out',providerId:7}}];
  for(const event of ctx.decryptedEvents)ctx.historyIndex.addEvent(event);
  ctx.$('search').value='historical body';
  assert.equal(runInContext('filteredEvents()[0]?.eventId',ctx),'event-1',
    'A metadata-only later delivery event cannot hide a matching historical body');
  ctx.$('search').value='';ctx.$('kindFilter').value='sms.history';
  assert.equal(runInContext('filteredEvents().length',ctx),1,'Type filtering precedes status folding');
}
{
  const ctx=context({api:async()=>page([],false)});
  runInContext(implementation('renderHistoryCoverage'),ctx);
  ctx.batch=[row(1)];await runInContext('ingestEvents(batch,false)',ctx);
  ctx.historyHasMore=false;runInContext('renderHistoryCoverage()',ctx);
  assert.match(ctx.$('historyCoverage').textContent,/1 records loaded/);
  assert.match(ctx.$('historyCoverage').textContent,/Relay last reported 100/);
  assert.match(ctx.$('historyCoverage').textContent,/in this scan/);
  assert.match(ctx.$('historyExplanation').textContent,/memory.*cleared on lock/);
  assert.match(ctx.$('historyNodeCoverage').textContent,/Unuploaded or unscanned.*outside Relay/);
  assert.equal(ctx.$('historyLoadAll').disabled,true);
  ctx.devices[0].state={};runInContext('renderHistoryCoverage()',ctx);
  assert.match(ctx.$('historyNodeCoverage').textContent,/counts are unavailable/);
}
{
  const first=deferred(),controller=new AbortController();let requests=0;
  const ctx=context({setTimeout,clearTimeout,lastActivity:Date.now(),csrfToken:'',signal:controller.signal,
    fetch:async(path,options)=>{assert.equal(options.signal,controller.signal);requests++;first.resolve();return {status:503};}});
  runInContext(implementation('api'),ctx);
  const pending=runInContext("api('/api/v1/events',{signal})",ctx);await first.promise;
  controller.abort();await assert.rejects(pending,error=>error.name==='AbortError');
  assert.equal(requests,1,'Stopping cancels a transient-error retry before another request');
}
console.log('PASS: actual app paging, partial-page resume, index wiring, reset barriers and honest coverage text');
