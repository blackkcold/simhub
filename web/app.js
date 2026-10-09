import {tr, applyI18n, getLocale, setLocale, localizeMessage} from './i18n.js';
const $ = (id) => document.getElementById(id);
const enc = new TextEncoder();
const dec = new TextDecoder();
const VAULT_STORE = 'simhub_vault_v1';
const PBKDF2_ITER = 310000;
const DEFAULT_SESSION_IDLE_MS = 8 * 60 * 60 * 1000;
const SESSION_VAULT_CACHE = 'simhub_session_vault_v1';
const PHONE_OVERRIDES_STORE = 'simhub_phone_overrides_v1';
let phoneOverrides={};
let sessionIdleMs = DEFAULT_SESSION_IDLE_MS;

let vaultKey=null,vaultRaw=null,devices=[],events=[],decryptedEvents=[],lastSeq=0,oldestSeq=0,pollTimer=null,eventSource=null,autoLockTimer=null,refreshTimer=null,lastActivity=Date.now();
let csrfToken='',stepUpUntil=0,secondFactorIsTotp=true,activeStepUp=null,enrolling=false,initialEventsLoaded=false,passkeyCount=0;
let olderCursor=null,historyHasMore=true,visibleOffset=0,activeConversationKey=null,diagnosticsDeviceId=null;
const expandedDeviceDetails=new Set();
const eventIds=new Set();
let securityEpoch=0;
let eventDataGeneration=0;
const vaultLockChannel=typeof BroadcastChannel==='function'?new BroadcastChannel('simhub-vault-lock-v1'):null;
const deviceKeyCache=new Map();
const titleKeys={inbox:['title_inbox','subtitle_inbox'],send:['title_send','subtitle_send'],devices:['title_devices','subtitle_devices'],settings:['title_settings','subtitle_settings']};

