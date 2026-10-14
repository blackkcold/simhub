package com.blackkcold.simhub

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
    private var pairingRole by mutableIntStateOf(-1) // -1 = closed; 0 = SIM node; 1 = controller
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
        setContent{HubTheme(if(pairingRole==0||selected?.optString("localRole")=="NODE")"node" else "controller"){Surface(Modifier.fillMaxSize()){Screen()}}}
        lifecycleScope.launch{repeatOnLifecycle(Lifecycle.State.STARTED){
            discovery=PeerDiscovery(this@PeerActivity)
            try{discovery?.start()}catch(_:Exception){notice="局域网发现不可用；请检查本地网络权限"}
            try{while(isActive){delay(12000);reload();if(selected!=null&&tab==1&&!busy&&!historyMode)load(false)}}finally{discovery?.close();discovery=null}
        }}
    }
    override fun onStop(){super.onStop();ControllerAccessGate.clearAll();inbox.clear();remoteState=null}
    private fun reload(){peers=(0 until store().list().length()).map{store().list().getJSONObject(it)};listening=PeerService.enabled();selected?.let{p->selected=peers.find{it.optString("id")==p.optString("id")}}}
    private fun work(task:suspend ()->Unit){if(busy)return;busy=true;notice="";lifecycleScope.launch{try{task()}catch(e:Exception){notice=when(e){is SecurityException->"安全或权限校验未通过：${e.message?.take(80)}";else->when {
                    e.message?.contains("peer_ice_not_connected")==true->"无法建立数据直连。请保持两台设备联网、启动前台连接服务；受限 NAT 无法保证穿透。"
                    e.message?.contains("public_peer_unavailable")==true->"公网发现尚未完成。请确认双机扫码已完成，等待信令连接后重试。"
                    e.message?.contains("relay_peer_response_pending")==true->"Relay 已接收请求，但设备尚未确认处理。请查询原任务状态。"
                    else->"设备暂时不可达。请检查两端网络与前台服务；无 Relay 授权时不会隐藏转发短信。"
                }}}finally{busy=false;reload()}}}
    private fun requestAccess(){if(!SmsAccessPolicy.rolePermitsAccess(this)){getSystemService(RoleManager::class.java)?.let{role.launch(it.createRequestRoleIntent(RoleManager.ROLE_SMS))};return};val list=mutableListOf(Manifest.permission.READ_SMS,Manifest.permission.RECEIVE_SMS,Manifest.permission.READ_PHONE_STATE);if(Build.VERSION.SDK_INT>=33)list.add(Manifest.permission.POST_NOTIFICATIONS);if(Build.VERSION.SDK_INT>=37)list.add("android.permission.ACCESS_LOCAL_NETWORK");if(SmsSendPolicy.canSend(this)||getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS)==true)list.add(Manifest.permission.SEND_SMS);permissions.launch(list.toTypedArray())}
    private fun startNode(){PeerService.start(this);lifecycleScope.launch{delay(800);reload();notice=PeerService.error()}}
    private fun scan(){IntentIntegrator(this).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE).setPrompt("扫描另一台手机的 P2P 二维码").setBeepEnabled(false).setOrientationLocked(false).initiateScan()}
    @Deprecated("ZXing compatibility") override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){super.onActivityResult(requestCode,resultCode,data);val result:IntentResult?=IntentIntegrator.parseActivityResult(requestCode,resultCode,data);val contents=result?.contents?:return
        work{val resultQr=withContext(Dispatchers.IO){val manager=PeerPairingManager(applicationContext);when(PeerPairingManager.kind(contents)){"offer"->manager.respond(contents,Build.MODEL);"response"->{manager.accept(contents);""};else->throw SecurityException("peer_qr_kind_rejected")}};qr=resultQr;PeerService.start(this@PeerActivity);notice=if(resultQr.isBlank())"双向扫码已确认，管理手机现在可以连接" else "请让 SIM 节点扫描此响应二维码，公网连接会自动建立"}
    }
    private suspend fun rpc(p:JSONObject,id:String,request:JSONObject):JSONObject{
        return withContext(Dispatchers.IO){PeerHubGateway.request(applicationContext,p.getString("id"),id,request)}
    }
    private suspend fun load(older:Boolean){val p=selected?:return;val req=JSONObject().put("action","sms.page").put("limit",100);if(older&&cursor!=null)req.put("cursor",cursor)
        val page=rpc(p,UUID.randomUUID().toString(),req);if(!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))return;if(page.optString("state")!="succeeded"){notice="短信读取未完成：${page.optString("reason")}";return};if(inbox.accept(page.toString(),older,older)){cursor=page.optJSONObject("cursor");historyMode=older}
        if(!older){
            val stateResult=rpc(p,UUID.randomUUID().toString(),JSONObject().put("action","device.state"))
            remoteState=stateResult.optJSONObject("device")
            stateResult.optJSONObject("relayHint")?.let{hint->withContext(Dispatchers.IO){val fresh=store().peer(p.getString("id"));fresh.put("relayHint",hint);store().update(fresh)}}
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
        work{val result=withContext(Dispatchers.IO){PeerHubGateway.send(applicationContext,p.getString("id"),sendId!!,sendRequest!!)};notice="短信状态：${result.optString("state")}";if(result.optString("state") in listOf("submitted","sent","delivered","succeeded")){compose=false;load(false)}else if(result.optString("state") in listOf("indeterminate","claimed","unknown"))notice="发送结果待确认。已保留原命令，不会产生新的发送请求。请在设备卡片查看状态。"}
    }
    @Composable private fun Screen(){
        BackHandler(enabled=tab!=0){tab=0}
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)){
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
                TextButton(onClick={if(tab==0)finish() else tab=0}){Text(if(tab==0)"返回 SIM Hub" else "返回设备")}
                Text(when(tab){1->"加密短信";2->"连接与授权";else->"设备直连"},style=MaterialTheme.typography.titleLarge)
            }
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            if(notice.isNotBlank())Text(notice,Modifier.fillMaxWidth().padding(12.dp),color=MaterialTheme.colorScheme.primary)
            when(tab){
                0->DeviceScreen()
                1->{val p=selected;if(p==null){Text("请先双机扫码，然后选择管理设备",Modifier.padding(24.dp))}else{
                    Text("${p.optString("name")} · ${connectionLabel(p)} · 端到端加密",Modifier.padding(horizontal=16.dp,vertical=8.dp),style=MaterialTheme.typography.labelMedium)
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
            Text("添加设备",style=MaterialTheme.typography.titleMedium)
            Text("选择本机角色后按步骤完成双向扫码。P2P 不需要服务器或 Vault；连接可用性取决于实际网络。",style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Button(onClick={pairingRole=0;qr=""}){Text("作为 SIM 节点")}
                OutlinedButton(onClick={pairingRole=1;qr=""}){Text("作为管理终端")}
            }
            if(pairingRole==0){
                Text("步骤 1 / 3 · 开启本机服务，选择授权后生成二维码",style=MaterialTheme.typography.titleSmall)
                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Switch(listening,{if(it)startNode() else{PeerService.stop(this@PeerActivity);listening=false}});Text("允许已配对设备连接")}
                Text("持续监听会显示前台通知；关闭后此 SIM 节点无法通过 P2P 接收请求。",style=MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick={requestAccess()}){Text("检查短信读取权限")}
                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Checkbox(allowSend,{allowSend=it});Text("允许本次配对发送短信（默认关闭）")}
                Button(onClick={work{if(!PeerService.enabled())throw IllegalStateException("start_node_listener_first");qr=withContext(Dispatchers.IO){PeerPairingManager(applicationContext).offer(Build.MODEL,allowSend,PeerService.endpoints())};notice="步骤 2 / 3 · 请使用管理手机扫描二维码，然后使用本机扫描其响应二维码"}},enabled=listening){Text("生成配对二维码")}
                if(!SmsSendPolicy.canSend(this@PeerActivity))Text("发送短信需要本机系统短信角色及相应权限；仅授权读取时无需切换默认短信应用。",style=MaterialTheme.typography.bodySmall)
            }else if(pairingRole==1){
                Text("步骤 2 / 3 · 扫描节点二维码，再让节点扫描本机响应二维码",style=MaterialTheme.typography.titleSmall)
                Button(onClick={scan()}){Text("扫描节点二维码")}
                Text("两台设备完成身份确认后会自动尝试公网数据直连。",style=MaterialTheme.typography.bodySmall)
            }
        }}
        if(qr.isNotBlank())item{HubCard(Modifier.fillMaxWidth()){
            val bitmap=remember(qr){try{BarcodeEncoder().encodeBitmap(qr,BarcodeFormat.QR_CODE,720,720).asImageBitmap()}catch(_:Exception){null}};Text("仅让你要配对的手机扫描",style=MaterialTheme.typography.titleMedium)
            bitmap?.let{Image(it,"P2P pairing QR",Modifier.fillMaxWidth().heightIn(max=340.dp))};Text("${if(qr.isNotBlank())"五分钟内完成双向扫码；二维码不包含短信或 Relay 密钥" else ""}",style=MaterialTheme.typography.bodySmall);TextButton(onClick={qr=""}){Text("隐藏二维码")}
        }}
        items(peers,key={it.optString("id")}){p->HubCard(Modifier.fillMaxWidth()){
            Text(p.optString("name"),style=MaterialTheme.typography.titleMedium)
            Text("${if(p.optString("localRole")=="NODE")"已授权管理终端" else "SIM 节点"} · ${pairLabel(p)}",style=MaterialTheme.typography.bodySmall)
            Text(connectionLabel(p),color=if(PeerCloudConnector.dataChannelOnline(p.optString("id")))MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if(p.optBoolean("relayConfigured"))"授权：P2P + Relay · Relay 优先" else "授权：仅 P2P · 未配置备用路径",style=MaterialTheme.typography.bodySmall)
            if(p.optString("pairState")=="PENDING")Text("等待节点扫描响应二维码；完成双向确认后才能读取短信。",style=MaterialTheme.typography.bodySmall)
            if(selected?.optString("id")==p.optString("id"))remoteState?.let{state->
                Text("电量 ${state.opt("batteryPct")?:"—"}% · ${if(state.optBoolean("charging"))"充电中" else "电池供电"} · ${state.optString("network","未知网络")}")
                Text("Android ${state.optString("androidVersion")} · ${state.optString("appVersion")} · ${if(state.optBoolean("smsSendAllowed"))"系统允许发送" else "当前仅允许读取"}")
            }
            // Full identity fingerprints and ICE diagnostics are available only in device details.
            if(p.optString("localRole")=="CONTROLLER"&&p.optString("pairState")!="REVOKED")OutlinedButton(onClick={work{
                val pending=withContext(Dispatchers.IO){store().outbound(p.getString("id"))};if(pending.length()==0){notice="没有待确认的发送命令"}else{
                    val cmd=pending.getJSONObject(0);if(cmd.optString("state")=="expired"){val result=rpc(p,UUID.randomUUID().toString(),JSONObject().put("action","command.status").put("commandId",cmd.getString("id")));val state=result.optString("state");notice="过期命令状态：$state；不会重新发送";if(state in listOf("submitted","sent","delivered","succeeded","failed","expired"))withContext(Dispatchers.IO){store().clearOutbound(p.getString("id"),cmd.getString("id"))}}else{val result=withContext(Dispatchers.IO){PeerHubGateway.send(applicationContext,p.getString("id"),cmd.getString("id"),cmd.getJSONObject("request"))};notice="原命令状态：${result.optString("state")}"}
                }
            }}){Text("检查 / 重试待确认命令")}
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                if(p.optString("localRole")=="CONTROLLER"&&p.optString("pairState")!="REVOKED"){
                    Button(onClick={select(p)}){Text(if(p.optString("pairState")=="PENDING")"验证连接" else "管理短信")}
                    OutlinedButton(onClick={selected=p;tab=2}){Text("连接详情")}
                }
                if(p.optString("pairState")!="REVOKED")TextButton(onClick={work{withContext(Dispatchers.IO){store().revoke(p.getString("id"));PeerInboxCache.clear(applicationContext,p.getString("id"))};if(selected?.optString("id")==p.optString("id")){inbox.clear();selected=null};PeerCloudConnector.closePeer(p.getString("id"));notice="本机已立即撤销此授权；远端旧凭据无法继续访问"}}){Text("撤销授权")}
            }
        }}
    }}
    private fun unlockRelay(p:JSONObject){
        val origin=p.optString("relayOrigin",ControllerProfiles.selected(this)?:"")
        if(ControllerLinks.normalize(origin)==null)return
        android.hardware.biometrics.BiometricPrompt.Builder(this).setTitle("验证本机身份以使用 Relay")
            .setDeviceCredentialAllowed(true).build().authenticate(android.os.CancellationSignal(),mainExecutor,
                object:android.hardware.biometrics.BiometricPrompt.AuthenticationCallback(){
                    override fun onAuthenticationSucceeded(result:android.hardware.biometrics.BiometricPrompt.AuthenticationResult){
                        if(!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))return
                        ControllerAccessGate.grant(origin);notice="Relay 本机身份验证已通过";reload()
                    }
                })
    }
    @Composable private fun ConnectionScreen(){LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{HubCard(Modifier.fillMaxWidth()){
            Text("自动公网 P2P",style=MaterialTheme.typography.titleMedium)
            Text("双机扫码后自动交换加密连接信息，并通过 STUN 尝试公网直连。无需填写地址、部署服务器或开放端口。")
            Text("默认使用 PeerJS Cloud 公共信令和 Google / Cloudflare STUN；服务可见 IP 与连接元数据，无法解密短信。未配置 Relay 时不使用 TURN、信箱或数据转发，直连失败即失败。")
            Text("Relay 独立配置；同时配置时优先 Relay，失败后尝试已授权 P2P。")
            (selected?.let{listOf(it)}?:emptyList()).filter{it.optString("pairState")!="REVOKED"}.forEach{p->
                Text("${p.optString("name")} · ${connectionLabel(p)}")
                Text("P2P 授权：${pairLabel(p)} · 读取${if(p.optString("permissions").split(",").contains("send"))"、发送" else "（只读）"}",style=MaterialTheme.typography.bodySmall)
                Text("发现服务：${p.optString("discoveryState","WAITING")} · 上次成功传输：${routeLabel(p.optString("activeTransport"))}",style=MaterialTheme.typography.bodySmall)
                Text("设备身份：${p.optString("id").take(16)}…",style=MaterialTheme.typography.labelSmall)
                if(p.optBoolean("publicDiscoveryEnabled")){
                    OutlinedButton(onClick={work{withContext(Dispatchers.IO){PeerCloudConnector.ensure(applicationContext,p.getString("id"));PeerCloudConnector.reconnect(p.getString("id"))};notice="已重新尝试发现与 P2P 直连；仅信令就绪不等于可读取短信"}}){Text("重新尝试直连")}
                }
                if(p.optString("localRole")=="CONTROLLER"){
                    val relayOrigin=p.optString("relayOrigin",ControllerProfiles.selected(this@PeerActivity)?:"")
                    if(ControllerLinks.normalize(relayOrigin)!=null&&ControllerSecureClient(this@PeerActivity,relayOrigin).requireLocalUnlock()&&!ControllerAccessGate.permits(relayOrigin))OutlinedButton(onClick={unlockRelay(p)}){Text("验证本机身份以使用 Relay")}
                    if(p.optBoolean("relayConfigured"))OutlinedButton(onClick={work{withContext(Dispatchers.IO){val fresh=store().peer(p.getString("id"));fresh.put("relayConfigured",false).remove("relayOrigin");fresh.remove("relayDeviceId");fresh.put("relayAssociationDisabled",true).put("relayBindingState","NONE").put("policy","P2P_ONLY");store().update(fresh)};notice="已取消此节点 Relay 关联，仅使用 P2P"}}){Text("取消此节点 Relay 关联")}
                    else OutlinedButton(onClick={work{withContext(Dispatchers.IO){val fresh=store().peer(p.getString("id"));fresh.put("relayAssociationDisabled",false);store().update(fresh);if(!PeerRelayTransport.associate(applicationContext,fresh))throw IllegalStateException("尚未在两端独立授权 Relay，或 Relay 设备身份未匹配。可重新配对获取节点标识")};notice="已验证同一节点身份；Relay 优先，P2P 备用"}}){Text("关联此节点已授权的 Relay")}
                }
                if(!p.optBoolean("publicDiscoveryEnabled"))OutlinedButton(onClick={work{withContext(Dispatchers.IO){val fresh=store().peer(p.getString("id"));fresh.put("publicDiscoveryEnabled",true);store().update(fresh)};PeerService.start(this@PeerActivity);notice="已允许此配对使用上述公共信令发现；两端均需启用"}}){Text("允许此旧配对使用公网发现")}
            }
            OutlinedButton(onClick={startActivity(Intent(this@PeerActivity,ControllerActivity::class.java))}){Text("配置 Relay（可选）")}
        }}
        item{Text("测试版 ${BuildConfig.VERSION_NAME}\n包名 ${packageName}\n公网直连受 NAT、防火墙、Android 权限及后台状态影响。",style=MaterialTheme.typography.bodySmall)}
    }}
    private fun pairLabel(p:JSONObject)=when(p.optString("pairState")){"PENDING"->"待双向确认";"ACTIVE"->"已配对";"REVOKED"->"已撤销";else->"待配对"}
    private fun connectionLabel(p:JSONObject):String {
        if(PeerCloudConnector.dataChannelOnline(p.optString("id")))return "设备 P2P 直连已建立"
        val last=p.optLong("lastSyncAt",0)
        val recentlyUsed=last>0&&System.currentTimeMillis()/1000-last<30
        if(recentlyUsed)return "最近通过${routeLabel(p.optString("activeTransport"))}通信"
        return when(p.optString("discoveryState")){
            "READY"->"公网发现就绪 · 尚未确认数据连接"
            "CONNECTING"->"正在寻找设备"
            "UNAVAILABLE"->"公网发现服务不可用"
            else->"未连接 · 等待设备上线"
        }
    }
    private fun routeLabel(route:String)=when(route){"LAN"->"局域网直连";"DIRECT"->"P2P 直连";"TURN"->"加密 TURN 中继";"MAILBOX"->"加密信箱";"LEGACY_RELAY"->"Relay";else->"上次路径未知"}
}

@Composable fun PeerEntryCard(){val context=androidx.compose.ui.platform.LocalContext.current;HubCard(Modifier.fillMaxWidth()){
    Text("P2P / Relay 配对",style=MaterialTheme.typography.titleMedium);Text("双机扫码直连，或继续使用原有服务器注册。两套授权与密钥独立。",style=MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick={context.startActivity(Intent(context,PeerActivity::class.java))}){Text("打开 P2P 设备直连")}
}}
