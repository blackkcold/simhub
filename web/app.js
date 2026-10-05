const $ = (id) => document.getElementById(id);
const enc = new TextEncoder();
const dec = new TextDecoder();
const VAULT_STORE = 'simhub_vault_v1';
const TOKEN_STORE = 'simhub_admin_token_v1';
const PBKDF2_ITER = 310000;
const AUTO_LOCK_MS = 15 * 60 * 1000;

let adminToken = localStorage.getItem(TOKEN_STORE) || sessionStorage.getItem(TOKEN_STORE) || '';
let vaultKey = null;
let vaultRaw = null;
let devices = [];
let events = [];
let decryptedEvents = [];
let lastSeq = 0;
let pollTimer = null;
let autoLockTimer = null;
let lastActivity = Date.now();

const titles = {
  inbox: ['Inbox', 'End-to-end encrypted SMS'],
  send: ['Send SMS', 'Encrypted remote sending'],
  devices: ['Devices', 'SIM nodes and live state'],
  settings: ['Settings', 'Enrollment, vault and notifications'],
};

function b64u(bytes) {
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}
function unb64u(s) {
  s = s.replace(/-/g, '+').replace(/_/g, '/');
  s += '='.repeat((4 - (s.length % 4)) % 4);
  const raw = atob(s); const out = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
  return out;
}
function uuid() { return crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${b64u(crypto.getRandomValues(new Uint8Array(12)))}`; }
function escapeHtml(v='') { return String(v).replace(/[&<>'"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[c])); }
function toast(msg) { const t=$('toast'); t.textContent=msg; t.classList.add('show'); clearTimeout(t._x); t._x=setTimeout(()=>t.classList.remove('show'),2600); }
function fmtTime(ts) { if (!ts) return '—'; return new Intl.DateTimeFormat(undefined,{month:'short',day:'2-digit',hour:'2-digit',minute:'2-digit'}).format(new Date(ts*1000)); }
function deviceName(id) { return devices.find(d=>d.id===id)?.name || 'SIM Node'; }

async function deriveWrapKey(passphrase, salt) {
  const base = await crypto.subtle.importKey('raw', enc.encode(passphrase), 'PBKDF2', false, ['deriveKey']);
  return crypto.subtle.deriveKey({name:'PBKDF2',salt,iterations:PBKDF2_ITER,hash:'SHA-256'}, base, {name:'AES-GCM',length:256}, false, ['encrypt','decrypt']);
}
async function importVault(raw) { vaultRaw = new Uint8Array(raw); vaultKey = await crypto.subtle.importKey('raw', vaultRaw, {name:'AES-GCM'}, false, ['encrypt','decrypt']); resetAutoLock(); }
async function createVault(passphrase) {
  if (passphrase.length < 10) throw new Error('Use a vault passphrase of at least 10 characters.');
  const raw = crypto.getRandomValues(new Uint8Array(32));
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const wrapKey = await deriveWrapKey(passphrase, salt);
  const ct = new Uint8Array(await crypto.subtle.encrypt({name:'AES-GCM',iv,additionalData:enc.encode('simhub-vault-wrap-v1')},wrapKey,raw));
  localStorage.setItem(VAULT_STORE, JSON.stringify({v:1,kdf:'PBKDF2-SHA256',iterations:PBKDF2_ITER,salt:b64u(salt),iv:b64u(iv),ct:b64u(ct)}));
  await importVault(raw);
}
async function unlockVault(passphrase) {
  const obj = JSON.parse(localStorage.getItem(VAULT_STORE) || 'null');
  if (!obj?.ct) throw new Error('No vault exists in this browser. Create one first.');
  const salt=unb64u(obj.salt), iv=unb64u(obj.iv), ct=unb64u(obj.ct);
  const wrapKey=await deriveWrapKey(passphrase,salt);
  try {
    const raw=await crypto.subtle.decrypt({name:'AES-GCM',iv,additionalData:enc.encode('simhub-vault-wrap-v1')},wrapKey,ct);
    await importVault(raw);
  } catch { throw new Error('Vault passphrase is incorrect or the local vault is damaged.'); }
}
async function encryptJson(obj, aad) {
  if (!vaultKey) throw new Error('Vault locked');
  const iv=crypto.getRandomValues(new Uint8Array(12));
  const pt=enc.encode(JSON.stringify(obj));
  const ct=new Uint8Array(await crypto.subtle.encrypt({name:'AES-GCM',iv,additionalData:enc.encode(aad)},vaultKey,pt));
  return {v:1,alg:'A256GCM',iv:b64u(iv),ct:b64u(ct)};
}
async function decryptJson(cipher, aad) {
  const pt=await crypto.subtle.decrypt({name:'AES-GCM',iv:unb64u(cipher.iv),additionalData:enc.encode(aad)},vaultKey,unb64u(cipher.ct));
  return JSON.parse(dec.decode(pt));
}

async function api(path, {method='GET', body=null}={}) {
  const headers = {'Authorization':`Bearer ${adminToken}`};
  const totp=$('totp').value.trim(); if (totp) headers['X-SimHub-TOTP']=totp;
  if (body!==null) headers['Content-Type']='application/json';
  const res=await fetch(path,{method,headers,body:body===null?undefined:JSON.stringify(body),cache:'no-store'});
  let data={}; try{data=await res.json();}catch{}
  if(!res.ok) throw new Error(data.message || data.error || `HTTP ${res.status}`);
  return data;
}

function setConnected(on) { $('relayDot').classList.toggle('ok',on); $('relayText').textContent=on?'Relay connected':'Not connected'; }
function showUnlocked() { $('lockedPanel').hidden=true; $('appContent').hidden=false; $('lockBtn').hidden=false; $('vaultStatus').textContent='Vault unlocked in memory. The relay does not receive this key.'; setConnected(true); }
function lockVault() {
  vaultKey=null; if(vaultRaw) vaultRaw.fill(0); vaultRaw=null; events=[]; decryptedEvents=[]; lastSeq=0;
  clearInterval(pollTimer); pollTimer=null; clearTimeout(autoLockTimer); autoLockTimer=null;
  $('lockedPanel').hidden=false; $('appContent').hidden=true; $('lockBtn').hidden=true; setConnected(false); $('passphrase').value='';
  toast('Vault locked');
}
function resetAutoLock(){ lastActivity=Date.now(); clearTimeout(autoLockTimer); if(vaultKey) autoLockTimer=setTimeout(()=>lockVault(),AUTO_LOCK_MS); }
['pointerdown','keydown','touchstart'].forEach(ev=>document.addEventListener(ev,()=>{if(vaultKey && Date.now()-lastActivity>2000) resetAutoLock();},{passive:true}));

async function connectAndUnlock() {
  adminToken=$('adminToken').value.trim(); if(adminToken.length<32) throw new Error('Admin token is missing or too short.');
  await api('/api/v1/auth/check');
  if($('rememberToken').checked){ localStorage.setItem(TOKEN_STORE,adminToken); sessionStorage.removeItem(TOKEN_STORE); } else { sessionStorage.setItem(TOKEN_STORE,adminToken); localStorage.removeItem(TOKEN_STORE); }
  await unlockVault($('passphrase').value);
  showUnlocked(); await fullRefresh(); startPolling();
}
async function createVaultFlow(){
  const pass=$('passphrase').value; await createVault(pass); $('gateHint').textContent='New vault created locally. Connect with your relay token now.'; toast('Vault created');
}

async function loadDevices(){
  const d=await api('/api/v1/devices'); devices=d.devices||[]; renderDevices(); renderDeviceSelectors();
}
async function loadEvents(){
  if(!vaultKey) return;
  let loops=0;
  while(loops++<10){
    const r=await api(`/api/v1/events?since=${lastSeq}&limit=500`); const batch=r.events||[]; if(!batch.length) break;
    for(const e of batch){
      lastSeq=Math.max(lastSeq,e.seq||0); if(events.some(x=>x.eventId===e.eventId)) continue;
      events.push(e);
      try{ const payload=await decryptJson(e.ciphertext,'simhub-event-v1'); decryptedEvents.push({...e,payload}); maybeNotify({...e,payload}); }
      catch(err){ decryptedEvents.push({...e,payload:null,decryptError:true}); }
    }
    if(batch.length<500) break;
  }
  if(events.length>5000){events=events.slice(-5000);decryptedEvents=decryptedEvents.slice(-5000)}
  renderInbox();
}
async function fullRefresh(){
  try{await loadDevices(); await loadEvents(); setConnected(true);}catch(e){setConnected(false); toast(e.message); throw e;}
}
function startPolling(){ clearInterval(pollTimer); pollTimer=setInterval(()=>{if(vaultKey) fullRefresh().catch(()=>{});},5000); }

function renderDeviceSelectors(){
  const filters=$('deviceFilter'); const send=$('sendDevice');
  const oldF=filters.value, oldS=send.value;
  filters.innerHTML='<option value="">All devices</option>'+devices.map(d=>`<option value="${escapeHtml(d.id)}">${escapeHtml(d.name)}</option>`).join('');
  send.innerHTML=devices.filter(d=>!d.revoked).map(d=>`<option value="${escapeHtml(d.id)}">${escapeHtml(d.name)}</option>`).join('');
  if([...filters.options].some(o=>o.value===oldF))filters.value=oldF;
  if([...send.options].some(o=>o.value===oldS))send.value=oldS;
  updateSubscriptionSelector();
}
function updateSubscriptionSelector(){
  const d=devices.find(x=>x.id===$('sendDevice').value); const subs=d?.state?.subscriptions||[];
  $('sendSubscription').innerHTML=subs.map(s=>`<option value="${escapeHtml(String(s.subscriptionId))}">${escapeHtml(s.alias||s.displayName||s.carrierName||`SIM ${Number(s.slotIndex)+1}`)} · ${escapeHtml(s.carrierName||'')}</option>`).join('') || '<option value="">No active SIM reported</option>';
}
function renderDevices(){
  const box=$('deviceList'); if(!devices.length){box.innerHTML='<div class="empty card">No enrolled devices.</div>';return;}
  box.innerHTML=devices.map(d=>{
    const s=d.state||{}, subs=s.subscriptions||[];
    return `<div class="card device-card"><div class="device-head"><div><h2>${escapeHtml(d.name)}</h2><div class="meta">${escapeHtml(d.model||'Android')} · Android ${escapeHtml(d.osVersion||'?')} · ${escapeHtml(d.group||'ungrouped')}</div></div><span class="status-pill ${d.online?'online':''}">${d.revoked?'revoked':d.online?'online':'offline'}</span></div><div class="device-stats"><div class="stat"><b>${s.batteryPct??'—'}${s.batteryPct!=null?'%':''}</b><span>${s.charging?'charging':'battery'}</span></div><div class="stat"><b>${escapeHtml(s.network||'—')}</b><span>network</span></div><div class="stat"><b>${subs.length}</b><span>active SIMs</span></div></div><div class="sim-list">${subs.map(x=>`<div class="sim"><span>${escapeHtml(x.alias||x.displayName||x.carrierName||'SIM')} ${x.isEmbedded?'· eSIM':''}</span><span class="meta">slot ${Number(x.slotIndex)+1} · signal ${x.signalLevel??'—'}/4 · ${escapeHtml(x.serviceState||'unknown')}</span></div>`).join('')||'<div class="meta">No SIM snapshot yet.</div>'}</div><div class="row wrap" style="margin-top:14px"><button class="ghost mini" data-action="refresh" data-id="${d.id}">Refresh state</button><button class="ghost mini" data-action="sync" data-id="${d.id}">Sync SMS history</button><button class="ghost mini" data-action="diag" data-id="${d.id}">Diagnostics</button><button class="ghost mini" data-action="edit" data-id="${d.id}">Rename/group</button>${!d.revoked?`<button class="danger mini" data-action="revoke" data-id="${d.id}">Revoke</button>`:''}</div><div class="meta" style="margin-top:10px">Last seen ${fmtTime(d.lastSeenAt)}</div></div>`;
  }).join('');
}
function filteredMessages(){
  const q=$('search').value.trim().toLowerCase(), dev=$('deviceFilter').value, kind=$('kindFilter').value;
  return decryptedEvents.filter(e=>{
    if(dev&&e.deviceId!==dev) return false; if(kind&&e.kind!==kind) return false;
    if(!['sms.received','sms.sent','sms.failed','sms.history'].includes(e.kind)) return false;
    if(!q) return true; const p=e.payload||{}; return [p.sender,p.recipient,p.contactName,p.body,p.otp?.value,deviceName(e.deviceId)].filter(Boolean).join(' ').toLowerCase().includes(q);
  }).sort((a,b)=>(b.occurredAt||0)-(a.occurredAt||0));
}
function renderInbox(){
  const list=filteredMessages(), box=$('inboxList'); $('emptyInbox').hidden=!!list.length;
  let otpCount=0;
  box.innerHTML=list.map(e=>{
    const p=e.payload||{}; const inbound=e.kind==='sms.received'||p.direction==='in'; const who=p.contactName||p.sender||p.recipient||'Unknown'; const body=e.decryptError?'[Unable to decrypt with this vault]':(p.body||'');
    if(p.otp?.value && inbound) otpCount++;
    return `<article class="message"><div class="avatar">${escapeHtml((who||'?').trim().slice(0,1).toUpperCase())}</div><div><div class="msg-head"><strong>${escapeHtml(who)}</strong><span class="meta">${inbound?'received':'sent'} · ${escapeHtml(deviceName(e.deviceId))}</span></div><div class="msg-body">${escapeHtml(body)}</div>${p.otp?.value?`<div class="otp"><span>OTP</span><code>${escapeHtml(p.otp.value)}</code><button class="ghost mini copy-otp" data-otp="${escapeHtml(p.otp.value)}">Copy</button></div>`:''}</div><div class="meta">${fmtTime(e.occurredAt)}<br>${escapeHtml(p.subscriptionAlias||e.subscriptionId||'')}</div></article>`;
  }).join('');
  $('otpBadge').hidden=!otpCount; $('otpBadge').textContent=otpCount?String(otpCount):'';
}

async function queueCommand(deviceId,type,payload,ttl=120){
  const issued=Math.floor(Date.now()/1000), commandId=uuid(); const body={v:1,action:type,commandId,issuedAt:issued,expiresAt:issued+ttl,...payload};
  const cipher=await encryptJson(body,'simhub-command-v1');
  return api(`/api/v1/devices/${encodeURIComponent(deviceId)}/commands`,{method:'POST',body:{commandId,idempotencyKey:commandId,type,expiresAt:issued+ttl,ciphertext:cipher}});
}
async function sendSms(){
  const deviceId=$('sendDevice').value, subscriptionId=$('sendSubscription').value, to=$('sendTo').value.trim(), body=$('sendBody').value;
  if(!deviceId||!subscriptionId) throw new Error('Choose an online SIM Node and subscription.');
  if(!/^\+?[0-9*# ()-]{3,40}$/.test(to)) throw new Error('Recipient number format is invalid.');
  if(!body.trim()) throw new Error('Message is empty.');
  await queueCommand(deviceId,'sms.send',{subscriptionId:Number(subscriptionId),to,body},180);
  $('sendBody').value=''; updateCharCount(); toast('Encrypted SMS command queued');
}
async function createEnrollment(){
  if(!vaultRaw) throw new Error('Vault must be unlocked.');
  const name=$('enrollName').value.trim()||'Android SIM Node'; const r=await api('/api/v1/enrollments',{method:'POST',body:{ttlSeconds:600}});
  const server=location.origin; const link=`simhub://enroll?v=1&server=${encodeURIComponent(server)}&token=${encodeURIComponent(r.token)}&key=${encodeURIComponent(b64u(vaultRaw))}&name=${encodeURIComponent(name)}`;
  $('enrollLink').value=link; $('openEnroll').href=link; $('enrollResult').hidden=false; toast('Enrollment link created for 10 minutes');
}
async function copy(text,msg='Copied'){ await navigator.clipboard.writeText(text); toast(msg); }
function updateCharCount(){ const n=$('sendBody').value.length; $('smsCount').textContent=`${n} characters · approx. ${Math.max(1,Math.ceil(n/(n>0&&/^[\x00-\x7F]*$/.test($('sendBody').value)?160:70)))} SMS part(s)`; }
function maybeNotify(e){
  if(e.kind!=='sms.received')return; if(Notification.permission!=='granted'||document.visibilityState==='visible')return;
  const p=e.payload||{}; new Notification('SIM Hub',{body:p.otp?.value?'New OTP received':'New SMS received',icon:'/icon.svg',tag:e.eventId});
}