function b64u(bytes){let s='';for(let i=0;i<bytes.length;i+=0x8000)s+=String.fromCharCode(...bytes.subarray(i,i+0x8000));return btoa(s).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/g,'');}
function unb64u(s){s=s.replace(/-/g,'+').replace(/_/g,'/');s+='='.repeat((4-(s.length%4))%4);const raw=atob(s),out=new Uint8Array(raw.length);for(let i=0;i<raw.length;i++)out[i]=raw.charCodeAt(i);return out;}
function field(v){return b64u(enc.encode(String(v==null?'':v)));}
function eventAad(deviceId,eventId,kind,occurredAt,subscriptionId,hasOtp){return 'simhub-event-v2|'+field(deviceId)+'|'+field(eventId)+'|'+field(kind)+'|'+occurredAt+'|'+field(subscriptionId)+'|'+(hasOtp?'1':'0');}
function commandAad(deviceId,commandId,type,createdAt,expiresAt,idempotencyKey){return 'simhub-command-v2|'+field(deviceId)+'|'+field(commandId)+'|'+field(type)+'|'+createdAt+'|'+expiresAt+'|'+field(idempotencyKey);}
function uuid(){return crypto.randomUUID?crypto.randomUUID():String(Date.now())+'-'+b64u(crypto.getRandomValues(new Uint8Array(12)));}
function escapeHtml(v){return String(v==null?'':v).replace(/[&<>'"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[c]));}
function toast(msg){const t=$('toast');t.textContent=localizeMessage(msg);t.classList.add('show');clearTimeout(t._x);t._x=setTimeout(()=>t.classList.remove('show'),2600);}
function fmtTime(ts){if(!ts)return '—';return new Intl.DateTimeFormat(getLocale()==='zh-CN'?'zh-CN':'en',{month:'short',day:'2-digit',hour:'2-digit',minute:'2-digit'}).format(new Date(ts*1000));}
function deviceName(id){const d=devices.find(x=>x.id===id);return d?d.name:tr('sim_node');}

// Temporary browser-tab vault recovery: an encrypted blob in sessionStorage and an
// origin-bound, non-extractable WebCrypto key in IndexedDB. No raw key or admin token
// is written to persistent string storage. XSS/compromised browsers remain a threat.
async function tabVaultKey(){
  const db=await new Promise((resolve,reject)=>{const req=indexedDB.open('simhub_session_crypto_v1',1);req.onupgradeneeded=()=>req.result.createObjectStore('keys');req.onsuccess=()=>resolve(req.result);req.onerror=()=>reject(req.error);});
  try{
    const existing=await new Promise((resolve,reject)=>{const tx=db.transaction('keys','readonly'),q=tx.objectStore('keys').get('vault');q.onsuccess=()=>resolve(q.result);q.onerror=()=>reject(q.error);});
    if(existing)return existing;
    const key=await crypto.subtle.generateKey({name:'AES-GCM',length:256},false,['encrypt','decrypt']);
    await new Promise((resolve,reject)=>{const tx=db.transaction('keys','readwrite');tx.objectStore('keys').put(key,'vault');tx.oncomplete=resolve;tx.onerror=()=>reject(tx.error);});
    return key;
  }finally{db.close();}
}
function clearTabVault(){try{sessionStorage.removeItem(SESSION_VAULT_CACHE);}catch{}}
async function cacheTabVault(){
  if(!vaultRaw||!vaultKey)return;
  const snapshot=vaultKey,raw=new Uint8Array(vaultRaw),iv=crypto.getRandomValues(new Uint8Array(12));
  try{
    const key=await tabVaultKey();
    const ct=await crypto.subtle.encrypt({name:'AES-GCM',iv,additionalData:enc.encode('simhub-session-tab-v1')},key,raw);
    if(snapshot===vaultKey)sessionStorage.setItem(SESSION_VAULT_CACHE,JSON.stringify({v:1,iv:b64u(iv),ct:b64u(new Uint8Array(ct)),lastActivity}));
  }catch(e){console.warn('Session vault resume unavailable',e.name);}
  finally{raw.fill(0);}
}
async function restoreTabVault(){
  let saved;
  try{saved=JSON.parse(sessionStorage.getItem(SESSION_VAULT_CACHE)||'null');}catch{}
  if(!saved||saved.v!==1||!Number.isFinite(saved.lastActivity)||Date.now()-saved.lastActivity>=sessionIdleMs||saved.lastActivity>Date.now()+60000){clearTabVault();return false;}
  try{
    const key=await tabVaultKey();
    const raw=await crypto.subtle.decrypt({name:'AES-GCM',iv:unb64u(saved.iv),additionalData:enc.encode('simhub-session-tab-v1')},key,unb64u(saved.ct));
    if(raw.byteLength!==32)throw new Error('Invalid vault key length');
    await importVault(raw);
    lastActivity=saved.lastActivity;try{const restored=JSON.parse(sessionStorage.getItem(SESSION_VAULT_CACHE));restored.lastActivity=lastActivity;sessionStorage.setItem(SESSION_VAULT_CACHE,JSON.stringify(restored));}catch{}armAutoLock();
    await loadPhoneOverrides();showUnlocked();await fullRefresh();refreshVersionInfo().catch(()=>{});startRealtime();
    return true;
  }catch(e){clearTabVault();if(vaultKey)lockVault();return false;}
}
async function resumeExistingSession(){
  try{await api('/api/v1/auth/check');await restoreTabVault();}catch{clearTabVault();}
}

async function loadPhoneOverrides(){
  phoneOverrides={};
  if(!vaultKey)return;
  try{
    const saved=JSON.parse(localStorage.getItem(PHONE_OVERRIDES_STORE)||'null');if(!saved)return;
    const clear=await crypto.subtle.decrypt({name:'AES-GCM',iv:unb64u(saved.iv),additionalData:enc.encode('simhub-phone-overrides-v1')},vaultKey,unb64u(saved.ct));
    const parsed=JSON.parse(dec.decode(clear));if(parsed&&typeof parsed==='object'&&!Array.isArray(parsed))phoneOverrides=parsed;
  }catch{phoneOverrides={};}
}
async function savePhoneOverrides(){
  if(!vaultKey)throw Error('Vault locked');
  const iv=crypto.getRandomValues(new Uint8Array(12));
  const ct=await crypto.subtle.encrypt({name:'AES-GCM',iv,additionalData:enc.encode('simhub-phone-overrides-v1')},vaultKey,enc.encode(JSON.stringify(phoneOverrides)));
  localStorage.setItem(PHONE_OVERRIDES_STORE,JSON.stringify({v:1,iv:b64u(iv),ct:b64u(new Uint8Array(ct))}));
}
function phoneOverrideKey(device,ch){return device.id+'|'+ch.id+'|'+String(ch.revision||ch.channelRevision||1);}
async function editSimPhone(deviceId,channelId){
  const d=devices.find(x=>x.id===deviceId),ch=nodeChannels(d).find(x=>String(x.id)===channelId);
  if(!d||!ch)throw Error('SIM 通道不存在');
  const input=prompt('设置 SIM 电话号码（仅当前浏览器加密保存；留空清除覆盖值）',ch.phoneNumber||'');
  if(input===null)return;
  const number=input.trim();
  if(number&&!/^\+?[0-9 ()-]{5,24}$/.test(number))throw Error('电话号码格式无效');
  const key=phoneOverrideKey(d,ch);
  if(number)phoneOverrides[key]=number;else delete phoneOverrides[key];
  await savePhoneOverrides();renderDevices();renderDeviceSelectors();renderInbox();updateReplyChannels();
}
async function deriveWrapKey(passphrase,salt){const base=await crypto.subtle.importKey('raw',enc.encode(passphrase),'PBKDF2',false,['deriveKey']);return crypto.subtle.deriveKey({name:'PBKDF2',salt:salt,iterations:PBKDF2_ITER,hash:'SHA-256'},base,{name:'AES-GCM',length:256},false,['encrypt','decrypt']);}
async function wrapVaultRaw(raw,passphrase){if(passphrase.length<10)throw new Error('Use a vault passphrase of at least 10 characters.');const salt=crypto.getRandomValues(new Uint8Array(16)),iv=crypto.getRandomValues(new Uint8Array(12)),wrapKey=await deriveWrapKey(passphrase,salt);const ct=new Uint8Array(await crypto.subtle.encrypt({name:'AES-GCM',iv:iv,additionalData:enc.encode('simhub-vault-wrap-v1')},wrapKey,raw));localStorage.setItem(VAULT_STORE,JSON.stringify({v:1,kdf:'PBKDF2-SHA256',iterations:PBKDF2_ITER,salt:b64u(salt),iv:b64u(iv),ct:b64u(ct)}));}
async function importVault(raw){vaultRaw=new Uint8Array(raw);vaultKey=await crypto.subtle.importKey('raw',vaultRaw,{name:'AES-GCM'},false,['encrypt','decrypt']);deviceKeyCache.clear();resetAutoLock();await cacheTabVault();}
async function createVault(passphrase){const raw=crypto.getRandomValues(new Uint8Array(32));await wrapVaultRaw(raw,passphrase);await importVault(raw);}
async function importRecoveryFlow(){const text=$('recoveryKey').value.trim(),prefix='SIMHUB-RECOVERY-V1:';if(!text.startsWith(prefix))throw new Error('Recovery key format is invalid.');const raw=unb64u(text.slice(prefix.length));if(raw.length!==32)throw new Error('Recovery key length is invalid.');await wrapVaultRaw(raw,$('passphrase').value);await importVault(raw);$('recoveryKey').value='';toast('Recovery key imported and wrapped locally');}
async function unlockVault(passphrase){const obj=JSON.parse(localStorage.getItem(VAULT_STORE)||'null');if(!obj||!obj.ct)throw new Error('No vault exists in this browser. Create or import one first.');const wrapKey=await deriveWrapKey(passphrase,unb64u(obj.salt));try{const raw=await crypto.subtle.decrypt({name:'AES-GCM',iv:unb64u(obj.iv),additionalData:enc.encode('simhub-vault-wrap-v1')},wrapKey,unb64u(obj.ct));await importVault(raw);}catch(e){throw new Error('Vault passphrase is incorrect or the local vault is damaged.');}}
async function keyIdForRaw(raw){const digest=new Uint8Array(await crypto.subtle.digest('SHA-256',raw));return b64u(digest.slice(0,12));}
async function deriveLegacyDevice(deviceId){if(!vaultRaw)throw new Error('Vault locked');const ikm=await crypto.subtle.importKey('raw',vaultRaw,'HKDF',false,['deriveBits']);const bits=await crypto.subtle.deriveBits({name:'HKDF',hash:'SHA-256',salt:enc.encode('simhub-device-v1'),info:enc.encode(deviceId)},ikm,256);const raw=new Uint8Array(bits),key=await crypto.subtle.importKey('raw',raw,{name:'AES-GCM'},false,['encrypt','decrypt']);return {raw:raw,key:key,kid:await keyIdForRaw(raw),mode:'legacy'};}
async function wrapNodeKey(raw,kid){const iv=crypto.getRandomValues(new Uint8Array(12)),aad=enc.encode('simhub-node-key-wrap-v1|'+kid),ct=new Uint8Array(await crypto.subtle.encrypt({name:'AES-GCM',iv:iv,additionalData:aad},vaultKey,raw));return {v:1,alg:'A256GCM',iv:b64u(iv),ct:b64u(ct)};}
async function unwrapNodeKey(envelope,kid){const raw=new Uint8Array(await crypto.subtle.decrypt({name:'AES-GCM',iv:unb64u(envelope.iv),additionalData:enc.encode('simhub-node-key-wrap-v1|'+kid)},vaultKey,unb64u(envelope.ct)));if(await keyIdForRaw(raw)!==kid)throw new Error('Wrapped node key does not match key id');return raw;}
async function encryptBootstrapNodeKey(nodeRaw,bootstrapRaw,kid){
  const key=await crypto.subtle.importKey('raw',bootstrapRaw,{name:'AES-GCM'},false,['encrypt']);
  const iv=crypto.getRandomValues(new Uint8Array(12)),aad=enc.encode('simhub-bootstrap-node-key-v1|'+kid);
  const ct=new Uint8Array(await crypto.subtle.encrypt({name:'AES-GCM',iv:iv,additionalData:aad},key,nodeRaw));
  return {v:1,alg:'A256GCM',iv:b64u(iv),ct:b64u(ct)};
}
async function deviceCrypto(deviceId){if(deviceKeyCache.has(deviceId))return deviceKeyCache.get(deviceId);const info=devices.find(x=>x.id===deviceId);let out;if(info&&info.keyId&&info.wrappedKey){const raw=await unwrapNodeKey(info.wrappedKey,info.keyId),key=await crypto.subtle.importKey('raw',raw,{name:'AES-GCM'},false,['encrypt','decrypt']);out={raw:raw,key:key,kid:info.keyId,mode:'node'};}else out=await deriveLegacyDevice(deviceId);deviceKeyCache.set(deviceId,out);return out;}
async function decryptEvent(e){const cipher=e.ciphertext;if(cipher&&cipher.v===1){const pt=await crypto.subtle.decrypt({name:'AES-GCM',iv:unb64u(cipher.iv),additionalData:enc.encode('simhub-event-v1')},vaultKey,unb64u(cipher.ct));return JSON.parse(dec.decode(pt));}if(!cipher||cipher.v!==2)throw new Error('Unsupported ciphertext version');let d=await deviceCrypto(e.deviceId);if(cipher.kid!==d.kid){const info=devices.find(x=>x.id===e.deviceId);if(info&&info.pendingKeyId===cipher.kid&&info.pendingWrappedKey){const raw=await unwrapNodeKey(info.pendingWrappedKey,info.pendingKeyId),key=await crypto.subtle.importKey('raw',raw,{name:'AES-GCM'},false,['decrypt']);d={raw:raw,key:key,kid:info.pendingKeyId,mode:'pending-node'};}else{const hist=(info?.historicalWrappedKeys||[]).find(k=>k.keyId===cipher.kid);if(hist){const raw=await unwrapNodeKey(hist.wrappedKey,hist.keyId),key=await crypto.subtle.importKey('raw',raw,{name:'AES-GCM'},false,['decrypt']);d={raw,key,kid:hist.keyId,mode:'retired-node'};}else{const legacy=await deriveLegacyDevice(e.deviceId);if(cipher.kid!==legacy.kid)throw new Error('Node key mismatch');d=legacy;}}}const aad=eventAad(e.deviceId,e.eventId,e.kind,e.occurredAt,e.subscriptionId,e.hasOtp),pt=await crypto.subtle.decrypt({name:'AES-GCM',iv:unb64u(cipher.iv),additionalData:enc.encode(aad)},d.key,unb64u(cipher.ct));return JSON.parse(dec.decode(pt));}
function versionAtLeast(v,target){const a=String(v||'0').split('.').map(Number),b=String(target).split('.').map(Number);for(let i=0;i<Math.max(a.length,b.length);i++){const x=a[i]||0,y=b[i]||0;if(x!==y)return x>y;}return true;}
async function encryptCommand(deviceId,outer,payload){const info=devices.find(x=>x.id===deviceId);if(!versionAtLeast(info&&info.appVersion,'0.1.5')){const iv=crypto.getRandomValues(new Uint8Array(12)),ct=new Uint8Array(await crypto.subtle.encrypt({name:'AES-GCM',iv:iv,additionalData:enc.encode('simhub-command-v1')},vaultKey,enc.encode(JSON.stringify(payload))));return {v:1,alg:'A256GCM',iv:b64u(iv),ct:b64u(ct)};}const d=await deviceCrypto(deviceId),iv=crypto.getRandomValues(new Uint8Array(12)),aad=commandAad(deviceId,outer.commandId,outer.type,outer.createdAt,outer.expiresAt,outer.idempotencyKey),ct=new Uint8Array(await crypto.subtle.encrypt({name:'AES-GCM',iv:iv,additionalData:enc.encode(aad)},d.key,enc.encode(JSON.stringify(payload))));return {v:2,alg:'A256GCM',kid:d.kid,iv:b64u(iv),ct:b64u(ct)};}

async function api(path,opts){opts=opts||{};const method=opts.method||'GET',body=Object.prototype.hasOwnProperty.call(opts,'body')?opts.body:null,headers={};if(body!==null)headers['Content-Type']='application/json';if(vaultKey&&Date.now()-lastActivity<60000)headers['X-SimHub-Activity']='1';if(!['GET','HEAD'].includes(method)&&path!=='/api/v1/auth/session'&&csrfToken)headers['X-SimHub-CSRF']=csrfToken;const res=await fetch(path,{method:method,headers:headers,body:body===null?undefined:JSON.stringify(body),cache:'no-store',credentials:'same-origin'});let data={};try{data=await res.json();}catch(e){}if(!res.ok){if(res.status===401&&vaultKey&&path!=='/api/v1/auth/check')lockVault();throw new Error(data.message||data.error||('HTTP '+res.status));}if(data.csrfToken)csrfToken=data.csrfToken;if(typeof data.totpRequired==='boolean')secondFactorIsTotp=data.totpRequired;if(typeof data.passkeyCount==='number')passkeyCount=data.passkeyCount;if(typeof data.sessionIdleTtlSeconds==='number')sessionIdleMs=Math.max(60000,data.sessionIdleTtlSeconds*1000);if(data.username&&$('username'))$('username').value=data.username;return data;}
async function establishSession(){try{return await api('/api/v1/auth/check');}catch(e){}const adminToken=$('adminToken').value.trim(),totp=$('totp').value.trim(),username=$('username').value.trim();if(adminToken.length<32)throw new Error('Admin token is required for a new session.');const data=await api('/api/v1/auth/session',{method:'POST',body:{username:username,adminToken:adminToken,totp:totp}});$('adminToken').value='';$('totp').value='';return data;}
async function logoutSession(){try{await api('/api/v1/auth/logout',{method:'POST',body:{logout:true}});}catch(e){}csrfToken='';stepUpUntil=0;clearTabVault();}
async function ensureStepUp(){
  if(Date.now()<stepUpUntil-5000)return;
  const label=secondFactorIsTotp?'请输入当前 6 位 TOTP 验证码以确认敏感操作：':'开发模式：请再次输入管理员 Token：';
  if(activeStepUp)return activeStepUp;
  activeStepUp=(async()=>{
    if(passkeyCount>0&&window.PublicKeyCredential){
      try{await passkeyElevate();return;}catch(err){toast('通行密钥未完成，使用 TOTP 或恢复凭据验证');}
    }
    const value=await askStepUp(label);
    const body=secondFactorIsTotp?{totp:value.trim()}:{adminToken:value.trim()};
    const result=await api('/api/v1/auth/elevate',{method:'POST',body});
    stepUpUntil=result.elevatedUntil*1000;
  })();
  try{await activeStepUp;}finally{activeStepUp=null;}
}

function askStepUp(label){
  return new Promise((resolve,reject)=>{
    const dialog=$('stepupDialog'),form=$('stepupForm'),input=$('stepupValue'),cancel=$('stepupCancel');
    $('stepupPrompt').textContent=label;
    input.value='';input.type=secondFactorIsTotp?'text':'password';
    input.autocomplete=secondFactorIsTotp?'one-time-code':'off';
    const finish=(value)=>{form.removeEventListener('submit',submit);cancel.removeEventListener('click',dismiss);dialog.removeEventListener('cancel',dismiss);dialog.close();if(value)resolve(value);else reject(new Error('已取消二次验证'));};
    const submit=e=>{e.preventDefault();finish(input.value.trim());};
    const dismiss=e=>{e.preventDefault();finish('');};
    form.addEventListener('submit',submit);cancel.addEventListener('click',dismiss);dialog.addEventListener('cancel',dismiss);
    dialog.showModal();input.focus();
  });
}
// Credential serialization works in Safari as well as Chromium (toJSON is optional).
function credentialJson(credential){
  if(typeof credential.toJSON==='function')return credential.toJSON();
  const response=credential.response;
  const to64=x=>x==null?null:b64u(new Uint8Array(x));
  if(response.attestationObject){
    return {id:credential.id,rawId:to64(credential.rawId),type:'public-key',
      response:{clientDataJSON:to64(response.clientDataJSON),
        attestationObject:to64(response.attestationObject),
        transports:response.getTransports?response.getTransports():[]}};
  }
  return {id:credential.id,rawId:to64(credential.rawId),type:'public-key',
    response:{clientDataJSON:to64(response.clientDataJSON),
      authenticatorData:to64(response.authenticatorData),
      signature:to64(response.signature),userHandle:to64(response.userHandle)}};
}
function publicKeyOptions(options){
  const o=Object.assign({},options,{challenge:unb64u(options.challenge)});
  if(o.user)o.user=Object.assign({},o.user,{id:unb64u(o.user.id)});
  if(o.excludeCredentials)o.excludeCredentials=o.excludeCredentials.map(x=>({...x,id:unb64u(x.id)}));
  if(o.allowCredentials)o.allowCredentials=o.allowCredentials.map(x=>({...x,id:unb64u(x.id)}));
  return o;
}
async function passkeyAuthenticate(mode){
  if(!window.PublicKeyCredential||!navigator.credentials)throw new Error('当前浏览器不支持通行密钥');
  const login=mode==='login',url='/api/v1/auth/passkeys/'+mode;
  const start=await api(url+'/options',{method:'POST',body:{username:$('username').value.trim()}});
  const credential=await navigator.credentials.get({publicKey:publicKeyOptions(start.options)});
  if(!credential)throw new Error('通行密钥验证已取消');
  return api(url+'/verify',{method:'POST',body:{
    username:$('username').value.trim(),challengeId:start.challengeId,credential:credentialJson(credential)}});
}
async function passkeyElevate(){
  const result=await passkeyAuthenticate('elevate');
  stepUpUntil=result.elevatedUntil*1000;
}
async function passkeyLogin(){
  await passkeyAuthenticate('login');
  await api('/api/v1/auth/check');
  toast('通行密钥登录成功，请继续解锁本地 Vault');
  if($('passphrase').value){await connectAndUnlock();}
  else $('passphrase').focus();
}
async function refreshPasskeys(){
  const result=await api('/api/v1/auth/passkeys');
  passkeyCount=result.passkeys.length;
  $('passkeysList').innerHTML=result.passkeys.map(item=>
    '<div class="passkey-row"><span><strong>'+escapeHtml(item.label)+
    '</strong><small>'+escapeHtml(fmtTime(item.lastUsedAt||item.createdAt))+
    '</small></span><button class="danger mini" data-passkey-id="'+escapeHtml(item.id)+'" type="button">移除</button></div>'
  ).join('') || '<p class="hint">尚未注册通行密钥；建议至少注册两把。</p>';
}
async function registerPasskey(){
  if(!window.PublicKeyCredential||!navigator.credentials)throw new Error('当前浏览器不支持通行密钥');
  await ensureStepUp();
  const label=$('passkeyLabel').value.trim()||'SIM Hub Passkey';
  const start=await api('/api/v1/auth/passkeys/register/options',{method:'POST',body:{}});
  const credential=await navigator.credentials.create({publicKey:publicKeyOptions(start.options)});
  if(!credential)throw new Error('通行密钥注册已取消');
  await api('/api/v1/auth/passkeys/register/verify',{method:'POST',body:{
    challengeId:start.challengeId,credential:credentialJson(credential),label}});
  $('passkeyLabel').value='';
  await refreshPasskeys();
  toast('通行密钥已添加');
}
async function removePasskey(id){
  if(!confirm('确定移除此通行密钥？请确认仍有其他登录及恢复途径。'))return;
  await ensureStepUp();
  await api('/api/v1/auth/passkeys/remove',{method:'POST',body:{id}});
  await refreshPasskeys();
  toast('通行密钥已移除');
}
function setConnected(on){$('relayDot').classList.toggle('ok',on);$('relayText').textContent=tr(on?'relay_connected':'relay_disconnected');}
function showUnlocked(){$('loggedInUser').hidden=false;$('loggedInUser').textContent=$('username').value.trim();$('newSmsBtn').hidden=false;refreshPasskeys().catch(()=>{});$('lockedPanel').hidden=true;$('appContent').hidden=false;$('lockBtn').hidden=false;$('vaultStatus').textContent=tr('vault_unlocked');setConnected(true);}
function purgeSensitiveUI(){
  // Hidden DOM is still observable to local browser extensions and scripts.
  // Wipe all decrypted data and one-time credentials, not merely app arrays.
  for(const id of ['inboxList','conversationMessages','deviceList','diagnosticsOutput','enrollLink','replyTo','replyBody','sendTo','sendBody','recoveryKey','adminToken','totp','passphrase','stepupValue','enrollName','search','passkeysList']){
    const el=$(id);if(!el)continue;
    if('value' in el)el.value='';
    if(id==='diagnosticsOutput')el.textContent='';
    else if(!('value' in el))el.replaceChildren();
  }
  for(const id of ['deviceFilter','sendDevice','sendSubscription','replyDevice','replySubscription']){
    const el=$(id);if(el)el.replaceChildren();
  }
  const link=$('openEnroll');if(link){link.removeAttribute('href');link.hidden=true;}
  expandedDeviceDetails.clear();
  const enroll=$('enrollResult');if(enroll)enroll.hidden=true;
  const dialog=$('diagnosticsDialog');if(dialog?.open)dialog.close();
  const stepup=$('stepupDialog');if(stepup?.open){stepup.dispatchEvent(new Event('cancel',{cancelable:true}));if(stepup.open)stepup.close();}
  const operations=$('commandActivity');if(operations)operations.replaceChildren();
  const lifecycle=$('lifecycleHistory');if(lifecycle)lifecycle.replaceChildren();
  const badge=$('otpBadge');if(badge){badge.textContent='';badge.hidden=true;}
}
function lockVault(broadcast=true){
  securityEpoch++;
  phoneOverrides={};clearTabVault();activeConversationKey=null;diagnosticsDeviceId=null;
  $('smsLayout').classList.remove('conversation-open');
  purgeSensitiveUI();
  $('loggedInUser').hidden=true;$('newSmsBtn').hidden=true;stepUpUntil=0;
  vaultKey=null;if(vaultRaw)vaultRaw.fill(0);vaultRaw=null;deviceKeyCache.clear();
  devices=[];events=[];decryptedEvents=[];eventIds.clear();lastSeq=0;oldestSeq=0;
  initialEventsLoaded=false;olderCursor=null;historyHasMore=true;visibleOffset=0;
  clearInterval(pollTimer);pollTimer=null;clearTimeout(autoLockTimer);autoLockTimer=null;
  clearTimeout(refreshTimer);refreshTimer=null;
  if(eventSource){eventSource.close();eventSource=null;}
  $('lockedPanel').hidden=false;$('appContent').hidden=true;$('lockBtn').hidden=true;
  $('vaultStatus').textContent=tr('vault_locked');setConnected(false);
  if(broadcast)try{vaultLockChannel?.postMessage({action:'lock'});}catch{}
  toast('Vault locked');
}
if(vaultLockChannel)vaultLockChannel.onmessage=e=>{if(e.data?.action==='lock'&&vaultKey)lockVault(false);};

function armAutoLock(){clearTimeout(autoLockTimer);if(!vaultKey)return;const remaining=Math.max(0,sessionIdleMs-(Date.now()-lastActivity));autoLockTimer=setTimeout(()=>enforceAutoLock(),remaining);}
function resetAutoLock(){lastActivity=Date.now();try{const s=JSON.parse(sessionStorage.getItem(SESSION_VAULT_CACHE)||'null');if(s){s.lastActivity=lastActivity;sessionStorage.setItem(SESSION_VAULT_CACHE,JSON.stringify(s));}}catch{}armAutoLock();}
function enforceAutoLock(){if(!vaultKey)return false;if(Date.now()-lastActivity>=sessionIdleMs){lockVault();return true;}armAutoLock();return false;}
function noteActivity(){if(!vaultKey)return;if(enforceAutoLock())return;if(Date.now()-lastActivity>2000)resetAutoLock();}
['pointerdown','keydown','touchstart'].forEach(ev=>document.addEventListener(ev,noteActivity,{passive:true}));
document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='visible')enforceAutoLock();});
window.addEventListener('focus',enforceAutoLock);
window.addEventListener('pageshow',enforceAutoLock);

