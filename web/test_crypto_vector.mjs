import fs from 'node:fs';

const v=JSON.parse(fs.readFileSync(new URL('../test_vectors/crypto-v2.json',import.meta.url),'utf8'));
const enc=new TextEncoder();
function b64u(bytes){
  return Buffer.from(bytes).toString('base64').replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/g,'');
}
function unb64u(s){
  s=s.replace(/-/g,'+').replace(/_/g,'/');
  s+='='.repeat((4-(s.length%4))%4);
  return new Uint8Array(Buffer.from(s,'base64'));
}
function field(x){return b64u(enc.encode(String(x??'')));}
const aad='simhub-event-v2|'+field(v.deviceId)+'|'+field(v.eventId)+'|'+field(v.kind)+'|'+v.occurredAt+'|'+field(v.subscriptionId)+'|'+(v.hasOtp?'1':'0');
if(aad!==v.aad)throw new Error('AAD mismatch');
const raw=unb64u(v.keyBase64Url);
const digest=new Uint8Array(await crypto.subtle.digest('SHA-256',raw));
if(b64u(digest.slice(0,12))!==v.kid)throw new Error('kid mismatch');
const key=await crypto.subtle.importKey('raw',raw,{name:'AES-GCM'},false,['encrypt','decrypt']);
const ct=new Uint8Array(await crypto.subtle.encrypt({name:'AES-GCM',iv:unb64u(v.ivBase64Url),additionalData:enc.encode(aad)},key,enc.encode(v.plaintext)));
if(b64u(ct)!==v.ciphertextBase64Url)throw new Error('ciphertext mismatch');
const pt=await crypto.subtle.decrypt({name:'AES-GCM',iv:unb64u(v.ivBase64Url),additionalData:enc.encode(aad)},key,ct);
if(new TextDecoder().decode(pt)!==v.plaintext)throw new Error('decrypt mismatch');
console.log('crypto-v2 vector ok');
