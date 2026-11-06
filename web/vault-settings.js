// One revisioned opaque settings document shared by authorized controllers.
// The server sees only the existing Vault identity, revision and AEAD envelope.
const enc=new TextEncoder(),dec=new TextDecoder();
const b64=bytes=>{let s='';for(const x of bytes)s+=String.fromCharCode(x);return btoa(s).replace(/\+/g,'-').replace(/\//g,'_').replace(/=+$/,'');};
const unb64=s=>Uint8Array.from(atob(s.replace(/-/g,'+').replace(/_/g,'/')),c=>c.charCodeAt(0));
function values(input,max){
  if(!input||typeof input!=='object'||Array.isArray(input))return {};
  const entries=Object.entries(input);
  if(entries.length>1000)throw Error('Too many SIM labels');
  const safe=entries.filter(([id,value])=>id.length<=400&&id.includes('|')&&
    typeof value==='string'&&value.length<=max&&!/[\u0000-\u001f\u007f]/.test(value));
  return Object.fromEntries(safe);
}
export function normalizedSettings(input){
  if(!input||input.v!==1)throw Error('Unsupported encrypted settings format');
  return {...input,phoneOverrides:values(input.phoneOverrides,32),channelAliases:values(input.channelAliases,80)};
}
export class VaultSettingsClient{
  constructor({vaultId,keyId,key,api,isCurrent=()=>true}){
    Object.assign(this,{vaultId,keyId,key,api,isCurrent});
  }
  guard(){if(!this.key||!this.isCurrent())throw Error('Vault locked');}
  close(){this.key=null;}
  async decode(response,defaults={v:1,phoneOverrides:{},channelAliases:{}}){
    this.guard();
    if(response.vaultId!==this.vaultId||response.keyId!==this.keyId)throw Error('Settings belong to another Vault');
    if(!Number.isSafeInteger(response.revision)||response.revision<0)throw Error('Invalid settings revision');
    if(!response.ciphertext)return {settings:normalizedSettings(defaults),revision:response.revision};
    const c=response.ciphertext;
    if(c.v!==2||c.alg!=='A256GCM'||c.kid!==this.keyId)throw Error('Invalid settings envelope');
    const plain=new Uint8Array(await crypto.subtle.decrypt({name:'AES-GCM',iv:unb64(c.iv),
      additionalData:enc.encode('simhub-vault-settings-v1|'+this.vaultId)},this.key,unb64(c.ct)));
    try{this.guard();return {settings:normalizedSettings(JSON.parse(dec.decode(plain))),revision:response.revision};}
    finally{plain.fill(0);}
  }
  async load(defaults){this.guard();return this.decode(await this.api('/api/v1/vault/settings'),defaults);}
  async update(section,id,value,defaults){
    if(!['phoneOverrides','channelAliases'].includes(section)||!String(id).includes('|'))throw Error('Invalid SIM setting');
    const max=section==='phoneOverrides'?32:80;
    if(typeof value!=='string'||value.length>max||/[\u0000-\u001f\u007f]/.test(value))throw Error('Invalid SIM label');
    for(let attempt=0;attempt<3;attempt++){
      const current=await this.load(defaults),settings=current.settings;
      if(value)settings[section][id]=value;else delete settings[section][id];
      const plain=enc.encode(JSON.stringify(settings)),iv=crypto.getRandomValues(new Uint8Array(12));
      if(plain.byteLength>44984)throw Error('Encrypted settings are full');
      let encrypted;
      try{this.guard();encrypted=await crypto.subtle.encrypt({name:'AES-GCM',iv,
        additionalData:enc.encode('simhub-vault-settings-v1|'+this.vaultId)},this.key,plain);}
      finally{plain.fill(0);}
      this.guard();
      try{
        const response=await this.api('/api/v1/vault/settings',{method:'POST',body:{
          vaultId:this.vaultId,keyId:this.keyId,expectedRevision:current.revision,
          ciphertext:{v:2,alg:'A256GCM',kid:this.keyId,iv:b64(iv),ct:b64(new Uint8Array(encrypted))}}});
        return await this.decode(response);
      }catch(error){
        if(error.status!==409||error.code!=='settings_conflict'||attempt===2)throw error;
        // Merge only this edit into the newest decrypted document; preserve
        // unrelated concurrent edits and any future version-compatible fields.
      }
    }
  }
}
