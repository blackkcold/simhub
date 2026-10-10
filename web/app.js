import {tr, applyI18n, getLocale, setLocale, localizeMessage} from './i18n.js';
const $ = (id) => document.getElementById(id);
const enc = new TextEncoder();
const dec = new TextDecoder();
const VAULT_STORE = 'simhub_vault_v1';
const PBKDF2_ITER = 310000;
const DEFAULT_SESSION_IDLE_MS = 8 * 60 * 60 * 1000;
const SESSION_VAULT_CACHE = 'simhub_session_vault_v1';
const TRUSTED_VAULT_CACHE = 'simhub_trusted_vault_v1';
const TRUSTED_VAULT_CONFIG = 'simhub_trusted_vault_options_v1';
let authenticated=false;
let passwordConfigured=false;
const PHONE_OVERRIDES_STORE = 'simhub_phone_overrides_v1';
let phoneOverrides={};
let sessionIdleMs = DEFAULT_SESSION_IDLE_MS;

let vaultKey=null,vaultRaw=null,devices=[],events=[],decryptedEvents=[],lastSeq=0,oldestSeq=0,pollTimer=null,eventSource=null,autoLockTimer=null,refreshTimer=null,lastActivity=Date.now();
let csrfToken='',stepUpUntil=0,secondFactorIsTotp=true,activeStepUp=null,enrolling=false,initialEventsLoaded=false,passkeyCount=0;
let olderCursor=null,historyHasMore=true,visibleCount=40,activeConversationKey=null,diagnosticsDeviceId=null;
let historyFetchInFlight=null,loadObserver=null,lastAutoScroll=-1;
let poolState=null;
const expandedDeviceDetails=new Set();
let enrollStep=1,enrollMode='package',nodeBaseUrl='',enrollStartingDevices=new Set();
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
function trustedOptions(){
  try{const p=JSON.parse(localStorage.getItem(TRUSTED_VAULT_CONFIG)||'null');
    return {enabled:p?.enabled===true,duration:[900,3600,28800].includes(p?.duration)?p.duration:28800};
  }catch{return {enabled:false,duration:28800};}
}
function clearTrustedVault(){try{localStorage.removeItem(TRUSTED_VAULT_CACHE);}catch{}}
async function storeTrustedVault(){
  const config=trustedOptions();
  if(!config.enabled||!vaultRaw||!authenticated)return;
  const secret=new Uint8Array(vaultRaw),iv=crypto.getRandomValues(new Uint8Array(12));
  try{
    const key=await tabVaultKey();
    const ct=await crypto.subtle.encrypt({name:'AES-GCM',iv,additionalData:enc.encode('simhub-trusted-vault-v1')},key,secret);
    localStorage.setItem(TRUSTED_VAULT_CACHE,JSON.stringify({v:1,iv:b64u(iv),
      ct:b64u(new Uint8Array(ct)),expiresAt:Date.now()+Math.min(config.duration*1000,sessionIdleMs)}));
  }finally{secret.fill(0);}
}
async function restoreTrustedVault(){
  if(!authenticated||!trustedOptions().enabled)return false;
  try{
    const data=JSON.parse(localStorage.getItem(TRUSTED_VAULT_CACHE)||'null');
    if(!data||data.v!==1||Date.now()>=data.expiresAt)throw Error('Expired');
    const raw=await crypto.subtle.decrypt({name:'AES-GCM',iv:unb64u(data.iv),
      additionalData:enc.encode('simhub-trusted-vault-v1')},await tabVaultKey(),unb64u(data.ct));
    if(raw.byteLength!==32)throw Error('Invalid Vault Key');
    await importVault(raw);await loadPhoneOverrides();showUnlocked();await fullRefresh();
    refreshVersionInfo().catch(()=>{});startRealtime();return true;
  }catch{clearTrustedVault();return false;}
}
async function setAdminPassword(){
  const value=$('newAdminPassword').value;
  if(value.length<12)throw Error('请输入至少 12 位管理员密码');
  if(!confirm('修改管理员密码会撤销全部设备的管理员会话，需要重新登录。确认继续？'))return;
  await ensureStepUp();
  await api('/api/v1/auth/password',{method:'POST',body:{newPassword:value}});
  $('newAdminPassword').value='';authenticated=false;csrfToken='';clearTrustedVault();lockVault();
  toast('管理员密码已更新，全部会话已撤销，请重新登录');
}
async function updateTrustedOptions(){
  await ensureStepUp();
  const enabled=$('vaultTrustEnabled').checked,duration=Number($('vaultTrustDuration').value);
  localStorage.setItem(TRUSTED_VAULT_CONFIG,JSON.stringify({enabled,duration}));
  if(enabled)await storeTrustedVault();else clearTrustedVault();
  toast(enabled?'已允许本设备有效期内免重复解锁':'已关闭自动解锁');
}
function showVaultStage(){
  authenticated=true;
  $('authStage').hidden=true;$('vaultStage').hidden=false;
  $('gateHint').textContent=localStorage.getItem(VAULT_STORE)?'请输入 Vault 密码，或使用已授权的可信设备解锁':'首次使用：请导入已有恢复密钥，或创建新 Vault';
}
async function finishLogin(){
  showVaultStage();
  await restoreTrustedVault();
}
function promptLoginTotp(){
  return new Promise((resolve,reject)=>{
    const d=$('loginOtpDialog'),form=$('loginOtpForm'),input=$('totp'),cancel=$('loginOtpCancel');
    const clean=(value)=>{form.removeEventListener('submit',submit);cancel.removeEventListener('click',dismiss);
      d.removeEventListener('cancel',dismiss);d.close();input.value='';
      if(value)resolve(value);else reject(new Error('已取消二次验证'));};
    const submit=e=>{e.preventDefault();clean(input.value.trim());};
    const dismiss=e=>{e.preventDefault();clean('');};
    form.addEventListener('submit',submit);cancel.addEventListener('click',dismiss);
    d.addEventListener('cancel',dismiss);input.value='';d.showModal();input.focus();
  });
}

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
  try{
    await api('/api/v1/auth/check');authenticated=true;
    if(await restoreTabVault())return;
    await finishLogin();
  }catch{authenticated=false;clearTabVault();}
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
/** Safe failure category only: never surface raw ciphertext, plaintext or keys. */
function decryptFailureCategory(e,error){
  const d=devices.find(x=>x.id===e.deviceId);
  const kid=e.ciphertext?.kid||'';
  if(d?.state?.cryptoKeyId && d?.keyId && d.state.cryptoKeyId!==d.keyId)return 'device_key_diverged';
  if(error?.message==='Node key mismatch')return 'historical_key_missing';
  if(error?.message==='Wrapped node key does not match key id')return 'node_key_invalid';
  if(e.ciphertext?.v===1)return 'legacy_vault_unavailable';
  if(kid && d?.keyId && kid!==d.keyId &&
     !(d?.historicalWrappedKeys||[]).some(k=>k.keyId===kid))return 'historical_key_missing';
  if(error?.name==='OperationError')return 'vault_key_mismatch_or_integrity';
  return 'unreadable_ciphertext';
}
function renderDecryptNotice(){
  const el=$('decryptNotice');if(!el)return;
  const failures=decryptedEvents.filter(e=>e.kind.startsWith('sms.')&&e.decryptError);
  if(!failures.length){el.hidden=true;el.textContent='';return;}
  const counts={};for(const e of failures){const code=e.decryptCategory||'unreadable_ciphertext';counts[code]=(counts[code]||0)+1;}
  const primary=Object.entries(counts).sort((a,b)=>b[1]-a[1])[0]?.[0]||'unreadable_ciphertext';
  const detail=tr('decrypt_issue_'+primary);
  el.hidden=false;
  el.textContent=tr('decrypt_notice',{count:failures.length})+' '+detail+' '+tr('decrypt_recovery_hint');
}
async function decryptEvent(e){const cipher=e.ciphertext;if(cipher&&cipher.v===1){const pt=await crypto.subtle.decrypt({name:'AES-GCM',iv:unb64u(cipher.iv),additionalData:enc.encode('simhub-event-v1')},vaultKey,unb64u(cipher.ct));return JSON.parse(dec.decode(pt));}if(!cipher||cipher.v!==2)throw new Error('Unsupported ciphertext version');let d=await deviceCrypto(e.deviceId);if(cipher.kid!==d.kid){const info=devices.find(x=>x.id===e.deviceId);if(info&&info.pendingKeyId===cipher.kid&&info.pendingWrappedKey){const raw=await unwrapNodeKey(info.pendingWrappedKey,info.pendingKeyId),key=await crypto.subtle.importKey('raw',raw,{name:'AES-GCM'},false,['decrypt']);d={raw:raw,key:key,kid:info.pendingKeyId,mode:'pending-node'};}else{const hist=(info?.historicalWrappedKeys||[]).find(k=>k.keyId===cipher.kid);if(hist){const raw=await unwrapNodeKey(hist.wrappedKey,hist.keyId),key=await crypto.subtle.importKey('raw',raw,{name:'AES-GCM'},false,['decrypt']);d={raw,key,kid:hist.keyId,mode:'retired-node'};}else{const legacy=await deriveLegacyDevice(e.deviceId);if(cipher.kid!==legacy.kid)throw new Error('Node key mismatch');d=legacy;}}}const aad=eventAad(e.deviceId,e.eventId,e.kind,e.occurredAt,e.subscriptionId,e.hasOtp),pt=await crypto.subtle.decrypt({name:'AES-GCM',iv:unb64u(cipher.iv),additionalData:enc.encode(aad)},d.key,unb64u(cipher.ct));return JSON.parse(dec.decode(pt));}
function versionAtLeast(v,target){const a=String(v||'0').split('.').map(Number),b=String(target).split('.').map(Number);for(let i=0;i<Math.max(a.length,b.length);i++){const x=a[i]||0,y=b[i]||0;if(x!==y)return x>y;}return true;}
async function encryptCommand(deviceId,outer,payload){const info=devices.find(x=>x.id===deviceId);if(!versionAtLeast(info&&info.appVersion,'0.1.5')){const iv=crypto.getRandomValues(new Uint8Array(12)),ct=new Uint8Array(await crypto.subtle.encrypt({name:'AES-GCM',iv:iv,additionalData:enc.encode('simhub-command-v1')},vaultKey,enc.encode(JSON.stringify(payload))));return {v:1,alg:'A256GCM',iv:b64u(iv),ct:b64u(ct)};}const d=await deviceCrypto(deviceId),iv=crypto.getRandomValues(new Uint8Array(12)),aad=commandAad(deviceId,outer.commandId,outer.type,outer.createdAt,outer.expiresAt,outer.idempotencyKey),ct=new Uint8Array(await crypto.subtle.encrypt({name:'AES-GCM',iv:iv,additionalData:enc.encode(aad)},d.key,enc.encode(JSON.stringify(payload))));return {v:2,alg:'A256GCM',kid:d.kid,iv:b64u(iv),ct:b64u(ct)};}

