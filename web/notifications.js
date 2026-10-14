import {NotificationStore} from './notification-store.js';
const KEY='simhub_notify_preferences_v1';
export const CATEGORIES=['otp','sms','device_offline','device_recovered','task_failed','task_done','update_failed','update_done'];
export const DEFAULT_PREFERENCES=Object.freeze({enabled:true,otp:true,sms:true,device_offline:true,
  device_recovered:false,task_failed:true,task_done:false,update_failed:true,update_done:false,badge:true,foreground:true});
export const TITLES={
  zh:{otp:'收到新验证码',sms:'收到新短信',device_offline:'设备离线',device_recovered:'设备已恢复连接',
    task_failed:'远程任务失败',task_done:'远程任务完成',update_failed:'远程升级失败',update_done:'远程升级完成'},
  en:{otp:'New verification code',sms:'New SMS',device_offline:'Device offline',device_recovered:'Device reconnected',
    task_failed:'Remote command failed',task_done:'Remote command finished',update_failed:'Remote update failed',update_done:'Remote update finished'}
};
export function getNotificationPrefs(){
  let custom={};
  try{custom=JSON.parse(localStorage.getItem(KEY)||'{}')||{};}catch{}
  const prefs={...DEFAULT_PREFERENCES};
  for(const k of Object.keys(prefs))if(typeof custom[k]==='boolean')prefs[k]=custom[k];
  return prefs;
}
export function setNotificationPref(key,value){
  if(!(key in DEFAULT_PREFERENCES))return;
  const next={...getNotificationPrefs(),[key]:!!value};
  try{localStorage.setItem(KEY,JSON.stringify(next));}catch{}
  return next;
}
export function notificationPermission(){
  if(!('Notification' in globalThis)||!globalThis.isSecureContext)return 'unsupported';
  return Notification.permission;
}
// The Web Notification API cannot confirm that an OS banner was actually rendered.
// Return structured diagnostics instead of mistaking permission for delivery.
export async function sendBrowserNotification(kind,id,locale='zh-CN',force=false){
  const prefs=getNotificationPrefs();
  if(!force&&(!prefs.enabled||!prefs[kind]))return {ok:false,reason:'disabled'};
  if(!force&&!prefs.foreground&&document.visibilityState==='visible')return {ok:false,reason:'foreground_paused'};
  if(notificationPermission()!=='granted')return {ok:false,reason:'permission'};
  if(!('serviceWorker' in navigator))return {ok:false,reason:'worker_unsupported'};
  const title=TITLES[locale==='en'?'en':'zh'][kind]||'SIM Hub';
  try{
    // ready remains unresolved when registration/install fails; never leave the test button spinning.
    const registration=await new Promise((resolve,reject)=>{
      const timer=setTimeout(()=>reject(new Error('worker_timeout')),5000);
      Promise.resolve(navigator.serviceWorker.ready).then(value=>{clearTimeout(timer);resolve(value);},
        error=>{clearTimeout(timer);reject(error);});
    });
    if(!registration?.active||typeof registration.showNotification!=='function')
      return {ok:false,reason:'worker_unavailable'};
    await registration.showNotification('SIM Hub · '+title,{
      body:locale==='en'?'Open SIM Hub to review the event.':'打开 SIM Hub 查看详情。',
      icon:'/icon.svg',badge:'/icon.svg',tag:'simhub-'+id,renotify:false,
      data:{url:kind==='otp'||kind==='sms'?'/?view=inbox':'/?view=devices'}});
    return {ok:true,reason:'accepted'};
  }catch(error){
    const reason=error?.message==='worker_timeout'?'worker_timeout':'delivery_failed';
    console.warn('SIM Hub browser notification unavailable',error?.name||reason);
    return {ok:false,reason};
  }
}
export async function displayBrowserNotification(kind,id,locale='zh-CN',force=false){
  return (await sendBrowserNotification(kind,id,locale,force)).ok;
}
export class NotificationCenter{
  constructor(onChange=()=>{}){
    this.store=new NotificationStore();this.scope='admin';this.rows=[];this.onChange=onChange;this.locale='zh-CN';
    this.channel=typeof BroadcastChannel==='function'?new BroadcastChannel('simhub-notification-changes-v1'):null;
    if(this.channel)this.channel.onmessage=()=>{this.refresh().catch(()=>{});};
    if(typeof window!=='undefined')window.addEventListener('storage',e=>{if(e.key===KEY)this.publish(false);});
  }
  setScope(name){this.scope=String(name||'admin').trim().slice(0,64)||'admin';return this.refresh();}
  publish(broadcast=true){this.onChange(this.rows,getNotificationPrefs());if(broadcast)try{this.channel?.postMessage({changed:true});}catch{}}
  async refresh(){await this.store.prune(this.scope);this.rows=await this.store.list(this.scope);this.publish(false);return this.rows;}
  async record({id,kind,deviceId='',sourceEventId='',createdAt=Date.now()}){
    if(!CATEGORIES.includes(kind))return false;
    const scope=this.scope,inserted=await this.store.insert(scope,{id,kind,deviceId,sourceEventId,createdAt});
    if(inserted){await this.refresh();this.publish();await displayBrowserNotification(kind,id,this.locale);}
    return inserted;
  }
  async markRead(ids){const count=await this.store.read(this.scope,ids);if(count){await this.refresh();this.publish();}return count;}
  async clear(){await this.store.clear(this.scope);await this.refresh();this.publish();}
  unread(){return this.rows.filter(row=>!row.readAt&&(row.kind==='sms'||row.kind==='otp')).length;}
}