async function connectAndUnlock(){await establishSession();await unlockVault($('passphrase').value);await loadPhoneOverrides();showUnlocked();await fullRefresh();refreshVersionInfo().catch(()=>{});startRealtime();}
async function createVaultFlow(){await createVault($('passphrase').value);$('gateHint').textContent=tr('vault_created_hint');toast(tr('vault_created'));}

async function loadDevices(){
  const epoch=securityEpoch;
  const d=await api('/api/v1/devices');if(epoch!==securityEpoch||!vaultKey)return;
  const next=d.devices||[];
  const previousSms=new Map(devices.map(x=>[x.id,x.smsEpoch||0]));
  if(devices.length && (devices.some(x=>!next.some(n=>n.id===x.id)) ||
      next.some(x=>previousSms.has(x.id) && previousSms.get(x.id)!==(x.smsEpoch||0)))){
    resetEventCache();
  }
  const oldKeys=new Map(devices.map(x=>[x.id,[x.keyId,x.wrappedKey,x.pendingKeyId,x.historicalWrappedKeys].map(v=>JSON.stringify(v||'')).join('|')]));
  for(const node of next)if(oldKeys.get(node.id)!==[node.keyId,node.wrappedKey,node.pendingKeyId,node.historicalWrappedKeys].map(v=>JSON.stringify(v||'')).join('|'))deviceKeyCache.delete(node.id);
  devices=next;
  // Decrypt SIM numbers locally; the Relay only stores an opaque Node-Key envelope.
  for(const d of devices){
    if(epoch!==securityEpoch||!vaultKey)return;
    const n=d.state?.encryptedSimNumbers;
    if(!n||!vaultKey)continue;
    try{
      const event={deviceId:d.id,eventId:n.eventId,kind:'device.sim_inventory',occurredAt:n.occurredAt,subscriptionId:'-1',hasOtp:false,ciphertext:n.ciphertext};
      const clear=await decryptEvent(event);
      if(epoch!==securityEpoch||!vaultKey)return;
      d.state._phoneNumbers=Array.isArray(clear.numbers)?clear.numbers:[];
    }catch(err){console.warn('SIM number inventory unavailable',d.id,err.name);}
  }
  if(epoch!==securityEpoch||!vaultKey)return;
  renderDevices();renderDeviceSelectors();updateReplyDevices();renderInbox();
}
async function ingestEvents(batch,notify=true){
  const epoch=securityEpoch, dataGeneration=eventDataGeneration;
  let changed=false;
  for(const e of batch){
    if(epoch!==securityEpoch||dataGeneration!==eventDataGeneration||!vaultKey)return false;
    lastSeq=Math.max(lastSeq,e.seq||0);
    const id=e.deviceId+':'+e.eventId;
    if(eventIds.has(id))continue;
    const boundary=decryptedEvents.length>=30?Math.min(...decryptedEvents.map(x=>x.occurredAt||0)):0;
    if(notify&&boundary&&e.occurredAt<boundary)continue; // older sync data stays accessible via historical pagination
    events.push(e);eventIds.add(id);changed=true;
    try{
      const payload=await decryptEvent(e),row=Object.assign({},e,{payload});
      if(epoch!==securityEpoch||!vaultKey)return false;
      decryptedEvents.push(row);if(notify)maybeNotify(row);
    }catch(err){if(epoch!==securityEpoch||!vaultKey)return false;decryptedEvents.push(Object.assign({},e,{payload:null,decryptError:true,decryptReason:err.message}));}
  }
  return changed;
}
async function loadEvents(){
  if(!vaultKey)return;
  const epoch=securityEpoch, dataGeneration=eventDataGeneration;
  if(!initialEventsLoaded){
    const head=await api('/api/v1/events?latest=1&limit=1');if(epoch!==securityEpoch||dataGeneration!==eventDataGeneration||!vaultKey)return;
    lastSeq=head.events?.[0]?.seq||0;
    const first=await api('/api/v1/events?order=occurred&limit=30');if(epoch!==securityEpoch||dataGeneration!==eventDataGeneration||!vaultKey)return;
    olderCursor=first.nextBeforeTime?{time:first.nextBeforeTime,seq:first.nextBeforeSeq}:null;
    historyHasMore=!!first.hasMore;
    await ingestEvents(first.events||[],false);
    if(epoch!==securityEpoch||!vaultKey)return;
    initialEventsLoaded=true;renderInbox();return;
  }
  let changed=false,loops=0;
  while(loops++<3){
    const r=await api('/api/v1/events?since='+lastSeq+'&limit=100');if(epoch!==securityEpoch||dataGeneration!==eventDataGeneration||!vaultKey)return;
    const batch=r.events||[];
    if(!batch.length)break;
    changed=await ingestEvents(batch)||changed;
    if(batch.length<100)break;
  }
  if(changed&&epoch===securityEpoch&&vaultKey)renderInbox();
}
async function loadOlder(){
  if(!vaultKey)return;
  const epoch=securityEpoch, dataGeneration=eventDataGeneration;
  const count=buildThreads(filteredEvents()).length;
  if(visibleOffset+30<count){visibleOffset+=30;renderInbox();return;}
  if(!historyHasMore||!olderCursor)return;
  const r=await api('/api/v1/events?order=occurred&limit=30&beforeTime='+olderCursor.time+'&beforeSeq='+olderCursor.seq);
  if(epoch!==securityEpoch||dataGeneration!==eventDataGeneration||!vaultKey)return;
  olderCursor=r.nextBeforeTime?{time:r.nextBeforeTime,seq:r.nextBeforeSeq}:null;
  historyHasMore=!!r.hasMore;
  await ingestEvents(r.events||[],false);
  if(epoch!==securityEpoch||!vaultKey)return;
  if(buildThreads(filteredEvents()).length>visibleOffset+30)visibleOffset+=30;
  renderInbox();
}
let refreshing=null;
async function fullRefresh(){
  if(refreshing)return refreshing;
  const epoch=securityEpoch;
  refreshing=(async()=>{try{await loadDevices();if(epoch!==securityEpoch||!vaultKey)return;await loadEvents();if(epoch!==securityEpoch||!vaultKey)return;await loadCommandActivity();if(epoch!==securityEpoch||!vaultKey)return;await loadLifecycleHistory();if(epoch===securityEpoch&&vaultKey)setConnected(true);}catch(e){if(epoch===securityEpoch)setConnected(false);throw e;}finally{refreshing=null;}})();
  return refreshing;
}
function scheduleRefresh(){clearTimeout(refreshTimer);refreshTimer=setTimeout(()=>{if(vaultKey)fullRefresh().catch(e=>toast(e.message));},150);}
function startRealtime(){if(eventSource)eventSource.close();if('EventSource'in window){eventSource=new EventSource('/api/v1/stream',{withCredentials:true});eventSource.addEventListener('change',scheduleRefresh);eventSource.onopen=()=>setConnected(true);eventSource.onerror=()=>setConnected(false);}clearInterval(pollTimer);pollTimer=setInterval(()=>{if(vaultKey)fullRefresh().catch(()=>{});},60000);}

