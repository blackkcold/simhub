import {createRequire} from 'node:module';
import {readFile,writeFile} from 'node:fs/promises';
import http from 'node:http';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
const {chromium}=createRequire(import.meta.url)('playwright');
const role=process.env.P2P_PUBLIC_ROLE||'controller',session=process.env.P2P_PUBLIC_SESSION;
assert(session&&['node','controller'].includes(role));
// Deliberately non-production, run-scoped test key; never sends user data.
const key=crypto.createHash('sha256').update('simhub-public-sandbox/'+session).digest();
const me='sandbox-public-'+role+'-identity',other='sandbox-public-'+(role==='node'?'controller':'node')+'-identity',domain='simhub-peer-v1';
const room=id=>{const prk=crypto.createHmac('sha256','simhub-discovery-v1').update(key).digest();return 'sh'+crypto.createHmac('sha256',prk).update(domain+'|peerjs-room|'+id).update(Buffer.from([1])).digest('hex')};
const seal=(from,to,id,payload)=>{const expires=Math.floor(Date.now()/1000)+60,iv=crypto.randomBytes(12),c=crypto.createCipheriv('aes-256-gcm',key,iv);c.setAAD(Buffer.from(`${domain}|${from}|${to}|${id}|${expires}`));const ct=Buffer.concat([c.update(JSON.stringify(payload)),c.final(),c.getAuthTag()]);return {v:1,domain,from,to,id,expires,iv:iv.toString('base64url'),ct:ct.toString('base64url')}};
const open=f=>{assert.equal(f.domain,domain);assert.equal(f.from,other);assert.equal(f.to,me);assert(f.expires>=Date.now()/1000-1);const ct=Buffer.from(f.ct,'base64url'),c=crypto.createDecipheriv('aes-256-gcm',key,Buffer.from(f.iv,'base64url'));c.setAAD(Buffer.from(`${domain}|${f.from}|${f.to}|${f.id}|${f.expires}`));c.setAuthTag(ct.subarray(-16));return JSON.parse(Buffer.concat([c.update(ct.subarray(0,-16)),c.final()]).toString())};
const html=await readFile(new URL('../android/app/src/main/assets/p2p.html',import.meta.url));
const server=http.createServer((req,res)=>{res.writeHead(200,{'Content-Type':'text/html'});res.end(html)});await new Promise(r=>server.listen(0,'127.0.0.1',r));
const browser=await chromium.launch({headless:true,args:['--no-sandbox']});const page=await browser.newPage();
let signalsReceived=0,nativeErrors=0;const serverTypes={};let socket,ready=false,received=false,started=false,signalCount=0,srflx=0,timer,error;const localAddresses=new Set(),remoteAddresses=new Set();
const id='public-message-1234567890',payload={action:'sandbox.ping',body:'x'.repeat(128000)};
const sendSignal=signal=>{if(signal.candidate){if(!/ typ srflx(?: |$)/.test(signal.candidate.candidate))return;srflx++;localAddresses.add(signal.candidate.candidate.split(" ")[4]);}
  if(signal.description)signal.description.sdp=signal.description.sdp.split('\r\n').filter(s=>!/^a=candidate:.* typ (host|relay)(?: |$)/.test(s)).join('\r\n');
  const type=signal.description?(signal.description.type==='offer'?'OFFER':'ANSWER'):'CANDIDATE';
  const frame=seal(me,other,crypto.randomUUID(),{action:'signal',payload:signal});socket.send(JSON.stringify({type,dst:room(other),payload:{type:'data',connectionId:'simhub-p2p-v1',simhub:frame}}));signalCount++;
};
try{
  await page.exposeFunction('nativeEvent',async raw=>{const e=JSON.parse(raw);if(e.type==='error')nativeErrors++;
    if(e.type==='signal')sendSignal(e.payload);
    if(e.type==='connected'){assert.equal(e.route,'DIRECT');ready=true;}
    if(e.type==='data'){const f=JSON.parse(e.frame),p=open(f);assert.equal(f.id,id);
      if(role==='node'){assert.deepEqual(p,payload);received=true;await page.evaluate(frame=>peerCommand({type:'send',frame}),JSON.stringify(seal(me,other,id,{state:'acknowledged',bytes:p.body.length})));}
      else{assert.deepEqual(p,{state:'acknowledged',bytes:128000});received=true;}
    }
  });
  await page.addInitScript(()=>{window.PeerBridge={postMessage:raw=>window.nativeEvent(raw)}});
  await page.goto(`http://127.0.0.1:${server.address().port}/p2p.html`);
  socket=new WebSocket(`wss://0.peerjs.com/peerjs?key=peerjs&id=${room(me)}&token=${crypto.randomUUID()}&version=1.5.5`);
  socket.addEventListener('message',event=>{(async()=>{const m=JSON.parse(event.data);serverTypes[m.type]=(serverTypes[m.type]||0)+1;
    if(m.type==='OPEN'){started=true;await page.evaluate(initiator=>peerCommand({type:'init',servers:[{urls:['stun:stun.l.google.com:19302','stun:stun.cloudflare.com:3478']}],fallback:false,initiator}),role==='controller');return;}
    if(['ERROR','ID-TAKEN','INVALID-KEY'].includes(m.type))throw Error('Public signaling rejected '+m.type);
    if(['OFFER','ANSWER','CANDIDATE'].includes(m.type)){signalsReceived++;assert.equal(m.src,room(other));assert.equal(m.dst,room(me));const p=open(m.payload.simhub);assert.equal(p.action,'signal');if(p.payload.candidate){assert(/ typ srflx(?: |$)/.test(p.payload.candidate.candidate));remoteAddresses.add(p.payload.candidate.candidate.split(' ')[4]);}await page.evaluate(payload=>peerCommand({type:'signal',payload}),p.payload);}
  })().catch(e=>{error=e});});socket.addEventListener('error',()=>{error=Error('Public WebSocket unavailable')});
  timer=setInterval(()=>{if(socket.readyState===WebSocket.OPEN)socket.send('{"type":"HEARTBEAT"}');if(started&&!ready&&role==='controller')page.evaluate(()=>peerCommand({type:'restart'})).catch(e=>{error=e})},10000);
  const deadline=Date.now()+120000;let sent=false;
  while(Date.now()<deadline&&!received){if(error)throw error;if(ready&&role==='controller'&&!sent){sent=true;await page.evaluate(frame=>peerCommand({type:'send',frame}),JSON.stringify(seal(me,other,id,payload)));}await new Promise(r=>setTimeout(r,100));}
  console.log(JSON.stringify({diagnostic:true,role,started,ready,received,signalCount,signalsReceived,srflx,nativeErrors,serverTypes}));
  assert(received,'Public DIRECT session did not exchange authenticated data');assert(srflx>0,'No public STUN candidate was forwarded');assert([...remoteAddresses].some(ip=>!localAddresses.has(ip)),'Runners did not demonstrate distinct public endpoints');
  const result={publicP2p:'passed',role,route:'DIRECT',signalCount,srflx,bytes:128000,hostCandidatesForwarded:0,turnServers:0,distinctPublicEndpoints:true,session};
  await writeFile(`p2p-public-${role}.json`,JSON.stringify(result,null,2));console.log(JSON.stringify(result));
  // Keep receiver available long enough for the other runner to finish.
  await new Promise(r=>setTimeout(r,2000));
}finally{clearInterval(timer);socket?.close();await browser.close();await new Promise(r=>server.close(r));}
