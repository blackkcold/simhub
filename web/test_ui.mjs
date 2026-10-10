#!/usr/bin/env node
// Browser regression checks: modal geometry, interaction hit targets,
// responsive breakpoints and a unified new-message composer.
import assert from "node:assert/strict";
import {spawn} from "node:child_process";
import {mkdtemp, mkdir, rm} from "node:fs/promises";
import {tmpdir} from "node:os";
import {join} from "node:path";
import {chromium} from "playwright";

const tmp=await mkdtemp(join(tmpdir(),"simhub-ui-"));
await mkdir("ui-snapshots",{recursive:true});
const port=18983,root=process.cwd();
const server=spawn("python3",["server/simhub_server.py"],{
  cwd:root,env:{...process.env,SIMHUB_ADMIN_TOKEN:"A".repeat(48),
    SIMHUB_REQUIRE_TOTP:"false",SIMHUB_BIND:"127.0.0.1",
    SIMHUB_PORT:String(port),SIMHUB_DB:join(tmp,"test.db"),
    SIMHUB_WEB_ROOT:join(root,"web"),
    SIMHUB_MANAGEMENT_ORIGIN:"http://127.0.0.1:"+port,
    SIMHUB_PUBLIC_BASE_URL:"http://127.0.0.1:"+port},stdio:"pipe"});
