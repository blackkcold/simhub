import {getNotificationPrefs,setNotificationPref,notificationPermission,sendBrowserNotification,TITLES} from './notifications.js';
const icons={otp:'M4 5h16v14H4z M8 10h8 M8 14h5',sms:'M4 5h16v14H4z M8 10h8 M8 14h5',
  device_offline:'M4 9c5-5 11-5 16 0 M7 13c3-3 7-3 10 0 M12 17h.01 M3 3l18 18',
  device_recovered:'M4 9c5-5 11-5 16 0 M7 13c3-3 7-3 10 0 M12 18h.01',
  task_failed:'M12 4v10 M12 18h.01 M4 4h16v16H4z',task_done:'M5 12l4 4 10-10',
  update_failed:'M12 4v10 M12 18h.01 M4 4h16v16H4z',update_done:'M5 12l4 4 10-10'};
function safe(value){return String(value??'').replace(/[&<>'"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[c]));}
export function bindNotificationUI({center,getLocale,tr,toast,switchView,isUnlocked,deviceName}){
  const $=id=>document.getElementById(id);
  let requesting=false,sending=false,lastDiagnostic='';
  function render(){
    const badge=$('smsUnreadBadge'),count=center.unread(),prefs=getNotificationPrefs(),unlocked=isUnlocked();
    if(badge){
      badge.hidden=!unlocked||!prefs.badge||!count;
      badge.textContent=count>99?'99+':String(count);
      badge.closest('.nav')?.setAttribute('aria-label',tr('nav_inbox')+(count?' · '+count+' '+tr('notify_unread'):''));
    }
    document.querySelectorAll('[data-notify-pref]').forEach(el=>{
      el.checked=!!prefs[el.dataset.notifyPref];
      el.disabled=el.dataset.notifyPref!=='enabled'&&el.dataset.notifyPref!=='badge'&&!prefs.enabled;
    });
    const permission=notificationPermission(),status=$('notifyPermission'),help=$('notifyPermissionHelp');
    if(status){status.dataset.state=permission;status.textContent=tr('notify_permission_'+permission);}
    if(help)help.textContent=tr('notify_permission_help_'+permission);
    const action=$('notifyBtn');
    if(action){
      action.dataset.state=permission;
      action.textContent=tr(permission==='granted'?(prefs.enabled?'notify_permission_action_granted':'notify_permission_action_paused'):
        'notify_permission_action_'+permission);
      action.disabled=requesting||permission==='granted'||permission==='unsupported';
      action.setAttribute('aria-busy',String(requesting));
    }
    if($('notifyTestBtn')){
      $('notifyTestBtn').disabled=sending||permission!=='granted';
      $('notifyTestBtn').setAttribute('aria-busy',String(sending));
    }
    const diagnostic=$('notifyTestResult');
    if(diagnostic)diagnostic.textContent=lastDiagnostic;
    const list=$('notifyHistoryList');if(!list)return;
    if(!unlocked){list.replaceChildren();return;}
    const filter=$('notifyHistoryFilter')?.value||'all';
    const rows=center.rows.filter(row=>{
      if(filter==='unread')return !row.readAt;
      if(filter==='sms')return row.kind==='sms'||row.kind==='otp';
      if(filter==='device')return row.kind.startsWith('device_');
      if(filter==='task')return row.kind.startsWith('task_')||row.kind.startsWith('update_');
      return true;
    });
    if(!rows.length){list.textContent=tr('notify_empty');return;}
    const language=getLocale()==='en'?'en':'zh';
    list.innerHTML=rows.slice(0,500).map(row=>{
      const title=TITLES[language][row.kind]||row.kind,date=new Date(row.createdAt).toLocaleString(getLocale());
      return '<button type="button" class="notify-entry" data-notice-id="'+safe(row.id)+'">'+
        '<span class="notify-entry-icon" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="'+icons[row.kind]+'"/></svg></span>'+
        '<span class="notify-entry-content"><span class="notify-entry-title">'+safe(title)+'</span>'+
        '<span class="notify-entry-meta">'+safe(deviceName(row.deviceId))+'</span></span>'+
        '<time class="notify-entry-time">'+safe(date)+'</time>'+
        (row.readAt?'':'<span class="notify-entry-dot" aria-label="'+safe(tr('notify_unread'))+'"></span>')+'</button>';
    }).join('');
  }
  $('notifyBtn').onclick=async()=>{
    const permission=notificationPermission();
    if(permission==='denied'){
      toast(tr('notify_permission_help_denied'),{kind:'error'});return;
    }
    if(permission!=='default'||requesting)return;
    requesting=true;render();
    try{
      const p=await Notification.requestPermission();
      toast(tr(p==='granted'?'browser_notifications_enabled':'notification_permission_denied'),
        {kind:p==='granted'?'success':'error'});
    }catch{
      toast(tr('notify_permission_help_unsupported'),{kind:'error'});
    }finally{requesting=false;render();}
  };
  $('notifyTestBtn').onclick=async()=>{
    if(sending)return;
    sending=true;lastDiagnostic='';render();
    try{
      const result=await sendBrowserNotification('sms','test-'+Date.now(),getLocale(),true);
      lastDiagnostic=tr(result.ok?'notify_test_sent':'notify_test_reason_'+result.reason);
      toast(lastDiagnostic,{kind:result.ok?'success':'error'});
    }catch{
      lastDiagnostic=tr('notify_test_failed');toast(lastDiagnostic,{kind:'error'});
    }finally{sending=false;render();}
  };
  document.querySelectorAll('[data-notify-pref]').forEach(el=>el.onchange=()=>{
    setNotificationPref(el.dataset.notifyPref,el.checked);render();
  });
  $('notifyHistoryFilter').onchange=render;
  $('notifyMarkAll').onclick=()=>center.markRead().catch(e=>toast(e.message,{kind:'error'}));
  $('notifyClearHistory').onclick=()=>{
    if(confirm(tr('notify_clear_confirm')))center.clear().catch(e=>toast(e.message,{kind:'error'}));
  };
  $('notifyHistoryList').onclick=e=>{
    const button=e.target.closest('[data-notice-id]');if(!button)return;
    const item=center.rows.find(row=>row.id===button.dataset.noticeId);
    center.markRead([button.dataset.noticeId]).catch(()=>{});
    if(item)switchView(item.kind==='otp'||item.kind==='sms'?'inbox':'devices');
  };
  if('serviceWorker' in navigator){
    navigator.serviceWorker.addEventListener('message',e=>{
      if(e.data?.type==='notification-open'&&['inbox','devices','settings'].includes(e.data.view))
        switchView(e.data.view);
    });
    navigator.serviceWorker.addEventListener('controllerchange',render);
  }
  window.addEventListener('focus',render);
  window.addEventListener('pageshow',render);
  document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='visible')render();});
  if(navigator.permissions?.query)navigator.permissions.query({name:'notifications'})
    .then(status=>status.addEventListener?.('change',render)).catch(()=>{});
  render();
  return {render,setWorkerError(){
    lastDiagnostic=tr('notify_test_reason_worker_unavailable');render();
  }};
}
