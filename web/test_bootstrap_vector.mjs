import fs from 'node:fs';

const v=JSON.parse(fs.readFileSync(new URL('../test_vectors/bootstrap-v1.json',import.meta.url),'utf8'));
const enc=new TextEncoder();
function unb64u(s){s=s.replace(/-/g,'+').replace(/_/g,'/');s+='='.repeat((4-(s.length%4))%4);return new Uint8Array(Buffer.from(s,'base64'));}
function b64u(bytes){return Buffer.from(bytes).toString('base64').replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/g,'');}
const bootstrap=unb64u(v.bootstrapBase64Url),node=unb64u(v.nodeKeyBase64Url),iv=unb64u(v.ivBase64Url);
const proof=b64u(new Uint8Array(await crypto.subtle.digest('SHA-256',bootstrap)));
if(proof!=='Yw3NKWbEM2aRElRIu7JbT_QSpJxzLbLIq8G4WBvXEN0')throw new Error('bootstrap proof mismatch');
const key=await crypto.subtle.importKey('raw',bootstrap,{name:'AES-GCM'},false,['encrypt','decrypt']);
const ct=new Uint8Array(await crypto.subtle.encrypt({name:'AES-GCM',iv,additionalData:enc.encode(v.aad)},key,node));
if(b64u(ct)!==v.ciphertextBase64Url)throw new Error('bootstrap ciphertext mismatch');
const raw=new Uint8Array(await crypto.subtle.decrypt({name:'AES-GCM',iv,additionalData:enc.encode(v.aad)},key,ct));
if(b64u(raw)!==v.nodeKeyBase64Url)throw new Error('bootstrap decrypt mismatch');
console.log('bootstrap-v1 vector ok');
