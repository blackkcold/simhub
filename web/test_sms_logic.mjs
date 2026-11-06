#!/usr/bin/env node
// Verify consistent per-source conversation identity across Web and Android.
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {runInNewContext} from "node:vm";

const source=readFileSync(new URL("./app.js",import.meta.url),"utf8");
const versionCode=source.slice(source.indexOf("function versionAtLeast("),source.indexOf("function otaView("));
const {versionAtLeast}=runInNewContext(versionCode+"\n({versionAtLeast})",Object.create(null));
assert.equal(versionAtLeast("0.17.4-dev.8","0.17.4"),true);
assert.equal(versionAtLeast("0.17.4-dev.8","0.17.5"),false);
assert.equal(versionAtLeast("0.17.4-dev.0","0.13.3"),false);
assert.equal(versionAtLeast("1.invalid","0.13.3"),false);
function segment(from,to){
  const a=source.indexOf(from),b=source.indexOf(to,a+from.length);
  assert.ok(a>=0&&b>a,"Missing conversation implementation: "+from);
  return source.slice(a,b);
}
const helpers=segment("function collapseMessageEvents(source){","function filteredEvents(){")+
              segment("function buildThreads(source){","function renderInbox(){");
const {threadKey,eventSimKey,buildThreads,canonicalAddress,collapseMessageEvents}=runInNewContext(
  helpers+"\n({threadKey,eventSimKey,buildThreads,canonicalAddress,collapseMessageEvents})",Object.create(null));
function sms(deviceId,channelId,revision,direction,address,occurredAt){
  const payload={direction,body:"synthetic",channelId,channelRevision:revision};
  payload[direction==="in"?"sender":"recipient"]=address;
  return {deviceId,kind:direction==="in"?"sms.received":"sms.sent",payload,occurredAt};
}
const a=sms("a","sim-A",1,"in","+86 (138) 0013-8000",100);
const b=sms("b","sim-B",2,"out","13800138000",200);
const c=sms("a","sim-A",1,"in","0086 13800138000",150);
assert.notEqual(threadKey(a),threadKey(b),"Different nodes must not share a conversation");
assert.equal(threadKey(a),threadKey(c));
assert.notEqual(eventSimKey(a),eventSimKey(b));
assert.notEqual(threadKey(a),threadKey(sms("a","sim-A",2,"in","13800138000",180)),
  "Revision changes require a new physical source identity");
assert.notEqual(threadKey(a),threadKey(sms("a","sim-B",1,"in","13800138000",180)),
  "Different SIMs on one node cannot mix");
const threads=buildThreads([a,b,c]);
assert.equal(threads.length,2,"Separate SIM nodes must produce distinct conversations");
assert.equal(threads[0].messages.length,1,"Other nodes cannot be mixed into the latest conversation");
assert.equal(threads[1].messages.length,2,"The same SIM must retain its canonicalized thread");
assert.equal(threads[0].latest.occurredAt,200,"Most recent actual message must be previewed");
assert.notEqual(canonicalAddress("+12025550123"),canonicalAddress("+441632960000"),
  "Different countries must not be collapsed");
assert.notEqual(canonicalAddress("106900"),canonicalAddress("106901"),
  "Different shortcodes must remain distinct");
// Android/provider row IDs are local identifiers, not global SMS identities.
const sent=sms('a','sim-A',1,'out','+86 (138) 0013-8000',100);
sent.eventId='sent';sent.payload.providerId=7;
const delivered={...sent,eventId:'delivered',kind:'sms.delivered',occurredAt:200,
  payload:{...sent.payload,recipient:'13800138000'}};
const collisions=[
  sms('a','sim-B',1,'out','13800138000',300),
  sms('a','sim-A',2,'out','13800138000',400),
  sms('a','sim-A',1,'out','+441632960000',500),
  sms('b','sim-A',1,'out','13800138000',600)
];
collisions.forEach((e,i)=>{e.eventId='distinct-'+i;e.payload.providerId=7;});
const folded=collapseMessageEvents([sent,delivered,...collisions]);
assert.equal(folded.length,5,'A provider ID must not collapse another node, SIM, revision or correspondent');
assert.ok(folded.some(e=>e.eventId==='delivered'));assert.ok(!folded.some(e=>e.eventId==='sent'));
for(const e of collisions)assert.ok(folded.some(actual=>actual.eventId===e.eventId));
const legacy=sms('a','',0,'out','13800138000',700);
legacy.subscriptionId='1';legacy.payload.providerId=7;
const otherLegacy={...legacy,subscriptionId:'2',payload:{...legacy.payload}};
assert.equal(collapseMessageEvents([sent,legacy,otherLegacy]).length,3,
  'Unverified subscription identities cannot be merged with another source');
console.log("PASS: source-isolated conversation identity and canonical correspondent");