function renderDeviceSelectors(){const filters=$('deviceFilter'),send=$('sendDevice'),oldF=filters.value,oldS=send.value;filters.innerHTML='<option value="">'+escapeHtml(tr('all_devices'))+'</option>'+devices.map(d=>'<option value="'+escapeHtml(d.id)+'">'+escapeHtml(d.name)+'</option>').join('');send.innerHTML=devices.filter(d=>!d.revoked).map(d=>'<option value="'+escapeHtml(d.id)+'">'+escapeHtml(d.name)+'</option>').join('');if(Array.from(filters.options).some(o=>o.value===oldF))filters.value=oldF;if(Array.from(send.options).some(o=>o.value===oldS))send.value=oldS;updateSubscriptionSelector();}
function nodeChannels(d){
  if(!d||!d.state)return[];
  const channels=Array.isArray(d.state.channels)&&d.state.channels.length?d.state.channels:(d.state.subscriptions||[]).map(s=>Object.assign({id:s.channelId||String(s.subscriptionId),localId:String(s.subscriptionId),revision:s.channelRevision||1,kind:'android-sim'},s));
  const phones=d.state._phoneNumbers||[];
  return channels.map(ch=>{
    const found=phones.find(n=>String(n.channelId)===String(ch.id));
    return {...ch,phoneNumber:String(phoneOverrides[phoneOverrideKey(d,ch)]||found?.number||'').trim()};
  });
}
function channelTitle(ch){return (ch.phoneNumber?ch.phoneNumber+' · ':'')+(ch.alias||ch.displayName||ch.carrierName||ch.id);}
function messageChannelLabel(e){
  if(e.kind==='sms.history'&&!e.payload?.channelId)return tr('historical_sim_unverified');
  const d=devices.find(x=>x.id===e.deviceId),id=messageChannel(e);
  const channel=nodeChannels(d).find(ch=>String(ch.id)===id||String(ch.localId)===id);
  return channel?channelTitle(channel):id;
}
function updateSubscriptionSelector(){const d=devices.find(x=>x.id===$('sendDevice').value),channels=nodeChannels(d);$('sendSubscription').innerHTML=channels.map(ch=>'<option value="'+escapeHtml(String(ch.id))+'" data-local-id="'+escapeHtml(String(ch.localId==null?'':ch.localId))+'" data-revision="'+escapeHtml(String(ch.revision||ch.channelRevision||1))+'">'+escapeHtml(channelTitle(ch))+' · '+escapeHtml(ch.carrierName||ch.kind||'')+'</option>').join('')||'<option value="">'+escapeHtml(tr('no_active_channel'))+'</option>';}
function renderCommandActivity(commands){
  const box=$('commandActivity');if(!box||!vaultKey)return;
  const zh=getLocale()==='zh-CN';
  const types={'sms.send':zh?'发送短信':'SMS send','sms.sync_recent':zh?'同步最近短信':'Sync recent','sms.sync_older':zh?'同步更早短信':'Sync older','sms.sync_history':zh?'同步更早短信':'Sync older','diagnostics.request':zh?'设备诊断':'Diagnostics','device.refresh_state':zh?'刷新设备状态':'Refresh device','device.network_policy':zh?'联网策略':'Network policy','node.rotate_key':zh?'密钥轮换':'Key rotation'};
  const statuses={queued:zh?'已排队':'Queued',dispatched:zh?'已下发':'Dispatched',submitted:zh?'已提交运营商':'Submitted',sent:zh?'已发送':'Sent',delivered:zh?'已送达':'Delivered',succeeded:zh?'节点已执行':'Executed',failed:zh?'失败':'Failed',rejected:zh?'已拒绝':'Rejected',expired:zh?'已过期':'Expired'};
  box.innerHTML=commands.length?commands.map(c=>{
    const detail=c.result||{},status=statuses[c.state]||c.state||'—';
    const progress=c.type.startsWith('sms.sync_')&&c.state==='succeeded'
      ?' · '+(zh?'扫描':'Scanned')+' '+escapeHtml(detail.scanned??'—')+', '+(zh?'新排队':'New queued')+' '+escapeHtml(detail.queued??'—'):'';
    const reason=detail.reason?' · '+escapeHtml(detail.reason):'';
    return '<div class="command-item"><span class="command-label">'+escapeHtml(types[c.type]||c.type)+'<small>'+escapeHtml(deviceName(c.deviceId))+' · '+fmtTime(c.createdAt)+'</small></span><span class="command-status">'+escapeHtml(status)+progress+reason+'</span></div>';
  }).join(''):'<p class="hint">'+(zh?'暂无设备操作。':'No recent device operations.')+'</p>';
}
async function loadCommandActivity(){
  if(!vaultKey)return;
  const epoch=securityEpoch;
  const result=await api('/api/v1/commands/recent?limit=20');
  if(epoch!==securityEpoch||!vaultKey)return;
  renderCommandActivity(result.commands||[]);
}
async function loadLifecycleHistory(){
  if(!vaultKey)return;
  const result=await api('/api/v1/audit?limit=200');
  const container=$('lifecycleHistory');
  if(!container||!vaultKey)return;
  const relevant=(result.audit||[]).filter(row=>row.action.startsWith('device.reset.')||row.action==='device.sms.purge').slice(0,20);
  container.textContent=relevant.map(row=>fmtTime(row.occurred_at)+' · '+row.action+' · '+row.target).join('\n')||'暂无设备清理记录';
}
function resetEventCache(){
  eventDataGeneration++;
  events=[];decryptedEvents=[];eventIds.clear();
  lastSeq=0;oldestSeq=0;initialEventsLoaded=false;olderCursor=null;historyHasMore=true;visibleOffset=0;
  activeConversationKey=null;
  $('smsLayout').classList.remove('conversation-open');
}
async function refreshVersionInfo(){
  const el=$('deploymentInfo');
  if(!el)return;
  const info=await api('/api/v1/version');
  const deployed=info.deployedAt||null;
  const launched=info.startedAt?new Date(info.startedAt*1000).toLocaleString():null;
  const buildDate=deployed?new Date(deployed).toLocaleString():null;
  const zh=getLocale()==='zh-CN';
  el.textContent='SIM Hub v'+(info.version||'?')+' · '+(buildDate?(zh?'部署：':'Deployed: ')+buildDate:(zh?'服务启动：':'Started: ')+(launched||'—'));
  if($('deploymentInfoSettings'))$('deploymentInfoSettings').textContent=el.textContent;
}
function renderDevices(){
  const box=$('deviceList');
  if(!devices.length){box.innerHTML='<div class="empty card">'+escapeHtml(tr('no_devices'))+'</div>';return;}
  box.innerHTML=devices.map(d=>{
    const s=d.state||{},subs=nodeChannels(d);
    const healthKey=d.nodeType==='android'?(s.smsOperational===true?'sms_ready':s.smsOperational===false?'sms_unavailable':'sms_unverified'):(s.smsOperational===false?'sim_unavailable':'modem_unverified');
    const stateKey=d.revoked?'revoked':d.online?'online':'offline';
    const stateText=d.resetRequestedAt?(getLocale()==='zh-CN'?'等待设备重置':'Reset pending'):tr(stateKey);
    const buttons='<button class="ghost mini" data-action="refresh" data-id="'+escapeHtml(d.id)+'">'+escapeHtml(tr('action_refresh'))+'</button>'+
      (d.nodeType==='android'?'<button class="ghost mini" data-action="sync-recent" data-id="'+escapeHtml(d.id)+'">'+escapeHtml(tr('sync_recent_100'))+'</button><button class="ghost mini" data-action="sync-older" data-id="'+escapeHtml(d.id)+'">'+escapeHtml(tr('sync_older_100'))+'</button>':'')+
      '<button class="ghost mini" data-action="diagnostics" data-id="'+escapeHtml(d.id)+'">'+escapeHtml(tr('action_diagnostics'))+'</button>'+(d.nodeType==='android'&&versionAtLeast(d.appVersion,'0.5.0')?'<button class="ghost mini" data-action="network" data-id="'+escapeHtml(d.id)+'">'+escapeHtml(tr('mobile_fallback'))+'</button>':'')+
      (!d.keyId&&!d.pendingKeyId&&versionAtLeast(d.appVersion,'0.2.0')?'<button class="ghost mini" data-action="rotate-key" title="'+escapeHtml(tr('tip_rotate_key'))+'" data-id="'+escapeHtml(d.id)+'">'+escapeHtml(tr('action_rotate'))+'</button>':'')+
      (d.revoked?'':'<button class="danger mini" data-action="revoke" title="'+escapeHtml(tr('tip_revoke'))+'" data-id="'+escapeHtml(d.id)+'">'+escapeHtml(tr('action_revoke'))+'</button>');
    const zh=getLocale()==='zh-CN';
    const actions=(d.resetRequestedAt?'':buttons)+
      '<button class="ghost mini" data-action="purge-sms" data-id="'+escapeHtml(d.id)+'">'+(zh?'清理服务器短信':'Clear relay SMS')+'</button>'+
      (d.resetRequestedAt||d.revoked?'':('<button class="danger mini" data-action="reset-device" data-id="'+escapeHtml(d.id)+'">'+(zh?'双端解除配对':'Unpair both ends')+'</button>'))+
      '<button class="danger mini" data-action="force-delete" data-id="'+escapeHtml(d.id)+'">'+(zh?'强制删除':'Force delete')+'</button>';
    return '<article class="card device device-card"><div class="device-head"><div><h3>'+escapeHtml(d.name)+'</h3><p>'+escapeHtml(d.model||'Android')+' · '+escapeHtml(d.appVersion||'')+' · '+escapeHtml(d.smsCount??0)+' SMS</p></div><span class="status-pill '+(d.online?'online':'')+'">'+escapeHtml(stateText)+'</span></div>'+
      '<div class="device-stats"><div class="stat"><b>'+escapeHtml(tr(healthKey))+'</b><span>SMS</span></div><div class="stat"><b>'+(s.batteryPct==null?'—':escapeHtml(s.batteryPct)+'%')+'</b><span>'+escapeHtml(tr('battery'))+'</span></div><div class="stat"><b>'+escapeHtml(s.network||'—')+'</b><span>'+escapeHtml(tr('network'))+'</span></div><div class="stat"><b>'+(s.pendingEvents==null?'—':escapeHtml(s.pendingEvents))+'</b><span>'+escapeHtml(tr('pending'))+'</span></div></div>'+
      '<div class="device-stats"><div class="stat"><b>'+escapeHtml(s.charging===true?'充电中':s.charging===false?'未充电':'—')+'</b><span>充电状态</span></div><div class="stat"><b>'+escapeHtml(s.network==='WIFI'?'已连接 Wi-Fi':s.network==='CELLULAR'?'使用移动数据':s.network||'—')+'</b><span>联网状态</span></div></div>'+ '<div class="device-stats"><div class="stat"><b>'+escapeHtml(s.dataFallbackEnabled===true?(s.dataFallbackStatus||'待确认'):'未启用')+'</b><span>蜂窝数据接管</span></div></div>'+ '<div class="device-stats"><div class="stat"><b>'+fmtTime(s.lastSyncSuccessAt)+'</b><span>'+escapeHtml(tr('last_sync'))+'</span></div><div class="stat"><b>'+fmtTime(s.lastSmsReceivedAt)+'</b><span>'+escapeHtml(tr('last_sms'))+'</span></div><div class="stat"><b>'+escapeHtml(s.lastSyncError||tr('none'))+'</b><span>'+escapeHtml(tr('sync_error'))+'</span></div></div>'+
      '<p class="hint device-retry-status">'+(s.nextSyncAllowedAt&&s.nextSyncAllowedAt>Math.floor(Date.now()/1000)?(getLocale()==='zh-CN'?'网络重试：':'Next retry: ')+fmtTime(s.nextSyncAllowedAt)+' · '+(getLocale()==='zh-CN'?'失败次数 ':'Failures ')+escapeHtml(s.syncBackoffFailures||0):'')+'</p>'+
      '<div class="sim-list">'+(subs.map(x=>'<div class="sim"><strong>'+escapeHtml(x.displayName||x.carrierName||x.id||'SIM')+'</strong><small>'+escapeHtml(x.phoneNumber||tr('phone_unknown'))+' · '+escapeHtml(x.serviceState||'')+' · '+escapeHtml(tr('signal'))+' '+(x.signalLevel==null?'—':escapeHtml(x.signalLevel))+'</small><button class="ghost mini" data-action="edit-sim" data-id="'+escapeHtml(d.id)+'" data-channel="'+escapeHtml(x.id)+'" title="号码仅在当前浏览器加密保存">'+escapeHtml(tr('set_sim_phone'))+'</button></div>').join('')||'<small>'+escapeHtml(tr('no_subscriptions'))+'</small>')+'</div><details class="device-actions" data-device="'+escapeHtml(d.id)+'"'+(expandedDeviceDetails.has(d.id)?' open':'')+'><summary>'+(getLocale()==='zh-CN'?'更多操作 · 同步 / 诊断 / 安全':'More actions · Sync / Diagnostics / Security')+'</summary><div class="row wrap">'+actions+'</div></details></article>';
  }).join('');
}
function collapseMessageEvents(source){
  const rank={'sms.history':0,'sms.sent':2,'sms.delivered':3,'sms.failed':4},byProvider=new Map(),rest=[];
  for(const e of source){
    const p=e.payload||{},providerId=p.providerId;
    if(p.direction==='out'&&providerId!=null){
      const key=e.deviceId+':'+providerId,old=byProvider.get(key);
      if(!old||(rank[e.kind]||0)>=(rank[old.kind]||0))byProvider.set(key,e);
    }else rest.push(e);
  }
  return rest.concat(Array.from(byProvider.values()));
}
function eventIsInbound(e){
  const p=e.payload||{};
  return e.kind==='sms.received'||p.direction==='in'||(e.kind==='sms.history'&&p.direction!=='out');
}
function messageAddress(e){
  const p=e.payload||{};
  return String((eventIsInbound(e)?p.sender:p.recipient)||p.sender||p.recipient||'').trim();
}
function messageChannel(e){
  const p=e.payload||{},id=String(p.channelId||e.subscriptionId||'');
  const d=devices.find(x=>x.id===e.deviceId);
  const channel=nodeChannels(d).find(ch=>String(ch.id)===id||String(ch.localId)===id);
  return channel?String(channel.id):id;
}
function threadKey(e){
  const phone=messageAddress(e).replace(/[\s()-]/g,'');
  return e.deviceId+'|'+messageChannel(e)+'|'+phone;
}
function filteredEvents(){
  const q=$('search').value.trim().toLowerCase(),dev=$('deviceFilter').value,kind=$('kindFilter').value;
  return collapseMessageEvents(decryptedEvents).filter(e=>e.kind.startsWith('sms.')&&(!dev||e.deviceId===dev)&&(!kind||e.kind===kind))
    .filter(e=>{if(!q)return true;const p=e.payload||{};return [p.body,p.sender,p.recipient,p.contactName,p.otp&&p.otp.value,deviceName(e.deviceId)].some(v=>String(v||'').toLowerCase().includes(q));});
}
function buildThreads(source){
  const threads=new Map();
  for(const e of source){
    const key=threadKey(e);
    if(!threads.has(key))threads.set(key,[]);
    threads.get(key).push(e);
  }
  return [...threads.entries()].map(([key,messages])=>{
    messages.sort((a,b)=>(a.occurredAt||0)-(b.occurredAt||0));
    return {key,messages,latest:messages[messages.length-1]};
  }).sort((a,b)=>(b.latest.occurredAt||0)-(a.latest.occurredAt||0));
}
function renderInbox(){
  const threads=buildThreads(filteredEvents()),show=threads.slice(visibleOffset,visibleOffset+30);
  $('emptyInbox').hidden=!!show.length;
  $('loadOlderBtn').hidden=!historyHasMore&&threads.length<=visibleOffset+30;
  $('loadNewerBtn').hidden=visibleOffset===0;
  const otpCount=threads.reduce((n,t)=>n+t.messages.filter(e=>eventIsInbound(e)&&e.payload?.otp?.value).length,0);
  $('otpBadge').hidden=!otpCount;$('otpBadge').textContent=otpCount?String(otpCount):'';
  $('inboxList').innerHTML=show.map(t=>{
    const e=t.latest,p=e.payload||{},who=p.contactName||messageAddress(e)||tr('unknown');
    const body=e.decryptError?'['+tr('decrypt_failed')+']':p.body||'';
    return '<button type="button" class="message'+(activeConversationKey===t.key?' active':'')+
      '" data-thread="'+escapeHtml(t.key)+'"><span class="avatar">'+escapeHtml(who.slice(0,1).toUpperCase())+
      '</span><span class="message-body"><span class="message-title"><strong>'+escapeHtml(who)+
      '</strong><small class="meta">'+fmtTime(e.occurredAt)+'</small></span>'+
      '<span class="message-preview">'+escapeHtml(body)+'</span><small class="meta">'+
      escapeHtml(deviceName(e.deviceId))+' · '+escapeHtml(messageChannelLabel(e))+'</small></span></button>';
  }).join('');
  renderConversation();
}
function updateReplyDevices(){
  const el=$('replyDevice'),chosen=el.value;
  el.innerHTML='<option value="">'+escapeHtml(tr('sim_node'))+'</option>'+
    devices.filter(d=>!d.revoked).map(d=>'<option value="'+escapeHtml(d.id)+'">'+escapeHtml(d.name)+'</option>').join('');
  if([...el.options].some(x=>x.value===chosen))el.value=chosen;
  updateReplyChannels();
}
function updateReplyChannels(preferred){
  const d=devices.find(x=>x.id===$('replyDevice').value),channels=nodeChannels(d);
  const el=$('replySubscription'),selected=preferred||el.value;
  el.innerHTML='<option value="">'+escapeHtml(tr('sim_subscription'))+'</option>'+
    channels.map(ch=>'<option value="'+escapeHtml(String(ch.id))+'">'+escapeHtml(channelTitle(ch))+'</option>').join('');
  if([...el.options].some(x=>x.value===selected))el.value=selected;
  const selectedChannel=channels.find(ch=>String(ch.id)===el.value);
  $('replyChannelLabel').textContent=selectedChannel?(d.name+' · '+channelTitle(selectedChannel)):tr('choose_channel');
}
function renderConversation(){
  if(!activeConversationKey){
    $('conversationTitle').textContent=tr('choose_conversation');
    $('conversationMeta').textContent='';
    $('conversationMessages').innerHTML='<p class="hint">'+escapeHtml(tr('choose_conversation_hint'))+'</p>';
    $('replyComposer').hidden=true;
    return;
  }
  $('replyComposer').hidden=false;
  if(activeConversationKey==='__new'){
    $('conversationTitle').textContent=tr('new_sms');
    $('conversationMeta').textContent='';
    $('conversationMessages').innerHTML='<p class="hint">'+escapeHtml(tr('new_sms_hint'))+'</p>';
    return;
  }
  const messages=buildThreads(collapseMessageEvents(decryptedEvents)).find(t=>t.key===activeConversationKey)?.messages||[];
  if(!messages.length){
    $('conversationMessages').innerHTML='<p class="hint">'+escapeHtml(tr('load_older'))+'</p>';
    return;
  }
  const last=messages[messages.length-1];
  const pane=$('conversationMessages');
  const previousKey=pane.dataset.threadKey;
  const nearBottom=pane.scrollHeight-pane.scrollTop-pane.clientHeight<100;
  const oldScroll=pane.scrollTop;
  $('conversationTitle').textContent=last.payload?.contactName||messageAddress(last)||tr('unknown');
  $('conversationMeta').textContent=deviceName(last.deviceId)+' · '+messageChannelLabel(last);
  $('conversationMessages').innerHTML=messages.map(e=>{
    const p=e.payload||{},inbound=eventIsInbound(e),
      status=inbound?tr('received'):(e.kind==='sms.failed'?tr('failed'):e.kind==='sms.delivered'?'✓✓':tr('sent')),
      body=e.decryptError?'['+tr('decrypt_failed')+']':p.body||'';
    const otp=p.otp?.value&&inbound?'<div class="otp"><code>'+escapeHtml(p.otp.value)+
      '</code><button class="ghost mini copy-otp" data-otp="'+escapeHtml(p.otp.value)+'" type="button">'+escapeHtml(tr('copy'))+'</button></div>':'';
    return '<div class="bubble'+(inbound?'':' outbound')+'"><p>'+escapeHtml(body)+
      '</p>'+otp+'<small class="meta">'+fmtTime(e.occurredAt)+' · '+escapeHtml(status)+'</small></div>';
  }).join('');
  pane.dataset.threadKey=activeConversationKey;
  if(previousKey!==activeConversationKey||nearBottom)pane.scrollTop=pane.scrollHeight;
  else pane.scrollTop=oldScroll;
}
function openConversation(key){
  const selected=buildThreads(collapseMessageEvents(decryptedEvents)).find(x=>x.key===key);
  if(!selected)return;
  activeConversationKey=key;visibleOffset=0;
  const last=selected.latest,number=messageAddress(last),did=last.deviceId,channel=messageChannel(last);
  updateReplyDevices();$('replyDevice').value=did;updateReplyChannels(channel);
  $('replyTo').value=number;
  $('replyOptions').open=false;
  const canReply=/^\+?[0-9 ()-]{3,40}$/.test(number)&&!!$('replySubscription').value;
  $('replySend').disabled=!canReply;
  if(!canReply)toast('发件人不可直接回复，或原设备 / SIM 已不可用；请检查收件人和发送通道');
  syncResponsiveConversation();
  renderInbox();
}
function openNewMessage(){
  switchView('inbox');activeConversationKey='__new';
  updateReplyDevices();
  $('replyDevice').value='';
  updateReplyChannels();
  $('replyTo').value='';$('replyBody').value='';$('replySend').disabled=false;
  $('replyOptions').open=true;
  syncResponsiveConversation();
  renderInbox();$('replyTo').focus();
}
function countSmsSegments(value){
  const basic="@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
  const extended="^{}\\[~]|€",gsm=[...value].every(ch=>basic.includes(ch)||extended.includes(ch));
  const units=gsm?[...value].reduce((n,ch)=>n+(extended.includes(ch)?2:1),0):value.length;
  return units===0?0:Math.ceil(units/(gsm?(units<=160?160:153):(units<=70?70:67)));
}
function updateReplyCount(){
  const v=$('replyBody').value;
  $('replyCount').textContent=tr('chars_parts',{chars:v.length,parts:countSmsSegments(v)});
}
async function sendConversationReply(){
  const btn=$('replySend');
  if(btn.disabled)return;
  const dest=$('replyTo').value.trim(),device=$('replyDevice').value,channel=$('replySubscription').value;
  if(!device||!channel)throw new Error('请选择可用设备和 SIM 通道');
  const sendDevice=$('sendDevice'),sendSub=$('sendSubscription');
  sendDevice.value=device;updateSubscriptionSelector();sendSub.value=channel;
  if(sendSub.value!==channel)throw new Error('该 SIM 通道已失效，重新选择后再发送');
  $('sendTo').value=dest;$('sendBody').value=$('replyBody').value;
  btn.disabled=true;
  try{
    await sendSms();
    // The existing encrypted command path performs confirmation and step-up.
    // Preserve the draft if user cancels or network fails.
    if(!$('sendBody').value){
      $('replyBody').value='';updateReplyCount();
    }
  }finally{btn.disabled=false;}
}
async function queueCommand(deviceId,type,payload,ttl){if(type==='sms.send'||type==='node.rotate_key'||type==='device.network_policy')await ensureStepUp();ttl=ttl||120;const createdAt=Math.floor(Date.now()/1000),commandId=uuid(),idempotencyKey=commandId,expiresAt=createdAt+ttl,inner=Object.assign({v:2,action:type,commandId:commandId,issuedAt:createdAt,expiresAt:expiresAt},payload),outer={commandId:commandId,idempotencyKey:idempotencyKey,type:type,createdAt:createdAt,expiresAt:expiresAt};outer.ciphertext=await encryptCommand(deviceId,outer,inner);return api('/api/v1/devices/'+encodeURIComponent(deviceId)+'/commands',{method:'POST',body:outer});}
async function sendSms(){const deviceId=$('sendDevice').value,select=$('sendSubscription'),channelId=select.value,opt=select.selectedOptions[0],to=$('sendTo').value.trim(),body=$('sendBody').value;if(!deviceId||!channelId)throw new Error('Choose an online SIM Node and SMS channel.');if(!/^\+?[0-9 ()-]{3,40}$/.test(to))throw new Error('Recipient number format is invalid.');if(!body.trim())throw new Error('Message is empty.');const selected=devices.find(x=>x.id===deviceId);if(selected&&selected.state&&selected.state.smsOperational===false)throw new Error('The selected node reports SMS unavailable. Fix the SMS role, permissions or SIM first.');if(!confirm(tr('confirm_send',{device:deviceName(deviceId),to:to})))return;const localId=opt?opt.dataset.localId:'',revision=opt?Number(opt.dataset.revision||1):1,payload={channelId:channelId,channelRevision:revision,to:to,body:body};if(/^\d+$/.test(localId))payload.subscriptionId=Number(localId);await queueCommand(deviceId,'sms.send',payload,180);$('sendBody').value='';updateCharCount();toast('Encrypted SMS command queued');}
async function createEnrollment(){
  if(!vaultRaw)throw new Error('Vault must be unlocked.');
  const epoch=securityEpoch;
  await ensureStepUp();
  if(epoch!==securityEpoch||!vaultKey)throw Error('Vault locked while creating enrollment');
  const type=$('enrollType').value==='modem'?'modem':'android',name=$('enrollName').value.trim()||(type==='modem'?'DJI / Modem SIM Node':'Android SIM Node');
  const nodeRaw=crypto.getRandomValues(new Uint8Array(32)),bootstrapRaw=crypto.getRandomValues(new Uint8Array(32)),kid=await keyIdForRaw(nodeRaw);
  let value='';
  try{
    const wrapped=await wrapNodeKey(nodeRaw,kid),bootstrapEnvelope=await encryptBootstrapNodeKey(nodeRaw,bootstrapRaw,kid),bootstrap=b64u(bootstrapRaw),bootstrapHash=b64u(new Uint8Array(await crypto.subtle.digest('SHA-256',bootstrapRaw)));
    const capabilities=type==='modem'?['sms.receive','sms.send','sms.history','signal.basic','signal.radio']:['sms.receive','sms.send','sms.history','signal.basic','dual-sim'];
    const r=await api('/api/v1/enrollments',{method:'POST',body:{ttlSeconds:600,nodeType:type,capabilities:capabilities,keyId:kid,wrappedKey:wrapped,bootstrapEnvelope:bootstrapEnvelope,bootstrapHash:bootstrapHash}});
    if(epoch!==securityEpoch||!vaultKey)throw Error('Vault locked while creating enrollment');
    const server=r.server||location.origin;
    if(type==='modem'){
      value=JSON.stringify({version:4,server:server,token:r.token,bootstrap:bootstrap,name:name,nodeType:'modem'},null,2);
      $('openEnroll').hidden=true;
    }else{
      value='simhub://enroll?v=4&server='+encodeURIComponent(server)+'&token='+encodeURIComponent(r.token)+'&bootstrap='+encodeURIComponent(bootstrap)+'&name='+encodeURIComponent(name);
      // Never hand bootstrap credentials to another app through a custom-Scheme
      // intent chooser: copy and paste the one-time package INSIDE the Agent.
      $('openEnroll').removeAttribute('href');$('openEnroll').hidden=true;
    }
    $('enrollLink').value=value;$('enrollResult').hidden=false;
    toast(tr(type==='modem'?'enroll_modem_done':'enroll_android_done'));
  }finally{
    nodeRaw.fill(0);bootstrapRaw.fill(0);
  }
}
async function rotateDeviceKey(deviceId){await ensureStepUp();const d=devices.find(x=>x.id===deviceId);if(!d||d.keyId)throw new Error('Device already uses an independent node key.');if(d.pendingKeyId)throw new Error('A node-key rotation is already pending.');const raw=crypto.getRandomValues(new Uint8Array(32)),kid=await keyIdForRaw(raw),wrapped=await wrapNodeKey(raw,kid);await api('/api/v1/devices/'+encodeURIComponent(deviceId),{method:'PATCH',body:{pendingKeyId:kid,pendingWrappedKey:wrapped}});try{await queueCommand(deviceId,'node.rotate_key',{keyId:kid,nodeKey:b64u(raw)},300);}finally{raw.fill(0);}toast('Node-key rotation queued. It will activate after the device confirms the new key.');}
async function copy(text,msg){await navigator.clipboard.writeText(text);toast(msg||tr('copied'));}
function updateCharCount(){const value=$('sendBody').value;$('smsCount').textContent=tr('chars_parts',{chars:value.length,parts:countSmsSegments(value)});}
function maybeNotify(e){if(e.kind!=='sms.received'||Notification.permission!=='granted'||document.visibilityState==='visible')return;const p=e.payload||{};new Notification('SIM Hub',{body:tr(p.otp&&p.otp.value?'new_otp':'new_sms'),icon:'/icon.svg',tag:e.deviceId+':'+e.eventId});}
async function refreshDiagnostics(){
  if(!diagnosticsDeviceId||!vaultKey)return;
  const epoch=securityEpoch;
  const pane=$('diagnosticsOutput');pane.textContent='正在获取最近诊断结果…';
  try{
    const path='/api/v1/events?order=occurred&kind=device.diagnostics&limit=20&device='+encodeURIComponent(diagnosticsDeviceId);
    const result=await api(path),records=[];
    if(epoch!==securityEpoch||!vaultKey)return;
    for(const e of result.events||[]){
      if(epoch!==securityEpoch||!vaultKey)return;
      try{
        const payload=await decryptEvent(e);
        if(epoch!==securityEpoch||!vaultKey)return;
        // Never render the encrypted SIM inventory or any secret envelopes in support UI.
        const sanitized={...payload};
        delete sanitized.encryptedSimNumbers;
        records.push({time:new Date(e.occurredAt*1000).toLocaleString(),requestId:payload.requestId||null,diagnostics:sanitized});
      }catch(err){records.push({time:e.occurredAt,error:'无法解密诊断结果'});}
    }
    if(epoch===securityEpoch&&vaultKey)pane.textContent=records.length?JSON.stringify(records,null,2):'暂无诊断记录。点击设备上的“健康检查”后，再刷新结果。';
  }catch(e){if(epoch===securityEpoch&&vaultKey)pane.textContent='诊断读取失败：'+e.message;}
}
async function openDiagnostics(id){
  diagnosticsDeviceId=id;
  $('diagnosticsTitle').textContent='设备健康检查 · '+deviceName(id);
  $('diagnosticsDialog').showModal();
  await refreshDiagnostics();
}

