import assert from 'node:assert/strict';
import {nodeDashboardModel,renderNodeDashboard,signalBars,wifiArcs,batteryLevel} from './device-dashboard.js';
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

assert.match(batteryLevel(76,true),/width="34\.96"/);
assert.match(batteryLevel(76,true),/node-charge-icon/);
assert.doesNotMatch(batteryLevel(999,false),/node-battery-fill/,'invalid battery is not clamped');
assert.equal((signalBars(2).match(/class="node-lit"/g)||[]).length,2);
assert.equal((signalBars(null).match(/class="node-lit"/g)||[]).length,0);
assert.equal((wifiArcs(true,3).match(/class="node-lit"/g)||[]).length,3);
assert.equal((wifiArcs(true,null).match(/class="node-lit"/g)||[]).length,0);
const visual=render({...base,state:{...base.state,batteryPct:76,charging:true,
  wifiConnected:true,wifiSignalLevel:3,sampledAt:Math.floor(Date.now()/1000)}});
assert.match(visual,/node-battery-level/);
assert.match(visual,/node-wifi-arcs/);
assert.match(visual,/最近采样/);
assert.match(render({...base,state:{...base.state,sampledAt:1}}),/状态已过期/);
console.log('SIM node health dashboard model and escaping: OK');