async function api(path,opts){opts=opts||{};const method=opts.method||'GET',body=Object.prototype.hasOwnProperty.call(opts,'body')?opts.body:null,headers={};if(body!==null)headers['Content-Type']='application/json';if(vaultKey&&Date.now()-lastActivity<60000)headers['X-SimHub-Activity']='1';if(!['GET','HEAD'].includes(method)&&path!=='/api/v1/auth/session'&&csrfToken)headers['X-SimHub-CSRF']=csrfToken;let res;
  // Only retry idempotent reads: never resend SMS, enrollment, or update POSTs.
  const safe=method==='GET'||method==='HEAD';
  for(let attempt=0;attempt<4;attempt++){
    try{
      res=await fetch(path,{method:method,headers:headers,body:body===null?undefined:JSON.stringify(body),cache:'no-store',credentials:'same-origin'});
      if(!safe||![502,503,504].includes(res.status)||attempt===3)break;
    }catch(error){
      if(!safe||attempt===3)throw error;
    }
    await new Promise(resolve=>setTimeout(resolve,250*Math.pow(2,attempt)));
  }
  let data={};try{data=await res.json();}catch(e){}if(!res.ok){if(res.status===401&&vaultKey&&path!=='/api/v1/auth/check'){authenticated=false;lockVault();}throw new Error(data.message||data.error||('HTTP '+res.status));}if(data.csrfToken)csrfToken=data.csrfToken;if(typeof data.totpRequired==='boolean')secondFactorIsTotp=data.totpRequired;
  if(typeof data.passwordConfigured==='boolean'){passwordConfigured=data.passwordConfigured;
    const status=$('adminPasswordStatus');if(status)status.textContent=passwordConfigured?'已设置独立管理员密码':'未设置独立密码：当前仍使用 Admin Token 登录';}if(typeof data.passkeyCount==='number')passkeyCount=data.passkeyCount;if(typeof data.sessionIdleTtlSeconds==='number')sessionIdleMs=Math.max(60000,data.sessionIdleTtlSeconds*1000);if(data.username&&$('username'))$('username').value=data.username;return data;}
