// Persistent SMS intent journal. Only authenticated AES-GCM ciphertext is stored.
// An accepted-but-unanswered POST is recovered with the original command envelope.
const enc=new TextEncoder(),dec=new TextDecoder();
const prefix='simhub_intents_v1:';
const b64=bytes=>{let value='';for(const x of bytes)value+=String.fromCharCode(x);return btoa(value);};
const unb64=value=>Uint8Array.from(atob(value),c=>c.charCodeAt(0));
const copy=value=>JSON.parse(JSON.stringify(value));
const known=phase=>phase==='observed';
export function intentFingerprint(deviceId,payload){
  return JSON.stringify([deviceId,String(payload.channelId),Number(payload.channelRevision),
    String(payload.to).trim(),String(payload.body)]);
}

export class IntentJournal{
  constructor({scope,key,isCurrent=()=>true,storage=localStorage,locks=globalThis.navigator?.locks}){
    this.scope=String(scope);this.key=key;this.isCurrent=isCurrent;
    this.storage=storage;this.locks=locks;this.epoch=0;this.tail=Promise.resolve();
    this.prefix=prefix+encodeURIComponent(this.scope)+':';
  }
  close(){this.epoch++;this.key=null;}
  current(epoch,key){return this.epoch===epoch&&this.key===key&&!!key&&this.isCurrent();}
  async read(id){
    const epoch=this.epoch,key=this.key;
    if(!this.current(epoch,key))throw Error('Vault locked');
    const saved=this.storage.getItem(this.prefix+id);if(!saved)return null;
    const envelope=JSON.parse(saved);
    if(envelope.v!==1)throw Error('Unsupported local intent format');
    const plain=new Uint8Array(await crypto.subtle.decrypt({name:'AES-GCM',iv:unb64(envelope.iv),
      additionalData:enc.encode(this.prefix+id)},key,unb64(envelope.ct)));
    try{
      if(!this.current(epoch,key))throw Error('Vault locked');
      return JSON.parse(dec.decode(plain));
    }finally{plain.fill(0);}
  }
  async write(id,value){
    const epoch=this.epoch,key=this.key;
    if(!this.current(epoch,key))throw Error('Vault locked');
    const plain=enc.encode(JSON.stringify(value)),iv=crypto.getRandomValues(new Uint8Array(12));
    if(plain.byteLength>128*1024)throw Error('Local intent is too large');
    try{
      const encrypted=await crypto.subtle.encrypt({name:'AES-GCM',iv,
        additionalData:enc.encode(this.prefix+id)},key,plain);
      if(!this.current(epoch,key))throw Error('Vault locked');
      this.storage.setItem(this.prefix+id,JSON.stringify({v:1,iv:b64(iv),ct:b64(new Uint8Array(encrypted))}));
    }finally{plain.fill(0);}
  }
  async exclusive(name,action){
    // Web Locks serializes the same durable draft across tabs. Refuse a send
    // when unavailable instead of relying on a racy localStorage read/write.
    if(!this.locks?.request)throw Error('This browser cannot safely lock a saved send. Use a current HTTPS browser.');
    return this.locks.request(this.prefix+name,action);
  }
  async list(){
    const ids=[];
    for(let i=0;i<this.storage.length;i++){
      const name=this.storage.key(i);
      if(name?.startsWith(this.prefix+'intent:'))ids.push(name.slice(this.prefix.length));
    }
    const values=[];
    for(const id of ids){const item=await this.read(id);if(item)values.push(item);}
    return values.sort((a,b)=>b.createdAt-a.createdAt);
  }
  draft(id){return this.read('draft:'+id);}
  async saveDraft(id,value){
    const epoch=this.epoch,key=this.key,snapshot=copy(value);
    // Snapshot before any asynchronous work; older keystrokes cannot overwrite
    // newer input. Cross-tab writes share the same lock as submit/reconcile.
    const task=this.tail.catch(()=>{}).then(()=>this.exclusive('draft:'+id,async()=>{
      if(!this.current(epoch,key))throw Error('Vault locked');
      const old=await this.draft(id),intent=old?.intentId?await this.read('intent:'+old.intentId):null;
      const intentId=intent&&!known(intent.phase)?intent.id:null;
      await this.write('draft:'+id,{...snapshot,intentId,updatedAt:Date.now()});
    }));
    this.tail=task;return task;
  }
  async archive(id){
    return this.exclusive('intent:'+id,async()=>{
      const item=await this.read('intent:'+id);if(!item)return;
      if(!known(item.phase)||['queued','dispatched','submitted','outcome_unknown'].includes(item.state))
        throw Error('Resolve this send before removing its local record');
      this.storage.removeItem(this.prefix+'intent:'+id);
    });
  }
}

