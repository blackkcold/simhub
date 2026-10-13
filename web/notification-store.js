// Notification metadata only: no SMS text, OTP, Vault keys or raw sender numbers.
const DATABASE='simhub-notification-metadata-v1',STORE='entries',LIMIT=500,AGE=30*86400000;
const memory=new Map();
function openDb(){
  if(!('indexedDB' in globalThis))return Promise.resolve(null);
  return new Promise(resolve=>{
    try {
      const request=indexedDB.open(DATABASE,1);
      request.onupgradeneeded=()=>request.result.createObjectStore(STORE,{keyPath:'key'});
      request.onsuccess=()=>resolve(request.result);
      request.onerror=()=>resolve(null);
      request.onblocked=()=>resolve(null);
    }catch{resolve(null);}
  });
}
function transact(db,mode,fn){
  if(!db)return Promise.resolve(fn(null));
  return new Promise((resolve,reject)=>{
    let settled=false,result;
    try {
      const tx=db.transaction(STORE,mode),table=tx.objectStore(STORE);
      fn(table,answer=>{result=answer;});
      tx.oncomplete=()=>{settled=true;resolve(result);};
      tx.onerror=()=>{settled=true;reject(tx.error||new Error('notification storage'));};
      tx.onabort=()=>{if(!settled)reject(tx.error||new Error('notification transaction aborted'));};
    }catch(e){reject(e);}
  });
}
export class NotificationStore {
  constructor(){this.dbPromise=openDb();}
  async list(scope){
    const db=await this.dbPromise;
    if(!db)return [...memory.values()].filter(row=>row.scope===scope).sort((a,b)=>b.createdAt-a.createdAt);
    const rows=await transact(db,'readonly',(store,done)=>{const req=store.getAll();req.onsuccess=()=>done(req.result);});
    return (rows||[]).filter(row=>row.scope===scope).sort((a,b)=>b.createdAt-a.createdAt);
  }
  async insert(scope,item){
    const key=scope+'|'+item.id,db=await this.dbPromise;
    const record={key,scope,id:String(item.id),kind:item.kind,deviceId:item.deviceId||'',
      sourceEventId:item.sourceEventId||'',createdAt:Number(item.createdAt)||Date.now(),readAt:null};
    if(!db){if(memory.has(key))return false;memory.set(key,record);await this.prune(scope);return true;}
    const inserted=await transact(db,'readwrite',(store,done)=>{
      const req=store.get(key);
      req.onsuccess=()=>{if(req.result){done(false);return;}store.add(record);done(true);};
    });
    if(inserted)await this.prune(scope);
    return !!inserted;
  }
  async read(scope,ids=null){
    const rows=await this.list(scope);
    const selected=new Set(ids||[]);
    const pending=rows.filter(row=>row.readAt==null&&(!ids||selected.has(row.id)));
    const db=await this.dbPromise,time=Date.now();
    if(db)await transact(db,'readwrite',store=>{for(const row of pending)store.put({...row,readAt:time});});
    else for(const row of pending)memory.set(row.key,{...row,readAt:time});
    return pending.length;
  }
  async clear(scope){
    const rows=await this.list(scope),db=await this.dbPromise;
    if(db)await transact(db,'readwrite',store=>{for(const row of rows)store.delete(row.key);});
    else for(const row of rows)memory.delete(row.key);
  }
  async prune(scope){
    const rows=await this.list(scope),cut=Date.now()-AGE;
    const drop=rows.filter((row,i)=>i>=LIMIT||row.createdAt<cut);
    if(!drop.length)return;
    const db=await this.dbPromise;
    if(db)await transact(db,'readwrite',store=>{for(const row of drop)store.delete(row.key);});
    else for(const row of drop)memory.delete(row.key);
  }
}
