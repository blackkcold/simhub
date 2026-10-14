import {createRequire} from 'node:module';
import {readFile} from 'node:fs/promises';
import http from 'node:http';
import assert from 'node:assert/strict';
const require=createRequire(import.meta.url);
const {chromium}=require('playwright');
const html=await readFile(new URL('../android/app/src/main/assets/p2p.html',import.meta.url));
const server=http.createServer((req,res)=>{res.writeHead(200,{'Content-Type':'text/html'});res.end(html)});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
const contexts=await Promise.all([browser.newContext(),browser.newContext()]);
const pages=await Promise.all(contexts.map(c=>c.newPage()));
const events=[[],[]];let signalTasks=[];
try{
  for(let i=0;i<2;i++){
    await pages[i].exposeFunction('nativeEvent',async raw=>{
      const event=JSON.parse(raw);events[i].push(event);
      if(event.type==='signal'){
        const task=pages[1-i].evaluate(payload=>window.peerCommand({type:'signal',payload}),event.payload);
        signalTasks.push(task);await task;
      }
    });
    await pages[i].addInitScript(()=>{window.PeerBridge={postMessage:raw=>window.nativeEvent(raw)}});
    await pages[i].goto(`http://127.0.0.1:${server.address().port}/p2p.html`);
  }
  await pages[1].evaluate(()=>window.peerCommand({type:'init',servers:[],fallback:false,initiator:false}));
  await pages[0].evaluate(()=>window.peerCommand({type:'init',servers:[],fallback:false,initiator:true}));
  const until=Date.now()+15000;
  while(Date.now()<until&&!events.every(e=>e.some(x=>x.type==='connected')))await new Promise(r=>setTimeout(r,100));
  assert(events.every(e=>e.some(x=>x.type==='connected'&&x.route==='DIRECT')),'Real ICE/DTLS connection did not open');
  const frame=JSON.stringify({id:'sandbox-message-123456789',ct:'x'.repeat(500000)});
  await pages[0].evaluate(frame=>window.peerCommand({type:'send',frame}),frame);
  const deadline=Date.now()+15000;
  while(Date.now()<deadline&&!events[1].some(x=>x.type==='data'))await new Promise(r=>setTimeout(r,100));
  assert.equal(events[1].find(x=>x.type==='data')?.frame,frame,'Large frame chunk reassembly failed');
  assert(!events.some(e=>e.some(x=>x.type==='error')),'WebRTC reported an error');
  const reply=JSON.stringify({id:'sandbox-reply-123456789',state:'acknowledged'});
  await pages[1].evaluate(frame=>window.peerCommand({type:'send',frame}),reply);
  const replyDeadline=Date.now()+5000;
  while(Date.now()<replyDeadline&&!events[0].some(x=>x.type==='data'))await new Promise(r=>setTimeout(r,100));
  assert.equal(events[0].find(x=>x.type==='data')?.frame,reply);
  console.log(JSON.stringify({webrtc:'passed',engines:2,route:'DIRECT',largeFrameBytes:frame.length,reply:'passed'}));
}finally{
  await browser.close();await new Promise(resolve=>server.close(resolve));
}
