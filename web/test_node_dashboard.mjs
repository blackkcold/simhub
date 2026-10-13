import assert from 'node:assert/strict';
import {nodeDashboardModel,renderNodeDashboard} from './device-dashboard.js';
const base={id:'node-1',name:'fold5',model:'vivo',appVersion:'0.16.2',online:true,smsCount:321,
  state:{batteryPct:100,network:'WiFi',smsMode:'companion',smsReadPermission:true,
    smsReceivePermission:true,smsSendPermission:true,pendingEvents:0,pendingCommandAcks:0,
    lastSyncSuccessAt:1791860000,lastSmsBroadcastAt:1791850000}};
const escapeHtml=(s)=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const render=(device)=>renderNodeDashboard(device,{escapeHtml,fmtTime:x=>String(x),
  deviceStatusText:d=>d.online?'在线':'离线',channels:[],buttons:'<div id="actions"></div>',zh:true});
const m=nodeDashboardModel(base);
assert.equal(m.pct,100);
assert.equal(m.pending,0);
assert.equal(m.send,false,'SEND_SMS permission alone must never enable companion sending');
assert.equal(m.events[0].label,'同步完成');
const html=render(base);
assert.match(html,/SIM 与短信能力/);
assert.match(html,/短信同步/);
assert.match(html,/设备与网络/);
assert.match(html,/任务与诊断/);
assert.match(html,/100%/);
assert.match(html,/不接管 · 默认禁止发送/);
assert.match(html,/id="actions"/);
const missing=nodeDashboardModel({online:false,state:{batteryPct:null}});
assert.equal(missing.pct,null,'unknown battery cannot be shown as 0');
assert.equal(missing.pending,null,'unknown event queue cannot be shown as 0');
assert.match(render({online:false,state:{}}),/最近一次上报/);
const hostile={...base,state:{...base.state,network:'<img src=x onerror=alert(1)>'}};
assert.ok(!render(hostile).includes('<img '),'untrusted node labels must be escaped');
assert.ok(render(hostile).includes('&lt;img '));
const dev=nodeDashboardModel({...base,state:{...base.state,
  smsDeveloperOverride:true,smsSendAllowed:true}});
assert.equal(dev.send,false);
assert.equal(dev.developerSend,true,'explicit developer override is separately reported');
console.log('SIM node health dashboard model and escaping: OK');
