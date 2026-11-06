export const HISTORY_PAGE_SIZE=500; // Relay supports at most 1,000 rows per request.
export const HISTORY_PAGE_INTERVAL_MS=750; // Leave room within the 120/min admin budget for live refreshes.
export function waitForHistoryPage(signal){
  return new Promise(resolve=>{
    const finish=()=>{clearTimeout(timer);signal?.removeEventListener('abort',finish);resolve();};
    const timer=setTimeout(finish,HISTORY_PAGE_INTERVAL_MS);
    signal?.addEventListener('abort',finish,{once:true});if(signal?.aborted)finish();
  });
}
const integer=value=>typeof value==='number'&&Number.isSafeInteger(value)&&value>0;
const before=(a,b)=>a.time<b.time||(a.time===b.time&&a.seq<b.seq);
export function validateHistoryPage(response,previous=null,limit=HISTORY_PAGE_SIZE){
  if(!response||!Array.isArray(response.events)||typeof response.hasMore!=='boolean'||response.events.length>limit)
    throw Error('Invalid history page; the previous checkpoint was retained.');
  let last=previous;
  for(const event of response.events){
    const cursor={time:event.occurredAt,seq:event.seq};
    if(!integer(cursor.time)||!integer(cursor.seq)||!event.kind?.startsWith('sms.')||
      typeof event.deviceId!=='string'||typeof event.eventId!=='string'||(last&&!before(cursor,last)))
      throw Error('History page did not advance safely; the previous checkpoint was retained.');
    last=cursor;
  }
  const cursor=response.events.length?last:null;
  if(response.hasMore&&!cursor||cursor&&(response.nextBeforeTime!==cursor.time||response.nextBeforeSeq!==cursor.seq))
    throw Error('Invalid history cursor; the previous checkpoint was retained.');
  return {events:response.events,hasMore:response.hasMore,cursor};
}

// Runs only after explicit user action. Cancellation keeps the last fully
// ingested page's checkpoint; a resumed request may overlap safely via dedup.
export class HistoryBackfill{
  constructor({loadPage,hasMore,onChange=()=>{},yieldTask=waitForHistoryPage}){
    Object.assign(this,{loadPage,hasMore,onChange,yieldTask});
    this.active=null;this.status='idle';this.pages=0;this.rows=0;this.error='';
  }
  get running(){return !!this.active;}
  cancel(){
    if(!this.active)return;
    this.active.controller.abort();this.status='stopping';this.onChange();
  }
  reset(){
    this.active?.controller.abort();this.active=null;
    this.status='idle';this.pages=0;this.rows=0;this.error='';
  }
  start(){
    if(this.active)return this.active.promise;
    if(!this.hasMore()){this.status='complete';this.onChange();return Promise.resolve();}
    const run={controller:new AbortController(),promise:null};
    this.active=run;this.status='running';this.error='';this.onChange();
    run.promise=this.work(run);return run.promise;
  }
  async work(run){
    try{
      while(this.active===run&&!run.controller.signal.aborted&&this.hasMore()){
        const result=await this.loadPage(run.controller.signal);
        if(this.active!==run||run.controller.signal.aborted)break;
        this.pages++;this.rows+=Number(result?.loaded)||0;this.onChange();
        if(this.hasMore())await this.yieldTask(run.controller.signal);
      }
      if(this.active===run)this.status=run.controller.signal.aborted?'paused':'complete';
    }catch(error){
      if(this.active!==run)return;
      if(run.controller.signal.aborted)this.status='paused';
      else{this.status=error.code==='history_memory_limit'?'limited':'error';this.error=String(error.message||error);throw error;}
    }finally{
      if(this.active===run){this.active=null;this.onChange();}
    }
  }
}