async function handleDeviceAction(btn){
  const id=btn.dataset.id, action=btn.dataset.action;
  if(action==='refresh'){await queueCommand(id,'device.refresh_state',{});toast('Refresh queued');}
  if(action==='sync'){await queueCommand(id,'sms.sync_history',{maxMessages:5000},600);toast('History sync queued');}
  if(action==='diag'){await queueCommand(id,'diagnostics.request',{});toast('Diagnostics queued');}
  if(action==='edit'){
    const d=devices.find(x=>x.id===id); const name=prompt('Device name',d?.name||''); if(name===null)return; const group=prompt('Group',d?.group||''); if(group===null)return;
    await api(`/api/v1/devices/${id}`,{method:'PATCH',body:{name,group}}); await loadDevices();
  }
  if(action==='revoke'&&confirm('Revoke this device token? Re-enrollment will be required.')){await api(`/api/v1/devices/${id}`,{method:'PATCH',body:{revoke:true}});await loadDevices();toast('Device revoked');}
}

function switchView(name){
  document.querySelectorAll('.nav').forEach(x=>x.classList.toggle('active',x.dataset.view===name)); document.querySelectorAll('.view').forEach(x=>x.classList.toggle('active',x.id===`view-${name}`));
  $('viewTitle').textContent=titles[name][0]; $('viewSubtitle').textContent=titles[name][1]; if(name==='send')updateSubscriptionSelector();
}

