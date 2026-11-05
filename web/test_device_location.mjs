import assert from 'node:assert/strict';
import {validateNodeLocation,osmEmbedUrl,osmExternalUrl} from './device-location.js';
import {renderNodeDashboard} from './device-dashboard.js';

const event={kind:'device.location',receivedAt:1792380000};
const payload={latitude:22.543096,longitude:114.057865,accuracyMeters:32,
  sampledAt:Math.floor(Date.now()/1000)};
const safe=validateNodeLocation(payload,event);
assert.ok(safe);
assert.equal(safe.latitude,22.543096);
assert.equal(validateNodeLocation({...payload,latitude:'22.5'},event),null);
assert.equal(validateNodeLocation({...payload,longitude:Infinity},event),null);
assert.equal(validateNodeLocation({...payload,accuracyMeters:-4},event),null);
assert.equal(validateNodeLocation({...payload,sampledAt:Date.now()/1000+999},event),null);
assert.equal(validateNodeLocation(payload,{kind:'sms.received'}),null);
assert.match(osmEmbedUrl(safe),/^https:\/\/www\.openstreetmap\.org\/export\/embed\.html\?bbox=/);
assert.match(osmExternalUrl(safe),/^https:\/\/www\.openstreetmap\.org\/\?mlat=/);
assert.equal(osmEmbedUrl({...safe,latitude:999}),null);

const escapeHtml=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const render=(args)=>renderNodeDashboard({id:'node-1',name:'phone',online:true,state:{
  locationSharingEnabled:true,locationRemoteAllowed:false}},{
  escapeHtml,fmtTime:n=>String(n),deviceStatusText:()=> 'Online',
  channels:[],buttons:'',zh:true,...args});
const closed=render({locationInfo:{kind:'loaded',location:safe}});
assert.doesNotMatch(closed,/<iframe/,'No external map request before explicit action');
assert.match(closed,/将发送给 OpenStreetMap/);
assert.doesNotMatch(closed,/data-action="location-remote"/,'Default remote permission is off');
const opened=render({locationInfo:{kind:'loaded',location:safe},mapVisible:true});
assert.match(opened,/<iframe/);
assert.match(opened,/© OpenStreetMap contributors/);
assert.match(render({locationInfo:null,mapVisible:true}),/按需读取/);
console.log('Encrypted location presentation and opt-in OSM map: OK');