let browser;
try {
  let ready=false;
  for(let i=0;i<100;i++){
    await new Promise(resolve=>setTimeout(resolve,75));
    try{const r=await fetch("http://127.0.0.1:"+port+"/readyz");if(r.ok){ready=true;break;}}catch{}
  }
  assert.ok(ready,"Server did not start for UI test");
  browser=await chromium.launch({headless:true,args:["--no-sandbox"]});
  for(const width of [375,768,1440]){
    for(const theme of ["light","dark"]){
      const context=await browser.newContext({viewport:{width,height:900},colorScheme:theme});
      const page=await context.newPage(),errors=[],consoleErrors=[],httpErrors=[];
      page.on("console",m=>{if(m.type()==="error")consoleErrors.push(m.text());});
      page.on("response",r=>{if(r.status()>=400)httpErrors.push(r.status()+" "+r.url());});
      page.on("requestfailed",r=>httpErrors.push(r.failure()?.errorText+" "+r.url()));
      page.on("pageerror",e=>errors.push(e.message));
      await page.goto("http://127.0.0.1:"+port+"/",{waitUntil:"networkidle"});
      if(width===375){
        const gateOverflow=await page.locator("main").evaluate(el=>getComputedStyle(el).overflowY);
        assert.notEqual(gateOverflow,"hidden","Locked mobile login must remain vertically scrollable");
      }
      assert.equal(await page.locator("#username").count(),1);
      assert.equal(await page.locator("#passkeyLoginBtn").count(),1);
      assert.equal(await page.evaluate(()=>typeof window.QRCode),"function","Offline QR renderer must load");
      assert.equal(await page.locator("#approvePairCode").count(),1);
      await page.evaluate(()=>document.querySelector("#stepupDialog").showModal());
      const positions=await page.evaluate(()=>{
        const d=document.querySelector("#stepupDialog").getBoundingClientRect();
        const i=document.querySelector("#stepupValue").getBoundingClientRect();
        const b=document.querySelector(".stepup-actions").getBoundingClientRect();
        return {dialog:d.width,inputBottom:i.bottom,buttonsTop:b.top,right:d.right};
      });
      assert.ok(positions.dialog<=500,"Dialog is too wide at "+width);
      assert.ok(positions.right<=width+2,"Dialog overflows viewport at "+width);
      assert.ok(positions.buttonsTop-positions.inputBottom>=16,"Buttons touch input at "+width+" "+JSON.stringify(positions));
      await page.locator("#stepupDialog").evaluate(d=>d.close());
      await page.evaluate(()=>{
        document.querySelector("#lockedPanel").hidden=true;
        document.querySelector("#appContent").hidden=false;
        document.querySelector("#newSmsBtn").hidden=false;
      });
      if(!await page.locator("#newSmsBtn").evaluate(el=>!!el.onclick)){
        console.error("UI-MODULE-LOADING",JSON.stringify(await page.evaluate(async()=>{
          const r=await fetch("/app.js",{cache:"no-store"});
          return {status:r.status,mime:r.headers.get("content-type"),script:document.querySelector("script[src]")?.outerHTML};
        })),consoleErrors,httpErrors,errors);
      }
      assert.equal(await page.locator("#simFilter").count(),1,"SIM source filter must exist");
      // No enrolled send-capable device: forcing the button visible for this
      // layout fixture must not bypass the read-only sender policy.
      await page.locator("#newSmsBtn").click();
      assert.equal(await page.locator("#replyComposer").isVisible(),false,
        "Read-only device must not enter SMS compose");
      // Isolate responsive composer geometry from the device capability check.
      await page.evaluate(()=>{
        document.getElementById("replyComposer").hidden=false;
        document.getElementById("replyOptions").open=true;
        document.getElementById("smsLayout").classList.add("conversation-open");
      });
      await page.locator("#replyTo").fill("+8613800138000");
      await page.locator("#replyBody").fill("Hello");
      const composer=await page.locator("#replyComposer").boundingBox();
      assert.ok(composer&&composer.width<=width+1,"Composer overflows screen");
      await page.screenshot({path:"ui-snapshots/simhub-"+width+"-"+theme+".png",fullPage:true});
      assert.deepEqual(errors,[],"Browser errors at "+width+" / "+theme);
      await context.close();
    }
  }
  // Regression: arbitrary-length phone numbers / message previews must never
  // enlarge a thread beyond its column, and the floating dock must not scroll.
  // Reuse one page and resize it. Creating 13 sessions would incorrectly trip
  // the Relay's production IP request budget without exercising responsiveness.
  const responsiveContext=await browser.newContext({viewport:{width:320,height:812},colorScheme:"light"});
  const responsivePage=await responsiveContext.newPage(),responsiveErrors=[];
  responsivePage.on("pageerror",e=>responsiveErrors.push(e.message));
  const responsiveResponse=await responsivePage.goto("http://127.0.0.1:"+port+"/",{waitUntil:"domcontentloaded"});
  assert.ok(responsiveResponse?.ok(),"Responsive test page failed to load");
  await responsivePage.locator("#lockedPanel").waitFor({state:"attached"});
  await responsivePage.evaluate(()=>{
    document.getElementById("lockedPanel").hidden=true;
    document.getElementById("appContent").hidden=false;
  });
  for(const width of [320,360,375,390,430,600,760,768,900,1024,1180,1366,1920]){
    const page=responsivePage,jsErrors=responsiveErrors;
    await page.setViewportSize({width,height:812});
    await page.locator('.nav[data-view="inbox"]').click();
    await page.evaluate(()=>{
      document.getElementById("lockedPanel").hidden=true;
      document.getElementById("appContent").hidden=false;
      document.getElementById("newSmsBtn").hidden=false;
      const text="【模拟通知】 "+("这是一条包含超长号码和短信正文的测试消息 1234567890 ".repeat(16));
      document.getElementById("inboxItems").innerHTML=Array.from({length:26},(_,i)=>
        '<button type="button" class="message"><span class="avatar">9</span>'+
        '<span class="message-body"><span class="message-title"><strong>'+
        '10682635927538612345678901234567890'+i+'</strong><small class="meta">2026-10-08 17:55</small></span>'+
        '<span class="message-preview">'+text+'</span><small class="meta">'+text+'</small></span></button>').join("");
      document.getElementById("replyComposer").hidden=false;
      document.getElementById("conversationTitle").textContent="超长 SIM 号码 "+("12345678901234567890".repeat(8));
      document.getElementById("conversationMessages").innerHTML='<div class="bubble"><p>'+text+'</p></div>';
      document.getElementById("replyTo").value="+861380000000012345678901234";
      document.getElementById("replyBody").value=text;
      document.getElementById("replyDevice").innerHTML='<option>Android phone '+("X".repeat(100))+'</option>';
      document.getElementById("replySubscription").innerHTML='<option>SIM '+("0".repeat(100))+'</option>';
    });
    const layout=await page.evaluate(()=>{
      const rect=id=>{const r=document.querySelector(id).getBoundingClientRect();return {x:r.left,y:r.top,right:r.right,bottom:r.bottom,width:r.width,height:r.height};};
      const doc=document.documentElement;
      return {docWidth:doc.scrollWidth,viewport:window.innerWidth,
        list:rect(".sms-sidebar"),message:rect("#inboxItems .message"),
        panel:rect("#conversationPanel"),previewStyle:getComputedStyle(document.querySelector(".message-preview")).display};
    });
    assert.ok(layout.docWidth<=width+1,`Horizontal page overflow at ${width}: ${JSON.stringify(layout)}`);
    assert.ok(layout.message.right<=layout.list.right+1,`Message overlaps another column at ${width}`);
    if(width>900){
      const listOverflow=await page.locator("#inboxList").evaluate(el=>({scroll:el.scrollHeight,client:el.clientHeight,overflow:getComputedStyle(el).overflowY}));
      assert.ok(listOverflow.scroll>listOverflow.client&&listOverflow.overflow==="auto",`Inbox must scroll independently at ${width}: ${JSON.stringify(listOverflow)}`);
      const cardSize=await page.locator('#inboxItems .message').first().evaluate(el=>({height:el.getBoundingClientRect().height,flex:getComputedStyle(el).flexShrink}));
      assert.ok(cardSize.height>=76 && Number(cardSize.flex)===0,`Inbox cards must not shrink at ${width}: ${JSON.stringify(cardSize)}`);
      await page.locator('#inboxList').evaluate(el=>{el.scrollTop=el.scrollHeight;});
      const actualScroll=await page.locator('#inboxList').evaluate(el=>el.scrollTop);
      assert.ok(actualScroll>0,`Inbox scrollTop must advance at ${width}`);
    }
    assert.equal(layout.previewStyle,"block",`SMS preview must ellipsize as a block at ${width}`);
    if(width>900){
      assert.ok(layout.list.right+5<=layout.panel.x,`Desktop SMS columns overlap at ${width}`);
    }else if(width>760){
      assert.ok(layout.panel.y>=layout.list.bottom-1,`Tablet columns must stack at ${width}`);
    }else{
      const before=await page.locator(".sidebar").boundingBox();
      assert.ok(layout.list.height>=200,`Inbox must remain useful at ${width}: ${JSON.stringify(layout)}`);
      assert.ok(layout.list.bottom>=before.y-40,`Inbox must fill viewport above floating dock at ${width}: ${JSON.stringify(layout)}`);
      assert.ok(before&&before.y>=width*0,`Mobile floating dock not rendered at ${width}`);
      assert.ok(before.y>600&&before.y+before.height<=812,`Dock must sit at bottom at ${width}: ${JSON.stringify(before)}`);
      assert.ok(before.x>=8&&before.x+before.width<=width-8,`Dock must float with margins at ${width}`);
      await page.evaluate(()=>window.scrollTo(0,document.documentElement.scrollHeight));
      const after=await page.locator(".sidebar").boundingBox();
      assert.ok(Math.abs(after.y-before.y)<=2,`Bottom dock scrolls with SMS content at ${width}`);
      assert.ok(after.y+after.height<=812,`Dock outside viewport after scrolling at ${width}`);
      const dockHit=await page.evaluate(()=>{
        const box=document.querySelector(".sidebar").getBoundingClientRect();
        return document.elementFromPoint(box.left+box.width/2,box.top+box.height/2)?.closest(".sidebar")!==null;
      });
      assert.ok(dockHit,`Bottom dock is blocked by content at ${width}`);
      await page.evaluate(()=>{window.scrollTo(0,0);document.getElementById("smsLayout").classList.add("conversation-open");});
      const conversation=await page.locator("#conversationPanel").boundingBox();
      const dock=await page.locator(".sidebar").boundingBox();
      assert.ok(conversation&&conversation.y>=0&&conversation.y+conversation.height<=dock.y-5,
        `Mobile conversation dialog overlaps bottom dock at ${width}`);
      assert.ok(conversation.x>=0&&conversation.x+conversation.width<=width+1,
        `Mobile conversation dialog exceeds viewport at ${width}`);
      await page.locator("#backConversation").click();
      assert.equal(await page.locator("#conversationPanel").isVisible(),false,
        `Back should dismiss mobile conversation at ${width}`);
      const nav=page.locator('.nav[data-view="devices"]');
      await nav.click();
      assert.ok(await page.locator("#view-devices").isVisible(),`Mobile navigation must work at ${width}`);
    }
    assert.deepEqual(jsErrors,[],`Responsive JS errors at ${width}`);
  }
  await responsiveContext.close();
  // Reopening an active browser tab must not demand the Vault passphrase again.
  const c=await browser.newContext({viewport:{width:1120,height:800}});
  const p=await c.newPage();
  await p.goto("http://127.0.0.1:"+port+"/",{waitUntil:"networkidle"});
  await p.locator("#username").fill("admin");
  await p.locator("#adminToken").fill("A".repeat(48));
  await p.locator("#unlockBtn").click();
  await p.locator("#passphrase").fill("session-resume-test-passphrase");
  p.once("dialog",d=>d.accept());
  await p.locator("#createVaultBtn").click();
  await p.waitForFunction(()=>!!sessionStorage.getItem("simhub_session_vault_v1"),null,{timeout:12000});
  const loginErrors=[];
  p.on("response",response=>{if(response.url().includes("/api/")&&response.status()>=400)loginErrors.push({url:response.url(),status:response.status()});});
  p.on("pageerror",error=>loginErrors.push({pageError:error.message}));
  try{await p.waitForFunction(()=>!document.getElementById("appContent").hidden,null,{timeout:12000});}
  catch(error){
    const state=await p.evaluate(()=>({toast:document.getElementById("toast").textContent,username:document.getElementById("username").value,tokenSize:document.getElementById("adminToken").value.length,vault:!!localStorage.getItem("simhub_vault_v1"),snapshot:!!sessionStorage.getItem("simhub_session_vault_v1"),locked:document.getElementById("lockedPanel").hidden}));
    console.error("SESSION-LOGIN-DEBUG",JSON.stringify({state,loginErrors}));
    throw error;
  }
  // v0.10.0 device onboarding: both user-selected methods must work without
  // relocating the administrator to Settings or exposing bootstrap secrets.
  await p.locator('.nav[data-view="devices"]').click();
  await p.locator("#addDeviceBtn").click();
  assert.equal(await p.locator("#enrollDialog").evaluate(el=>el.open),true,"Device wizard must open on Devices");
  assert.equal(await p.locator("#enrollCard").getAttribute("data-enroll-step"),"1");
  await p.locator('[data-enroll-mode-choice="code"]').click();
  await p.locator("#enrollNext").click();
  assert.equal(await p.locator("#enrollCard").getAttribute("data-enroll-step"),"2");
  assert.equal(await p.locator("#enrollCard").getAttribute("data-enroll-mode"),"code");
  await p.waitForFunction(()=>!!document.getElementById("nodeEndpointValue").value,null,{timeout:5000});
  assert.equal(await p.locator("#nodeEndpointValue").inputValue(),"http://127.0.0.1:"+port);
  assert.equal(await p.locator("#copyNodeEndpoint").isEnabled(),true);
  await p.locator("#closeEnroll").click();
  assert.equal(await p.locator("#enrollDialog").evaluate(el=>el.open),false);
  await p.locator("#addDeviceBtn").click();
  await p.locator("#enrollNext").click();
  assert.equal(await p.locator("#enrollCard").getAttribute("data-enroll-mode"),"package");
  assert.equal(await p.locator("#enrollBtn").isVisible(),true);
  assert.equal(await p.locator("#enrollLink").isVisible(),false);
  await p.locator("#closeEnroll").click();
  await p.locator("#languageSelect").selectOption("en");
  await p.locator("#addDeviceBtn").click();
  assert.equal(await p.locator('[data-i18n="ux_package_mode"]').textContent(),"Scan QR / copy link");
  await p.locator('[data-enroll-mode-choice="code"]').click();
  await p.locator("#enrollNext").click();
  await p.locator("#pairCodeInput").fill("123");
  await p.locator("#approvePairCode").click();
  assert.match(await p.locator("#pairCodeStatus").textContent(),/8-digit pairing code/);
  await p.locator("#closeEnroll").click();
  await p.locator("#languageSelect").selectOption("zh-CN");
  await p.locator('.nav[data-view="settings"]').click();
  await p.locator('[data-settings-link="system"]').click();
  assert.equal(await p.locator('[data-settings-group="system"]').isVisible(),true);
  assert.equal(await p.locator('[data-settings-group="security"]').first().isVisible(),false);
  await p.locator('[data-settings-link="security"]').click();
  assert.equal(await p.locator('[data-settings-group="security"]').first().isVisible(),true);
  // A desktop settings category never stretches unrelated cards into the same row.
  assert.equal(await p.locator(".settings-grid").evaluate(el=>getComputedStyle(el).gridTemplateColumns.split(" ").length),1);
  await p.reload({waitUntil:"domcontentloaded"});
  await p.waitForFunction(()=>!document.getElementById("appContent").hidden,null,{timeout:12000});
  assert.equal(await p.locator("#lockedPanel").isVisible(),false,"Refresh must restore Vault in a valid active tab");
  const sibling=await c.newPage();
  await sibling.goto("http://127.0.0.1:"+port+"/",{waitUntil:"domcontentloaded"});
  await sibling.locator("#passphrase").fill("session-resume-test-passphrase");
  await sibling.locator("#vaultUnlockBtn").click();
  await sibling.waitForFunction(()=>!document.getElementById("appContent").hidden,null,{timeout:12000});
  // Exercise the real ES-module application through a synthetic Relay API
  // response, not inaccessible module-local browser variables. The final item
  // has an old provider timestamp but a newer Relay sequence.
  const synthetic=Array.from({length:32},(_,i)=>({
    deviceId:"synthetic-node",eventId:"synthetic-event-"+i,
    kind:"sms.history",seq:50000+i,
    occurredAt:i===31?100:1790000000+i,
    subscriptionId:"-1",hasOtp:false,
    ciphertext:{v:2,kid:"synthetic-wrong-key",iv:"AA",ct:"AA"}
  }));
  await p.route("**/api/v1/events?since=*",route=>route.fulfill({
    status:200,contentType:"application/json",
    body:JSON.stringify({events:synthetic})
  }));
  await p.locator("#refreshBtn").click();
  await p.waitForFunction(()=>document.getElementById("decryptNotice").textContent.includes("32"),null,{timeout:15000});
  assert.equal(await p.locator("#decryptNotice").isVisible(),true);
  const warning=await p.locator("#decryptNotice").textContent();
  assert.ok(!warning.includes("synthetic-wrong-key"),"Do not display ciphertext key material");
  await p.unroute("**/api/v1/events?since=*");
  // Use synthetic secrets only: test that hidden DOM, fields, links and even
  // diagnostic dialogs are destroyed when locking one of two active tabs.
  await p.evaluate(()=>{
    document.getElementById("inboxList").innerHTML='<div>PRIVATE_SMS_TEST_MARKER</div>';
    document.getElementById("deviceList").innerHTML='<div>PRIVATE_SIM_TEST_MARKER</div>';
    document.getElementById("conversationMessages").textContent='PRIVATE_THREAD_TEST_MARKER';
    document.getElementById("sendBody").value="PRIVATE_DRAFT_TEST_MARKER";
    document.getElementById("replyBody").value="PRIVATE_REPLY_TEST_MARKER";
    document.getElementById("recoveryKey").value="PRIVATE_KEY_TEST_MARKER";
    document.getElementById("enrollLink").value="PRIVATE_BOOTSTRAP_TEST_MARKER";
    document.getElementById("openEnroll").href="simhub://enroll?token=PRIVATE_LINK_TEST_MARKER";
    document.getElementById("enrollResult").hidden=false;
    document.getElementById("diagnosticsDialog").showModal();
    document.getElementById("diagnosticsOutput").textContent="PRIVATE_DIAG_TEST_MARKER";
  });
  // Programmatic click models the idle-expiry path even while a modal is open:
  // a pointer click cannot reach controls behind an active <dialog>.
  await p.evaluate(()=>document.getElementById("lockBtn").click());
  const leak=await p.evaluate(()=>{
    const ids=['inboxList','deviceList','conversationMessages','diagnosticsOutput','enrollLink','sendBody','replyBody','recoveryKey'];
    return {fields:ids.map(id=>{const el=document.getElementById(id);return {id,content:el.value??el.textContent??'',html:el.innerHTML}}),
      link:document.getElementById("openEnroll").getAttribute('href'),
      dialog:document.getElementById("diagnosticsDialog").open,
      appHidden:document.getElementById("appContent").hidden,
      sessionSnapshot:sessionStorage.getItem("simhub_session_vault_v1")};
  });
  assert.ok(leak.appHidden,"Locked Vault must hide app");
  assert.equal(leak.link,null,"Pairing href must be destroyed");
  assert.equal(leak.dialog,false,"Sensitive diagnostics dialog must close");
  assert.equal(leak.sessionSnapshot,null,"Local tab recovery must be invalidated");
  assert.ok(leak.fields.every(f=>!f.content&&!f.html),`Sensitive DOM not purged: ${JSON.stringify(leak.fields)}`);
  await sibling.waitForFunction(()=>document.getElementById("appContent").hidden,null,{timeout:12000});
  await p.reload({waitUntil:"domcontentloaded"});
  assert.equal(await p.locator("#appContent").isVisible(),false,"Manual lock must invalidate tab recovery");
  await sibling.close();
  await c.close();
  console.log("PASS: UI breakpoints, light/dark, dialog spacing and SMS new-compose");
} finally {
  if(browser)await browser.close();
  server.kill("SIGTERM");
  await rm(tmp,{recursive:true,force:true});
}
