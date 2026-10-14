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
    private var connectUrl by mutableStateOf("")
    private var useFallback by mutableStateOf(false)
    private var discovery:PeerDiscovery?=null
    private var connector:PeerInternetConnector?=null
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
    override fun onDestroy(){if(!PeerService.enabled())connector?.close();super.onDestroy()}
    private fun reload(){peers=(0 until store().list().length()).map{store().list().getJSONObject(it)};listening=PeerService.enabled();selected?.let{p->selected=peers.find{it.optString("id")==p.optString("id")}}}
    private fun work(task:suspend ()->Unit){if(busy)return;busy=true;notice="";lifecycleScope.launch{try{task()}catch(e:Exception){notice=when(e){is SecurityException->"安全或权限校验未通过：${e.message?.take(80)}";else->"设备暂时不可达。请确认节点已开启直连、网络权限及连接服务。"}}finally{busy=false;reload()}}}
    private fun requestAccess(){if(!SmsAccessPolicy.rolePermitsAccess(this)){getSystemService(RoleManager::class.java)?.let{role.launch(it.createRequestRoleIntent(RoleManager.ROLE_SMS))};return};val list=mutableListOf(Manifest.permission.READ_SMS,Manifest.permission.RECEIVE_SMS,Manifest.permission.READ_PHONE_STATE);if(Build.VERSION.SDK_INT>=33)list.add(Manifest.permission.POST_NOTIFICATIONS);if(Build.VERSION.SDK_INT>=37)list.add("android.permission.ACCESS_LOCAL_NETWORK");if(SmsSendPolicy.canSend(this)||getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS)==true)list.add(Manifest.permission.SEND_SMS);permissions.launch(list.toTypedArray())}
    private fun startNode(){if(Build.VERSION.SDK_INT>=37&&checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")!=PackageManager.PERMISSION_GRANTED){permissions.launch(arrayOf("android.permission.ACCESS_LOCAL_NETWORK"));notice="授权后请再次开启直连";return};PeerService.start(this);lifecycleScope.launch{delay(800);reload();notice=PeerService.error()}}
    private fun scan(){IntentIntegrator(this).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE).setPrompt("扫描另一台手机的 P2P 二维码").setBeepEnabled(false).setOrientationLocked(false).initiateScan()}
    @Deprecated("ZXing compatibility") override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);val result:IntentResult?=IntentIntegrator.parseActivityResult(requestCode,resultCode,data);val contents=result?.contents?:return
        work{val resultQr=withContext(Dispatchers.IO){val manager=PeerPairingManager(applicationContext);when(PeerPairingManager.kind(contents)){"offer"->manager.respond(contents,Build.MODEL);"response"->{manager.accept(contents);""};else->throw SecurityException("peer_qr_kind_rejected")}};qr=resultQr;notice=if(resultQr.isBlank())"双向扫码已确认，管理手机现在可以连接" else "请让 SIM 节点扫描此响应二维码，随后在管理手机刷新设备"}
    }
    private suspend fun rpc(p:JSONObject,id:String,request:JSONObject):JSONObject{
        return withContext(Dispatchers.IO){val coordinator=ConnectionCoordinator(store());(connector?:PeerInternetConnector.active(p.optString("id")))?.transport(p.optString("id"))?.let{coordinator.internet(it)};(connector?:PeerInternetConnector.active(p.optString("id")))?.mailbox(p.optString("id"))?.let{coordinator.mailbox(it)};coordinator.request(p.getString("id"),id,request)}
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
            Text("连接方式独立于设备角色",style=MaterialTheme.typography.titleMedium);Text("P2P 使用两台手机双向扫码。现有 Relay 继续在原设置页使用服务器扫码或配对码。")
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={requestAccess()}){Text("节点短信权限")};OutlinedButton(onClick={scan()}){Text("扫描 P2P 二维码")}}
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Switch(listening,{if(it)startNode() else{PeerService.stop(this@PeerActivity);listening=false}});Text("开启本机直连服务")}
            Text("只有 SIM 节点需要开启。持续监听会显示通知，关闭后远程不可达。",style=MaterialTheme.typography.bodySmall)
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
            Text("注册模式：${p.optString("policy")}\n当前链路：${routeLabel(p.optString("activeTransport"))}\n中继绑定：${p.optString("relayBindingState")}")
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
                if(p.optString("pairState")!="REVOKED")TextButton(onClick={work{withContext(Dispatchers.IO){store().revoke(p.getString("id"));PeerInboxCache.clear(applicationContext,p.getString("id"))};if(selected?.optString("id")==p.optString("id")){inbox.clear();selected=null};connector?.close();connector=null;notice="本机已立即撤销此授权；远端旧凭据无法继续访问"}}){Text("撤销授权")}
            }
        }}
    }}
    @Composable private fun ConnectionScreen(){LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{HubCard(Modifier.fillMaxWidth()){
            Text("局域网 / 公网直连",style=MaterialTheme.typography.titleMedium);Text("双机扫码无需服务器。同 Wi-Fi 或同一手机热点可直接连接；可达公网地址也可直接连接。公网 NAT 穿透需要连接服务。")
            Text("纯 P2P：失败即离线。中继备用：仅在明确授权并绑定连接服务后启用。实际路径始终显示。")
            Text("选择要绑定的已配对设备",style=MaterialTheme.typography.labelLarge)
            peers.filter{it.optString("pairState")!="REVOKED"}.forEach{p->FilterChip(selected=selected?.optString("id")==p.optString("id"),onClick={selected=p;connectUrl=p.optString("connectionService");useFallback=p.optBoolean("fallbackConsent")},label={Text("${p.optString("name")} · ${if(p.optString("localRole")=="NODE")"管理手机" else "SIM 节点"}")})}
            OutlinedTextField(connectUrl,{connectUrl=it},label={Text("可选连接服务 HTTPS 地址")},modifier=Modifier.fillMaxWidth())
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Switch(useFallback,{useFallback=it});Text("授权 TURN 与短期加密信箱回退")}
            Button(onClick={work{val peer=selected?:throw IllegalStateException("select_peer_first");connector?.close();connector=PeerInternetConnector(this@PeerActivity,peer.getString("id"));withContext(Dispatchers.IO){connector!!.connect(connectUrl,useFallback)};notice="连接服务已绑定；仅交换密文和必要连接元数据"}},enabled=connectUrl.startsWith("https://")){Text("绑定所选设备并连接")}
            OutlinedButton(onClick={work{selected?.let{p->val fresh=store().peer(p.getString("id"));fresh.put("policy","P2P_ONLY").put("fallbackConsent",false).put("relayBindingState","SUSPENDED").put("connectionEnabled",false);store().update(fresh)};connector?.close();connector=null;notice="已停用此设备的网络服务与中继回退"}}){Text("恢复纯直连")}
            Text("未配置公共服务地址。不会静默连接未知公共服务器。连接服务与现有 Relay Enrollment / Global Vault 完全分离。",style=MaterialTheme.typography.bodySmall)
        }}
        item{Text("测试版 ${BuildConfig.VERSION_NAME}\n包名 ${packageName}\n真实短信与后台可达性仍受 Android 权限、ROM、电量及网络影响。",style=MaterialTheme.typography.bodySmall)}
    }}
    private fun routeLabel(route:String)=when(route){"LAN"->"局域网直连";"DIRECT"->"公网直连";"TURN"->"加密 TURN 中继";"MAILBOX"->"加密信箱";else->"离线"}
}

@Composable fun PeerEntryCard(){val context=androidx.compose.ui.platform.LocalContext.current;HubCard(Modifier.fillMaxWidth()){
    Text("P2P / Relay 配对",style=MaterialTheme.typography.titleMedium);Text("双机扫码直连，或继续使用原有服务器注册。两套授权与密钥独立。",style=MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick={context.startActivity(Intent(context,PeerActivity::class.java))}){Text("打开 P2P 设备直连")}
}}
