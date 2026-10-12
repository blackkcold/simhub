package com.blackkcold.simhub

import android.net.Uri
import android.app.Activity
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.view.WindowManager
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal object ControllerCommandProgress {
    fun describe(cmd:JSONObject):String = when(val state=cmd.optString("state","queued")){
        "queued"->"排队中：等待设备唤醒领取（节能模式可能需要数分钟）"
        "dispatched"->"设备已领取，等待最终执行 ACK"
        "submitted"->"短信已提交系统，尚未确认发送"
        "sent"->"系统已发送，等待运营商回执"
        "delivered"->"运营商已确认送达"
        "succeeded"->"任务执行成功"
        "failed"->"任务失败："+cmd.optJSONObject("result")?.optString("reason").orEmpty().take(160)
        "rejected"->"设备拒绝任务"
        "expired"->"任务已过期，未被确认为执行成功"
        else->"任务状态：$state"
    }
}

private data class ControllerLoadBatch(
    val payload:String,val sequence:Long,val nextTime:Long,val nextSeq:Long,
    val unreadable:Int,val finalFragment:Boolean,val appendFragment:Boolean
)
private data class ControllerLoadResult(
    val devices:List<JSONObject>,val totpRequired:Boolean,
    val batches:List<ControllerLoadBatch>,val highWater:Long
)
/** Stable conversation identity and conservative phone normalization. */
internal object ControllerConversation {
    fun key(device:String,channel:String,revision:Int,address:String):String {
        val digits=address.filter{it.isDigit()}
        val number=when {
            digits.length==15 && digits.startsWith("0086")->digits.drop(4)
            digits.length==13 && digits.startsWith("86")->digits.drop(2)
            digits.length==11 && digits.startsWith("1")->digits
            address.trim().startsWith("+")->"+$digits"
            digits.isNotBlank()->digits
            else->address.trim().lowercase()
        }
        return "$device|$channel|$revision|$number"
    }
}

