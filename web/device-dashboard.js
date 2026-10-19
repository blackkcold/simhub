/** Semantic SIM-node health dashboard. Presentation only: no polling, key or command changes. */
import {osmEmbedUrl,osmExternalUrl} from './device-location.js';
const icons = {
  phone:'<rect x="6" y="2.5" width="12" height="19" rx="2.5"/><path d="M11 18.5h2"/>',
  battery:'<rect x="2" y="7" width="18" height="11" rx="2"/><path d="M22 10v5"/><path d="M5 10h11v5H5z"/>',
  message:'<path d="M20 11.5a7.5 7.5 0 0 1-7.5 7.5H5l1.5-3.5A7.5 7.5 0 1 1 20 11.5Z"/>',
  sync:'<path d="M20 7v5h-5M4 17v-5h5"/><path d="M6.2 9a7 7 0 0 1 12-2L20 12M4 12l1.8 5a7 7 0 0 0 12-2"/>',
  sim:'<path d="M6 2h9l5 5v15H6z"/><path d="M15 2v5h5"/><rect x="9" y="12" width="8" height="7" rx="1"/>',
  shield:'<path d="m12 2 8 4v6c0 5-3 8-8 10-5-2-8-5-8-10V6z"/><path d="m8 12 3 3 5-6"/>',
  network:'<path d="M2 9a15 15 0 0 1 20 0M5 12a10 10 0 0 1 14 0M9 16a4 4 0 0 1 6 0"/><circle cx="12" cy="20" r="1"/>',
  activity:'<path d="M2 12h5l3-7 4 14 3-7h5"/>',
  clock:'<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  tasks:'<rect x="4" y="3" width="16" height="18" rx="2"/><path d="m8 9 2 2 4-4M8 16h8"/>',
  alert:'<path d="m12 3 10 18H2z"/><path d="M12 9v5m0 3h.01"/>'
};
function icon(name){return '<svg class="node-icon" viewBox="0 0 24 24" width="21" height="21" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'+(icons[name]||icons.activity)+'</svg>';}
const validNumber = (value) => value===null||value===undefined||value==='' ? null : (Number.isFinite(Number(value)) ? Number(value) : null);
export function nodeDashboardModel(device){
  const s=device.state||{}, revoked=!!device.revoked, online=!!device.online&&!revoked;
  const battery=validNumber(s.batteryPct),pct=battery!==null&&Number.isInteger(battery)&&battery>=0&&battery<=100?battery:null;
  const sampledAt=validNumber(s.sampledAt),age=sampledAt===null?null:Math.floor(Date.now()/1000)-sampledAt;
  const maxAge=s.energyMode==='eco'?1800:s.energyMode==='realtime'?300:900;
  const freshness=age===null||age< -300?'unknown':age>maxAge?'stale':'fresh';
  const pending=validNumber(s.pendingEvents),ack=validNumber(s.pendingCommandAcks);
  const mode=s.smsMode||'', send=mode==='default'&&s.smsSendPermission===true&&s.smsSendAllowed!==false;
  const developerSend=mode==='companion'&&s.smsDeveloperOverride===true&&s.smsSendAllowed===true&&s.smsSendPermission===true;
  const events=[
    ['SMS Provider',s.lastSmsProviderChangeAt],
    ['SMS 广播',s.lastSmsBroadcastAt],
    ['历史补扫',s.lastReconcileAt],
    ['事件上传',s.lastEventUploadAt],
    ['同步完成',s.lastSyncSuccessAt]
  ].filter(([,time])=>time!==null&&time!==undefined&&time!==''&&Number.isFinite(Number(time))&&Number(time)>0)
   .map(([label,time])=>({label,time:Number(time)})).sort((a,b)=>b.time-a.time);
  return {s,online,pct,pending,ack,mode,send,developerSend,events,revoked,sampledAt,freshness};
}
/** Each lit mark corresponds to an actual SDK signal level, not continuous animation. */
export function signalBars(level){
  const n=validNumber(level),known=n!==null&&Number.isInteger(n)&&n>=0&&n<=4;
  return '<svg class="node-signal-bars" role="img" aria-label="'+(known?'Signal '+n+' of 4':'Signal unknown')+'" viewBox="0 0 48 32">'+
    [0,1,2,3].map(i=>'<rect x="'+(2+i*12)+'" y="'+(24-i*6)+'" width="9" height="'+(8+i*6)+'" rx="2" class="'+(known&&i<n?'node-lit':'node-dim')+'"/>').join('')+'</svg>';
}
export function wifiArcs(connected,level){
  const n=validNumber(level),known=connected===true&&n!==null&&Number.isInteger(n)&&n>=0&&n<=4;
  const parts=['M20 30 L20 30','M14 25 Q20 20 26 25','M8 19 Q20 8 32 19','M2 13 Q20 -2 38 13'];
  return '<svg class="node-wifi-arcs" viewBox="0 0 40 36" role="img" aria-label="'+(!connected?'Wi-Fi disconnected':!known?'Wi-Fi strength unknown':'Wi-Fi '+n+' of 4')+'">'+
    parts.map((path,i)=>'<path d="'+path+'" class="'+(known&&i<n?'node-lit':'node-dim')+'" fill="none" stroke-width="3.5" stroke-linecap="round"/>').join('')+'</svg>';
}
export function batteryLevel(pct,charging){
  const n=validNumber(pct),known=n!==null&&Number.isInteger(n)&&n>=0&&n<=100;
  return '<svg class="node-battery-level" viewBox="0 0 62 34" role="img" aria-label="'+(known?'Battery '+n+'%':'Battery unknown')+'">'+
    '<rect x="2" y="4" width="52" height="26" rx="6" class="node-battery-shell"/><rect x="55" y="12" width="5" height="10" rx="2" class="node-battery-terminal"/>'+
    (known?'<rect x="5" y="7" width="'+(46*n/100).toFixed(1)+'" height="20" rx="3" class="node-battery-fill"/>':'')+
    (charging===true?'<path d="M30 7L24 18h6l-2 9 10-13h-7l3-7z" class="node-charge-icon"/>':'')+'</svg>';
}
export function renderNodeDashboard(device,{escapeHtml:esc,fmtTime,deviceStatusText,channels,buttons,zh=true,locationInfo=null,mapVisible=false}){
  const m=nodeDashboardModel(device),s=m.s, e=(v)=>esc(v==null?'—':String(v));
  const t=(cn,en)=>zh?cn:en;
  const number=(n)=>n==null?'—':e(n);
  const freshness=m.freshness==='fresh'?t('最近采样','Recently sampled'):m.freshness==='stale'?t('状态已过期','Stale snapshot'):t('采样时间未知','Sample time unknown');
  const networkCard=(label,glyph,detail)=>'<div class="node-network-card">'+glyph+'<div><strong>'+e(label)+'</strong><small>'+e(detail)+'</small></div></div>';
  const wifiDetail=s.wifiConnected===false?t('未连接','Disconnected'):s.wifiConnected!==true?t('状态未知','Unknown'):
    s.wifiSignalLevel===undefined||s.wifiSignalLevel===null?t('已连接 · 强度未知','Connected · strength unknown'):
    t('信号等级','Signal level')+' '+e(s.wifiSignalLevel)+'/4';
  const time=(x)=>x?e(fmtTime(x)):t('暂无记录','No record');
  const telemetryTime=m.sampledAt&&m.freshness!=='unknown'?time(m.sampledAt):'—';
  const header=(symbol,title,subtitle='')=>'<div class="node-panel-heading"><span class="node-icon-box">'+icon(symbol)+'</span><div><h3>'+e(title)+'</h3>'+(subtitle?'<p>'+e(subtitle)+'</p>':'')+'</div></div>';
  const row=(label,value,mark='')=>'<div class="node-fact"><span>'+e(label)+'</span><strong'+(mark?' class="'+mark+'"':'')+'>'+e(value)+'</strong></div>';
  const chip=(name,value,kind='')=>'<div class="node-health-metric '+kind+'"><div class="node-metric-head">'+icon(name)+'</div><strong>'+e(value)+'</strong><small>'+({battery:t('设备电量','Battery'),message:t('短信总量','Messages'),sync:t('待上传事件','Pending events')}[name]||'')+'</small></div>';
  const perm=(label,value)=>row(label,value===true?t('已授权','Granted'):value===false?t('未授权','Not granted'):t('未报告','Unknown'),value===true?'node-positive':value===false?'node-warning':'');
  const sims=channels.map((c,i)=>'<div class="node-sim-row">'+
    '<span class="node-icon-box">'+signalBars(c.signalLevel)+'</span><div class="node-sim-main"><strong>'+e(c.displayName||c.alias||c.carrierName||'SIM '+(i+1))+'</strong>'+
    '<small>'+e(c.phoneNumber||t('号码未设置','Number unknown'))+' · '+e(c.serviceState||c.carrierName||'—')+
    ' · '+t('信号','Signal')+' '+e(c.signalLevel??'—')+'</small></div>'+
    '<button class="ghost mini" type="button" data-action="edit-sim" data-id="'+e(device.id)+'" data-channel="'+e(c.id)+'">'+t('设置号码','Edit number')+'</button></div>').join('');
  const sendMode=m.mode==='companion'?(m.developerSend?t('开发者临时发送','Temporary developer send'):t('不接管 · 默认禁止发送','Companion · read-only')):
    m.mode==='default'?t('默认短信应用','Default SMS handler'):t('未报告','Unknown');
  const sendCapability=m.send?t('满足基本发送条件','Basic send prerequisites met'):m.developerSend?t('仅开发者测试','Developer test only'):t('不可远程发送','Remote send unavailable');
  const issues=['lastUploadError','lastEventQueueError','lastSyncError','smsProviderError','stateCollectionError']
    .filter(k=>s[k]).map(k=>({type:k,detail:String(s[k])}));
  const rings=m.pct==null?'<span class="node-gauge-na">—</span>':
    '<svg class="node-gauge" viewBox="0 0 100 100" role="img" aria-label="'+e(t('电量','Battery')+' '+m.pct+'%')+'">'+
    '<circle cx="50" cy="50" r="39" class="node-gauge-track"/>'+
    '<circle cx="50" cy="50" r="39" class="node-gauge-value" stroke-dasharray="'+(m.pct*2.45044).toFixed(2)+' 245.04" transform="rotate(-90 50 50)"/>'+
    '<text x="50" y="57" text-anchor="middle">'+e(m.pct)+'%</text></svg>';
  const activity=m.events.length?'<ol class="node-activity">'+m.events.slice(0,6).map(a=>
    '<li><span class="node-activity-point" aria-hidden="true"></span><div><strong>'+e(a.label)+'</strong><small>'+time(a.time)+'</small></div></li>').join('')+'</ol>':
    '<p class="node-empty">'+t('暂无可用的历史事件','No recorded activity yet')+'</p>';
  const hasLocation=locationInfo?.kind==='loaded'&&locationInfo.location;
  const position=hasLocation?locationInfo.location:null;
  const mapUrl=position&&mapVisible?osmEmbedUrl(position):null;
  const mapLink=position?osmExternalUrl(position):null;
  const locationStatus=locationInfo?.kind==='loaded'?
    t('最近一次定位 · 不是实时追踪','Last recorded position · not live tracking'):
    locationInfo?.kind==='none'?t('服务器没有已保存的位置','No saved position on Relay'):
    locationInfo?.kind==='error'?t('位置读取失败，请重试','Unable to load position'):
    t('按需读取端到端加密的位置','Load encrypted location on demand');
  const locationControls='<div class="row wrap node-location-actions">'+
    '<button class="ghost mini" type="button" data-action="location-load" data-id="'+e(device.id)+'">'+t('读取加密位置','Load encrypted position')+'</button>'+
    (position&&!mapVisible?'<button class="ghost mini" type="button" data-action="location-map" data-id="'+e(device.id)+'">'+t('显示 OSM 地图','Show OSM map')+'</button>':'')+
    (s.locationSharingEnabled===true&&s.locationRemoteAllowed===true?
      '<button class="ghost mini" type="button" data-action="location-remote" data-id="'+e(device.id)+'">'+t('请求一次远程定位','Request one-shot location')+'</button>':'')+
    '<button class="ghost mini" type="button" data-action="location-purge" data-id="'+e(device.id)+'">'+t('清除服务器位置','Delete Relay position')+'</button></div>';
  const locationDetails=position?
    '<div class="node-location-facts">'+row(t('纬度','Latitude'),position.latitude.toFixed(6))+
    row(t('经度','Longitude'),position.longitude.toFixed(6))+
    row(t('精度半径','Accuracy radius'),Math.round(position.accuracyMeters)+' m')+
    row(t('定位时间','Position time'),fmtTime(position.sampledAt))+'</div>':'';
  const mapView=mapUrl?'<div class="node-map-frame"><iframe loading="lazy" sandbox="allow-scripts allow-same-origin allow-popups" referrerpolicy="no-referrer" title="OpenStreetMap" src="'+mapUrl+'"></iframe><div class="node-map-attribution"><a href="'+mapLink+'" target="_blank" rel="noopener noreferrer">'+t('在 OpenStreetMap 中打开','Open in OpenStreetMap')+'</a> · © OpenStreetMap contributors</div></div>':'';
  const mapDisclosure=position&&!mapVisible?'<p class="node-field-note">'+t(
    '地图默认不加载。点击“显示 OSM 地图”后，坐标将发送给 OpenStreetMap 服务。',
    'Map is not loaded by default. Choosing Show OSM map shares the coordinates with OpenStreetMap.')+'</p>':'';
  const queue=Array.isArray(s.pendingEventTasks)&&s.pendingEventTasks.length?
    '<div class="node-task-items">'+s.pendingEventTasks.slice(0,10).map(q=>row(q.kind||'unknown',fmtTime(q.queuedAt))).join('')+'</div>':
    '<p class="node-empty">'+t('没有已报告的待处理事件任务','No pending event tasks reported')+'</p>';
  return '<div class="node-dashboard">'+
    '<section class="node-identity"><div class="node-identity-title">'+
    '<span class="status-pill '+(m.online?'online':'')+'">'+e(deviceStatusText(device))+'</span>'+
    '<span>'+e(device.model||device.nodeType||'Android')+' · v'+e(device.appVersion||'—')+'</span></div>'+
    (!m.online?'<p class="node-stale">'+t('设备不在线：以下为最近一次上报的状态，并非实时值。','Device offline: last reported values, not live data.')+'</p>':'')+
    '<p class="node-sample-time '+(m.freshness==='fresh'?'':'node-stale')+'">'+e(freshness)+' · '+telemetryTime+'</p></section>'+
    '<section class="node-metrics" aria-label="'+t('设备关键指标','Device metrics')+'">'+
    '<div class="node-health-metric"><div class="node-metric-head">'+batteryLevel(m.pct,s.charging)+'</div><strong>'+e(m.pct==null?'—':m.pct+'%')+'</strong><small>'+t('设备电量','Battery')+'</small></div>'+
    chip('message',device.smsCount??'—')+chip('sync',number(m.pending))+'</section>'+
    '<div class="node-dashboard-grid">'+
    '<section class="node-panel">'+header('shield',t('SIM 与短信能力','SIM & SMS capability'))+
      '<div class="node-emphasis">'+e(sendMode)+'</div>'+perm(t('短信读取','SMS read'),s.smsReadPermission)+
      perm(t('短信接收','SMS receive'),s.smsReceivePermission)+perm(t('短信发送权限','SMS send permission'),s.smsSendPermission)+
      row(t('实际发送能力','Effective sending'),sendCapability,m.send?'node-positive':'')+
      row(t('短信服务','SMS service'),s.smsOperational===true?t('可用','Available'):s.smsOperational===false?t('不可用','Unavailable'):t('未验证','Unverified'))+'</section>'+
    '<section class="node-panel">'+header('sync',t('短信同步','SMS synchronization'))+
      '<div class="node-emphasis">'+t('最近同步','Last sync')+' · '+time(s.lastSyncSuccessAt)+'</div>'+
      row(t('待上传事件','Pending events'),number(m.pending))+row(t('待发送 ACK','Pending acknowledgements'),number(m.ack))+
      row(t('累计上传确认','Confirmed uploads'),s.uploadedEventTotal??'—')+
      row(t('上传回执计数','Receipt counter'),s.uploadedEventReceipts??'—')+
      row(t('上次上传数量','Last batch size'),s.lastEventUploadCount??'—')+
      '<p class="node-field-note">'+t('上传回执与确认总量可能具有不同口径，不直接计算成功率。','Receipt and upload counters may have different definitions; no success rate is inferred.')+'</p></section>'+
    '<section class="node-panel">'+header('sim',t('SIM 通道','SIM channels'))+
      (sims||'<p class="node-empty">'+t('尚无通道上报','No channels reported')+'</p>')+'</section>'+
    '<section class="node-panel">'+header('network',t('设备与网络','Device & connectivity'))+
      '<div class="node-network-visuals">'+
        networkCard('Wi-Fi',wifiArcs(s.wifiConnected,s.wifiSignalLevel),wifiDetail)+
        channels.slice(0,8).map((ch,i)=>networkCard(ch.displayName||ch.alias||'SIM '+(i+1),
          signalBars(ch.signalLevel),ch.signalLevel==null?t('信号未知','Signal unknown'):t('信号','Signal')+' '+ch.signalLevel+'/4')).join('')+
      '</div><div class="node-battery-row">'+rings+'<div>'+row(t('网络','Network'),s.network||'—')+
      row(t('充电','Charging'),s.charging===true?t('充电中','Charging'):s.charging===false?t('未充电','Not charging'):t('未知','Unknown'))+'</div></div>'+
      row(t('蜂窝数据接管','Cellular data fallback'),s.dataFallbackEnabled===true?(s.dataFallbackStatus||t('已开启','Enabled')):s.dataFallbackEnabled===false?t('未开启','Disabled'):t('未知','Unknown'))+'</section>'+
    '<section class="node-panel node-location-panel">'+header('network',t('位置与隐私','Location & privacy'))+
       '<p class="node-empty">'+e(locationStatus)+'</p>'+
       row(t('本机位置共享','Local location sharing'),s.locationSharingEnabled===true?t('已授权','Enabled'):s.locationSharingEnabled===false?t('关闭','Off'):t('未报告','Unknown'))+
       row(t('远程定位请求','Remote positioning'),s.locationRemoteAllowed===true?t('本机允许','Allowed on node'):t('默认禁止','Not allowed by node'))+
       locationDetails+locationControls+mapDisclosure+mapView+
       '<p class="node-field-note">'+t('位置仅以 Node Key 密文存储；地图不会自动打开。','Location is stored as Node Key ciphertext; maps never auto-load.')+'</p></section>'+
    '<section class="node-panel">'+header('activity',t('最近活动','Recent activity'),t('真实上报事件','Reported events'))+activity+
      '<p class="node-field-note">'+t('当前数据为状态快照，不构造缺乏采样依据的趋势图。','Snapshot data only; no synthetic trends.')+'</p></section>'+
    '<section class="node-panel">'+header('tasks',t('任务与诊断','Tasks & diagnostics'))+
      row(t('待处理事件','Queued events'),number(m.pending))+row(t('待处理命令 ACK','Queued command ACKs'),number(m.ack))+
      '<details class="node-tasks-details"><summary>'+t('查看任务明细','Task details')+'</summary>'+queue+'</details>'+
      (issues.length?'<div class="node-problems">'+issues.map(i=>'<p>'+icon('alert')+'<strong>'+e(i.type)+'</strong> '+e(i.detail)+'</p>').join('')+'</div>':
      '<p class="node-positive node-check">'+icon('shield')+' '+t('未报告同步或状态采集错误','No sync or collection errors reported')+'</p>')+
      '</section></div>'+buttons+'</div>';
}