export class SendCoordinator{
  constructor({journal,api,encrypt,validate=()=>{},now=()=>Math.floor(Date.now()/1000),newId=()=>crypto.randomUUID()}){
    Object.assign(this,{journal,api,encrypt,validate,now,newId});
  }
  path(item){return '/api/v1/devices/'+encodeURIComponent(item.deviceId)+'/commands/'+encodeURIComponent(item.id);}
  async remember(item){item.updatedAt=this.now();await this.journal.write('intent:'+item.id,item);return item;}
  async observe(item){
    try{
      const response=await this.api(this.path(item)),command=response.command;
      if(!command||String(command.commandId||command.id)!==item.id||command.deviceId!==item.deviceId)
        throw Error('Command reconciliation returned a different identity');
      item.phase='observed';item.state=command.state;item.result=command.result||{};
      await this.remember(item);return command;
    }catch(error){if(error.status===404&&error.code==='command_not_found')return null;throw error;}
  }
  async reconcile(id){
    return this.journal.exclusive('intent:'+id,async()=>{
      const item=await this.journal.read('intent:'+id);if(!item)throw Error('Saved send not found');
      const command=await this.observe(item);
      return {item,command};
    });
  }
  async submit({draftId,deviceId,payload,ttl}){
    await this.journal.tail;
    return this.journal.exclusive('draft:'+draftId,async()=>{
      const fingerprint=intentFingerprint(deviceId,payload),draft=await this.journal.draft(draftId);
      let item=draft?.intentId?await this.journal.read('intent:'+draft.intentId):null;
      if(item&&item.fingerprint!==fingerprint)
        throw Error('Resolve the previous send in Send center before changing its recipient or SIM.');
      if(!item){
        const all=await this.journal.list();
        if(all.length>=100)throw Error('Send center is full. Archive resolved records before sending.');
        this.validate(deviceId,payload);
        const id=this.newId(),createdAt=this.now(),expiresAt=createdAt+ttl;
        const outer={commandId:id,idempotencyKey:id,type:'sms.send',createdAt,expiresAt};
        outer.ciphertext=await this.encrypt(deviceId,outer,{v:2,action:'sms.send',commandId:id,
          issuedAt:createdAt,expiresAt,...payload});
        item={v:1,id,deviceId,draftId,payload:copy(payload),fingerprint,outer,
          phase:'prepared',state:'prepared',createdAt,updatedAt:createdAt};
        // Both writes finish before any network side effect. A write failure
        // leaves a harmless orphan, never a radio command without a journal.
        await this.remember(item);
        await this.journal.write('draft:'+draftId,{...copy(payload),deviceId,intentId:id,updatedAt:Date.now()});
      }
      return this.journal.exclusive('intent:'+item.id,async()=>{
        if(item.phase!=='prepared'){
          const command=await this.observe(item);
          if(command){await this.finishDraft(item);return {accepted:true,duplicate:true,commandId:item.id,state:command.state};}
          // A known acceptance cannot be disproved by later retention/deletion.
          if(known(item.phase))throw Error('This command was previously accepted. Do not send it again.');
        }
        if(this.now()>=item.outer.expiresAt){
          item.state='expired_unconfirmed';await this.remember(item);
          throw Error('Saved command expired. Its outcome is unconfirmed; verify the node before creating another send.');
        }
        this.validate(item.deviceId,item.payload);
        item.phase='submitting';item.state='acceptance_unknown';await this.remember(item);
        let response;
        try{
          response=await this.api('/api/v1/devices/'+encodeURIComponent(item.deviceId)+'/commands',
            {method:'POST',body:item.outer});
          if(response.accepted!==true||String(response.commandId)!==item.id)throw Error('Command acceptance is unconfirmed');
        }catch(error){
          item.phase='uncertain';item.state='acceptance_unknown';
          try{await this.remember(item);}catch{}
          throw error;
        }
        item.phase='observed';item.state=response.state||'queued';await this.remember(item);
        await this.finishDraft(item);return response;
      });
    });
  }
  async finishDraft(item){
    const draft=await this.journal.draft(item.draftId);
    if(draft?.intentId===item.id)
      await this.journal.write('draft:'+item.draftId,{...draft,body:'',updatedAt:Date.now()});
  }
  async retry(id){
    const item=await this.journal.read('intent:'+id);if(!item)throw Error('Saved send not found');
    if(known(item.phase)){
      const result=await this.reconcile(id);
      return {accepted:true,duplicate:true,commandId:id,state:result.item.state};
    }
    return this.submit({draftId:item.draftId,deviceId:item.deviceId,payload:item.payload,
      ttl:item.outer.expiresAt-item.outer.createdAt});
  }
}
