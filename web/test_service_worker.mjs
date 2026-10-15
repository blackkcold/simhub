#!/usr/bin/env node
// Service Worker regressions: offline MIME safety and cache namespace isolation.
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {runInNewContext} from 'node:vm';

const listeners=new Map(),deleted=[];
let online=false;
const shell={kind:'offline-html'};
const caches={
  async keys(){return ['simhub-shell-v21','simhub-shell-v22','another-application-cache'];},
  async delete(name){deleted.push(name);return true;},
  async match(request){
    const path=typeof request==='string'?request:new URL(request.url).pathname;
    if(path==='/index.html')return shell;
    return undefined;
  },
  async open(){return {addAll:async()=>{}}}
};
const ctx={
  self:{addEventListener:(name,handler)=>listeners.set(name,handler),
    skipWaiting:async()=>{},clients:{claim:async()=>{}}},
  caches,location:{origin:'https://simhub.example'},
  URL,Response:{error:()=>({kind:'network-error'})},
  fetch:async request=>{
    if(!online)throw Error('offline');
    return {kind:'network-response',url:request.url};
  }
};
runInNewContext(readFileSync(new URL('./sw.js',import.meta.url),'utf8'),ctx,
  {filename:'sw.js'});
async function dispatch(name,request){
  let task;
  listeners.get(name)({
    request,
    waitUntil:promise=>{task=promise;},
    respondWith:promise=>{task=promise;}
  });
  return task&&await task;
}
await dispatch('activate');
assert.ok(deleted.includes('simhub-shell-v21')&&deleted.includes('simhub-shell-v22'),
  'Old SIM Hub shell caches should be removed');
assert.ok(deleted.every(name=>name.startsWith('simhub-shell-')) &&
  !deleted.includes('another-application-cache'),
  'Do not delete caches belonging to other apps');
const request=(path,mode='same-origin')=>({url:'https://simhub.example'+path,
  method:'GET',mode});
assert.equal((await dispatch('fetch',request('/app.js'))).kind,'network-error',
  'Missing JavaScript must never receive HTML');
assert.equal(await dispatch('fetch',request('/view/inbox','navigate')),shell,
  'Offline navigations may use the HTML shell');
assert.equal(await dispatch('fetch',request('/api/v1/events')),undefined,
  'Private API calls must never be intercepted or cached');
online=true;
assert.equal((await dispatch('fetch',request('/app.js'))).kind,'network-response');
console.log('PASS: service worker offline assets and cache isolation');