/** Single Jetpack Compose surface: no WebView, nested browser shell or duplicate navigation. */
@Composable
fun ControllerWorkspace(visible:Boolean,revision:Int,onScan:()->Unit,
                        onSwitchNode:()->Unit,tab:Int,onSelectTab:(Int)->Unit,
                        refreshTick:Int=0,modifier:Modifier=Modifier){
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var profiles by remember(revision){mutableStateOf(ControllerProfiles.list(context))}
    var selected by remember(revision){mutableStateOf(ControllerProfiles.selected(context))}
    val client=remember(selected){selected?.let{ControllerSecureClient(context.applicationContext,it)}}
    val inbox=remember(selected){ControllerInboxState()}
    var add by remember{mutableStateOf(false)}
    var addUrl by remember{mutableStateOf("")}
    var pairing by remember(selected){mutableStateOf(client?.pendingPairing())}
    var error by remember(selected){mutableStateOf("")}
    var busy by remember(selected){mutableStateOf(false)}
    var authorized by remember(selected){mutableStateOf(client?.hasGrant()==true)}
    var devices by remember(selected){mutableStateOf<List<JSONObject>>(emptyList())}
    var page by remember(selected){mutableIntStateOf(0)}
    var cursors by remember(selected){mutableStateOf(listOf<Pair<Long,Long>>())}
    var unreadable by remember(selected){mutableIntStateOf(0)}
    var lastEventSeq by remember(selected){mutableLongStateOf(0L)}
    var showWrite by remember{mutableStateOf(false)}
    var composeRecipient by remember(selected){mutableStateOf("")}
    var composeMessage by remember(selected){mutableStateOf("")}
    var composeSubmittedDraft by remember(selected){mutableStateOf("")}
    var composeCommandId by remember(selected){mutableStateOf(UUID.randomUUID().toString())}
    var composeDeviceIdx by remember(selected){mutableIntStateOf(0)}
    var composeChannelIdx by remember(selected){mutableIntStateOf(0)}
    var showReset by remember{mutableStateOf(false)}
    var resetCredential by remember{mutableStateOf("")}
    var selectedDevice by remember{mutableStateOf<JSONObject?>(null)}
    var actionError by remember{mutableStateOf("")}
    var serverVersion by remember{mutableStateOf("—")}
    var secondFactorTotp by remember(selected){mutableStateOf(true)}
    var requireLocalUnlock by remember(selected){mutableStateOf(client?.requireLocalUnlock()==true)}
    var localUnlocked by remember(selected){mutableStateOf(!requireLocalUnlock)}
    var localAuthError by remember(selected){mutableStateOf("")}
    var lastCommandId by remember(selected){mutableStateOf("")}
    var lastCommandState by remember(selected){mutableStateOf("")}
    var lastCommandDevice by remember(selected){mutableStateOf("")}
    var commandHistory by remember(selected){mutableStateOf<List<JSONObject>>(emptyList())}
    var commandHistoryError by remember(selected){mutableStateOf("")}
    var expandCommands by remember(selected){mutableStateOf(false)}
    var commandTick by remember(selected){mutableIntStateOf(0)}
    // Controller-only screen capture protection, including Android Recents.
    DisposableEffect(context,visible){
        val window=(context as? Activity)?.window
        if(visible)window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose{if(visible)window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)}
    }

    // Optional OS-level app gate. This is not a cryptographically authentication-bound
    // Keystore key; such a migration requires a separate rewrap and recovery protocol.
    fun authenticateLocal(enable:Boolean=false){
        val activity=context as? Activity
        if(activity==null){localAuthError="当前设备无法打开系统身份验证";return}
        try{
            val builder=BiometricPrompt.Builder(activity)
                .setTitle("SIM Hub 管理中心")
                .setSubtitle("使用指纹、人脸或系统锁屏验证")
            if(Build.VERSION.SDK_INT>=30)
                builder.setAllowedAuthenticators(
                    android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            else builder.setDeviceCredentialAllowed(true)
            builder.build().authenticate(CancellationSignal(),activity.mainExecutor,
                object:BiometricPrompt.AuthenticationCallback(){
                    override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult){
                        localAuthError=""
                        if(enable){client?.setRequireLocalUnlock(true);requireLocalUnlock=true}
                        localUnlocked=true
                    }
                    override fun onAuthenticationError(errorCode:Int,errString:CharSequence){
                        localAuthError=errString.toString()
                    }
                })
        }catch(e:Exception){localAuthError=e.message?:"系统身份验证不可用"}
    }
    val activity=context as? androidx.activity.ComponentActivity
    DisposableEffect(activity,selected,requireLocalUnlock){
        val observer=LifecycleEventObserver{_,event->
            if(event==Lifecycle.Event.ON_STOP && requireLocalUnlock){
                localUnlocked=false;inbox.clear();lastEventSeq=0L
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose{activity?.lifecycle?.removeObserver(observer)}
    }
    fun chooseOrigin(value:String){
        ControllerProfiles.select(context,value)
        selected=value;onSelectTab(0)
    }
    // Exactly one load at a time. A second user request is queued, never discarded.
    var pendingLoad by remember(selected){mutableStateOf("")}
    fun load(mode:String="delta"){
        val api=client?:return
        if(!api.hasGrant() || (requireLocalUnlock && !localUnlocked))return
        if(mode=="delta" && page>0)return // Historical browsing is a stable window.
        if(busy){pendingLoad=mode;return}
        busy=true
        scope.launch {
            try {
                val data=withContext(Dispatchers.IO){
                    val d=api.api("/api/v1/devices")
                    val auth=api.api("/api/v1/auth/check")
                    val all=d.optJSONArray("devices")?:JSONArray()
                    val nodes=(0 until all.length()).mapNotNull{all.optJSONObject(it)}
                    val byId=nodes.associateBy{it.optString("id")}
                    val delta=mode=="delta" && lastEventSeq>0
                    // Read the sequence watermark *before* the latest-time page.
                    // Concurrent uploads can then safely be replayed as delta.
                    val highWater=if(mode=="latest" || (mode=="delta" && !delta))
                        api.api("/api/v1/events?latest=1&limit=1").optLong("nextSince",0L)
                        else 0L
                    val requested=if(mode=="older")page+1 else 0
                    val cursor=if(requested>0)cursors.getOrNull(requested-1) else null
                    if(mode=="older" && cursor==null)
                        throw IllegalStateException("历史游标已失效，请返回最新短信重试")
                    val suffix=if(cursor!=null)
                        "&beforeTime=${cursor.first}&beforeSeq=${cursor.second}" else ""
                    val result=ArrayList<ControllerLoadBatch>()
                    var sequence=lastEventSeq
                    val vault=api.vault()
                    try {
                        // 50 bounded ciphertext events per batch. Up to eight batches
                        // each refresh; remaining events resume from the ACKed cursor.
                        val rounds=if(delta)8 else 1
                        for(batchIndex in 0 until rounds){
                            val events=if(delta)
                                api.api("/api/v1/events?since=$sequence&limit=50")
                            else api.api("/api/v1/events?order=occurred&limit=50"+suffix)
                            val rows=events.optJSONArray("events")?:JSONArray()
                            if(rows.length()>50)throw IllegalStateException("Relay batch exceeds negotiated limit")
                            val messages=JSONArray()
                            var failures=0
                            var highest=sequence
                            for(i in 0 until rows.length()){
                                val item=rows.optJSONObject(i)?:throw IllegalStateException("Malformed event")
                                val eventSeq=item.optLong("seq",0)
                                if(eventSeq<=0)throw IllegalStateException("Invalid event sequence")
                                if(delta && eventSeq<=highest)
                                    throw IllegalStateException("Non-monotonic delta sequence")
                                highest=maxOf(highest,eventSeq)
                                if(!item.optString("kind").startsWith("sms."))continue
                                val node=byId[item.optString("deviceId")]
                                var plaintext=JSONObject()
                                var failed=false
                                try{
                                    if(node==null)throw SecurityException("Unknown node")
                                    plaintext=api.decryptEvent(item,node,vault)
                                }catch(_:Exception){failed=true;failures++}
                                val kind=item.optString("kind")
                                val direction=plaintext.optString("direction")
                                val inbound=kind=="sms.received"||direction=="in"||
                                    (kind=="sms.history"&&direction!="out")
                                val sender=if(inbound)plaintext.optString("sender") else plaintext.optString("recipient")
                                val address=sender.ifBlank{plaintext.optString("sender",plaintext.optString("recipient","未知"))}
                                val ch=plaintext.optString("channelId")
                                val rev=plaintext.optInt("channelRevision")
                                val provenance=if(ch.isEmpty()||rev==0)"历史 SIM · 来源待确认"
                                    else (plaintext.optString("simTag").ifBlank{ch})+" · r"+rev
                                val otp=plaintext.optJSONObject("otp")?.optString("value").orEmpty()
                                // Do not coalesce separate devices/SIM revisions
                                // merely because their phone number is the same.
                                val thread=ControllerConversation.key(item.optString("deviceId"),ch,rev,address)
                                messages.put(JSONObject().put("id",item.optString("deviceId")+":"+item.optString("eventId"))
                                    .put("thread",thread).put("sender",plaintext.optString("contactName").ifBlank{address}.take(100))
                                    .put("body",if(failed)"" else plaintext.optString("body").take(4000))
                                    .put("timestamp",item.optLong("occurredAt")).put("inbound",inbound)
                                    .put("device",node?.optString("name")?.take(100)?:"未知设备")
                                    .put("sim",provenance.take(90))
                                    .put("sourceId",(item.optString("deviceId")+"|"+ch+"|"+rev).take(160))
                                    .put("otp",otp.take(20)).put("failed",failed))
                            }
                            // A single SMS may contain many escapable characters.
                            // Split after JSON encoding, not just by event count.
                            // Only the final fragment may advance the event cursor.
                            val fragments=ArrayList<JSONArray>()
                            var fragment=JSONArray()
                            var encodedSize=256
                            for(j in 0 until messages.length()){
                                val entry=messages.getJSONObject(j)
                                val length=entry.toString().length+1
                                if(fragment.length()>0 && encodedSize+length>400_000){
                                    fragments.add(fragment);fragment=JSONArray();encodedSize=256
                                }
                                fragment.put(entry);encodedSize+=length
                            }
                            fragments.add(fragment)
                            for(j in fragments.indices){
                                val final=j==fragments.lastIndex
                                val payload=JSONObject().put("type","snapshot").put("v",1)
                                    .put("messages",fragments[j])
                                    .put("unreadable",if(final)failures else 0)
                                    .put("hasMore",events.optBoolean("hasMore"))
                                    .put("hasNewer",requested>0).put("incremental",delta)
                                result.add(ControllerLoadBatch(payload.toString(),
                                    if(final)highest else sequence,
                                    events.optLong("nextBeforeTime"),events.optLong("nextBeforeSeq"),
                                    if(final)failures else 0,final,j>0))
                            }
                            sequence=highest
                            if(!delta || rows.length()<50)break
                        }
                    }finally{vault.fill(0)}
                    ControllerLoadResult(nodes,auth.optBoolean("totpRequired",true),result,highWater)
                }
                if(requireLocalUnlock && !localUnlocked)return@launch
                // Main-thread ACK: only successfully accepted decrypted content
                // allows an event cursor or historical-page index to move.
                for(batch in data.batches){
                    if(!inbox.accept(batch.payload,append=mode=="older" || mode=="delta" || batch.appendFragment,
                                     preferOlder=mode=="older"))
                        throw IllegalStateException("短信批次未接收；游标未推进，可安全重试")
                    if(mode=="delta" && batch.finalFragment)
                        lastEventSeq=maxOf(lastEventSeq,batch.sequence,data.highWater)
                    else if(mode=="older" && batch.finalFragment){
                        page+=1
                        if(batch.nextTime>0 && batch.nextSeq>0)
                            cursors=cursors.take(page)+listOf(batch.nextTime to batch.nextSeq)
                    }else if(mode=="latest" && batch.finalFragment){
                        page=0
                        cursors=if(batch.nextTime>0 && batch.nextSeq>0)
                            listOf(batch.nextTime to batch.nextSeq) else emptyList()
                        lastEventSeq=maxOf(lastEventSeq,data.highWater)
                    }
                    if(mode=="latest")unreadable=batch.unreadable
                    else unreadable+=batch.unreadable
                }
                devices=data.devices
                secondFactorTotp=data.totpRequired
                authorized=true;error=""
            }catch(e:Exception){
                error=e.message?:"无法连接管理中心"
                if(e is SecurityException){
                    authorized=false;inbox.clear();devices=emptyList()
                    lastCommandId="";lastCommandState="";lastEventSeq=0
                }
            }finally{busy=false}
        }
    }
    fun refreshDelta(){load("delta")}
    fun loadOlderPage(){load("older")}
    fun navigateToLatest(){load("latest")}
    LaunchedEffect(busy,pendingLoad){
        if(!busy && pendingLoad.isNotBlank()){
            val next=pendingLoad;pendingLoad=""
            load(next)
        }
    }
    LaunchedEffect(selected,visible,localUnlocked,requireLocalUnlock){
        if(visible && (!requireLocalUnlock || localUnlocked) && client?.hasGrant()==true){
            authorized=true;refreshDelta()
        }
    }
    LaunchedEffect(selected,visible,authorized,localUnlocked,requireLocalUnlock){
        if(visible && authorized && (!requireLocalUnlock || localUnlocked))while(true){
            delay(60000)
            if(!busy)refreshDelta()
        }
    }
    LaunchedEffect(pairing,selected,visible){
        if(!visible)return@LaunchedEffect
        val api=client?:return@LaunchedEffect
        while(pairing!=null && !api.hasGrant()){
            delay(2400)
            try{
                if(withContext(Dispatchers.IO){api.pollPairing()}){
                    authorized=true;pairing=null;refreshDelta();break
                }
            }catch(e:Exception){error=e.message?:"配对失败";pairing=null;break}
        }
    }
    LaunchedEffect(refreshTick){if(refreshTick>0 && visible && authorized && (!requireLocalUnlock || localUnlocked))refreshDelta()}
    // Command progress is durable on Relay, not an ephemeral two-minute poll.
    // Continue observing queued/ACKed commands across Eco-mode wakeups and app restarts.
    LaunchedEffect(selected,visible,authorized,localUnlocked,requireLocalUnlock,commandTick){
        val api=client?:return@LaunchedEffect
        if(!visible || !authorized || (requireLocalUnlock && !localUnlocked))return@LaunchedEffect
        while(true){
            if(!busy){
                try{
                    val recent=withContext(Dispatchers.IO){
                        api.api("/api/v1/commands/recent?limit=50").optJSONArray("commands")?:JSONArray()
                    }
                    commandHistory=(0 until recent.length()).mapNotNull{recent.optJSONObject(it)}
                    commandHistoryError=""
                    val current=commandHistory.firstOrNull{it.optString("commandId")==lastCommandId}
                    if(current!=null)
                        lastCommandState=ControllerCommandProgress.describe(current)
                }catch(e:Exception){
                    commandHistoryError=e.message?:"任务状态暂不可用，稍后将继续重试"
                }
            }
            delay(60000)
        }
    }
    if(!visible)return
    if(requireLocalUnlock && !localUnlocked && authorized){
        Box(modifier.fillMaxSize(),contentAlignment=Alignment.Center){
            Column(Modifier.padding(24.dp).widthIn(max=430.dp),
                verticalArrangement=Arrangement.spacedBy(14.dp),
                horizontalAlignment=Alignment.CenterHorizontally){
                HubIcon(R.drawable.ic_hub_shield,Modifier.size(44.dp))
                Text(hubLabel("管理中心已锁定","Management center locked"),
                    style=MaterialTheme.typography.titleLarge)
                Text(hubLabel("使用系统身份验证继续访问加密短信与设备管理。",
                    "Authenticate with your device to view encrypted messages and manage nodes."))
                if(localAuthError.isNotEmpty())Text(localAuthError,color=MaterialTheme.colorScheme.error)
                Button(onClick={authenticateLocal()},modifier=Modifier.fillMaxWidth()){
                    Text(hubLabel("使用系统指纹或锁屏解锁","Unlock with device authentication"))
                }
            }
        }
        return
    }
    Column(modifier.fillMaxSize()) {
        if(selected==null){
            Box(Modifier.weight(1f),contentAlignment=Alignment.Center){
                Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally){
                    HubIcon(R.drawable.ic_hub_shield,Modifier.size(44.dp))
                    Spacer(Modifier.height(18.dp))
                    Text("连接管理中心",style=MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick=onScan){Text(hubLabel("扫描配置二维码","Scan configuration QR"))}
                    TextButton(onClick={add=true}){Text(hubLabel("输入管理地址","Enter management URL"))}
                }
            }
        }else if(!authorized){
            Box(Modifier.weight(1f),contentAlignment=Alignment.Center){
                Column(Modifier.padding(22.dp).widthIn(max=460.dp),
                    verticalArrangement=Arrangement.spacedBy(14.dp)){
                    HubIcon(R.drawable.ic_hub_shield,Modifier.size(40.dp))
                    Text("安全授权管理终端",style=MaterialTheme.typography.titleLarge)
                    Text("在本机浏览器中登录管理后台（可使用已注册的 Passkey/指纹），解锁共享 Vault 后批准此手机。无需第二台设备。",
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(pairing!=null){
                        Text("配对码  ${pairing!!.code}",
                            style=MaterialTheme.typography.headlineMedium)
                        OutlinedButton(onClick={
                            val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip=ClipData.newPlainText("SIM Hub 一次性配对码",pairing!!.code)
                            if(Build.VERSION.SDK_INT>=33){
                                clip.description.extras=android.os.PersistableBundle().apply{
                                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE,true)
                                }
                            }
                            clipboard.setPrimaryClip(clip)
                        },modifier=Modifier.fillMaxWidth()) { Text("复制配对码，粘贴到本机浏览器") }
                        Text("终端指纹：${client?.identityFingerprint()}",
                            style=MaterialTheme.typography.labelSmall)
                        Text("请在本机浏览器打开后台：连接管理手机 → 待授权终端，核对本页配对码并批准。返回 App 后会自动完成加密授权。",
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick={
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                Uri.parse(selected + "/?controller=local")))
                        },modifier=Modifier.fillMaxWidth()) { Text(hubLabel("在本机浏览器认证并批准","Approve in browser")) }
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }else{
                        if(client?.hasGrant()==true)OutlinedButton(onClick={
                            authorized=true;refreshDelta()
                        },modifier=Modifier.fillMaxWidth()){Text("重试已授权终端连接")}
                        Button(enabled=!busy && client?.hasGrant()!=true,onClick={
                            busy=true;scope.launch {
                                try{pairing=withContext(Dispatchers.IO){
                                    client!!.beginPairing(android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL)
                                };error=""}catch(e:Exception){error=e.message?:"无法发起配对"}
                                finally{busy=false}
                            }
                        },modifier=Modifier.fillMaxWidth()){Text(hubLabel("申请管理终端授权","Request device authorization"))}
                    }
                    if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
                }
            }
        }else{
            if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error,
                modifier=Modifier.padding(horizontal=16.dp,vertical=6.dp))
            if(tab==0){
                LazyColumn(Modifier.weight(1f),
                    contentPadding=PaddingValues(16.dp),
                    verticalArrangement=Arrangement.spacedBy(12.dp)){
                    item {
                        HubCard(Modifier.fillMaxWidth()) {
                            Text("管理中心",style=MaterialTheme.typography.titleLarge,
                                fontWeight=FontWeight.SemiBold)
                            Spacer(Modifier.height(10.dp))
                            StatePill("已授权 · 端到端加密",true)
                            Spacer(Modifier.height(12.dp))
                            InfoRow("服务器",Uri.parse(selected.orEmpty()).host?:selected.orEmpty())
                            InfoRow("已加载 SIM 节点",devices.size.toString())
                            InfoRow("无法解密消息",unreadable.toString())
                        }
                    }
                    item {
                        HubCard(Modifier.fillMaxWidth()) {
                            HubSectionTitle("快捷操作")
                            FilledTonalButton(onClick={onSelectTab(1)},
                                modifier=Modifier.fillMaxWidth()) { Text(hubLabel("查看短信","View messages")) }
                            FilledTonalButton(onClick={onSelectTab(2)},
                                modifier=Modifier.fillMaxWidth()) { Text(hubLabel("管理设备","Manage devices")) }
                            OutlinedButton(onClick={refreshDelta()},
                                modifier=Modifier.fillMaxWidth()) { Text(hubLabel("刷新加密数据","Refresh encrypted data")) }
                        }
                    }
                    item{
                        HubCard(Modifier.fillMaxWidth()){
                            HubSectionTitle(hubLabel("远程任务中心","Remote command center"))
                            Text(hubLabel("队列接收不代表设备执行成功；任务状态来自服务器持久化 ACK。",
                                "Queued is not completed. States are read from durable Relay acknowledgements."),
                                style=MaterialTheme.typography.bodySmall,
                                color=MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick={commandTick++}){
                                Text(hubLabel("刷新任务状态","Refresh command status"))
                            }
                            if(commandHistoryError.isNotEmpty())
                                Text(commandHistoryError,color=MaterialTheme.colorScheme.error)
                            if(commandHistory.isEmpty())
                                Text(hubLabel("暂无可查询的任务","No commands to display"))
                            commandHistory.take(if(expandCommands)50 else 5).forEach{cmd->
                                HorizontalDivider()
                                val type=cmd.optString("type")
                                val deviceName=devices.firstOrNull{it.optString("id")==cmd.optString("deviceId")}
                                    ?.optString("name")?:cmd.optString("deviceId").take(8)
                                Text("$deviceName · $type",
                                    style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.SemiBold)
                                Text(ControllerCommandProgress.describe(cmd),
                                    style=MaterialTheme.typography.bodySmall)
                                Text("ID "+cmd.optString("commandId").take(12)+
                                    " · "+android.text.format.DateFormat.format(
                                        "MM-dd HH:mm",cmd.optLong("createdAt")*1000).toString(),
                                    style=MaterialTheme.typography.labelSmall,
                                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if(commandHistory.size>5)TextButton(onClick={expandCommands=!expandCommands}){
                                Text(if(expandCommands)hubLabel("收起","Show fewer")
                                    else hubLabel("展开更多任务","Show more commands"))
                            }
                        }
                    }
                    if(error.isNotEmpty())item {
                        Text(error,color=MaterialTheme.colorScheme.error)
                    }
                }
            }else if(tab==1){
                Box(Modifier.weight(1f)){
                    if(!inbox.unlocked && busy){
                        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){
                            Column(horizontalAlignment=Alignment.CenterHorizontally){
                                CircularProgressIndicator()
                                Spacer(Modifier.height(12.dp))
                                Text("正在验证终端并加载加密短信")
                            }
                        }
                    }else NativeControllerInbox(inbox,
                        onRefresh={refreshDelta()},
                        onOlder={loadOlderPage()},
                        onNewer={navigateToLatest()},
                        onOpenLogin={refreshDelta()},
                        onWrite={showWrite=true})
                    if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }else if(tab==2){
                LazyColumn(Modifier.weight(1f),
                    contentPadding=PaddingValues(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                    items(devices,key={it.optString("id")}){d->
                        HubCard(Modifier.fillMaxWidth()){
                            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                                Row(verticalAlignment=Alignment.CenterVertically){
                                    Column(Modifier.weight(1f)){
                                        Text(d.optString("name","SIM 节点"),fontWeight=FontWeight.SemiBold)
                                        Text(d.optString("model"),style=MaterialTheme.typography.bodySmall)
                                    }
                                    Text(if(d.optBoolean("online"))"在线" else "离线",
                                        color=if(d.optBoolean("online"))MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                val state=d.optJSONObject("state")?:JSONObject()
                                Text("短信：${d.optInt("smsCount")} 条 · ${state.optString("smsMode","未知模式")}",
                                    style=MaterialTheme.typography.bodySmall)
                                Text("电量：${state.optString("batteryPct","—")}%  · ${state.optString("network","—")}",
                                    style=MaterialTheme.typography.bodySmall)
                                TextButton(onClick={selectedDevice=d}){Text(hubLabel("设备详情与操作","Device details and actions"))}
                            }
                        }
                    }
                }
            }else{
                LazyColumn(Modifier.weight(1f),
                    contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    item{
                        Text("管理空间",style=MaterialTheme.typography.titleMedium)
                        ListItem(headlineContent={Text(Uri.parse(selected).host?:selected.orEmpty())},
                            supportingContent={Text("终端签名已授权 · 加密密钥仅存于本机")},
                            leadingContent={HubIcon(R.drawable.ic_hub_shield)})
                        Text("终端标识：${client?.controllerId()?.take(12)}…",
                            style=MaterialTheme.typography.bodySmall)
                    }
                    item{
                        HubSectionTitle("管理空间")
                        OutlinedButton(onClick={add=true},
                            modifier=Modifier.fillMaxWidth()) { Text(hubLabel("添加管理地址","Add management URL")) }
                        profiles.forEach { origin ->
                            TextButton(onClick={chooseOrigin(origin)},
                                modifier=Modifier.fillMaxWidth()) {
                                Text(Uri.parse(origin).host?:origin)
                            }
                        }
                    }
                    item {
                        Text("安全与权限",style=MaterialTheme.typography.titleMedium)
                        Text("可在本机完成云端授权撤销及本地密钥清除；离线时也可仅清除本机身份，但云端授权不会因此撤销。",
                            color=MaterialTheme.colorScheme.onSurfaceVariant,
                            style=MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment=Alignment.CenterVertically){
                            Column(Modifier.weight(1f)){
                                Text(hubLabel("重新进入时验证身份","Require authentication on return"))
                                Text(hubLabel("使用安卓系统生物识别或锁屏；这是应用访问保护，并非密钥的硬件认证绑定。",
                                    "Android biometrics or device credential. App access gate, not a hardware-bound Vault key."),
                                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked=requireLocalUnlock,onCheckedChange={enabled->
                                if(enabled)authenticateLocal(enable=true)
                                else{client?.setRequireLocalUnlock(false);requireLocalUnlock=false;localUnlocked=true}
                            })
                        }
                        OutlinedButton(onClick={showReset=true}){Text(hubLabel("清除此手机的管理授权","Remove authorization from this phone"))}
                    }
                    item{
                        Text("版本及诊断",style=MaterialTheme.typography.titleMedium)
                        Text("Android ${BuildConfig.VERSION_NAME} · 原生管理端")
                        OutlinedButton(onClick={
                            scope.launch{try{
                                serverVersion=withContext(Dispatchers.IO){
                                    client!!.api("/api/v1/version").optString("version","未知")
                                }
                            }catch(e:Exception){error=e.message?:"版本检查失败"}}
                        }){Text("服务器版本：$serverVersion")}
                    }
                }
            }

        }
    }
    if(add)AlertDialog(onDismissRequest={add=false},title={Text(hubLabel("添加管理空间","Add management space"))},
        text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
            OutlinedTextField(addUrl,{addUrl=it},label={Text("HTTPS 管理地址")},
                singleLine=true,modifier=Modifier.fillMaxWidth())
            TextButton(onClick={add=false;onScan()}){Text(hubLabel("扫描配置二维码","Scan configuration QR"))}
        }},confirmButton={TextButton(onClick={
            val origin=ControllerProfiles.add(context,addUrl)
            if(origin==null)error="请输入合法的 HTTPS 管理地址"
            else{profiles=ControllerProfiles.list(context);chooseOrigin(origin);addUrl="";add=false}
        }){Text(hubLabel("添加","Add"))}},dismissButton={TextButton(onClick={add=false}){Text(hubLabel("取消","Cancel"))}})
    if(showReset)AlertDialog(onDismissRequest={showReset=false},
        title={Text(hubLabel("清除本机授权？","Remove local authorization?"))},
        text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text("建议先撤销云端管理终端，再删除此手机的本地密钥。不会影响 SIM 节点短信。")
            OutlinedTextField(resetCredential,{resetCredential=it},
                label={Text(if(secondFactorTotp)"二次验证 TOTP（云端撤销）" else "管理员令牌（云端撤销）")},
                singleLine=true,visualTransformation=PasswordVisualTransformation())
            if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
        }},
        confirmButton={
            TextButton(enabled=!busy,onClick={
                val api=client?:return@TextButton
                busy=true
                scope.launch{
                    try{
                        withContext(Dispatchers.IO){
                            if(resetCredential.isNotBlank())api.api("/api/v1/auth/elevate","POST",
                                JSONObject().put(if(secondFactorTotp)"totp" else "adminToken",resetCredential))
                            api.api("/api/v1/controllers/revoke","POST",
                                JSONObject().put("controllerId",api.controllerId()))
                        }
                        api.localReset();authorized=false;inbox.clear();devices=emptyList()
                        lastCommandId="";showReset=false;resetCredential="";error=""
                    }catch(e:Exception){error="无法撤销云端授权："+(e.message?:"请检查网络或二次验证")}
                    finally{busy=false}
                }
            }){Text(hubLabel("撤销云端并清除本机","Revoke on server and remove locally"))}
        },
        dismissButton={
            TextButton(onClick={
                // Explicit offline-only fallback; this does not revoke Relay access.
                client?.localReset();authorized=false;inbox.clear();devices=emptyList()
                lastCommandId="";resetCredential="";showReset=false
            }){Text(hubLabel("仅清除此手机","Remove only from this phone"))}
        })
    selectedDevice?.let{d->
        AlertDialog(onDismissRequest={selectedDevice=null},
            title={Text(d.optString("name","设备详情"))},
            text={Column(Modifier.heightIn(max=450.dp).verticalScroll(rememberScrollState()),
                verticalArrangement=Arrangement.spacedBy(10.dp)){
                Text("设备 ID：${d.optString("id")}")
                Text("版本：${d.optString("appVersion")}")
                Text("连接：${if(d.optBoolean("online"))"在线" else "离线"}")
                Text("最近同步：${d.optLong("lastSeenAt")}")
                Text("远程同步使用原有端到端加密指令，操作异步排队并等待设备 ACK。",
                    style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(actionError.isNotEmpty())Text(actionError,color=MaterialTheme.colorScheme.error)
                if(lastCommandId.isNotBlank() && lastCommandDevice==d.optString("name"))
                    Text(lastCommandState.ifBlank{"查询任务状态中"},style=MaterialTheme.typography.bodySmall)
                Button(onClick={
                    scope.launch{
                        try{
                            val queuedId=withContext(Dispatchers.IO){
                                val id=UUID.randomUUID().toString()
                                val now=System.currentTimeMillis()/1000
                                val end=now+1200
                                val payload=JSONObject().put("v",2).put("action","sms.sync_recent")
                                    .put("commandId",id).put("issuedAt",now).put("expiresAt",end)
                                val cipher=client!!.encryptCommand(d,id,"sms.sync_recent",now,end,payload)
                                client.api("/api/v1/devices/"+d.getString("id")+"/commands","POST",
                                    JSONObject().put("commandId",id).put("idempotencyKey",id)
                                    .put("type","sms.sync_recent").put("createdAt",now)
                                    .put("expiresAt",end).put("ciphertext",cipher))
                                id
                            }
                            lastCommandId=queuedId
                            lastCommandDevice=d.optString("name")
                            lastCommandState="等待设备连接并领取任务"
                            actionError="同步任务已入队，可在首页跟踪执行结果"
                        }catch(e:Exception){actionError=e.message?:"指令失败"}
                    }
                }){Text(hubLabel("下发同步最近短信","Sync recent messages"))}
            }},
            confirmButton={TextButton(onClick={selectedDevice=null;actionError=""}){Text(hubLabel("关闭","Close"))}})
    }
    if(showWrite){
        var sendError by remember{mutableStateOf("")}
        var sending by remember{mutableStateOf(false)}
        // Draft, SIM choice and idempotency key survive closing the compose page.
        val recipient=composeRecipient
        val message=composeMessage
        val submittedDraft=composeSubmittedDraft
        val messageCommandId=composeCommandId
        val deviceIdx=composeDeviceIdx
        val channelIdx=composeChannelIdx
        val possible=devices.filter{d->
            val st=d.optJSONObject("state")?:JSONObject()
            val channels=st.optJSONArray("channels")
            d.optBoolean("online") && channels!=null && channels.length()>0 &&
                (st.optBoolean("smsSendAllowed") || d.optString("nodeType")=="modem")
        }
        val current=possible.getOrNull(deviceIdx)
        val channels=current?.optJSONObject("state")?.optJSONArray("channels")?:JSONArray()
        val currentDraft=(current?.optString("id")?:"")+"|"+deviceIdx+"|"+channelIdx+"|"+recipient+"|"+message
        var stepUp by remember{mutableStateOf("")}
        Dialog(onDismissRequest={showWrite=false},
            properties=DialogProperties(usePlatformDefaultWidth=false)){
            Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){
                Column(Modifier.fillMaxSize().imePadding().padding(18.dp)){
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                        Text(hubLabel("发送加密短信","Send encrypted SMS"),
                            modifier=Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                        TextButton(onClick={showWrite=false}){Text(hubLabel("关闭","Close"))}
                    }
                    HorizontalDivider()
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement=Arrangement.spacedBy(12.dp)){
                if(sendError.isNotEmpty())Text(sendError,color=MaterialTheme.colorScheme.error)
                if(submittedDraft.isNotEmpty() && currentDraft!=submittedDraft)
                    Text(hubLabel("重试需保留原收件人、SIM 与短信内容；修改内容请新建发送任务。",
                        "For an idempotent retry, restore the original recipient, SIM and message."),
                        color=MaterialTheme.colorScheme.error)
                if(possible.isEmpty())Text("当前没有允许发送短信的在线 SIM 节点。")
                else{
                    Text("发送设备",style=MaterialTheme.typography.labelLarge)
                    possible.forEachIndexed{i,d->
                        FilterChip(selected=i==deviceIdx,onClick={composeDeviceIdx=i;composeChannelIdx=0},
                            label={Text(d.optString("name"))})
                    }
                    Text("来源 SIM",style=MaterialTheme.typography.labelLarge)
                    for(i in 0 until channels.length()){
                        val ch=channels.optJSONObject(i)?:continue
                        FilterChip(selected=i==channelIdx,onClick={composeChannelIdx=i},
                            label={Text(ch.optString("displayName").ifBlank{ch.optString("id")})})
                    }
                    OutlinedTextField(recipient,{composeRecipient=it},label={Text(hubLabel("收件人号码","Recipient number"))})
                    OutlinedTextField(message,{composeMessage=it},label={Text(hubLabel("短信内容","Message"))},minLines=3)
                    OutlinedTextField(stepUp,{stepUp=it},label={Text(if(secondFactorTotp)"二次验证 TOTP" else "管理员令牌（敏感操作）")},
                        singleLine=true,visualTransformation=PasswordVisualTransformation())
                }
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
                        Button(enabled=!sending && (submittedDraft.isEmpty() || submittedDraft==currentDraft) && current!=null && recipient.isNotBlank() && message.isNotBlank(),
                onClick={
                    if(submittedDraft.isEmpty())composeSubmittedDraft=currentDraft
                    sending=true;sendError=""
                    scope.launch{
                        try {
                            val queuedId=withContext(Dispatchers.IO){
                                // Server always independently enforces second factor for sms.send.
                                if(stepUp.isNotEmpty())client!!.api("/api/v1/auth/elevate","POST",
                                    JSONObject().put(if(secondFactorTotp)"totp" else "adminToken",stepUp))
                                val channel=channels.optJSONObject(channelIdx)?:throw IllegalStateException("SIM 未选择")
                                val id=messageCommandId
                                val now=System.currentTimeMillis()/1000
                                val expires=now+1200
                                val payload=JSONObject().put("v",2).put("action","sms.send")
                                    .put("commandId",id).put("issuedAt",now).put("expiresAt",expires)
                                    .put("channelId",channel.optString("id"))
                                    .put("channelRevision",channel.optInt("revision",1))
                                    .put("to",recipient).put("body",message)
                                val cipher=client!!.encryptCommand(current!!,id,"sms.send",now,expires,payload)
                                client.api("/api/v1/devices/"+current.getString("id")+"/commands","POST",
                                    JSONObject().put("commandId",id).put("idempotencyKey",id)
                                        .put("type","sms.send").put("createdAt",now)
                                        .put("expiresAt",expires).put("ciphertext",cipher))
                                id
                            }
                            lastCommandId=queuedId
                            lastCommandDevice=current?.optString("name").orEmpty()
                            lastCommandState="等待设备连接并领取任务"
                            showWrite=false;error=""
                            composeRecipient="";composeMessage=""
                            composeSubmittedDraft=""
                            composeCommandId=UUID.randomUUID().toString()
                        }catch(e:Exception){
                            sendError=(e.message?:"请求失败")+
                                "。若请求状态不明，请先查看命令执行状态再重试。"
                        }finally{sending=false}
                    }
                        }){Text(hubLabel("确认发送","Confirm send"))}
                    }
                }
            }
        }
    }
}