async function configureNetworkFallback(id){
  const d=devices.find(x=>x.id===id),list=nodeChannels(d);
  if(!d||!versionAtLeast(d.appVersion,'0.5.0'))throw Error('Android Agent 需升级到 v0.5.0');
  const options=list.map((ch,i)=>(i+1)+'. '+channelTitle(ch)).join('\n');
  const chosen=prompt('Wi-Fi 断开后，Android 只能自动使用系统预设的默认数据 SIM。\\n先在手机系统设置中启用移动数据并设定默认 SIM。\\n输入序号选择需要监控的 SIM；输入 0 关闭。\\n'+options,'0');
  if(chosen===null)return;
  if(!/^\d+$/.test(chosen.trim()))throw Error('无效选项');
  const choice=Number(chosen.trim());
  if(choice<0||choice>list.length)throw Error('无效选项');
  const channel=list[choice-1],enabled=choice>0;
  await queueCommand(id,'device.network_policy',{enabled,channelId:enabled?String(channel.id):'',channelRevision:enabled?Number(channel.revision||channel.channelRevision||1):0},900);
  toast(enabled?'备用数据策略已提交；请确认 Android 系统默认数据 SIM 一致':'关闭备用数据监控已提交');
}
async function handleDeviceAction(btn){
  const id=btn.dataset.id,action=btn.dataset.action;
  const d=devices.find(x=>x.id===id);
  if(!d)return;
  const zh=getLocale()==='zh-CN';
  if(action==='purge-sms'){
    if(!confirm(zh?'仅清除该设备在服务器中的短信和未完成短信指令，不会清除 Android 手机上的原始短信。确认继续？':'Delete only relay-hosted SMS and queued SMS commands, not messages on the phone?'))return;
    await ensureStepUp();
    const result=await api('/api/v1/devices/'+encodeURIComponent(id)+'/sms',{method:'DELETE'});
    await fullRefresh();
    toast((zh?'已清除服务器短信 ':'Relay SMS removed: ')+result.deleted);
    return;
  }
  if(action==='reset-device'){
    if(!versionAtLeast(d.appVersion,d.nodeType==='modem'?'0.3.2':'0.5.3')){
      toast(zh?'请先升级设备端至 v0.5.3；离线或无法升级的设备可强制删除':'Upgrade Agent to v0.5.3 before bidirectional unpair; force delete is available');
      return;
    }
    if(!confirm(zh?'将停止该设备短信功能，等待设备上线后自动解除配对并清理服务器数据；手机原始短信不受影响。确认继续？':'Stop relay access and queue a remote unpair. The device will reset on its next connection. Continue?'))return;
    await ensureStepUp();
    await api('/api/v1/devices/'+encodeURIComponent(id)+'/reset-request',{method:'POST',body:{}});
    await fullRefresh();
    toast(zh?'已申请重置；等待设备确认':'Unpair requested; awaiting device');
    return;
  }
  if(action==='force-delete'){
    if(!confirm(zh?'强制删除将立即撤销所有服务器凭据、删除服务器短信和设备历史。离线手机无法收到即时重置通知。继续？':'Force delete immediately removes relay records and credentials. An offline device cannot be notified immediately. Continue?'))return;
    if(!confirm(zh?'此操作不可撤销。再次确认强制删除设备：'+d.name:'Permanently delete relay records for: '+d.name+'?'))return;
    await ensureStepUp();
    await api('/api/v1/devices/'+encodeURIComponent(id),{method:'DELETE'});
    await fullRefresh();
    toast(zh?'服务器设备数据已清除':'Relay device records deleted');
    return;
  }if(action==='edit-sim'){await editSimPhone(id,btn.dataset.channel);return;}if(action==='network'){await configureNetworkFallback(id);return;}if(action==='refresh'){await queueCommand(id,'device.refresh_state',{});toast('Refresh queued');}else if(action==='sync-recent'){const d=devices.find(x=>x.id===id);if(!versionAtLeast(d?.appVersion,'0.5.0'))throw new Error('请先升级 Android 节点至 v0.5.0 后再同步最近 100 条');await queueCommand(id,'sms.sync_recent',{maxMessages:100},900);toast('最近 100 条同步请求已入队');}else if(action==='sync-older'){const d=devices.find(x=>x.id===id);await queueCommand(id,versionAtLeast(d?.appVersion,'0.5.0')?'sms.sync_older':'sms.sync_history',{maxMessages:100},900);toast('更早 100 条同步请求已入队');}else if(action==='diagnostics'){await openDiagnostics(id);await queueCommand(id,'diagnostics.request',{});toast('健康检查已入队，稍后刷新结果');}else if(action==='rotate-key'){await rotateDeviceKey(id);}else if(action==='revoke'&&confirm(tr('confirm_revoke'))){await ensureStepUp();await api('/api/v1/devices/'+encodeURIComponent(id),{method:'PATCH',body:{revoke:true}});await fullRefresh();}}
