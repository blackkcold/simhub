package com.blackkcold.simhub

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.blackkcold.simhub.p2p.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.integration.android.IntentResult
import com.journeyapps.barcodescanner.BarcodeEncoder
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Native P2P workspace. PeerStore is the only identity source; no Vault bootstrap. */
class PeerActivity:ComponentActivity(){
    private var peers by mutableStateOf<List<JSONObject>>(emptyList())
    private var qr by mutableStateOf("")
    private var notice by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var listening by mutableStateOf(false)
    private var tab by mutableIntStateOf(0)
    private var allowSend by mutableStateOf(false)
    private var selected by mutableStateOf<JSONObject?>(null)
    private var remoteState by mutableStateOf<JSONObject?>(null)
    private var cursor:JSONObject?=null
    private var historyMode=false
    private val inbox=ControllerInboxState()
    private var recipient by mutableStateOf("")
    private var body by mutableStateOf("")
    private var compose by mutableStateOf(false)
    private var sendRequest:JSONObject?=null
    private var sendId:String?=null
    private var channelIndex by mutableIntStateOf(0)
    private var discovery:PeerDiscovery?=null
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){reload()}
    private val role=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){reload()}
    private fun store()=PeerStore.get(this)
    override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);UiLocale.apply(this);reload()
        setContent{MaterialTheme{Surface(Modifier.fillMaxSize()){Screen()}}}
        lifecycleScope.launch{repeatOnLifecycle(Lifecycle.State.STARTED){
            discovery=PeerDiscovery(this@PeerActivity)
            try{discovery?.start()}catch(_:Exception){notice="局域网发现不可用；请检查本地网络权限"}
            try{while(isActive){delay(12000);reload();if(selected!=null&&tab==1&&!busy&&!historyMode)load(false)}}finally{discovery?.close();discovery=null}
        }}
    }
    override fun onStop(){super.onStop();inbox.clear();remoteState=null}
    private fun reload(){peers=(0 until store().list().length()).map{store().list().getJSONObject(it)};listening=PeerService.enabled();selected?.let{p->selected=peers.find{it.optString("id")==p.optString("id")}}}
    private fun work(task:suspend ()->Unit){if(busy)return;busy=true;notice="";lifecycleScope.launch{try{task()}catch(e:Exception){notice=when(e){is SecurityException->"安全或权限校验未通过：${e.message?.take(80)}";else->"设备暂时不可达。请确认节点已开启直连、网络权限。未配置 Relay 时直连失败即失败。"}}finally{busy=false;reload()}}}
    private fun requestAccess(){if(!SmsAccessPolicy.rolePermitsAccess(this)){getSystemService(RoleManager::class.java)?.let{role.launch(it.createRequestRoleIntent(RoleManager.ROLE_SMS))};return};val list=mutableListOf(Manifest.permission.READ_SMS,Manifest.permission.RECEIVE_SMS,Manifest.permission.READ_PHONE_STATE);if(Build.VERSION.SDK_INT>=33)list.add(Manifest.permission.POST_NOTIFICATIONS);if(Build.VERSION.SDK_INT>=37)list.add("android.permission.ACCESS_LOCAL_NETWORK");if(SmsSendPolicy.canSend(this)||getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS)==true)list.add(Manifest.permission.SEND_SMS);permissions.launch(list.toTypedArray())}
    private fun startNode(){PeerService.start(this);lifecycleScope.launch{delay(800);reload();notice=PeerService.error()}}
    private fun scan(){IntentIntegrator(this).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE).setPrompt("扫描另一台手机的 P2P 二维码").setBeepEnabled(false).setOrientationLocked(false).initiateScan()}
    @Deprecated("ZXing compatibility") override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);val result:IntentResult?=IntentIntegrator.parseActivityResult(requestCode,resultCode,data);val contents=result?.contents?:return
        work{val resultQr=withContext(Dispatchers.IO){val manager=PeerPairingManager(applicationContext);when(PeerPairingManager.kind(contents)){"offer"->manager.respond(contents,Build.MODEL);"response"->{manager.accept(contents);""};else->throw SecurityException("peer_qr_kind_rejected")}};qr=resultQr;PeerService.start(this@PeerActivity);notice=if(resultQr.isBlank())"双向扫码已确认，管理手机现在可以连接" else "请让 SIM 节点扫描此响应二维码，公网连接会自动建立"}
    }
    private suspend fun rpc(p:JSONObject,id:String,request:JSONObject):JSONObject{
        return withContext(Dispatchers.IO){
            val coordinator=ConnectionCoordinator(store())
            if(p.optBoolean("publicDiscoveryEnabled")){PeerCloudConnector.ensure(applicationContext,p.getString("id"));PeerCloudConnector.active(p.getString("id"))?.let{coordinator.internet(it)}}
            coordinator.request(p.getString("id"),id,request)
        }
    }
    private suspend fun load(older:Boolean){val p=selected?:return;val req=JSONObject().put("action","sms.page").put("limit",100);if(older&&cursor!=null)req.put("cursor",cursor)
        val page=rpc(p,UUID.randomUUID().toString(),req);if(page.optString("state")!="succeeded"){notice="短信读取未完成：${page.optString("reason")}";return};if(inbox.accept(page.toString(),older,older)){cursor=page.optJSONObject("cursor");historyMode=older}
        if(!older){
            remoteState=rpc(p,UUID.randomUUID().toString(),JSONObject().put("action","device.state")).optJSONObject("device")
            acceptEvents(p)
            withContext(Dispatchers.IO){PeerInboxCache.save(applicationContext,p.getString("id"),inbox)}
        }
    }
    private suspend fun acceptEvents(p:JSONObject){
        val result=rpc(p,UUID.randomUUID().toString(),JSONObject().put("action","events.page"))
        val events=result.optJSONArray("events")?:return;val rows=JSONArray();val ack=JSONArray()
        for(i in 0 until events.length()){
            val event=events.getJSONObject(i);val payload=event.getJSONObject("payload");val eventId=event.getString("id");val address=payload.optString("sender",payload.optString("recipient"));val sub=payload.optInt("subscriptionId",-1)
            val provider=payload.optLong("providerId",-1).takeIf{it>=0}?.toString()?:Regex("^sms-provider-([0-9]+).*$").find(eventId)?.groupValues?.get(1)
            rows.put(JSONObject().put("id",if(provider!=null)"peer-${p.getString("id")}-$provider" else "peer-event-${p.getString("id")}-${eventId}")
                .put("thread",PeerCrypto.digest(PeerCrypto.utf8("${p.getString("id")}|$sub|$address"))).put("sender",address).put("body",payload.optString("body")).put("timestamp",event.optLong("occurred"))
                .put("inbound",payload.optString("direction")=="in").put("device",p.optString("name")).put("sim",payload.optString("simTag","SIM $sub")).put("sourceId","${p.getString("id")}-$sub").put("otp",payload.optJSONObject("otp")?.optString("value")?:"").put("failed",event.optString("kind")=="sms.failed"))
            ack.put(event.getLong("seq"))
        }
        if(rows.length()>0&&inbox.accept(JSONObject().put("type","snapshot").put("v",1).put("messages",rows).put("incremental",true).toString(),true)){
            withContext(Dispatchers.IO){PeerInboxCache.save(applicationContext,p.getString("id"),inbox)}
            rpc(p,UUID.randomUUID().toString(),JSONObject().put("action","events.ack").put("sequences",ack))
        }
    }
    private fun select(p:JSONObject){selected=p;tab=1;qr="";cursor=null;historyMode=false;inbox.clear();PeerInboxCache.restore(applicationContext,p.getString("id"),inbox);work{load(false)}}
    private fun beginWrite(to:String=""){recipient=to;body="";sendId=null;sendRequest=null;channelIndex=0;compose=true}
    private fun send(){val p=selected?:return;val channels=remoteState?.optJSONArray("channels")?:return;val ch=channels.optJSONObject(channelIndex)?:return
        if(sendId==null){sendId=UUID.randomUUID().toString();sendRequest=JSONObject().put("action","sms.send").put("to",recipient).put("body",body).put("channelId",ch.getString("id")).put("channelRevision",ch.getLong("revision")).put("commandExpires",System.currentTimeMillis()/1000+120)}
        work{withContext(Dispatchers.IO){store().queueOutbound(p.getString("id"),sendId!!,sendRequest!!)};val result=rpc(p,sendId!!,sendRequest!!);if(result.optString("state") in listOf("submitted","failed","expired"))withContext(Dispatchers.IO){store().clearOutbound(p.getString("id"),sendId!!)};notice="短信状态：${result.optString("state")}";if(result.optString("state")=="submitted"){compose=false;load(false)}}
    }
    @Composable private fun Screen(){
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)){
            Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.SpaceBetween){TextButton(onClick={finish()}){Text("返回 SIM Hub")};Text("P2P 设备直连",style=MaterialTheme.typography.titleLarge)}
            Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("配对与设备","短信","连接设置").forEachIndexed{i,text->FilterChip(selected=tab==i,onClick={tab=i},label={Text(text)})}}
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(notice.isNotBlank())Text(notice,Modifier.fillMaxWidth().padding(12.dp),color=MaterialTheme.colorScheme.primary)
            when(tab){
                0->DeviceScreen()
                1->{val p=selected;if(p==null){Text("请先双机扫码，然后选择管理设备",Modifier.padding(24.dp))}else{
                    Text("${p.optString("name")} · ${routeLabel(p.optString("activeTransport"))} · 端到端加密",Modifier.padding(horizontal=16.dp,vertical=8.dp),style=MaterialTheme.typography.labelMedium)
                    if(!inbox.unlocked)Button(onClick={work{load(false)}},Modifier.padding(16.dp)){Text("连接并读取短信")}
                    else NativeControllerInbox(inbox,onRefresh={work{load(false)}},onOlder={work{load(true)}},onNewer={work{load(false)}},onOpenLogin={tab=0},onWrite={beginWrite()},onReply={beginWrite(it)},canCompose=p.optString("permissions").contains("send")&&remoteState?.optBoolean("smsSendAllowed")==true)
                }}
                else->ConnectionScreen()
            }
        }
        if(compose)AlertDialog(onDismissRequest={if(!busy)compose=false},title={Text("通过已授权 SIM 发送短信")},text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
            OutlinedTextField(recipient,{if(sendId==null)recipient=it},label={Text("收件号码")},enabled=sendId==null)
            OutlinedTextField(body,{if(sendId==null)body=it},label={Text("短信内容")},enabled=sendId==null)
            val channels=remoteState?.optJSONArray("channels")?:JSONArray();for(i in 0 until channels.length()){val ch=channels.getJSONObject(i);FilterChip(selected=channelIndex==i,onClick={if(sendId==null)channelIndex=i},label={Text("SIM ${ch.optInt("slotIndex")+1} · ${ch.optString("carrierName")}")})}
            Text("断线重试保留同一命令 ID；不确定状态不会自动重复发送。",style=MaterialTheme.typography.bodySmall)
        }},confirmButton={Button(onClick={send()},enabled=!busy&&recipient.isNotBlank()&&body.isNotBlank()){Text(if(sendId==null)"发送" else "重试原命令")}},dismissButton={TextButton(onClick={compose=false},enabled=!busy){Text("关闭")}})
    }
    @Composable private fun DeviceScreen(){LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{HubCard(Modifier.fillMaxWidth()){
            Text("连接方式独立于设备角色",style=MaterialTheme.typography.titleMedium);Text("P2P 双机扫码后自动使用公共信令与 STUN 尝试直连；仅交换加密连接信息。Relay 可另行配置，配置后优先使用。")
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={requestAccess()}){Text("节点短信权限")};OutlinedButton(onClick={scan()}){Text("扫描 P2P 二维码")}}
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Switch(listening,{if(it)startNode() else{PeerService.stop(this@PeerActivity);listening=false}});Text("开启本机直连服务")}
            Text("只有 SIM 节点需要开启。持续监听会显示通知，关闭后 P2P 远程不可达。",style=MaterialTheme.typography.bodySmall)
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Checkbox(allowSend,{allowSend=it});Text("授予此新配对远程发送权限")}
            Button(onClick={work{if(!PeerService.enabled())throw IllegalStateException("start_node_listener_first");qr=withContext(Dispatchers.IO){PeerPairingManager(applicationContext).offer(Build.MODEL,allowSend,PeerService.endpoints())};notice="管理手机扫码后，节点还需扫描管理手机的响应二维码"}},enabled=listening){Text("节点：生成配对二维码")}
            if(!SmsSendPolicy.canSend(this@PeerActivity))OutlinedButton(onClick={getSystemService(RoleManager::class.java)?.let{if(it.isRoleAvailable(RoleManager.ROLE_SMS))role.launch(it.createRequestRoleIntent(RoleManager.ROLE_SMS))}}){Text("需要发送时设为默认短信应用")}
        }}
        if(qr.isNotBlank())item{HubCard(Modifier.fillMaxWidth()){
            val bitmap=remember(qr){try{BarcodeEncoder().encodeBitmap(qr,BarcodeFormat.QR_CODE,720,720).asImageBitmap()}catch(_:Exception){null}};Text("仅让你要配对的手机扫描",style=MaterialTheme.typography.titleMedium)
            bitmap?.let{Image(it,"P2P pairing QR",Modifier.fillMaxWidth().heightIn(max=340.dp))};Text("${if(qr.isNotBlank())"五分钟内完成双向扫码；二维码不包含短信或 Relay 密钥" else ""}",style=MaterialTheme.typography.bodySmall);TextButton(onClick={qr=""}){Text("隐藏二维码")}
        }}
        items(peers,key={it.optString("id")}){p->HubCard(Modifier.fillMaxWidth()){
            Text(p.optString("name"),style=MaterialTheme.typography.titleMedium);Text("${if(p.optString("localRole")=="NODE")"授权的管理手机" else "SIM 节点"} · ${p.optString("pairState")}")
            Text("注册模式：${p.optString("policy")}\n当前链路：${routeLabel(p.optString("activeTransport"))}\n公网发现：${p.optString("discoveryState")}")
            if(selected?.optString("id")==p.optString("id"))remoteState?.let{state->
                Text("电量 ${state.opt("batteryPct")?:"—"}% · ${if(state.optBoolean("charging"))"充电中" else "电池供电"} · ${state.optString("network","未知网络")}")
                Text("Android ${state.optString("androidVersion")} · ${state.optString("appVersion")} · ${if(state.optBoolean("smsSendAllowed"))"系统允许发送" else "当前仅允许读取"}")
            }
            Text("身份指纹：${p.optString("id").take(16)}",style=MaterialTheme.typography.labelSmall)
            if(p.optString("localRole")=="CONTROLLER"&&p.optString("pairState")!="REVOKED")OutlinedButton(onClick={work{
                val pending=withContext(Dispatchers.IO){store().outbound(p.getString("id"))};if(pending.length()==0){notice="没有待确认的发送命令"}else{
                    val cmd=pending.getJSONObject(0);if(cmd.optString("state")=="expired"){withContext(Dispatchers.IO){store().clearOutbound(p.getString("id"),cmd.getString("id"))};notice="命令已过期，不会补发"}else{val result=rpc(p,cmd.getString("id"),cmd.getJSONObject("request"));notice="原命令状态：${result.optString("state")}";if(result.optString("state") in listOf("submitted","failed","expired"))withContext(Dispatchers.IO){store().clearOutbound(p.getString("id"),cmd.getString("id"))}}
                }
            }}){Text("检查 / 重试待确认命令")}
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                if(p.optString("localRole")=="CONTROLLER"&&p.optString("pairState")!="REVOKED"){
                    Button(onClick={select(p)}){Text("管理短信")}
                    OutlinedButton(onClick={selected=p;work{remoteState=rpc(p,UUID.randomUUID().toString(),JSONObject().put("action","device.state")).optJSONObject("device");notice="设备状态已刷新"}}){Text("设备状态")}
                }
                if(p.optString("pairState")!="REVOKED")TextButton(onClick={work{withContext(Dispatchers.IO){store().revoke(p.getString("id"));PeerInboxCache.clear(applicationContext,p.getString("id"))};if(selected?.optString("id")==p.optString("id")){inbox.clear();selected=null};PeerCloudConnector.closePeer(p.getString("id"));notice="本机已立即撤销此授权；远端旧凭据无法继续访问"}}){Text("撤销授权")}
            }
        }}
    }}
    @Composable private fun ConnectionScreen(){LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{HubCard(Modifier.fillMaxWidth()){
            Text("自动公网 P2P",style=MaterialTheme.typography.titleMedium)
            Text("双机扫码后自动交换加密连接信息，并通过 STUN 尝试公网直连。无需填写地址、部署服务器或开放端口。")
            Text("默认使用 PeerJS Cloud 公共信令和 Google / Cloudflare STUN；服务可见 IP 与连接元数据，无法解密短信。未配置 Relay 时不使用 TURN、信箱或数据转发，直连失败即失败。")
            Text("Relay 独立配置；同时配置时优先 Relay，失败后尝试已授权 P2P。")
            peers.filter{it.optString("pairState")!="REVOKED"}.forEach{p->
                Text("${p.optString("name")} · ${p.optString("discoveryState","WAITING")}")
                if(!p.optBoolean("publicDiscoveryEnabled"))OutlinedButton(onClick={work{withContext(Dispatchers.IO){val fresh=store().peer(p.getString("id"));fresh.put("publicDiscoveryEnabled",true);store().update(fresh)};PeerService.start(this@PeerActivity);notice="已允许此配对使用上述公共信令发现；两端均需启用"}}){Text("允许此旧配对使用公网发现")}
            }
            OutlinedButton(onClick={startActivity(Intent(this@PeerActivity,ControllerActivity::class.java))}){Text("配置 Relay（可选）")}
        }}
        item{Text("测试版 ${BuildConfig.VERSION_NAME}\n包名 ${packageName}\n公网直连受 NAT、防火墙、Android 权限及后台状态影响。",style=MaterialTheme.typography.bodySmall)}
    }}
    private fun routeLabel(route:String)=when(route){"LAN"->"局域网直连";"DIRECT"->"公网直连";"TURN"->"加密 TURN 中继";"MAILBOX"->"加密信箱";else->"离线"}
}

@Composable fun PeerEntryCard(){val context=androidx.compose.ui.platform.LocalContext.current;HubCard(Modifier.fillMaxWidth()){
    Text("P2P / Relay 配对",style=MaterialTheme.typography.titleMedium);Text("双机扫码直连，或继续使用原有服务器注册。两套授权与密钥独立。",style=MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick={context.startActivity(Intent(context,PeerActivity::class.java))}){Text("打开 P2P 设备直连")}
}}
