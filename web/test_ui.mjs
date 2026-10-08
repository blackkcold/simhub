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
      assert.equal(await page.locator("#username").count(),1);
      assert.equal(await page.locator("#passkeyLoginBtn").count(),1);
      await page.evaluate(()=>document.querySelector("#stepupDialog").showModal());
      const positions=await page.evaluate(()=>{
        const d=document.querySelector("#stepupDialog").getBoundingClientRect();
        const i=document.querySelector("#stepupValue").getBoundingClientRect();
        const b=document.querySelector(".stepup-actions").getBoundingClientRect();
        return {dialog:d.width,inputBottom:i.bottom,buttonsTop:b.top,right:d.right};
      });
      assert.ok(positions.dialog<=500,"Dialog is too wide at "+width);
      assert.ok(positions.right<=width+2,"Dialog overflows viewport at "+width);
      assert.ok(positions.buttonsTop-positions.inputBottom>=16,"Buttons touch input at "+width);
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
      await page.locator("#newSmsBtn").click();
      const visible=await page.locator("#replyComposer").isVisible();
      if(!visible){
        console.error("UI-DIAGNOSTICS",JSON.stringify(await page.evaluate(()=>{
          const x=id=>{const e=document.getElementById(id);return {
            exists:!!e,hidden:e?.hidden,css:e?getComputedStyle(e).display:null,
            className:e?.className};};
          return {composer:x('replyComposer'),app:x('appContent'),
            inbox:x('view-inbox'),layout:x('smsLayout'),
            newButton:x('newSmsBtn'),handler:typeof document.getElementById('newSmsBtn').onclick};
        })),errors);
      }
      assert.ok(visible,"New SMS composer hidden");
      await page.locator("#replyTo").fill("+8613800138000");
      await page.locator("#replyBody").fill("Hello");
      const composer=await page.locator("#replyComposer").boundingBox();
      assert.ok(composer&&composer.width<=width+1,"Composer overflows screen");
      await page.screenshot({path:"ui-snapshots/simhub-"+width+"-"+theme+".png",fullPage:true});
      assert.deepEqual(errors,[],"Browser errors at "+width+" / "+theme);
      await context.close();
    }
  }
  console.log("PASS: UI breakpoints, light/dark, dialog spacing and SMS new-compose");
} finally {
  if(browser)await browser.close();
  server.kill("SIGTERM");
  await rm(tmp,{recursive:true,force:true});
}