function wire(){
  $('adminToken').value=adminToken; $('rememberToken').checked=!!localStorage.getItem(TOKEN_STORE); $('gateHint').textContent=localStorage.getItem(VAULT_STORE)?'Local vault found. Enter its passphrase.':'No local vault found. Create one before enrollment.';
  $('unlockBtn').onclick=()=>connectAndUnlock().catch(e=>toast(e.message)); $('createVaultBtn').onclick=()=>createVaultFlow().catch(e=>toast(e.message)); $('lockBtn').onclick=lockVault; $('refreshBtn').onclick=()=>vaultKey?fullRefresh().catch(e=>toast(e.message)):toast('Vault is locked');
  document.querySelectorAll('.nav').forEach(b=>b.onclick=()=>switchView(b.dataset.view));
  $('search').oninput=renderInbox; $('deviceFilter').onchange=renderInbox; $('kindFilter').onchange=renderInbox; $('sendDevice').onchange=updateSubscriptionSelector; $('sendBody').oninput=updateCharCount;
  $('sendBtn').onclick=()=>sendSms().catch(e=>toast(e.message)); $('enrollBtn').onclick=()=>createEnrollment().catch(e=>toast(e.message)); $('copyEnroll').onclick=()=>copy($('enrollLink').value,'Enrollment link copied').catch(e=>toast(e.message));
  $('exportKeyBtn').onclick=()=>vaultRaw?copy(`SIMHUB-RECOVERY-V1:${b64u(vaultRaw)}`,'Recovery key copied').catch(e=>toast(e.message)):toast('Vault locked');
  $('notifyBtn').onclick=async()=>{const p=await Notification.requestPermission();toast(p==='granted'?'Browser notifications enabled':'Notification permission not granted');};
  $('forgetBtn').onclick=()=>{if(confirm('Forget admin token and local wrapped vault from this browser?')){localStorage.removeItem(TOKEN_STORE);sessionStorage.removeItem(TOKEN_STORE);localStorage.removeItem(VAULT_STORE);lockVault();$('adminToken').value='';toast('Browser credentials forgotten');}};
  $('deviceList').onclick=e=>{const b=e.target.closest('button[data-action]');if(b)handleDeviceAction(b).catch(err=>toast(err.message));};
  $('inboxList').onclick=e=>{const b=e.target.closest('.copy-otp');if(b)copy(b.dataset.otp,'OTP copied').catch(err=>toast(err.message));};
  if('serviceWorker'in navigator) navigator.serviceWorker.register('/sw.js').catch(()=>{});
}
wire();