function syncResponsiveConversation(){
  const layout=$('smsLayout');
  if(!layout)return;
  const mobile=window.matchMedia('(max-width: 760px)').matches;
  const inbox=document.getElementById('view-inbox')?.classList.contains('active');
  layout.classList.toggle('conversation-open',mobile&&inbox&&!!activeConversationKey);
}
function switchView(name){
  if(name!=='inbox'){
    // Preserve the selected thread for desktop, but never leave a mobile overlay
    // floating above another section or behind the persistent bottom dock.
    $('smsLayout').classList.remove('conversation-open');
  }
  document.querySelectorAll('.nav').forEach(x=>x.classList.toggle('active',x.dataset.view===name));document.querySelectorAll('.view').forEach(x=>x.classList.toggle('active',x.id==='view-'+name));$('viewTitle').textContent=tr(titleKeys[name][0]);$('viewSubtitle').textContent=tr(titleKeys[name][1]);if(name==='send')updateSubscriptionSelector();$('newSmsBtn').hidden=!vaultKey||name!=='inbox';syncResponsiveConversation();}

function relocalizeDynamic(){
  applyI18n();
  const active=document.querySelector('.nav.active')?.dataset.view||'inbox';
  switchView(active);
  setConnected($('relayDot').classList.contains('ok'));
  $('gateHint').textContent=localStorage.getItem(VAULT_STORE)?tr('local_vault_found'):tr('no_local_vault');
  if(vaultKey)$('vaultStatus').textContent=tr('vault_unlocked');
  renderDeviceSelectors();
  renderDevices();
  renderInbox();
  updateCharCount();
}

