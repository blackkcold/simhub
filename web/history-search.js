// This index exists only in the unlocked tab's RAM. It has no storage adapter.
// Bound posting-list overhead; rows beyond the budget remain searchable by scan.
export const normalizeSearch=value=>String(value??'').normalize('NFKC').toLowerCase();
export const eventSearchId=event=>event.deviceId+':'+event.eventId;
export function smsSearchFields(event){
  const p=event.payload||{};
  return [p.body,p.sender,p.recipient,p.contactName,p.otp?.value];
}
export const HISTORY_MAX_ROWS=50000;
export const HISTORY_MAX_ESTIMATED_BYTES=64*1024*1024;
export class HistoryLoadBudget{
  constructor({maxRows=HISTORY_MAX_ROWS,maxEstimatedBytes=HISTORY_MAX_ESTIMATED_BYTES}={}){
    this.maxRows=maxRows;this.maxEstimatedBytes=maxEstimatedBytes;this.clear();
  }
  clear(){this.rows=0;this.estimatedBytes=0;this.limited=false;}
  addEvent(event,bounded=false){
    // Conservative UTF-16 string estimate plus row overhead. This is an
    // admission estimate, not a promise about a browser engine's total heap.
    const estimate=JSON.stringify(event).length*2+512+
      (event.kind?.startsWith('sms.')?smsSearchFields(event).reduce((n,value)=>n+normalizeSearch(value).length*2,0):0);
    if(bounded&&(this.rows+1>this.maxRows||this.estimatedBytes+estimate>this.maxEstimatedBytes)){
      this.limited=true;
      throw Object.assign(Error('History loading reached this tab\'s memory budget. Some retained records remain unsearched.'),{code:'history_memory_limit'});
    }
    this.rows++;this.estimatedBytes+=estimate;
  }
}
export class LocalHistoryIndex{
  constructor({maxPostings=200000,maxGramsPerRow=4096}={}){
    this.maxPostings=maxPostings;this.maxGramsPerRow=maxGramsPerRow;
    this.rows=new Map();this.postings=new Map();this.fallback=new Set();this.postingCount=0;
  }
  get size(){return this.rows.size;}
  clear(){this.rows.clear();this.postings.clear();this.fallback.clear();this.postingCount=0;}
  remove(id){
    const prior=this.rows.get(id);if(!prior)return;
    for(const gram of prior.grams||[]){
      const entries=this.postings.get(gram);entries.delete(id);this.postingCount--;
      if(!entries.size)this.postings.delete(gram);
    }
    this.rows.delete(id);this.fallback.delete(id);
  }
  add(id,values){
    this.remove(id);
    const fields=values.map(normalizeSearch).filter(Boolean),grams=new Set();
    let bounded=true;
    outer:for(const field of fields)for(let i=0;i+3<=field.length;i++){
      grams.add(field.slice(i,i+3));
      if(grams.size>this.maxGramsPerRow||this.postingCount+grams.size>this.maxPostings){bounded=false;break outer;}
    }
    this.rows.set(id,{fields,grams:bounded?grams:null});
    if(!bounded){this.fallback.add(id);return;}
    for(const gram of grams){
      if(!this.postings.has(gram))this.postings.set(gram,new Set());
      this.postings.get(gram).add(id);this.postingCount++;
    }
  }
  addEvent(event){
    if(event.kind?.startsWith('sms.')&&event.payload&&!event.decryptError)
      this.add(eventSearchId(event),smsSearchFields(event));
  }
  search(value){
    const query=normalizeSearch(value),found=new Set();
    let candidates=this.rows.keys();
    if(query.length>=3){
      let smallest=null;
      // A subset of query grams suffices: exact substring verification below
      // rejects false positives without constructing an unbounded query index.
      for(let i=0;i+3<=query.length&&i<64;i++){
        const posting=this.postings.get(query.slice(i,i+3));
        if(!posting){smallest=[];break;}
        if(!smallest||posting.size<smallest.size)smallest=posting;
      }
      candidates=new Set([...(smallest||[]),...this.fallback]);
    }
    for(const id of candidates){
      const row=this.rows.get(id);
      if(!query||row.fields.some(field=>field.includes(query)))found.add(id);
    }
    return found;
  }
}

const count=value=>typeof value==='number'&&Number.isSafeInteger(value)&&value>=0?value:null;
export function summarizeHistoryCoverage(events,devices,deviceId=''){
  const rows=events.filter(e=>e.kind?.startsWith('sms.')&&(!deviceId||e.deviceId===deviceId));
  const nodes=devices.filter(d=>!deviceId||d.id===deviceId);
  let retainedKnown=0,retainedUnknownNodes=0,pending=0,pendingUnknownNodes=0;
  for(const d of nodes){
    const retained=count(d.smsCount),queued=count(d.state?.pendingEvents);
    if(retained===null)retainedUnknownNodes++;else retainedKnown+=retained;
    if(queued===null)pendingUnknownNodes++;else pending+=queued;
  }
  const searchable=rows.filter(e=>!!e.payload&&!e.decryptError).length;
  return {loaded:rows.length,searchable,unreadable:rows.length-searchable,
    retained:retainedUnknownNodes||!nodes.length?null:retainedKnown,retainedKnown,retainedUnknownNodes,
    pending,pendingUnknownNodes:pendingUnknownNodes||(!nodes.length?1:0),nodeCount:nodes.length};
}
