#!/usr/bin/env node
// Pure conversation identity regression: evaluate the actual browser helper
// definitions without a Relay or DOM and verify cross-SIM consolidation.
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
import {runInNewContext} from "node:vm";

const source=readFileSync(new URL("./app.js",import.meta.url),"utf8");
function segment(from,to){
  const a=source.indexOf(from),b=source.indexOf(to,a+from.length);
  assert.ok(a>=0&&b>a,"Missing conversation implementation: "+from);
  return source.slice(a,b);
}
const helpers=segment("function eventIsInbound(e){","function filteredEvents(){")+
              segment("function buildThreads(source){","function renderInbox(){");
const {threadKey,eventSimKey,buildThreads,canonicalAddress}=runInNewContext(
  helpers+"\n({threadKey,eventSimKey,buildThreads,canonicalAddress})",Object.create(null));
function sms(deviceId,channelId,revision,direction,address,occurredAt){
  const payload={direction,body:"synthetic",channelId,channelRevision:revision};
  payload[direction==="in"?"sender":"recipient"]=address;
  return {deviceId,kind:direction==="in"?"sms.received":"sms.sent",payload,occurredAt};
}
const a=sms("a","sim-A",1,"in","+86 (138) 0013-8000",100);
const b=sms("b","sim-B",2,"out","13800138000",200);
const c=sms("a","sim-A",1,"in","0086 13800138000",150);
assert.equal(threadKey(a),threadKey(b));
assert.equal(threadKey(a),threadKey(c));
assert.notEqual(eventSimKey(a),eventSimKey(b));
const threads=buildThreads([a,b,c]);
assert.equal(threads.length,1,"Correspondent must appear once across SIMs");
assert.equal(threads[0].messages.length,3,"Consolidation must preserve all messages");
assert.equal(threads[0].latest.occurredAt,200,"Most recent actual message must be previewed");
assert.notEqual(canonicalAddress("+12025550123"),canonicalAddress("+441632960000"),
  "Different countries must not be collapsed");
assert.notEqual(canonicalAddress("106900"),canonicalAddress("106901"),
  "Different shortcodes must remain distinct");
console.log("PASS: canonical correspondent grouping and source SIM provenance");