async function establishSession(){
  try{const session=await api('/api/v1/auth/check');authenticated=true;return session;}catch{}
  const password=$('adminToken').value,username=$('username').value.trim();
  if(!username||!password)throw Error('请输入用户名和密码');
  const start=await api('/api/v1/auth/start',{method:'POST',body:{username,password}});
  $('adminToken').value='';
  let result=start;
  if(start.requiresTotp) {
    const totp=await promptLoginTotp();
    result=await api('/api/v1/auth/session',{method:'POST',body:{username,challengeId:start.challengeId,totp}});
  }
  authenticated=true;
  return result;
}
async function logoutSession(){try{await api('/api/v1/auth/logout',{method:'POST',body:{logout:true}});}catch(e){}authenticated=false;csrfToken='';stepUpUntil=0;clearTabVault();clearTrustedVault();}
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
  await finishLogin();
  toast('通行密钥登录成功');
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
function showUnlocked(){$('loggedInUser').hidden=false;$('loggedInUser').textContent=$('username').value.trim();$('newSmsBtn').hidden=true;refreshPasskeys().catch(()=>{});$('lockedPanel').hidden=true;$('appContent').hidden=false;$('lockBtn').hidden=false;$('vaultStatus').textContent=tr('vault_unlocked');setConnected(true);}
function purgeSensitiveUI(){
  // Hidden DOM is still observable to local browser extensions and scripts.
  // Wipe all decrypted data and one-time credentials, not merely app arrays.
  for(const id of ['inboxItems','conversationMessages','deviceList','smsPoolMembers','diagnosticsOutput','enrollLink','replyTo','replyBody','sendTo','sendBody','recoveryKey','gateRecoveryKey','newAdminPassword','adminToken','totp','passphrase','stepupValue','enrollName','search','passkeysList','nodeEndpointValue','deviceDetailContent','deviceDetailTitle','enrollFinishTitle','enrollFinishText','deviceSummary']){
    const el=$(id);if(!el)continue;
    if('value' in el)el.value='';
    if(id==='diagnosticsOutput')el.textContent='';
    else if(!('value' in el))el.replaceChildren();
  }
  // Never trust the scroll container to contain only authored child nodes.
  // Keep its pagination shell intact, but erase unexpected injected nodes as well.
  const inboxRoot=$('inboxList');
  if(inboxRoot){
    for(const child of [...inboxRoot.childNodes]){
      const known=child.nodeType===1 && (
        ['inboxItems','emptyInbox','loadSentinel'].includes(child.id) ||
        child.classList?.contains('pager-actions'));
      if(!known)child.remove();
    }
  }
  for(const id of ['deviceFilter','sendDevice','sendSubscription','replyDevice','replySubscription']){
    const el=$(id);if(el)el.replaceChildren();
  }
  const link=$('openEnroll');if(link){link.removeAttribute('href');link.hidden=true;}
  expandedDeviceDetails.clear();
  const enroll=$('enrollResult');if(enroll)enroll.hidden=true;
  if($('enrollQr'))$('enrollQr').replaceChildren();
  if($('pairCodeInput'))$('pairCodeInput').value='';
  if($('pairCodeStatus'))$('pairCodeStatus').textContent='';
  for(const id of ['enrollDialog','deviceDetailDialog','loginOtpDialog']){const d=$(id);if(d?.open)d.close();}
  nodeBaseUrl='';enrollStartingDevices.clear();
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
  poolState=null;devices=[];events=[];decryptedEvents=[];eventIds.clear();lastSeq=0;oldestSeq=0;
  initialEventsLoaded=false;olderCursor=null;historyHasMore=true;visibleCount=40;lastAutoScroll=-1;
  clearInterval(pollTimer);pollTimer=null;clearTimeout(autoLockTimer);autoLockTimer=null;
  clearTimeout(refreshTimer);refreshTimer=null;
  if(eventSource){eventSource.close();eventSource=null;}
  $('lockedPanel').hidden=false;$('appContent').hidden=true;$('lockBtn').hidden=true;
  clearTrustedVault();
  $('authStage').hidden=authenticated;$('vaultStage').hidden=!authenticated;
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

async function connectAndUnlock(){
  await establishSession();
  if(!authenticated)throw Error('请先登录');
  await unlockVault($('passphrase').value);
  await loadPhoneOverrides();showUnlocked();await fullRefresh();refreshVersionInfo().catch(()=>{});startRealtime();
  await storeTrustedVault();
}
async function loginFlow(){await establishSession();await finishLogin();}
async function importGateRecovery(){
  $('recoveryKey').value=$('gateRecoveryKey').value;
  await importRecoveryFlow();
  $('gateRecoveryKey').value='';
  await loadPhoneOverrides();showUnlocked();await fullRefresh();refreshVersionInfo().catch(()=>{});startRealtime();
  await storeTrustedVault();
}
async function createVaultFlow(){
  if(!authenticated)throw Error('请先登录');
  if(localStorage.getItem(VAULT_STORE))throw Error('此浏览器已有 Vault，请使用恢复密钥而不是创建新密钥');
  if(!confirm('仅首次创建管理池时使用：如果 Relay 已经存在加密短信，新 Vault 无法解密旧数据。确认创建？'))return;
  await createVault($('passphrase').value);
  await loadPhoneOverrides();showUnlocked();await fullRefresh();refreshVersionInfo().catch(()=>{});startRealtime();
  await storeTrustedVault();
}

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
  renderDevices();renderDeviceSelectors();updateReplyDevices();renderInbox();syncSendControls();
}
async function ingestEvents(batch,notify=true){
  const epoch=securityEpoch, dataGeneration=eventDataGeneration;
  let changed=false;
  for(const e of batch){
    if(epoch!==securityEpoch||dataGeneration!==eventDataGeneration||!vaultKey)return false;
    lastSeq=Math.max(lastSeq,e.seq||0);
    const id=e.deviceId+':'+e.eventId;
    if(eventIds.has(id))continue;
    // An SMS uploaded today can carry an older provider timestamp. Keep it visible
    // instead of discarding new Relay sequences solely due to message age.
    events.push(e);eventIds.add(id);changed=true;
    try{
      const payload=await decryptEvent(e),row=Object.assign({},e,{payload});
      if(epoch!==securityEpoch||!vaultKey)return false;
      decryptedEvents.push(row);if(notify)maybeNotify(row);
    }catch(err){if(epoch!==securityEpoch||!vaultKey)return false;decryptedEvents.push(Object.assign({},e,{payload:null,decryptError:true,decryptCategory:decryptFailureCategory(e,err)}));}
  }
  return changed;
}
async function loadEvents(){
  if(!vaultKey)return;
  const epoch=securityEpoch, dataGeneration=eventDataGeneration;
  if(!initialEventsLoaded){
    const head=await api('/api/v1/events?latest=1&limit=1');if(epoch!==securityEpoch||dataGeneration!==eventDataGeneration||!vaultKey)return;
    lastSeq=head.events?.[0]?.seq||0;
    const first=await api('/api/v1/events?order=occurred&limit=100');if(epoch!==securityEpoch||dataGeneration!==eventDataGeneration||!vaultKey)return;
    olderCursor=first.nextBeforeTime?{time:first.nextBeforeTime,seq:first.nextBeforeSeq}:null;
    historyHasMore=!!first.hasMore;
    await ingestEvents(first.events||[],false);
    if(epoch!==securityEpoch||!vaultKey)return;
    // Recent uploads can contain old-dated messages excluded by occurredAt paging.
    // Fetch the latest Relay sequence page too, without losing the original head.
    const newest=await api('/api/v1/events?latest=1&limit=100');
    if(epoch!==securityEpoch||dataGeneration!==eventDataGeneration||!vaultKey)return;
    await ingestEvents(newest.events||[],false);
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
  if(historyFetchInFlight)return historyFetchInFlight;
  const threads=buildThreads(filteredEvents());
  if(visibleCount<threads.length){visibleCount=Math.min(visibleCount+30,threads.length);renderInbox();return;}
  if(!historyHasMore||!olderCursor)return;
  const epoch=securityEpoch, dataGeneration=eventDataGeneration;
  historyFetchInFlight=(async()=>{
    const r=await api('/api/v1/events?order=occurred&limit=50&beforeTime='+olderCursor.time+'&beforeSeq='+olderCursor.seq);
    if(epoch!==securityEpoch||dataGeneration!==eventDataGeneration||!vaultKey)return;
    olderCursor=r.nextBeforeTime?{time:r.nextBeforeTime,seq:r.nextBeforeSeq}:null;
    historyHasMore=!!r.hasMore;
    await ingestEvents(r.events||[],false);
    if(epoch!==securityEpoch||!vaultKey)return;
    visibleCount=Math.min(Math.max(visibleCount+30,buildThreads(filteredEvents()).length),1000);
    renderInbox();
  })();
  try{await historyFetchInFlight;}finally{historyFetchInFlight=null;}
}

let refreshing=null;
async function fullRefresh(){
  if(refreshing)return refreshing;
  const epoch=securityEpoch;
  refreshing=(async()=>{try{await loadDevices();if(epoch!==securityEpoch||!vaultKey)return;await loadPool();if(epoch!==securityEpoch||!vaultKey)return;await loadEvents();if(epoch!==securityEpoch||!vaultKey)return;await loadCommandActivity();if(epoch!==securityEpoch||!vaultKey)return;await loadLifecycleHistory();if(epoch===securityEpoch&&vaultKey)setConnected(true);}catch(e){if(epoch===securityEpoch)setConnected(false);throw e;}finally{refreshing=null;}})();
  return refreshing;
}
function scheduleRefresh(){clearTimeout(refreshTimer);refreshTimer=setTimeout(()=>{if(vaultKey)fullRefresh().catch(e=>toast(e.message));},150);}
function startRealtime(){if(eventSource)eventSource.close();if('EventSource'in window){eventSource=new EventSource('/api/v1/stream',{withCredentials:true});eventSource.addEventListener('change',scheduleRefresh);eventSource.onopen=()=>setConnected(true);eventSource.onerror=()=>setConnected(false);}clearInterval(pollTimer);pollTimer=setInterval(()=>{if(vaultKey)fullRefresh().catch(()=>{});},60000);}

function deviceCanSendSms(d){
  if(!d||d.revoked||d.resetRequestedAt)return false;
  const state=d.state||{};
  // Modem agents are independent send-capable nodes, not Android SMS role holders.
  if(d.nodeType==='modem'||state.nodeType==='modem')
    return state.smsOperational!==false && nodeChannels(d).length>0;
  if(state.smsSendAllowed===true)return nodeChannels(d).length>0;
  // Conservative fallback for Android nodes which have not yet upgraded.
  return state.smsMode==='default' && state.smsSendPermission===true && nodeChannels(d).length>0;
}
function sendingDevices(){return devices.filter(deviceCanSendSms);}
function hasSmsSending(){return sendingDevices().length>0;}
function syncSendControls(){
  $('newSmsBtn').hidden=!vaultKey || !document.getElementById('view-inbox').classList.contains('active') || !hasSmsSending();
  if(activeConversationKey && !hasSmsSending())$('replyComposer').hidden=true;
}
function renderDeviceSelectors(){const filters=$('deviceFilter'),send=$('sendDevice'),oldF=filters.value,oldS=send.value;filters.innerHTML='<option value="">'+escapeHtml(tr('all_devices'))+'</option>'+devices.map(d=>'<option value="'+escapeHtml(d.id)+'">'+escapeHtml(d.name)+'</option>').join('');send.innerHTML=sendingDevices().map(d=>'<option value="'+escapeHtml(d.id)+'">'+escapeHtml(d.name)+'</option>').join('');if(Array.from(filters.options).some(o=>o.value===oldF))filters.value=oldF;if(Array.from(send.options).some(o=>o.value===oldS))send.value=oldS;updateSubscriptionSelector();}
function nodeChannels(d){
  if(!d||!d.state)return[];
  const channels=Array.isArray(d.state.channels)&&d.state.channels.length?d.state.channels:(d.state.subscriptions||[]).map(s=>Object.assign({id:s.channelId||String(s.subscriptionId),localId:String(s.subscriptionId),revision:s.channelRevision||1,kind:'android-sim'},s));
  const phones=d.state._phoneNumbers||[];
  const profiles=decryptedEvents.filter(e=>e.deviceId===d.id&&e.kind==='sim.profile'&&e.payload)
    .sort((a,b)=>(b.occurredAt||0)-(a.occurredAt||0));
  return channels.map(ch=>{
    const found=phones.find(n=>String(n.channelId)===String(ch.id));
    const profile=profiles.find(e=>String(e.payload.channelId)===String(ch.id)&&
      Number(e.payload.channelRevision||1)===Number(ch.revision||ch.channelRevision||1))?.payload;
    return {...ch,alias:profile?.tag||ch.alias,
      phoneNumber:String(phoneOverrides[phoneOverrideKey(d,ch)]||found?.number||
        (profile?.tail?'••••'+profile.tail:'')).trim()};
  });
}
function channelTitle(ch){return (ch.phoneNumber?ch.phoneNumber+' · ':'')+(ch.alias||ch.displayName||ch.carrierName||ch.id);}
function messageChannelLabel(e){
  const p=e.payload||{},revision=Number(p.channelRevision||0);
  if(!p.channelId||!revision)return tr('historical_sim_unverified');
  const d=devices.find(x=>x.id===e.deviceId);
  const channel=nodeChannels(d).find(ch=>String(ch.id)===String(p.channelId)&&
    Number(ch.revision||ch.channelRevision||1)===revision);
  return channel?channelTitle(channel):(p.simTag||p.channelId)+' · '+(getLocale()==='zh-CN'?'历史版本':'historical revision');
}
function updateSubscriptionSelector(){const d=devices.find(x=>x.id===$('sendDevice').value),channels=nodeChannels(d);$('sendSubscription').innerHTML=channels.map(ch=>'<option value="'+escapeHtml(String(ch.id))+'" data-local-id="'+escapeHtml(String(ch.localId==null?'':ch.localId))+'" data-revision="'+escapeHtml(String(ch.revision||ch.channelRevision||1))+'">'+escapeHtml(channelTitle(ch))+' · '+escapeHtml(ch.carrierName||ch.kind||'')+'</option>').join('')||'<option value="">'+escapeHtml(tr('no_active_channel'))+'</option>';}
function renderCommandActivity(commands){
  const box=$('commandActivity');if(!box||!vaultKey)return;
  const zh=getLocale()==='zh-CN';
  const types={'sms.send':zh?'发送短信':'SMS send','sms.sync_recent':zh?'同步最近短信':'Sync recent','sms.sync_older':zh?'同步更早短信':'Sync older','sms.sync_history':zh?'同步更早短信':'Sync older','diagnostics.request':zh?'设备诊断':'Diagnostics','device.refresh_state':zh?'刷新设备状态':'Refresh device','device.network_policy':zh?'联网策略':'Network policy','node.rotate_key':zh?'密钥轮换':'Key rotation','ota.install':zh?'远程 APK 更新':'Remote APK update','ota.cancel':zh?'取消远程更新':'Cancel remote update'};
  const statuses={queued:zh?'已排队':'Queued',dispatched:zh?'已下发':'Dispatched',submitted:zh?'已提交运营商':'Submitted',sent:zh?'已发送':'Sent',delivered:zh?'已送达':'Delivered',succeeded:zh?'节点已执行':'Executed',failed:zh?'失败':'Failed',rejected:zh?'已拒绝':'Rejected',expired:zh?'已过期':'Expired'};
  box.innerHTML=commands.length?commands.map(c=>{
    const detail=c.result||{};
    const status=c.type==='ota.install'
      ?({queued:zh?'等待设备领取':'Queued',dispatched:zh?'设备已领取':'Dispatched',
          submitted:zh?'设备正在处理更新':'Device updating',
          succeeded:zh?'更新已确认':'Update confirmed',
          failed:zh?'更新失败':'Update failed',
          rejected:zh?'设备拒绝':'Rejected',
          expired:zh?'命令过期':'Expired'}[c.state]||c.state||'—')
      :(statuses[c.state]||c.state||'—');
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
  lastSeq=0;oldestSeq=0;initialEventsLoaded=false;olderCursor=null;historyHasMore=true;visibleCount=40;lastAutoScroll=-1;
  activeConversationKey=null;
  $('smsLayout').classList.remove('conversation-open');
}
let updatePolling=null;
async function refreshUpdateInfo(){
  const zh=getLocale()==='zh-CN', el=$('updateStatus');
  if(!el)return;
  el.textContent=zh?'正在查询版本信息…':'Checking available updates…';
  let result;
  try{result=await api('/api/v1/update');}catch(error){el.textContent=error.message;return;}
  const phase=result.phase||'idle', latest=result.latest||null;
  const states={queued:'排队中',checking:'检查版本',downloading:'下载中',
    verifying_image:'验证签名并拉取镜像',verified_image:'已验证镜像',backup:'备份数据',switching:'切换容器',
    verifying:'正在验证',complete:'更新成功',rolling_back:'自动回滚中',
    rolled_back:'已回滚',failed:'更新失败',rollback_failed:'回滚失败',
    interrupted:'更新中断',idle:'就绪'};
  const status=states[phase]||phase;
  const ignored=result.ignoredLatest;
  const version=latest||'—';
  el.textContent=(zh?'服务端 ':'Server ')+'v'+(result.installed||'?')+
      (zh?' · 最新 v':' · Latest v')+version+' · '+status+
      (ignored?(zh?'（已忽略）':' (ignored)'):'')+
      (result.error?' · '+result.error:'');
  $('applyUpdateBtn').hidden=!result.agent||result.mode!=='rootless-verified'||!result.available||ignored||
    ['queued','checking','downloading','building','verifying_image','verified_image','backup','switching','verifying','rolling_back'].includes(phase);
  $('ignoreUpdateBtn').hidden=!latest||!result.available;
  $('ignoreUpdateBtn').textContent=ignored?(zh?'恢复提醒':'Restore reminders'):(zh?'忽略此版本':'Ignore this version');
  $('updateAgentHelp').textContent=(result.agent&&result.mode==='rootless-verified')?'':(zh?
    '安全更新未启用：请迁移到 Rootless Docker，以普通用户运行 scripts/install-updater.sh；不支持旧版 root 更新器。':
    'Verified updater unavailable. Configure rootless Docker and run scripts/install-updater.sh without sudo; legacy root updater is disabled.');
  if(result.releaseError)$('updateAgentHelp').textContent+=' · '+result.releaseError;
  clearTimeout(updatePolling);
  if(['queued','checking','downloading','building','verifying_image','verified_image','backup','switching','verifying','rolling_back'].includes(phase))
    updatePolling=setTimeout(()=>refreshUpdateInfo().catch(e=>toast(e.message)),3500);
}
async function applyServerUpdate(){
  const status=await api('/api/v1/update');
  if(!status.agent||status.mode!=='rootless-verified'||!status.available||status.ignoredLatest)throw new Error('No installable update');
  if(!confirm('将备份数据库、下载并构建 GitHub 正式版本 v'+status.latest+'，短暂重启服务。是否继续？'))return;
  await ensureStepUp();
  await api('/api/v1/update/apply',{method:'POST',body:{version:status.latest}});
  toast('升级任务已提交');await refreshUpdateInfo();
}
async function toggleIgnoreUpdate(){
  const status=await api('/api/v1/update');
  if(!status.latest)throw new Error('No release found');
  await ensureStepUp();
  await api('/api/v1/update/ignore',{method:'POST',
    body:{version:status.ignoredLatest?null:status.latest}});
  await refreshUpdateInfo();
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
  nodeBaseUrl=info.nodeBaseUrl||(!info.separateSurfaces?location.origin:'');
  $('nodeEndpointValue').value=nodeBaseUrl;
  $('copyNodeEndpoint').disabled=!nodeBaseUrl;
  $('nodeAddressHint').textContent=nodeBaseUrl?tr('ux_node_valid'):tr('ux_node_missing');
}

async function loadPool(){
  if(!vaultKey)return;
  poolState=await api('/api/v1/pool');
  renderPool();
}
function renderPool(){
  const target=$('smsPoolMembers');if(!target)return;
  if(!poolState){target.textContent='尚未加载设备同步状态';return;}
  target.innerHTML='<p class="hint">仅已在 Android 主动开启的设备可加入。管理员必须解锁 Vault 并批准加密密钥分发。</p>'+
    (poolState.rotationRequired?'<p role="alert" class="hint">已撤销成员访问，但新密钥尚未分发。共享上传已安全暂停。<button class="primary mini" data-pool-action="rotate">完成待处理密钥轮换</button></p>':'')+
    poolState.members.map(m=>{
      const d=devices.find(x=>x.id===m.id);
      const status=!m.requested?'待设备开启':m.approved?'已授权':'等待授权';
      const action=m.requested&&!m.approved?'<button class="primary mini" data-pool-action="approve" data-id="'+escapeHtml(m.id)+'">授权加入</button>':
        m.approved?'<button class="danger mini" data-pool-action="revoke" data-id="'+escapeHtml(m.id)+'">撤销共享</button>':'';
      return '<div class="row between wrap pool-member"><span><strong>'+escapeHtml(d?.name||m.name||m.id)+'</strong> · '+
        escapeHtml(status)+'</span>'+action+'</div>';
    }).join('')+'<small class="hint">密钥版本：'+escapeHtml(String(poolState.epoch||0))+'</small>';
}
async function poolVaultEncrypt(raw,epoch){
  const iv=crypto.getRandomValues(new Uint8Array(12));
  const ct=new Uint8Array(await crypto.subtle.encrypt(
    {name:'AES-GCM',iv,additionalData:enc.encode('simhub-pool-vault-v1|default|'+epoch)},vaultKey,raw));
  return {v:1,alg:'A256GCM',iv:b64u(iv),ct:b64u(ct)};
}
async function poolVaultDecrypt(envelope,epoch){
  const raw=await crypto.subtle.decrypt({name:'AES-GCM',iv:unb64u(envelope.iv),
    additionalData:enc.encode('simhub-pool-vault-v1|default|'+epoch)},vaultKey,unb64u(envelope.ct));
  return new Uint8Array(raw);
}
async function poolMemberEncrypt(deviceId,raw,epoch){
  const node=await deviceCrypto(deviceId),iv=crypto.getRandomValues(new Uint8Array(12));
  const ct=new Uint8Array(await crypto.subtle.encrypt({name:'AES-GCM',iv,
    additionalData:enc.encode('simhub-pool-member-v1|default|'+deviceId+'|'+epoch)},node.key,raw));
  return {v:1,alg:'A256GCM',iv:b64u(iv),ct:b64u(ct)};
}
async function poolNewKey(){
  const raw=crypto.getRandomValues(new Uint8Array(32));
  const digest=new Uint8Array(await crypto.subtle.digest('SHA-256',raw));
  return {raw,keyId:b64u(digest.slice(0,12))};
}
async function grantPoolDevice(deviceId){
  if(!vaultKey)throw new Error('Vault locked');
  await ensureStepUp();
  await loadPool();
  const state=poolState,m=state.members.find(m=>m.id===deviceId);
  if(!m?.requested)throw new Error('请先在 Android 设备开启共享');
  let raw,keyId,vaultEnvelope,epoch=state.epoch;
  if(!epoch){
    epoch=1;const k=await poolNewKey();raw=k.raw;keyId=k.keyId;
    vaultEnvelope=await poolVaultEncrypt(raw,epoch);
  }else{
    raw=await poolVaultDecrypt(state.vaultEnvelope,epoch);
    keyId=state.keyId;
    const digest=b64u(new Uint8Array(await crypto.subtle.digest('SHA-256',raw)).slice(0,12));
    if(digest!==keyId)throw new Error('共享密钥身份不匹配');
  }
  try{
    const memberEnvelope=await poolMemberEncrypt(deviceId,raw,epoch);
    const historyEnvelopes={};
    for(const past of (state.keys||[]).filter(k=>k.epoch<epoch)){
      const pastRaw=await poolVaultDecrypt(past.vaultEnvelope,past.epoch);
      try{
        const digest=b64u(new Uint8Array(await crypto.subtle.digest('SHA-256',pastRaw)).slice(0,12));
        if(digest!==past.keyId)throw new Error('历史池密钥身份不匹配');
        historyEnvelopes[String(past.epoch)]=await poolMemberEncrypt(deviceId,pastRaw,past.epoch);
      }finally{pastRaw.fill(0);}
    }
    await api('/api/v1/pool/authorize',{method:'POST',body:{deviceId,epoch,keyId,
      vaultEnvelope,memberEnvelope,historyEnvelopes}});
  }finally{raw.fill(0);}
  await loadPool();
}
async function rotatePool(){
  await ensureStepUp();
  await loadPool();
  if(!poolState?.epoch)return;
  const key=await poolNewKey(),epoch=poolState.epoch+1;
  try{
    const members={};
    for(const m of poolState.members.filter(m=>m.requested&&m.approved))
      members[m.id]=await poolMemberEncrypt(m.id,key.raw,epoch);
    const vaultEnvelope=await poolVaultEncrypt(key.raw,epoch);
    await api('/api/v1/pool/rotate',{method:'POST',body:{keyId:key.keyId,expectedEpoch:poolState.epoch,vaultEnvelope,members}});
  }finally{key.raw.fill(0);}
  await loadPool();
}
async function revokePoolDevice(deviceId){
  await ensureStepUp();
  await api('/api/v1/pool/revoke',{method:'POST',body:{deviceId}});
  try{await rotatePool();}catch(e){await loadPool();throw new Error('共享权限已撤销，但需要完成密钥轮换：'+e.message);}
}


function deviceButtons(d) {
  const action=(key,label,danger=false)=>'<button class="'+(danger?'danger':'ghost')+' mini" type="button" data-action="'+key+'" data-id="'+escapeHtml(d.id)+'">'+escapeHtml(label)+'</button>';
  const ota=d.state||{},sdk=Number(ota.sdk||0);
  const otaVisible=d.nodeType==='android'&&versionAtLeast(d.appVersion,'0.13.3')&&
    ota.remoteOtaSupported===true&&(sdk>=36 || ota.remoteOtaDeveloperOverride===true);
  const otaReady=otaVisible&&ota.remoteOtaAuthorized===true&&
    ota.remoteOtaInstallPermission===true;
  const zh=getLocale()==='zh-CN';
  const otaStage={checking:'检查版本',downloading:'下载中',verified:'已校验',
    installing:'安装中',awaiting_confirmation:'等待手机确认',
    succeeded:'已更新',failed:'更新失败',idle:'空闲'}[ota.remoteOtaStage]||'待检测';
  const otaLabel=zh?otaStage:(ota.remoteOtaStage||'idle');
  const otaCancelable=otaReady&&['checking','downloading','verified'].includes(ota.remoteOtaStage);
  const otaDetails=otaVisible?'<div class="action-group"><h4>'+
    (zh?'应用远程更新':'Remote app updates')+'</h4><p class="hint">'+
    escapeHtml(sdk<36?(zh?'开发者测试模式 · 无法保证免确认安装':'Developer test mode · silent install not guaranteed'):
      (zh?'Android 16+ · 系统决定是否需要确认安装':'Android 16+ · Android may request confirmation'))+
    ' · '+escapeHtml(otaLabel)+(ota.remoteOtaError?' · '+escapeHtml(ota.remoteOtaError):'')+
    '</p>'+(otaReady?action('ota-install',zh?'更新到最新正式版':'Update to latest stable'):'<p class="hint">'+
       escapeHtml(ota.remoteOtaAuthorized!==true
         ?(zh?'请先在安卓设备设置中允许远程应用更新':'Enable remote updates on the Android device first')
         :(zh?'请先在安卓设备上授权安装未知来源应用':'Grant the Android install-sources permission first'))+
    '</p>')+(otaCancelable?action('ota-cancel',zh?'取消未提交的更新':'Cancel pending update'):'')+'</div>':'';

  return '<div class="detail-actions">'+
    '<div class="action-group"><h4>'+escapeHtml(tr('ux_sms_actions'))+'</h4><div class="row wrap">'+
    action('refresh',tr('action_refresh'))+
    (d.nodeType==='android'?action('sync-recent',tr('sync_recent_100'))+action('sync-older',tr('sync_older_100')):'')+
    action('diagnostics',tr('action_diagnostics'))+
    (d.nodeType==='android'&&versionAtLeast(d.appVersion,'0.5.0')?action('network',tr('mobile_fallback')):'')+
    '</div></div>'+otaDetails+'<details class="action-group destructive-group"><summary>'+escapeHtml(tr('ux_security_actions'))+'</summary><div class="row wrap">'+
    (!d.keyId&&!d.pendingKeyId&&versionAtLeast(d.appVersion,'0.2.0')?action('rotate-key',tr('action_rotate')):'')+
    action('purge-sms',tr('ux_clear_relay'))+
    (!d.revoked? action('revoke',tr('action_revoke'),true):'')+
    (!d.resetRequestedAt&&!d.revoked?action('reset-device',tr('ux_unpair'),true):'')+
    action('force-delete',tr('ux_force_delete'),true)+
    '</div></details></div>';
}
function deviceStatusText(d) {
  return d.resetRequestedAt?tr('ux_reset_pending'):d.revoked?tr('revoked'):d.online?tr('online'):tr('offline');
}
function renderDeviceDetail(id) {
  const d=devices.find(x=>x.id===id);
  if(!d)return false;
  const s=d.state||{},subs=nodeChannels(d);
  $('deviceDetailTitle').textContent=d.name;
  const stat=(label,value)=>'<div class="detail-stat"><small>'+escapeHtml(label)+'</small><strong>'+escapeHtml(value==null?'—':String(value))+'</strong></div>';
  const simRows=subs.map(ch=>'<div class="sim-detail"><div><strong>'+escapeHtml(ch.displayName||ch.carrierName||ch.id||'SIM')+'</strong><p class="hint">'+escapeHtml(ch.phoneNumber||tr('phone_unknown'))+' · '+escapeHtml(ch.serviceState||'')+' · '+escapeHtml(tr('signal'))+' '+escapeHtml(ch.signalLevel==null?'—':ch.signalLevel)+'</p></div><button class="ghost mini" type="button" data-action="edit-sim" data-id="'+escapeHtml(d.id)+'" data-channel="'+escapeHtml(ch.id)+'">'+escapeHtml(tr('set_sim_phone'))+'</button></div>').join('');
  const content=$('deviceDetailContent');
  const previousScroll=content.scrollTop;
  content.innerHTML='<div class="detail-intro"><span class="status-pill '+(d.online?'online':'')+'">'+escapeHtml(deviceStatusText(d))+'</span><span>'+escapeHtml(d.model||d.nodeType||'Android')+' · v'+escapeHtml(d.appVersion||'—')+'</span></div>'+
    '<section class="detail-section"><h3>'+escapeHtml(tr('ux_overview'))+'</h3><div class="detail-stats">'+
    stat(tr('sms_status_label'),s.smsOperational===true?tr('sms_ready'):s.smsOperational===false?tr('sms_unavailable'):tr('sms_unverified'))+
     stat(tr('sms_mode_label'),s.smsMode==='default'?tr('sms_mode_default'):s.smsMode==='companion'?tr('sms_mode_companion'):'—')+
     stat(tr('sms_read_permission'),s.smsReadPermission===true?tr('permission_yes'):s.smsReadPermission===false?tr('permission_no'):'—')+
     stat(tr('sms_receive_permission'),s.smsReceivePermission===true?tr('permission_yes'):s.smsReceivePermission===false?tr('permission_no'):'—')+
     stat(tr('sms_send_permission'),s.smsSendPermission===true?tr('permission_yes'):s.smsSendPermission===false?tr('permission_no'):'—')+
    stat(tr('battery'),s.batteryPct==null?'—':s.batteryPct+'%')+
    stat(tr('network'),s.network||'—')+
    stat(tr('pending'),s.pendingEvents)+
    stat(tr('charging_state'),s.charging===true?tr('charging_now'):s.charging===false?tr('not_charging'):'—')+
    stat(tr('ux_fallback_state'),s.dataFallbackEnabled===true?(s.dataFallbackStatus||'待确认'):'未启用')+
    stat(tr('last_sync'),fmtTime(s.lastSyncSuccessAt))+
    stat('SMS Provider 变化',fmtTime(s.lastSmsProviderChangeAt))+
    stat('SMS 广播',fmtTime(s.lastSmsBroadcastAt))+
    stat('最近补扫',fmtTime(s.lastReconcileAt))+
    stat('待上传事件 / 命令 ACK',(s.pendingEvents??0)+' / '+(s.pendingCommandAcks??0))+
    stat('已上传回执',(s.uploadedEventReceipts??0))+
    stat('累计确认上传',(s.uploadedEventTotal??0))+
    stat('最近上传',fmtTime(s.lastEventUploadAt))+
    stat('上次上传条数',(s.lastEventUploadCount??0))+
    stat(tr('last_sms'),fmtTime(s.lastSmsReceivedAt))+
    stat(tr('ux_sms_count'),d.smsCount??0)+
    '</div></section>'+
    '<section class="detail-section"><h3>'+escapeHtml(tr('ux_channels'))+'</h3><div class="sim-detail-list">'+(simRows||'<p class="hint">'+escapeHtml(tr('no_subscriptions'))+'</p>')+'</div></section>'+
    (Array.isArray(s.pendingEventTasks)&&s.pendingEventTasks.length?
      '<section class="detail-section"><h3>'+escapeHtml(tr('queue_task_preview'))+'</h3><div class="detail-stats">'+
      s.pendingEventTasks.slice(0,10).map(t=>stat(String(t.kind||'unknown'),fmtTime(t.queuedAt))).join('')+
      '</div></section>':'')+
    ((s.lastUploadError||s.lastEventQueueError||s.lastSyncError||s.smsProviderError||s.stateCollectionError)?'<section class="detail-section"><h3>'+escapeHtml(tr('ux_sync_warning'))+'</h3><p class="warn detail-error">'+escapeHtml(s.lastUploadError||s.lastEventQueueError||s.lastSyncError||s.smsProviderError||s.stateCollectionError)+'</p></section>':'')+
    ((s.cryptoKeyId&&d.keyId&&s.cryptoKeyId!==d.keyId)?'<section class="detail-section"><p class="warn detail-error">'+escapeHtml(tr('decrypt_issue_device_key_diverged'))+' '+escapeHtml(tr('decrypt_recovery_hint'))+'</p></section>':'')+
    deviceButtons(d);
  content.scrollTop=previousScroll;
  return true;
}
function openDeviceDetail(id) {
  if(!vaultKey||!renderDeviceDetail(id))return;
  $('deviceDetailDialog').dataset.deviceId=id;
  if(!$('deviceDetailDialog').open)$('deviceDetailDialog').showModal();
}
function renderDevices() {
  const box=$('deviceList'),online=devices.filter(d=>d.online).length;
  $('deviceSummary').textContent=devices.length?tr('ux_device_count',{total:devices.length,online:online}):tr('ux_device_intro');
  if(!devices.length){
    box.innerHTML='<div class="empty card"><p>'+escapeHtml(tr('no_devices'))+'</p><p class="hint">'+escapeHtml(tr('ux_no_device_help'))+'</p></div>';
  }else{
    box.innerHTML=devices.map(d=>{
      const s=d.state||{},subs=nodeChannels(d),status=deviceStatusText(d);
      const chips=subs.slice(0,3).map(ch=>'<span class="sim-chip">'+escapeHtml(ch.displayName||ch.carrierName||ch.phoneNumber||'SIM')+'</span>').join('');
      return '<article class="card device-card"><button class="device-open" type="button" data-device-open="'+escapeHtml(d.id)+'" aria-label="查看 '+escapeHtml(d.name)+' 详情">'+
        '<span class="device-head"><span class="device-identity"><span class="device-icon" aria-hidden="true"><svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><rect x="7" y="2.5" width="10" height="19" rx="2.5"/><path d="M11 18h2"/></svg></span><span class="device-titles"><strong>'+escapeHtml(d.name)+'</strong><small>'+escapeHtml(d.model||d.nodeType||'SIM 节点')+' · '+escapeHtml(d.smsCount??0)+' SMS</small></span></span><span class="status-pill '+(d.online?'online':'')+'">'+escapeHtml(status)+'</span></span>'+
        '<span class="device-chips">'+(chips||'<span class="sim-chip">'+escapeHtml(tr('ux_not_reported'))+'</span>')+'</span>'+
        '<span class="device-card-footer"><span>'+escapeHtml(s.batteryPct==null?'电量 —':s.batteryPct+'%')+' · '+escapeHtml(s.network||tr('ux_unknown_network'))+'</span><span>详情 ›</span></span>'+
        '</button></article>';
    }).join('');
  }
  const detail=$('deviceDetailDialog');
  if(detail?.open&&!renderDeviceDetail(detail.dataset.deviceId))detail.close();
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
  const p=e.payload||{},id=String(p.channelId||''),revision=Number(p.channelRevision||0);
  // Android subscription IDs may be recycled after a SIM change.
  if(!id||!revision)return 'unverified:'+String(e.subscriptionId||'');
  return id;
}
function canonicalAddress(value){
  const clean=String(value||'').trim().replace(/[\s()\-]/g,'').toLowerCase();
  const intl=clean.startsWith('00')?'+'+clean.slice(2):clean;
  return /^\+861[3-9]\d{9}$/.test(intl)?intl.slice(3):intl;
}
function threadKey(e){return canonicalAddress(messageAddress(e));}
function eventSimKey(e){
  const p=e.payload||{},ch=String(p.channelId||''),rev=Number(p.channelRevision||0);
  return e.deviceId+'|'+(ch&&rev?ch+'|'+rev:'unverified:'+String(p.subscriptionId??e.subscriptionId??-1));
}
function refreshSimFilter(){
  const el=$('simFilter'),prior=el.value;
  const known=new Map();
  for(const e of decryptedEvents){
    if(!e.kind.startsWith('sms.'))continue;
    const key=eventSimKey(e);
    if(!known.has(key))known.set(key,deviceName(e.deviceId)+' · '+messageChannelLabel(e));
  }
  el.innerHTML='<option value="">'+escapeHtml(tr('all_sims'))+'</option>'+
    [...known.entries()].sort((a,b)=>a[1].localeCompare(b[1])).map(([key,label])=>
      '<option value="'+escapeHtml(key)+'">'+escapeHtml(label)+'</option>').join('');
  if([...el.options].some(o=>o.value===prior))el.value=prior;
}
function filteredEvents(){
  const q=$('search').value.trim().toLowerCase(),dev=$('deviceFilter').value,
    sim=$('simFilter').value,kind=$('kindFilter').value;
  return collapseMessageEvents(decryptedEvents).filter(e=>
    e.kind.startsWith('sms.')&&(!dev||e.deviceId===dev)&&(!sim||eventSimKey(e)===sim)&&
    (!kind||e.kind===kind))
    .filter(e=>{if(!q)return true;const p=e.payload||{};
      return [p.body,p.sender,p.recipient,p.contactName,p.otp&&p.otp.value,deviceName(e.deviceId)]
        .some(v=>String(v||'').toLowerCase().includes(q));});
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
  refreshSimFilter();
  syncSendControls();
  renderDecryptNotice();
  const threads=buildThreads(filteredEvents()),show=threads.slice(0,visibleCount);
  const list=$('inboxList'),previousTop=list.scrollTop;
  $('emptyInbox').hidden=!!show.length;
  $('loadOlderBtn').hidden=!historyHasMore&&threads.length<=visibleCount;
  $('loadNewerBtn').hidden=true;
  const otpCount=threads.reduce((n,t)=>n+t.messages.filter(e=>eventIsInbound(e)&&e.payload?.otp?.value).length,0);
  $('otpBadge').hidden=!otpCount;$('otpBadge').textContent=otpCount?String(otpCount):'';
  $('inboxItems').innerHTML=show.map(t=>{
    const e=t.latest,p=e.payload||{},who=p.contactName||messageAddress(e)||tr('unknown');
    const body=e.decryptError?'['+tr('decrypt_failed')+']':p.body||'';
    return '<button type="button" class="message'+(activeConversationKey===t.key?' active':'')+
      '" data-thread="'+escapeHtml(t.key)+'"><span class="avatar">'+escapeHtml(who.slice(0,1).toUpperCase())+
      '</span><span class="message-body"><span class="message-title"><strong>'+escapeHtml(who)+
      '</strong><span class="message-meta-right"><span class="message-sim-tag" title="'+escapeHtml(messageChannelLabel(e))+'">'+
      escapeHtml(messageChannelLabel(e))+'</span><small class="meta">'+fmtTime(e.occurredAt)+'</small></span></span>'+
      '<span class="message-preview">'+escapeHtml(body)+'</span><small class="meta">'+
      escapeHtml(deviceName(e.deviceId))+'</small></span></button>';
  }).join('');
  list.scrollTop=previousTop;
  renderConversation();
}
function updateReplyDevices(){
  const el=$('replyDevice'),chosen=el.value;
  el.innerHTML='<option value="">'+escapeHtml(tr('sim_node'))+'</option>'+
    sendingDevices().map(d=>'<option value="'+escapeHtml(d.id)+'">'+escapeHtml(d.name)+'</option>').join('');
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
function messageTags(e){
  const inbound=eventIsInbound(e),d=devices.find(x=>x.id===e.deviceId),id=messageChannel(e),
    revision=Number(e.payload?.channelRevision||0),
    historic=!e.payload?.channelId||!revision,
    channel=historic?null:nodeChannels(d).find(ch=>String(ch.id)===id&&
      Number(ch.revision||ch.channelRevision||1)===revision),
    digits=String(e.payload?.simTail||channel?.phoneNumber||'').replace(/\D/g,''),
    sim=historic?(getLocale()==='zh-CN'?'历史 SIM · 归属待确认':'Historical SIM · unverified'):
      (e.payload?.simTag||channel?.alias||channel?.displayName||channel?.carrierName||'SIM')+(digits.length>=4?' · ••••'+digits.slice(-4):' · '+(getLocale()==='zh-CN'?'号码未知':'number unknown')),
    status=inbound?tr('received'):(e.kind==='sms.failed'?tr('failed'):e.kind==='sms.delivered'?'✓✓':tr('sent'));
  return [fmtTime(e.occurredAt),status,deviceName(e.deviceId),sim].map((v,i)=>
    '<span class="message-tag'+(historic&&i===3?' warning':'')+'">'+escapeHtml(v)+'</span>').join('');
}
function renderConversation(){
  if(!activeConversationKey){
    $('conversationTitle').textContent=tr('choose_conversation');
    $('conversationMeta').textContent='';
    $('conversationMessages').innerHTML='<p class="hint">'+escapeHtml(tr('choose_conversation_hint'))+'</p>';
    $('replyComposer').hidden=true;
    return;
  }
  $('replyComposer').hidden=!hasSmsSending();
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
      '</p>'+otp+'<div class="message-tags">'+messageTags(e)+'</div></div>';
  }).join('');
  pane.dataset.threadKey=activeConversationKey;
  if(previousKey!==activeConversationKey||nearBottom)pane.scrollTop=pane.scrollHeight;
  else pane.scrollTop=oldScroll;
}
function replyEligible(){
  const device=$('replyDevice').value,channel=$('replySubscription').value,dest=$('replyTo').value.trim();
  if(!deviceCanSendSms(devices.find(d=>d.id===device))||!channel||!/^\+?[0-9 ()-]{3,40}$/.test(dest))return false;
  if(activeConversationKey==='__new')return true;
  const selected=buildThreads(collapseMessageEvents(decryptedEvents)).find(t=>t.key===activeConversationKey);
  const p=selected?.latest?.payload||{},revision=Number(p.channelRevision||0);
  const current=nodeChannels(devices.find(d=>d.id===device)).find(ch=>String(ch.id)===channel);
  if(!current)return false;
  return selected?.messages.some(e=>e.deviceId===device &&
    String(e.payload?.channelId||'')===channel &&
    Number(e.payload?.channelRevision||0)===Number(current.revision||current.channelRevision||1))===true;
}
function refreshReplyEligibility(){$('replySend').disabled=!replyEligible();}
function openConversation(key){
  const selected=buildThreads(collapseMessageEvents(decryptedEvents)).find(x=>x.key===key);
  if(!selected)return;
  activeConversationKey=key;
  const last=selected.latest,number=messageAddress(last);
  const eligible=[...selected.messages].reverse().find(e=>{
    const d=devices.find(x=>x.id===e.deviceId);
    const ch=nodeChannels(d).find(c=>String(c.id)===String(e.payload?.channelId||''));
    return deviceCanSendSms(d) && ch &&
      Number(ch.revision||ch.channelRevision||1)===Number(e.payload?.channelRevision||0);
  });
  const did=eligible?.deviceId||'',channel=eligible?messageChannel(eligible):'';
  updateReplyDevices();$('replyDevice').value=did;updateReplyChannels(channel);
  $('replyTo').value=number;
  $('replyOptions').open=false;
  const canReply=!!eligible&&replyEligible();
  refreshReplyEligibility();
  if(!canReply && hasSmsSending()){$('replyOptions').open=true;toast('原会话没有可用的发送 SIM；请新建短信并选择有效通道');}
  syncResponsiveConversation();
  renderInbox();
}
function openNewMessage(){
  if(!hasSmsSending())return;
  switchView('inbox');activeConversationKey='__new';
  updateReplyDevices();
  $('replyDevice').value='';
  updateReplyChannels();
  $('replyTo').value='';$('replyBody').value='';refreshReplyEligibility();
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
  if(!replyEligible())throw new Error('历史短信来源或当前 SIM 版本无法验证；请新建短信并显式选择通道');
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
async function queueCommand(deviceId,type,payload,ttl){if(type==='sms.send'&&!deviceCanSendSms(devices.find(d=>d.id===deviceId)))throw new Error('该设备当前不允许发送短信');if(type==='sms.send'||type==='node.rotate_key'||type==='device.network_policy'||type==='ota.install'||type==='ota.cancel')await ensureStepUp();const target=devices.find(d=>d.id===deviceId),power=target?.state||{};
  const energy=power.effectiveEnergyMode||power.energyMode||'balanced';
  // A compact background policy must not make remote commands expire before
  // the next eligible fetch. Preserve a finite expiry and the encrypted AAD.
  const minimumTtl=(!power.foregroundRelay||energy==='eco')?1200:(energy==='balanced'?300:180);
  ttl=Math.max(ttl||120,minimumTtl);
  const createdAt=Math.floor(Date.now()/1000),commandId=uuid(),idempotencyKey=commandId,expiresAt=createdAt+ttl,inner=Object.assign({v:2,action:type,commandId:commandId,issuedAt:createdAt,expiresAt:expiresAt},payload),outer={commandId:commandId,idempotencyKey:idempotencyKey,type:type,createdAt:createdAt,expiresAt:expiresAt};outer.ciphertext=await encryptCommand(deviceId,outer,inner);return api('/api/v1/devices/'+encodeURIComponent(deviceId)+'/commands',{method:'POST',body:outer});}
async function sendSms(){const deviceId=$('sendDevice').value,select=$('sendSubscription'),channelId=select.value,opt=select.selectedOptions[0],to=$('sendTo').value.trim(),body=$('sendBody').value;if(!deviceCanSendSms(devices.find(d=>d.id===deviceId)))throw new Error('该设备当前是只读短信模式，不能发送');if(!deviceId||!channelId)throw new Error('Choose an online SIM Node and SMS channel.');if(!/^\+?[0-9 ()-]{3,40}$/.test(to))throw new Error('Recipient number format is invalid.');if(!body.trim())throw new Error('Message is empty.');const selected=devices.find(x=>x.id===deviceId);if(selected&&selected.state&&selected.state.smsOperational===false)throw new Error('The selected node reports SMS unavailable. Fix the SMS role, permissions or SIM first.');if(selected?.state?.effectiveEnergyMode==='eco'||selected?.state?.energyMode==='eco'||selected?.state?.foregroundRelay===false){
  if(!confirm((getLocale()==='zh-CN'?'该设备使用省电/非前台模式，发送可能延迟约 15 分钟。请确认接收方及发送时效要求。':'This device may check remote commands only every 15 minutes. Confirm the recipient and acceptable delay.')))return;
}
if(!confirm(tr('confirm_send',{device:deviceName(deviceId),to:to})))return;const localId=opt?opt.dataset.localId:'',revision=opt?Number(opt.dataset.revision||1):1,payload={channelId:channelId,channelRevision:revision,to:to,body:body};if(/^\d+$/.test(localId))payload.subscriptionId=Number(localId);await queueCommand(deviceId,'sms.send',payload,180);$('sendBody').value='';updateCharCount();toast('Encrypted SMS command queued');}
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
    const qr=$('enrollQr');qr.replaceChildren();
    if(type==='android'&&typeof QRCode!=='undefined')new QRCode(qr,{text:value,width:248,height:248,correctLevel:QRCode.CorrectLevel.L});
    toast(tr(type==='modem'?'enroll_modem_done':'enroll_android_done'));
  }finally{
    nodeRaw.fill(0);bootstrapRaw.fill(0);
  }
}
async function sha256Hex(text){
  const bytes=new Uint8Array(await crypto.subtle.digest('SHA-256',enc.encode(text)));
  return Array.from(bytes,b=>b.toString(16).padStart(2,'0')).join('');
}

/** Credential-free QR for pairing a management console (not a SIM node). */
function managementControllerLink(origin){
  const url=new URL(origin);
  if(url.protocol!=='https:'||url.username||url.password||url.search||url.hash||url.pathname!=='/')
    throw new Error('管理端需要有效的 HTTPS 根域名才能生成二维码');
  return 'simhub://controller?url='+encodeURIComponent(url.origin);
}
function openControllerConnect(){
  const qr=$('controllerQr'),hint=$('controllerConnectHint');
  const dialog=$('controllerConnectDialog');
  $('controllerConnectLink').value='';qr.replaceChildren();
  $('controllerCopyLink').disabled=false;$('controllerCopyAddress').disabled=false;
  try{
    const link=managementControllerLink(location.origin);
    $('controllerConnectLink').value=link;
    if(typeof QRCode!=='undefined'){
      new QRCode(qr,{text:link,width:232,height:232,correctLevel:QRCode.CorrectLevel.M});
      hint.textContent='扫码只会保存本服务器地址，手机仍需账号、OTP 及 Vault 授权。';
    }else{
      hint.textContent='二维码组件不可用，请复制配置链接。';
    }
  }catch(e){
    hint.textContent=e.message;
    $('controllerCopyLink').disabled=true;
    $('controllerCopyAddress').disabled=true;
  }
  dialog.showModal();
}
function closeControllerConnect(){
  $('controllerConnectDialog').close();
  $('controllerQr').replaceChildren();
}
function setSettingsCategory(category,open=true) {
  const titles={security:tr('ux_account_security'),notifications:tr('notifications'),system:tr('ux_system_updates')};
  const root=document.querySelector('.settings-shell');
  if(!root||!titles[category])return;
  root.dataset.category=category;
  root.classList.toggle('has-selection',open);
  $('settingsCategoryTitle').textContent=titles[category];
  root.querySelectorAll('[data-settings-link]').forEach(button=>{
    const selected=button.dataset.settingsLink===category;
    button.classList.toggle('active',selected);
    button.setAttribute('aria-current',selected?'page':'false');
  });
  root.querySelectorAll('[data-settings-group]').forEach(panel=>{panel.hidden=panel.dataset.settingsGroup!==category;});
}
function updateEnrollUI() {
  const body=$('enrollCard');
  body.dataset.enrollStep=String(enrollStep);
  body.dataset.enrollMode=enrollMode;
  document.querySelectorAll('[data-progress]').forEach(el=>{
    const step=Number(el.dataset.progress);
    el.classList.toggle('current',step===enrollStep);
    el.classList.toggle('done',step<enrollStep);
  });
  document.querySelectorAll('[data-enroll-mode-choice]').forEach(el=>{
    const selected=el.dataset.enrollModeChoice===enrollMode;
    el.classList.toggle('selected',selected);el.setAttribute('aria-pressed',String(selected));
    el.disabled=el.dataset.enrollModeChoice==='code'&&$('enrollType').value!=='android';
  });
  $('enrollBack').hidden=enrollStep===1;
  $('enrollNext').textContent=enrollStep===1?tr('ux_next'):enrollStep===2?tr('ux_continue'):tr('ux_finish');
  $('enrollNext').hidden=enrollStep===2&&enrollMode==='code';
}
function setEnrollMode(mode) {
  if(mode==='code'&&$('enrollType').value!=='android'){toast(tr('ux_invalid_method'));return;}
  enrollMode=mode;updateEnrollUI();
}
function showEnrollStep(step) {
  enrollStep=Math.max(1,Math.min(3,step));
  updateEnrollUI();
}
function closeEnrollDialog() {
  if($('enrollDialog').open)$('enrollDialog').close();
  $('enrollLink').value='';$('enrollQr').replaceChildren();$('enrollResult').hidden=true;
  $('pairCodeInput').value='';$('pairCodeStatus').textContent='';
  $('enrollName').value='';$('openEnroll').removeAttribute('href');$('openEnroll').hidden=true;
}
async function openEnrollDialog() {
  if(!vaultKey){toast(tr('vault_locked'));return;}
  enrollStartingDevices=new Set(devices.map(d=>d.id));
  $('enrollType').value='android';
  setEnrollMode('package');
  showEnrollStep(1);
  $('enrollDialog').showModal();
  refreshVersionInfo().catch(error=>{$('nodeAddressHint').textContent=error.message;});
}
async function advanceEnroll() {
  if(enrollStep===1){
    if(enrollMode==='code'&&$('enrollType').value!=='android')throw Error(tr('ux_invalid_method'));
    showEnrollStep(2);return;
  }
  if(enrollStep===2){
    if(enrollMode==='package'&&$('enrollResult').hidden)throw Error(tr('ux_missing_package'));
    showEnrollStep(3);return;
  }
  closeEnrollDialog();await fullRefresh();
}
async function refreshEnrolledDeviceStatus() {
  await fullRefresh();
  const connected=devices.filter(d=>!enrollStartingDevices.has(d.id));
  if(connected.length){
    $('enrollFinishTitle').textContent=tr('ux_new_device');
    $('enrollFinishText').textContent=tr('ux_new_device_note',{names:connected.map(d=>d.name).join(', ')});
  }else{
    $('enrollFinishTitle').textContent=tr('ux_pending_title');
    $('enrollFinishText').textContent=tr(enrollMode==='code'?'ux_pending_code':'ux_pending_package');
  }
}

async function approveDevicePairCode(){
  if(!vaultRaw||!vaultKey)throw Error(tr('ux_pair_unlock'));
  const code=$('pairCodeInput').value.trim();
  if(!/^\d{8}$/.test(code))throw Error(tr('ux_pair_length'));
  const epoch=securityEpoch;
  const item=await api('/api/v1/pairings/lookup?code='+encodeURIComponent(code));
  if(epoch!==securityEpoch||!vaultKey)throw Error('Vault 已锁定');
  const fingerprint=(await sha256Hex(item.publicKey)).slice(0,12).toUpperCase();
  $('pairCodeStatus').textContent=tr('ux_pair_candidate',{name:item.name,model:item.model,fingerprint:fingerprint});
  if(!confirm(tr('ux_pair_confirm',{fingerprint:fingerprint,name:item.name})))return;
  await ensureStepUp();
  if(epoch!==securityEpoch||!vaultKey)throw Error('Vault 已锁定');
  const nodeRaw=crypto.getRandomValues(new Uint8Array(32));
  const tokenBytes=crypto.getRandomValues(new Uint8Array(48));
  const deviceToken=b64u(tokenBytes);
  let shared;
  try{
    const keyId=await keyIdForRaw(nodeRaw);
    const wrappedKey=await wrapNodeKey(nodeRaw,keyId);
    const peer=await crypto.subtle.importKey('spki',unb64u(item.publicKey),{name:'ECDH',namedCurve:'P-256'},false,[]);
    const ephemeral=await crypto.subtle.generateKey({name:'ECDH',namedCurve:'P-256'},true,['deriveBits']);
    shared=new Uint8Array(await crypto.subtle.deriveBits({name:'ECDH',public:peer},ephemeral.privateKey,256));
    const base=await crypto.subtle.importKey('raw',shared,'HKDF',false,['deriveKey']);
    const key=await crypto.subtle.deriveKey({name:'HKDF',hash:'SHA-256',salt:enc.encode('simhub-pair-v1|'+item.requestId),info:enc.encode('node-key')},base,{name:'AES-GCM',length:256},false,['encrypt']);
    const iv=crypto.getRandomValues(new Uint8Array(12));
    const plaintext=enc.encode(JSON.stringify({nodeKey:b64u(nodeRaw),deviceToken}));
    const ct=new Uint8Array(await crypto.subtle.encrypt({name:'AES-GCM',iv,additionalData:enc.encode('simhub-pair-v1|'+item.requestId+'|'+keyId)},key,plaintext));
    const spki=b64u(new Uint8Array(await crypto.subtle.exportKey('spki',ephemeral.publicKey)));
    await api('/api/v1/pairings/'+encodeURIComponent(item.requestId)+'/approve',{method:'POST',body:{
      code,keyId,wrappedKey,
      envelope:{v:1,alg:'A256GCM',iv:b64u(iv),ct:b64u(ct),publicKey:spki},
      deviceTokenHash:await sha256Hex(deviceToken),
      completeProofHash:await sha256Hex('simhub-pair-complete-v1|'+item.requestId+'|'+deviceToken)
    }});
    $('pairCodeStatus').textContent=tr('ux_pair_approved',{name:item.name});
    $('pairCodeInput').value='';
    showEnrollStep(3);
    await refreshEnrolledDeviceStatus();
  }finally{
    nodeRaw.fill(0);tokenBytes.fill(0);if(shared)shared.fill(0);
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
  if(action==='ota-cancel'){
    if(!confirm(zh?'仅能取消尚未提交系统安装器的更新。继续？':'Cancel this update if installation has not been committed?'))return;
    await queueCommand(id,'ota.cancel',{},900);
    toast(zh?'已请求取消；以设备返回的状态为准':'Cancellation requested; wait for device confirmation');
    return;
  }
  if(action==='ota-install'){
    const state=d.state||{};
    if(d.nodeType!=='android'||!versionAtLeast(d.appVersion,'0.13.3')||
       state.remoteOtaSupported!==true||
       (Number(state.sdk||0)<36&&state.remoteOtaDeveloperOverride!==true)||
       state.remoteOtaAuthorized!==true||state.remoteOtaInstallPermission!==true)
      throw new Error(zh?'该设备未满足远程更新授权条件':'Remote update is not authorized on this device');
    if(!confirm(zh
      ?'只会安装 SIM Hub 官方 GitHub Release 的更高签名版本。Android 仍可能要求设备端确认；升级后将尝试恢复中继。立即向 '+d.name+' 下发更新？'
      :'Only a newer signed SIM Hub GitHub Release will be installed. Android may require device confirmation. Update '+d.name+'?'))return;
    await queueCommand(id,'ota.install',{targetVersion:'latest'},3600);
    toast(zh?'远程更新已排队；并不代表安装完成':'Remote update queued, not yet installed');
    await loadCommandActivity();
    return;
  }
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
  if(name==='settings'&&vaultKey)refreshUpdateInfo().catch(()=>{});
  if(name!=='inbox'){
    // Preserve the selected thread for desktop, but never leave a mobile overlay
    // floating above another section or behind the persistent bottom dock.
    $('smsLayout').classList.remove('conversation-open');
  }
  document.querySelectorAll('.nav').forEach(x=>x.classList.toggle('active',x.dataset.view===name));document.querySelectorAll('.view').forEach(x=>x.classList.toggle('active',x.id==='view-'+name));$('viewTitle').textContent=tr(titleKeys[name][0]);$('viewSubtitle').textContent=tr(titleKeys[name][1]);if(name==='send')updateSubscriptionSelector();syncSendControls();syncResponsiveConversation();}

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
  updateEnrollUI();
  const category=document.querySelector('.settings-shell')?.dataset.category||'security';
  setSettingsCategory(category,document.querySelector('.settings-shell')?.classList.contains('has-selection'));
  updateCharCount();
}

function wire(){
  applyI18n();
  $('gateHint').textContent=localStorage.getItem(VAULT_STORE)?tr('local_vault_found'):tr('no_local_vault');
  $('languageSelect').onchange=()=>{setLocale($('languageSelect').value);relocalizeDynamic();};
  $('loginForm').onsubmit=e=>{e.preventDefault();loginFlow().catch(err=>toast(err.message));};
  $('vaultUnlockBtn').onclick=()=>connectAndUnlock().catch(e=>toast(e.message));
  $('gateImportRecovery').onclick=()=>importGateRecovery().catch(e=>toast(e.message));
  const leaveAccount=async()=>{await logoutSession();lockVault();toast('已退出管理员登录');};
  $('vaultLogoutBtn').onclick=()=>leaveAccount().catch(e=>toast(e.message));
  $('logoutBtn').onclick=()=>leaveAccount().catch(e=>toast(e.message));
  const trust=trustedOptions();
  $('vaultTrustEnabled').checked=trust.enabled;$('vaultTrustDuration').value=String(trust.duration);
  $('vaultTrustEnabled').onchange=()=>updateTrustedOptions().catch(e=>{toast(e.message);$('vaultTrustEnabled').checked=trustedOptions().enabled;});
  $('vaultTrustDuration').onchange=()=>updateTrustedOptions().catch(e=>{toast(e.message);$('vaultTrustDuration').value=String(trustedOptions().duration);});
  $('passkeyLoginBtn').onclick=()=>passkeyLogin().catch(e=>toast(e.message));
  $('registerPasskeyBtn').onclick=()=>registerPasskey().catch(e=>toast(e.message));
  $('passkeysList').onclick=e=>{const b=e.target.closest('button[data-passkey-id]');if(b)removePasskey(b.dataset.passkeyId).catch(err=>toast(err.message));};
  $('createVaultBtn').onclick=()=>createVaultFlow().catch(e=>toast(e.message));
  $('importKeyBtn').onclick=()=>importRecoveryFlow().catch(e=>toast(e.message));
  $('lockBtn').onclick=lockVault;
  $('refreshBtn').onclick=()=>vaultKey?fullRefresh().catch(e=>toast(e.message)):toast(tr('vault_locked'));
  document.querySelectorAll('.nav').forEach(b=>b.onclick=()=>switchView(b.dataset.view));
  $('search').oninput=()=>{visibleCount=40;lastAutoScroll=-1;renderInbox();};
  $('deviceFilter').onchange=()=>{visibleCount=40;lastAutoScroll=-1;renderInbox();};
  $('kindFilter').onchange=()=>{visibleCount=40;lastAutoScroll=-1;renderInbox();};
  $('simFilter').onchange=()=>{visibleCount=40;lastAutoScroll=-1;renderInbox();};
  $('sendDevice').onchange=updateSubscriptionSelector;
  $('sendBody').oninput=updateCharCount;
  $('newSmsBtn').onclick=openNewMessage;
  $('backConversation').onclick=()=>{activeConversationKey=null;syncResponsiveConversation();renderInbox();$('search').focus({preventScroll:true});};
  window.addEventListener('resize',syncResponsiveConversation,{passive:true});
  window.addEventListener('orientationchange',syncResponsiveConversation,{passive:true});
  $('replyDevice').onchange=()=>{updateReplyChannels();refreshReplyEligibility();};
  $('replySubscription').onchange=()=>{updateReplyChannels($('replySubscription').value);refreshReplyEligibility();};
  $('replyBody').oninput=updateReplyCount;
  $('replyTo').oninput=refreshReplyEligibility;
  $('replySend').onclick=()=>sendConversationReply().catch(e=>toast(e.message));
  $('conversationMessages').onclick=e=>{const b=e.target.closest('.copy-otp');if(b)copy(b.dataset.otp,tr('otp_copied')).catch(err=>toast(err.message));};
  $('loadOlderBtn').onclick=()=>loadOlder().catch(e=>toast(e.message));
  const inboxScroller=$('inboxList');
  const maybeLoadMore=()=>{
    if(!vaultKey||historyFetchInFlight||(!historyHasMore&&visibleCount>=buildThreads(filteredEvents()).length))return;
    if(inboxScroller.scrollTop<lastAutoScroll+16)return;
    if(inboxScroller.scrollTop+inboxScroller.clientHeight<inboxScroller.scrollHeight-240)return;
    lastAutoScroll=inboxScroller.scrollTop;
    loadOlder().catch(e=>toast(e.message));
  };
  inboxScroller.addEventListener('scroll',maybeLoadMore,{passive:true});
  if('IntersectionObserver' in window){
    loadObserver=new IntersectionObserver(entries=>{
      if(entries[0]?.isIntersecting&&inboxScroller.scrollTop>0)maybeLoadMore();
    },{root:inboxScroller,rootMargin:'0px 0px 240px 0px'});
    loadObserver.observe($('loadSentinel'));
  }
  $('loadNewerBtn').onclick=()=>{$('inboxList').scrollTop=0;};
  $('sendBtn').onclick=()=>sendSms().catch(e=>toast(e.message));
  $('controllerConnectBtn').onclick=openControllerConnect;
  $('controllerConnectClose').onclick=closeControllerConnect;
  $('controllerConnectDialog').addEventListener('cancel',()=>queueMicrotask(()=> $('controllerQr').replaceChildren()));
  $('controllerCopyLink').onclick=()=>copy($('controllerConnectLink').value,'已复制管理配置链接').catch(e=>toast(e.message));
  $('controllerCopyAddress').onclick=()=>copy(location.origin,'已复制管理域名').catch(e=>toast(e.message));
  $('addDeviceBtn').onclick=()=>openEnrollDialog().catch(e=>toast(e.message));
  $('closeEnroll').onclick=closeEnrollDialog;
  $('enrollDialog').addEventListener('cancel',()=>{queueMicrotask(closeEnrollDialog);});
  $('enrollBack').onclick=()=>showEnrollStep(enrollStep-1);
  $('enrollNext').onclick=()=>advanceEnroll().catch(e=>toast(e.message));
  $('enrollRefreshDevices').onclick=()=>refreshEnrolledDeviceStatus().catch(e=>toast(e.message));
  $('enrollType').onchange=()=>{if($('enrollType').value!=='android'&&enrollMode==='code')enrollMode='package';updateEnrollUI();};
  document.querySelectorAll('[data-enroll-mode-choice]').forEach(b=>b.onclick=()=>setEnrollMode(b.dataset.enrollModeChoice));
  $('copyNodeEndpoint').onclick=()=>{if(nodeBaseUrl)copy(nodeBaseUrl,tr('ux_address_copied')).catch(e=>toast(e.message));};
  $('deviceDetailClose').onclick=()=>$('deviceDetailDialog').close();
  $('settingsBack').onclick=()=>document.querySelector('.settings-shell').classList.remove('has-selection');
  document.querySelectorAll('[data-settings-link]').forEach(b=>b.onclick=()=>setSettingsCategory(b.dataset.settingsLink,true));
  setSettingsCategory('security',false);
  $('enrollBtn').onclick=async()=>{
    if(enrolling)return;
    enrolling=true;const btn=$('enrollBtn');btn.disabled=true;btn.setAttribute('aria-busy','true');
    try{$('enrollResult').hidden=true;await createEnrollment();}catch(e){toast(e.message);}
    finally{enrolling=false;btn.disabled=false;btn.removeAttribute('aria-busy');}
  };
  $('copyEnroll').onclick=()=>copy($('enrollLink').value,tr('enrollment_link_copied')).catch(e=>toast(e.message));
  $('approvePairCode').onclick=async()=>{const b=$('approvePairCode');if(b.disabled)return;b.disabled=true;try{await approveDevicePairCode();}catch(e){$('pairCodeStatus').textContent=e.message;toast(e.message);}finally{b.disabled=false;}};
  $('exportKeyBtn').onclick=async()=>{try{if(!vaultRaw)throw new Error(tr('vault_locked'));await ensureStepUp();if(!confirm('恢复密钥可解密所有短信。确认复制到系统剪贴板？'))return;await copy('SIMHUB-RECOVERY-V1:'+b64u(vaultRaw),tr('recovery_key_copied'));}catch(e){toast(e.message);}};
  $('notifyBtn').onclick=async()=>{const p=await Notification.requestPermission();toast(tr(p==='granted'?'browser_notifications_enabled':'notification_permission_denied'));};
  $('setAdminPassword').onclick=()=>setAdminPassword().catch(e=>toast(e.message));
  $('revokeAllBtn').onclick=async()=>{if(confirm('撤销所有管理员会话，包括本设备？')){await ensureStepUp();await api('/api/v1/auth/revoke-all',{method:'POST',body:{confirm:true}});csrfToken='';lockVault();toast('所有管理员会话已撤销');}};
  $('forgetBtn').onclick=async()=>{if(confirm(tr('confirm_forget'))){await logoutSession();localStorage.removeItem(VAULT_STORE);localStorage.removeItem(PHONE_OVERRIDES_STORE);lockVault();toast(tr('credentials_forgotten'));}};
  $('diagnosticsClose').onclick=()=>$('diagnosticsDialog').close();
  $('diagnosticsRefresh').onclick=()=>refreshDiagnostics().catch(e=>toast(e.message));
  $('deviceList').onclick=e=>{const b=e.target.closest('[data-device-open]');if(b)openDeviceDetail(b.dataset.deviceOpen);};
  $('deviceDetailContent').onclick=e=>{const b=e.target.closest('button[data-action]');if(b)handleDeviceAction(b).catch(err=>toast(err.message));};
  $('refreshCommandActivity').onclick=()=>loadCommandActivity().catch(e=>toast(e.message));
  $('refreshPoolBtn').onclick=()=>loadPool().catch(e=>toast(e.message));
  $('checkUpdateBtn').onclick=()=>refreshUpdateInfo().catch(e=>toast(e.message));
  $('applyUpdateBtn').onclick=()=>applyServerUpdate().catch(e=>toast(e.message));
  $('ignoreUpdateBtn').onclick=()=>toggleIgnoreUpdate().catch(e=>toast(e.message));
  $('smsPoolMembers').onclick=e=>{const button=e.target.closest('[data-pool-action]');if(!button)return;
    const task=button.dataset.poolAction==='rotate'?rotatePool():
      button.dataset.poolAction==='approve'?grantPoolDevice(button.dataset.id):revokePoolDevice(button.dataset.id);
    task.catch(err=>toast(err.message));};
  $('inboxList').onclick=e=>{const b=e.target.closest('button[data-thread]');if(b)openConversation(b.dataset.thread);};
  if('serviceWorker'in navigator)navigator.serviceWorker.register('/sw.js').catch(()=>{});
  relocalizeDynamic();
}
wire();
resumeExistingSession().catch(()=>{});