function wire(){
  applyI18n();
  $('gateHint').textContent=localStorage.getItem(VAULT_STORE)?tr('local_vault_found'):tr('no_local_vault');
  $('languageSelect').onchange=()=>{setLocale($('languageSelect').value);relocalizeDynamic();};
  $('unlockBtn').onclick=()=>connectAndUnlock().catch(e=>toast(e.message));
  $('passkeyLoginBtn').onclick=()=>passkeyLogin().catch(e=>toast(e.message));
  $('registerPasskeyBtn').onclick=()=>registerPasskey().catch(e=>toast(e.message));
  $('passkeysList').onclick=e=>{const b=e.target.closest('button[data-passkey-id]');if(b)removePasskey(b.dataset.passkeyId).catch(err=>toast(err.message));};
  $('createVaultBtn').onclick=()=>createVaultFlow().catch(e=>toast(e.message));
  $('importKeyBtn').onclick=()=>importRecoveryFlow().catch(e=>toast(e.message));
  $('lockBtn').onclick=lockVault;
  $('refreshBtn').onclick=()=>vaultKey?fullRefresh().catch(e=>toast(e.message)):toast(tr('vault_locked'));
  document.querySelectorAll('.nav').forEach(b=>b.onclick=()=>switchView(b.dataset.view));
  $('search').oninput=()=>{visibleOffset=0;renderInbox();};
  $('deviceFilter').onchange=()=>{visibleOffset=0;renderInbox();};
  $('kindFilter').onchange=()=>{visibleOffset=0;renderInbox();};
  $('sendDevice').onchange=updateSubscriptionSelector;
  $('sendBody').oninput=updateCharCount;
  $('newSmsBtn').onclick=openNewMessage;
  $('backConversation').onclick=()=>{activeConversationKey=null;syncResponsiveConversation();renderInbox();$('search').focus({preventScroll:true});};
  window.addEventListener('resize',syncResponsiveConversation,{passive:true});
  window.addEventListener('orientationchange',syncResponsiveConversation,{passive:true});
  $('replyDevice').onchange=()=>{updateReplyChannels();$('replySend').disabled=false;};
  $('replySubscription').onchange=()=>{$('replySend').disabled=false;updateReplyChannels($('replySubscription').value);};
  $('replyBody').oninput=updateReplyCount;
  $('replyTo').oninput=()=>{$('replySend').disabled=false;};
  $('replySend').onclick=()=>sendConversationReply().catch(e=>toast(e.message));
  $('conversationMessages').onclick=e=>{const b=e.target.closest('.copy-otp');if(b)copy(b.dataset.otp,tr('otp_copied')).catch(err=>toast(err.message));};
  $('loadOlderBtn').onclick=()=>loadOlder().catch(e=>toast(e.message));
  $('loadNewerBtn').onclick=()=>{visibleOffset=Math.max(0,visibleOffset-30);renderInbox();};
  $('sendBtn').onclick=()=>sendSms().catch(e=>toast(e.message));
  $('addDeviceBtn').onclick=()=>{switchView('settings');$('enrollCard').scrollIntoView({behavior:'smooth',block:'start'});$('enrollName').focus({preventScroll:true});};
  $('enrollBtn').onclick=async()=>{
    if(enrolling)return;
    enrolling=true;const btn=$('enrollBtn');btn.disabled=true;btn.setAttribute('aria-busy','true');
    try{$('enrollResult').hidden=true;await createEnrollment();}catch(e){toast(e.message);}
    finally{enrolling=false;btn.disabled=false;btn.removeAttribute('aria-busy');}
  };
  $('copyEnroll').onclick=()=>copy($('enrollLink').value,tr('enrollment_link_copied')).catch(e=>toast(e.message));
  $('exportKeyBtn').onclick=async()=>{try{if(!vaultRaw)throw new Error(tr('vault_locked'));await ensureStepUp();if(!confirm('恢复密钥可解密所有短信。确认复制到系统剪贴板？'))return;await copy('SIMHUB-RECOVERY-V1:'+b64u(vaultRaw),tr('recovery_key_copied'));}catch(e){toast(e.message);}};
  $('notifyBtn').onclick=async()=>{const p=await Notification.requestPermission();toast(tr(p==='granted'?'browser_notifications_enabled':'notification_permission_denied'));};
  $('revokeAllBtn').onclick=async()=>{if(confirm('撤销所有管理员会话，包括本设备？')){await ensureStepUp();await api('/api/v1/auth/revoke-all',{method:'POST',body:{confirm:true}});csrfToken='';lockVault();toast('所有管理员会话已撤销');}};
  $('forgetBtn').onclick=async()=>{if(confirm(tr('confirm_forget'))){await logoutSession();localStorage.removeItem(VAULT_STORE);localStorage.removeItem(PHONE_OVERRIDES_STORE);lockVault();toast(tr('credentials_forgotten'));}};
  $('diagnosticsClose').onclick=()=>$('diagnosticsDialog').close();
  $('diagnosticsRefresh').onclick=()=>refreshDiagnostics().catch(e=>toast(e.message));
  $('deviceList').onclick=e=>{const b=e.target.closest('button[data-action]');if(b)handleDeviceAction(b).catch(err=>toast(err.message));};
  $('deviceList').addEventListener('toggle',e=>{const details=e.target.closest('details[data-device]');if(!details)return;if(details.open)expandedDeviceDetails.add(details.dataset.device);else expandedDeviceDetails.delete(details.dataset.device);},true);
  $('refreshCommandActivity').onclick=()=>loadCommandActivity().catch(e=>toast(e.message));
  $('inboxList').onclick=e=>{const b=e.target.closest('button[data-thread]');if(b)openConversation(b.dataset.thread);};
  if('serviceWorker'in navigator)navigator.serviceWorker.register('/sw.js').catch(()=>{});
  relocalizeDynamic();
}
wire();
resumeExistingSession().catch(()=>{});
