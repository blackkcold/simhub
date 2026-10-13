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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
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
/** First history request must initialize both cursors before incremental requests. */
internal object ControllerPagingPolicy {
    fun isInitialPage(mode:String,lastEventSeq:Long):Boolean =
        mode=="latest" || (mode=="delta" && lastEventSeq<=0L)
}

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

/** Presentation-only SIM labels. Preserve channel identity in sourceId/thread keys. */
internal object ControllerSourceLabel {
    fun name(node:JSONObject?,channelId:String,revision:Int,tag:String):String {
        if(channelId.isBlank() || revision<=0)return "历史 SIM · 来源待确认"
        val state=node?.optJSONObject("state")
        val channels=state?.optJSONArray("channels") ?: state?.optJSONArray("subscriptions") ?: JSONArray()
        for(i in 0 until channels.length()){
            val item=channels.optJSONObject(i)?:continue
            val id=item.optString("id").ifBlank{item.optString("channelId")}
            val rev=item.optInt("revision",item.optInt("channelRevision",1))
            if(id!=channelId || rev!=revision)continue
            for(key in listOf("alias","displayName","carrierName")){
                val value=item.optString(key).trim()
                if(value.isNotBlank() && value!=channelId && value.length<=48 &&
                    !value.matches(Regex("[0-9a-fA-F-]{20,}")))return value
            }
            val slot=item.optInt("slotIndex",item.optInt("slot",-1))
            if(slot in 0..7)return "SIM ${slot+1}"
        }
        val display=tag.trim()
        if(display.isNotEmpty() && display!=channelId && display.length<=48 &&
            !display.matches(Regex("[0-9a-fA-F-]{20,}")))return display
        return "历史 SIM"
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
    var pendingManageAction by remember{mutableStateOf<Pair<JSONObject,String>?>(null)}
    var manageValue by remember{mutableStateOf("")}
    var manageCredential by remember{mutableStateOf("")}
    var manageConfirm by remember{mutableStateOf("")}
    var manageChannelIndex by remember{mutableIntStateOf(-1)}
    var manageBusy by remember{mutableStateOf(false)}
    var manageError by remember{mutableStateOf("")}
    var actionError by remember{mutableStateOf("")}
    var actionBusy by remember{mutableStateOf(false)}
    var pendingSensitiveTask by remember { mutableStateOf<Pair<JSONObject,String>?>(null) }
    var sensitiveCredential by remember { mutableStateOf("") }
    var serverVersion by remember{mutableStateOf("—")}
    var poolStatus by remember(selected){mutableStateOf<JSONObject?>(null)}
    var auditRows by remember(selected){mutableStateOf<List<JSONObject>>(emptyList())}
    var adminOverviewError by remember(selected){mutableStateOf("")}
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
        val initialPage=ControllerPagingPolicy.isInitialPage(mode,lastEventSeq)
        busy=true
        scope.launch {
            try {
                val data=withContext(Dispatchers.IO){
                    val d=api.api("/api/v1/devices")
                    val auth=api.api("/api/v1/auth/check")
                    val all=d.optJSONArray("devices")?:JSONArray()
                    val nodes=(0 until all.length()).mapNotNull{all.optJSONObject(it)}
                    val byId=nodes.associateBy{it.optString("id")}
                    val delta=mode=="delta" && !initialPage
                    // Read the sequence watermark *before* the latest-time page.
                    // Concurrent uploads can then safely be replayed as delta.
                    val highWater=if(initialPage)
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
                                api.api("/api/v1/events?since=$sequence&scope=sms&limit=50")
                            else api.api("/api/v1/events?order=occurred&scope=sms&limit=50"+suffix)
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
                                val provenance=ControllerSourceLabel.name(node,ch,rev,plaintext.optString("simTag"))
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
                    if(!inbox.accept(batch.payload,append=mode=="older" || (mode=="delta" && !initialPage) || batch.appendFragment,
                                     preferOlder=mode=="older"))
                        throw IllegalStateException("短信批次未接收；游标未推进，可安全重试")
                    if(mode=="delta" && !initialPage && batch.finalFragment)
                        lastEventSeq=maxOf(lastEventSeq,batch.sequence,data.highWater)
                    else if(mode=="older" && batch.finalFragment){
                        page+=1
                        if(batch.nextTime>0 && batch.nextSeq>0)
                            cursors=cursors.take(page)+listOf(batch.nextTime to batch.nextSeq)
                    }else if(initialPage && batch.finalFragment){
                        page=0
                        cursors=if(batch.nextTime>0 && batch.nextSeq>0)
                            listOf(batch.nextTime to batch.nextSeq) else emptyList()
                        lastEventSeq=maxOf(lastEventSeq,data.highWater)
                    }
                    if(initialPage)unreadable=batch.unreadable
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
    /** Remote commands are E2EE encrypted, idempotent and acknowledged asynchronously. */
    fun queueDeviceAction(device:JSONObject,action:String,credential:String?=null,channelIndex:Int=-1){
        if((action.startsWith("ota-") || action=="restore-history") && credential==null){
            pendingSensitiveTask=device to action
            sensitiveCredential=""
            return
        }
        if(actionBusy || !authorized || (requireLocalUnlock && !localUnlocked))return
        val commandType=when(action){
            "sync-recent"->"sms.sync_recent"
            "sync-older"->"sms.sync_older"
            "restore-history"->"sms.restore_history"
            "refresh"->"device.refresh_state"
            "diagnostics"->"diagnostics.request"
            "ota-install"->"ota.install"
            "ota-cancel"->"ota.cancel"
            "network-enable"->"device.network_policy"
            "network-disable"->"device.network_policy"
            else->return
        }
        val state=device.optJSONObject("state")?:JSONObject()
        if(action.startsWith("ota-") && (
            device.optString("nodeType")!="android" || !state.optBoolean("remoteOtaSupported") ||
            !(state.optInt("sdk")>=36 || state.optBoolean("remoteOtaDeveloperOverride")) ||
            !state.optBoolean("remoteOtaAuthorized") || !state.optBoolean("remoteOtaInstallPermission"))) {
            actionError="节点不满足远程更新能力或授权条件";return
        }
        if(action=="restore-history" && device.optInt("appVersionCode",state.optInt("appVersionCode",0))<50){
            actionError="节点版本暂不支持历史恢复，请先升级";return
        }
        if(action=="ota-cancel" && state.optString("remoteOtaStage") !in
            listOf("checking","downloading","verified"))return
        actionError="";actionBusy=true
        scope.launch{
            try{
                val commandId=withContext(Dispatchers.IO){
                    val api=client?:throw SecurityException("管理授权已失效")
                    if(action.startsWith("ota-") || action=="restore-history" || action.startsWith("network-")){
                        if(credential.isNullOrBlank())throw SecurityException("此操作要求管理员二次验证")
                        api.api("/api/v1/auth/elevate","POST",JSONObject()
                            .put(if(secondFactorTotp)"totp" else "adminToken",credential))
                    }
                    val id=UUID.randomUUID().toString()
                    val now=System.currentTimeMillis()/1000
                    val energy=state.optString("effectiveEnergyMode",
                        state.optString("energyMode","balanced"))
                    val minTtl=if(!state.optBoolean("foregroundRelay") || energy=="eco")1200L
                        else if(energy=="balanced")300L else 180L
                    val end=now+maxOf(if(action=="ota-install")3600L else 900L,minTtl)
                    val payload=JSONObject().put("v",2).put("action",commandType)
                        .put("commandId",id).put("issuedAt",now).put("expiresAt",end)
                    if(action=="sync-recent" || action=="sync-older")payload.put("maxMessages",100)
                    if(action=="ota-install")payload.put("targetVersion","latest")
                    if(action.startsWith("network-")){
                        val enabled=action=="network-enable"
                        payload.put("enabled",enabled)
                        if(enabled){
                            val channel=ControllerNodePolicy.channels(device).optJSONObject(channelIndex)
                                ?:throw IllegalStateException("请选择有效 SIM 通道")
                            payload.put("channelId",channel.optString("id")
                                .ifBlank{channel.optString("channelId")})
                            payload.put("channelRevision",channel.optInt("revision",
                                channel.optInt("channelRevision",1)))
                            if(payload.optString("channelId").isBlank())
                                throw IllegalStateException("SIM 通道 ID 不可用")
                        }
                    }
                    val cipher=api.encryptCommand(device,id,commandType,now,end,payload)
                    api.api("/api/v1/devices/"+device.getString("id")+"/commands","POST",
                        JSONObject().put("commandId",id).put("idempotencyKey",id)
                            .put("type",commandType).put("createdAt",now)
                            .put("expiresAt",end).put("ciphertext",cipher))
                    id
                }
                lastCommandId=commandId;lastCommandDevice=device.optString("name")
                lastCommandState="任务已入队，等待设备领取及最终 ACK"
                commandTick++
            }catch(e:Exception){actionError=e.message?:"任务下发失败，请检查网络及设备状态"}
            finally{actionBusy=false}
        }
    }
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
    LaunchedEffect(selected,visible,authorized,tab,localUnlocked){
        if(!visible || !authorized || tab!=3 || (requireLocalUnlock && !localUnlocked))return@LaunchedEffect
        val api=client?:return@LaunchedEffect
        try{
            val (pool,audits)=withContext(Dispatchers.IO){
                val p=api.api("/api/v1/pool")
                val a=api.api("/api/v1/audit?limit=12").optJSONArray("audit")?:JSONArray()
                p to (0 until a.length()).mapNotNull{a.optJSONObject(it)}
            }
            poolStatus=pool;auditRows=audits;adminOverviewError=""
        }catch(e:Exception){adminOverviewError=e.message?:"管理记录读取失败"}
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
                    contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    item{
                        HubCard(Modifier.fillMaxWidth()){
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                                HubIcon(R.drawable.ic_hub_dashboard,Modifier.size(31.dp),MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f)){
                                    Text(hubLabel("管理中心","Management center"),
                                        style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                                    Text(Uri.parse(selected.orEmpty()).host?:selected.orEmpty(),
                                        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                StatePill(hubLabel("E2EE 已授权","E2EE authorized"),true)
                            }
                        }
                    }
                    item{
                        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                            ControllerMetric(hubLabel("设备总数","Total nodes"),devices.size.toString(),
                                R.drawable.ic_hub_devices,Modifier.weight(1f))
                            ControllerMetric(hubLabel("在线设备","Online nodes"),devices.count{it.optBoolean("online")}.toString(),
                                R.drawable.ic_hub_network,Modifier.weight(1f))
                        }
                    }
                    item{
                        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                            ControllerMetric(hubLabel("已加载短信","Loaded SMS"),inbox.messages.size.toString(),
                                R.drawable.ic_hub_messages,Modifier.weight(1f))
                            ControllerMetric(hubLabel("待处理任务","Pending tasks"),
                                commandHistory.count{it.optString("state") in listOf("queued","dispatched","submitted")}.toString(),
                                R.drawable.ic_hub_tasks,Modifier.weight(1f))
                        }
                    }
                    if(unreadable>0)item{
                        HubCard(Modifier.fillMaxWidth()){
                            Text(hubLabel("部分短信无法解密","Messages could not be decrypted"),
                                color=MaterialTheme.colorScheme.error)
                            Text(hubLabel("请检查历史设备密钥。","Check historical node keys."),
                                style=MaterialTheme.typography.bodySmall)
                        }
                    }
                    item{
                        HubCard(Modifier.fillMaxWidth()){
                            HubSectionTitle(hubLabel("快捷操作","Quick actions"))
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                FilledTonalButton(onClick={onSelectTab(1)},modifier=Modifier.weight(1f)){
                                    HubIcon(R.drawable.ic_hub_messages,Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(hubLabel("短信","Inbox"))
                                }
                                FilledTonalButton(onClick={onSelectTab(2)},modifier=Modifier.weight(1f)){
                                    HubIcon(R.drawable.ic_hub_devices,Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(hubLabel("设备","Devices"))
                                }
                            }
                            if(devices.any{ControllerNodePolicy.canSend(it)})
                                OutlinedButton(onClick={showWrite=true},modifier=Modifier.fillMaxWidth()){
                                    HubIcon(R.drawable.ic_hub_send,Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(hubLabel("发送加密短信","Send encrypted SMS"))
                                }
                            OutlinedButton(onClick={refreshDelta()},modifier=Modifier.fillMaxWidth()){
                                Text(hubLabel("刷新加密数据","Refresh encrypted data"))
                            }
                        }
                    }
                    item{
                        HubCard(Modifier.fillMaxWidth()){
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                                Text(hubLabel("远程任务","Remote tasks"),
                                    style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,
                                    modifier=Modifier.weight(1f))
                                IconButton(onClick={commandTick++}){
                                    HubIcon(R.drawable.ic_hub_sync,description=hubLabel("刷新任务","Refresh tasks"))
                                }
                            }
                            if(commandHistoryError.isNotEmpty())
                                Text(commandHistoryError,color=MaterialTheme.colorScheme.error)
                            if(commandHistory.isEmpty())
                                Text(hubLabel("暂无任务","No recent tasks"))
                            commandHistory.take(if(expandCommands)50 else 5).forEachIndexed{index,cmd->
                                if(index>0)HorizontalDivider()
                                ControllerTaskRow(cmd)
                            }
                            if(commandHistory.size>5)TextButton(onClick={expandCommands=!expandCommands}){
                                Text(if(expandCommands)hubLabel("收起","Show fewer") else hubLabel("全部任务","All tasks"))
                            }
                        }
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
                        onWrite={showWrite=true},
                         onReply={address->composeRecipient=address;showWrite=true},
                         canCompose=devices.any{ControllerNodePolicy.canSend(it)})
                    if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }else if(tab==2){
                LazyVerticalGrid(columns=GridCells.Adaptive(300.dp),modifier=Modifier.weight(1f),
                    contentPadding=PaddingValues(16.dp),
                    horizontalArrangement=Arrangement.spacedBy(12.dp),
                    verticalArrangement=Arrangement.spacedBy(12.dp)){
                    item(span={GridItemSpan(maxLineSpan)}){
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                            Column(Modifier.weight(1f)){
                                Text(hubLabel("已连接设备","Connected devices"),
                                    style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                                Text(devices.size.toString()+hubLabel(" 台设备"," nodes"),
                                    style=MaterialTheme.typography.bodySmall,
                                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick={refreshDelta()}){
                                HubIcon(R.drawable.ic_hub_sync,description=hubLabel("刷新设备","Refresh devices"))
                            }
                        }
                    }
                    item(span={GridItemSpan(maxLineSpan)}){
                        OutlinedButton(onClick={
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                Uri.parse(selected+"/")))
                        },modifier=Modifier.fillMaxWidth()){
                            HubIcon(R.drawable.ic_hub_add,Modifier.size(19.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(hubLabel("添加 SIM 节点（网页安全配对）","Add SIM node (secure Web enrollment)"))
                        }
                    }
                    if(devices.isEmpty())item(span={GridItemSpan(maxLineSpan)}){
                        HubCard(Modifier.fillMaxWidth()){
                            Text(hubLabel("暂无已配对设备","No enrolled devices"))
                            Text(hubLabel("请在网页管理后台添加 SIM 节点。","Enroll SIM nodes in the web console."),
                                style=MaterialTheme.typography.bodySmall)
                        }
                    }
                    gridItems(devices,key={it.optString("id")}){device->
                        ControllerDeviceCard(device){selectedDevice=device;actionError=""}
                    }
                }
            }else{
                LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(16.dp),
                    verticalArrangement=Arrangement.spacedBy(12.dp)){
                    item{
                        Text(hubLabel("管理设置","Management settings"),
                            style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                    }
                    item{
                        HubCard(Modifier.fillMaxWidth()){
                            Row(verticalAlignment=Alignment.CenterVertically,
                                horizontalArrangement=Arrangement.spacedBy(9.dp)){
                                HubIcon(R.drawable.ic_hub_share,Modifier.size(22.dp),
                                    MaterialTheme.colorScheme.primary)
                                HubSectionTitle(hubLabel("共享短信池","Shared SMS pool"))
                            }
                            if(adminOverviewError.isNotBlank())
                                Text(adminOverviewError,style=MaterialTheme.typography.bodySmall,
                                    color=MaterialTheme.colorScheme.error)
                            val pool=poolStatus
                            if(pool==null)Text(hubLabel("读取管理空间状态中…","Loading shared-pool status…"),
                                style=MaterialTheme.typography.bodySmall)
                            else {
                                if(pool.optBoolean("rotationRequired"))
                                    Text(hubLabel("密钥轮换待完成：共享上传已暂停，请完成安全轮换。",
                                        "Key rotation pending; shared uploads are paused."),
                                        color=MaterialTheme.colorScheme.error)
                                InfoRow(hubLabel("密钥版本","Key epoch"),pool.optString("epoch","—"))
                                val members=pool.optJSONArray("members")?:JSONArray()
                                InfoRow(hubLabel("共享成员","Pool members"),members.length().toString())
                                for(i in 0 until members.length()){
                                    val member=members.optJSONObject(i)?:continue
                                    val linked=devices.firstOrNull{it.optString("id")==member.optString("id")}
                                    val memberName=linked?.optString("name")?.takeIf{it.isNotBlank()}
                                        ?:member.optString("name").ifBlank{"SIM Node"}
                                    val status=when {
                                        !member.optBoolean("requested")->hubLabel("待设备启用","Node not enabled")
                                        member.optBoolean("approved")->hubLabel("已授权","Authorized")
                                        else->hubLabel("等待批准","Awaiting approval")
                                    }
                                    InfoRow(memberName,status)
                                }
                            }
                        }
                    }
                    item{
                        HubCard(Modifier.fillMaxWidth()){
                            HubSectionTitle(hubLabel("安全审计记录","Security audit"))
                            if(auditRows.isEmpty())Text(hubLabel("暂无近期审计记录","No recent audit records"),
                                style=MaterialTheme.typography.bodySmall)
                            auditRows.take(8).forEach{entry->
                                Row(Modifier.fillMaxWidth().padding(vertical=5.dp),
                                    verticalAlignment=Alignment.CenterVertically,
                                    horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                    HubIcon(R.drawable.ic_hub_shield,Modifier.size(16.dp),
                                        MaterialTheme.colorScheme.onSurfaceVariant)
                                    Column(Modifier.weight(1f)){
                                        Text(entry.optString("action").take(70),
                                            style=MaterialTheme.typography.bodySmall)
                                        val ts=entry.optLong("occurred_at",0L)
                                        if(ts>0)Text(android.text.format.DateFormat.format(
                                            "MM-dd HH:mm",ts*1000).toString(),
                                            style=MaterialTheme.typography.labelSmall,
                                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(entry.optString("result").take(16),
                                        style=MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    item{
                        HubCard(Modifier.fillMaxWidth()){
                            Row(verticalAlignment=Alignment.CenterVertically,
                                horizontalArrangement=Arrangement.spacedBy(11.dp)){
                                HubIcon(R.drawable.ic_hub_dashboard,Modifier.size(28.dp),
                                    MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f)){
                                    Text(hubLabel("管理空间","Workspace"),fontWeight=FontWeight.SemiBold)
                                    Text(Uri.parse(selected).host?:selected.orEmpty(),
                                        style=MaterialTheme.typography.bodySmall,
                                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                StatePill(hubLabel("已授权","Authorized"),true)
                            }
                            InfoRow(hubLabel("设备身份","Controller ID"),client?.controllerId()?.take(12).orEmpty()+"…")
                            HorizontalDivider(Modifier.padding(vertical=10.dp))
                            OutlinedButton(onClick={add=true},modifier=Modifier.fillMaxWidth()){
                                Text(hubLabel("添加管理地址","Add management URL"))
                            }
                            profiles.forEach{origin->
                                TextButton(onClick={chooseOrigin(origin)},
                                    modifier=Modifier.fillMaxWidth()){
                                    Text(Uri.parse(origin).host?:origin)
                                }
                            }
                        }
                    }
                    item{
                        HubCard(Modifier.fillMaxWidth()){
                            Row(verticalAlignment=Alignment.CenterVertically,
                                horizontalArrangement=Arrangement.spacedBy(9.dp)){
                                HubIcon(R.drawable.ic_hub_shield,Modifier.size(24.dp),
                                    MaterialTheme.colorScheme.primary)
                                Text(hubLabel("安全与访问","Security & access"),
                                    style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment=Alignment.CenterVertically){
                                Column(Modifier.weight(1f)){
                                    Text(hubLabel("返回时验证身份","Require unlock on return"),
                                        fontWeight=FontWeight.Medium)
                                    Text(hubLabel("系统生物识别/锁屏是应用门禁，不是硬件认证绑定的 Vault 密钥。",
                                        "System unlock is an app gate, not an auth-bound Vault key."),
                                        style=MaterialTheme.typography.bodySmall,
                                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked=requireLocalUnlock,onCheckedChange={enabled->
                                    if(enabled)authenticateLocal(enable=true)
                                    else{
                                        client?.setRequireLocalUnlock(false)
                                        requireLocalUnlock=false;localUnlocked=true
                                    }
                                })
                            }
                            HorizontalDivider(Modifier.padding(vertical=10.dp))
                            OutlinedButton(onClick={showReset=true},modifier=Modifier.fillMaxWidth()){
                                Text(hubLabel("撤销此手机的管理授权…","Revoke this controller…"))
                            }
                        }
                    }
                    item{
                        HubCard(Modifier.fillMaxWidth()){
                            Row(verticalAlignment=Alignment.CenterVertically,
                                horizontalArrangement=Arrangement.spacedBy(9.dp)){
                                HubIcon(R.drawable.ic_hub_tasks,Modifier.size(24.dp),
                                    MaterialTheme.colorScheme.primary)
                                Text(hubLabel("系统与版本","System & updates"),
                                    style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                            }
                            InfoRow(hubLabel("Android 版本","Android app"),BuildConfig.VERSION_NAME)
                            InfoRow(hubLabel("Relay 版本","Relay version"),serverVersion)
                            OutlinedButton(onClick={
                                scope.launch{
                                    try{serverVersion=withContext(Dispatchers.IO){
                                        client!!.api("/api/v1/version").optString("version","未知")
                                    }}catch(e:Exception){error=e.message?:"版本检查失败"}
                                }
                            },modifier=Modifier.fillMaxWidth()){
                                Text(hubLabel("检查服务器版本","Check Relay version"))
                            }
                            OutlinedButton(onClick={
                                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                    Uri.parse(selected+"/")))
                            },modifier=Modifier.fillMaxWidth()){
                                Text(hubLabel("打开网页高级管理","Open advanced Web management"))
                            }
                        }
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
    selectedDevice?.let{device->
        if(pendingSensitiveTask==null)Dialog(onDismissRequest={selectedDevice=null},
            properties=DialogProperties(usePlatformDefaultWidth=false)){
            Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){
                ControllerDeviceDetails(device,busy=actionBusy,
                    lastStatus=if(lastCommandDevice==device.optString("name"))lastCommandState else "",
                    error=actionError,
                    onClose={selectedDevice=null;actionError=""},
                    onAction={action->queueDeviceAction(device,action)},
                    onManage={action->
                        pendingManageAction=device to action
                        manageValue=device.optString("name")
                        manageCredential="";manageConfirm="";manageChannelIndex=-1;manageError=""
                    },
                    onAdvanced={
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                            Uri.parse(selected+"/")))
                    })
            }
        }
    }
    pendingManageAction?.let{(device,action)->
        val destructive=action in listOf("purge","revoke","delete")
        val sensitive=destructive || action=="network"
        val channels=ControllerNodePolicy.channels(device)
        val title=when(action){
            "rename"->hubLabel("修改设备名称","Rename node")
            "network"->hubLabel("备用移动数据策略","Mobile network fallback")
            "purge"->hubLabel("清理中继短信","Purge relay SMS")
            "revoke"->hubLabel("撤销设备授权","Revoke device")
            else->hubLabel("强制删除设备","Force delete device")
        }
        AlertDialog(
            onDismissRequest={if(!manageBusy)pendingManageAction=null},
            title={Text(title)},
            text={Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement=Arrangement.spacedBy(10.dp)){
                Text(device.optString("name").ifBlank{"SIM Node"},
                    fontWeight=FontWeight.SemiBold)
                if(action=="rename")OutlinedTextField(manageValue,{manageValue=it.take(80)},
                    label={Text(hubLabel("新设备名称","Device name"))},singleLine=true)
                if(action=="network"){
                    Text(hubLabel("Android 只能使用系统设定的默认数据 SIM；选择监控卡不会切换系统默认卡。",
                        "Android can only use the system's default mobile data SIM. This choice does not change it."),
                        style=MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment=Alignment.CenterVertically,
                        modifier=Modifier.clickable{manageChannelIndex=-1}){
                        RadioButton(selected=manageChannelIndex==-1,onClick={manageChannelIndex=-1})
                        Text(hubLabel("关闭备用数据监控","Disable fallback"))
                    }
                    for(i in 0 until channels.length()){
                        val ch=channels.optJSONObject(i)?:continue
                        Row(verticalAlignment=Alignment.CenterVertically,
                            modifier=Modifier.clickable{manageChannelIndex=i}){
                            RadioButton(selected=manageChannelIndex==i,onClick={manageChannelIndex=i})
                            Text(ch.optString("alias").ifBlank{ch.optString("displayName")
                                .ifBlank{ch.optString("carrierName").ifBlank{"SIM ${i+1}"}}})
                        }
                    }
                }
                if(destructive){
                    Text(when(action){
                        "purge"->hubLabel("删除该设备在中继服务器保存的短信及共享池副本，不能撤销。设备本地原始短信不受此操作影响。",
                            "Deletes relay SMS and shared-pool copies; local node SMS remain.")
                        "revoke"->hubLabel("撤销云端设备授权，设备需要重新配对。","Re-enrollment is required after revoke.")
                        else->hubLabel("强制删除设备与云端记录，需重新配对。","Deletes the node and cloud records; re-enrollment required.")
                    },color=MaterialTheme.colorScheme.error,
                        style=MaterialTheme.typography.bodySmall)
                    if(action=="purge" || action=="delete")
                        OutlinedTextField(manageConfirm,{manageConfirm=it},
                            label={Text(if(action=="purge")"输入 PURGE" else "输入 DELETE")},
                            singleLine=true)
                }
                if(sensitive)OutlinedTextField(manageCredential,{manageCredential=it},
                    label={Text(if(secondFactorTotp)"管理员二次验证 TOTP" else "管理员令牌")},
                    singleLine=true,visualTransformation=PasswordVisualTransformation())
                if(manageError.isNotBlank())
                    Text(manageError,color=MaterialTheme.colorScheme.error,
                        style=MaterialTheme.typography.bodySmall)
            }},
            confirmButton={TextButton(
                enabled=!manageBusy && (action!="rename" || manageValue.trim().isNotEmpty()) &&
                    (!sensitive || manageCredential.isNotBlank()) &&
                    (action!="purge" || manageConfirm=="PURGE") &&
                    (action!="delete" || manageConfirm=="DELETE") &&
                    (action!="network" || manageChannelIndex==-1 ||
                        channels.optJSONObject(manageChannelIndex)!=null),
                onClick={
                    if(action=="network"){
                        queueDeviceAction(device,if(manageChannelIndex>=0)"network-enable" else "network-disable",
                            manageCredential,manageChannelIndex)
                        pendingManageAction=null;manageCredential=""
                    }else{
                        val api=client
                        if(api==null){manageError="管理终端授权不可用"}
                        else {
                            manageBusy=true;manageError=""
                            val value=manageValue.trim()
                            val credential=manageCredential
                            scope.launch{
                                try{
                                    withContext(Dispatchers.IO){
                                        if(sensitive)api.api("/api/v1/auth/elevate","POST",
                                            JSONObject().put(if(secondFactorTotp)"totp" else "adminToken",credential))
                                        val uri="/api/v1/devices/"+device.getString("id")
                                        when(action){
                                            "rename"->api.api(uri+"/manage","POST",JSONObject()
                                                .put("action","rename").put("name",value))
                                            "revoke"->api.api(uri+"/manage","POST",JSONObject()
                                                .put("action","revoke"))
                                            "purge"->api.api(uri+"/sms","DELETE")
                                            "delete"->api.api(uri,"DELETE")
                                            else->throw IllegalArgumentException("Unsupported device action")
                                        }
                                    }
                                    pendingManageAction=null
                                    if(destructive){
                                        // A removed or revoked source must disappear from
                                        // the decrypted inbox immediately, not on next restart.
                                        inbox.clear();lastEventSeq=0L;page=0;cursors=emptyList()
                                        if(action!="purge")selectedDevice=null
                                        load("latest")
                                    }else refreshDelta()
                                }catch(e:Exception){manageError=e.message?:"操作失败，请检查权限和设备状态"}
                                finally{manageBusy=false;manageCredential="";manageConfirm=""}
                            }
                        }
                    }
                }){Text(hubLabel("确认","Confirm"))}},
            dismissButton={TextButton(enabled=!manageBusy,onClick={
                pendingManageAction=null;manageCredential="";manageConfirm="";manageError=""
            }){Text(hubLabel("取消","Cancel"))}}
        )
    }
    pendingSensitiveTask?.let{pending->
        val (device,action)=pending
        AlertDialog(onDismissRequest={pendingSensitiveTask=null;sensitiveCredential=""},
            icon={HubIcon(R.drawable.ic_hub_shield,Modifier.size(28.dp),
                MaterialTheme.colorScheme.primary)},
            title={Text(when(action){
                "restore-history"->hubLabel("确认恢复本机短信历史","Confirm history recovery")
                "ota-install"->hubLabel("确认远程安装","Confirm remote installation")
                else->hubLabel("取消待处理更新","Cancel pending update")})},
            text={
                Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Text(when(action){
                        "restore-history"->hubLabel("将重置本机历史短信上传索引并从最新短信重新导入。已有短信按 ID 去重，后续可重复使用“同步更早短信”补齐；服务端明确清除的短信仍不可恢复。",
                            "Reset the local upload index and replay recent history. Older history can be requested in pages. Explicit relay purges remain irreversible.")
                        "ota-install"->hubLabel("仅请求安装最新签名版本；安卓系统仍可能要求在设备上确认。任务入队不等于更新成功。",
                            "Only a newer signed build is requested. Android may still require confirmation; queued is not installed.")
                        else->hubLabel("仅能取消尚未提交系统安装器的更新。","Cancellation is limited to updates not yet committed to Android.")},
                        style=MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(sensitiveCredential,{sensitiveCredential=it},
                        label={Text(if(secondFactorTotp)hubLabel("二次验证 TOTP","TOTP")
                            else hubLabel("管理员令牌","Admin token"))},
                        singleLine=true,visualTransformation=PasswordVisualTransformation(),
                        modifier=Modifier.fillMaxWidth())
                }
            },
            confirmButton={TextButton(enabled=sensitiveCredential.isNotBlank()&&!actionBusy,onClick={
                val auth=sensitiveCredential
                pendingSensitiveTask=null;sensitiveCredential=""
                queueDeviceAction(device,action,auth)
            }){Text(hubLabel("确认提交","Confirm"))}},
            dismissButton={TextButton(onClick={pendingSensitiveTask=null;sensitiveCredential=""}){
                Text(hubLabel("取消","Cancel"))
            }})
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
        val possible=devices.filter{ControllerNodePolicy.canSend(it)}
        val current=possible.getOrNull(deviceIdx)
        val channels=current?.let{ControllerNodePolicy.channels(it)}?:JSONArray()
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
                if(possible.isEmpty())Text(hubLabel("当前没有具备发送能力的在线节点。不接管模式默认只读，完全接管模式需取得发送权限。",
                        "No send-capable online node. Companion mode is read-only; default SMS mode requires permission."))
                else{
                    Text(hubLabel("核对发送节点和 SIM，系统不会因会话来源自动替换路由。",
                        "Verify node and SIM; the conversation does not automatically change routing."),
                        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("发送设备",style=MaterialTheme.typography.labelLarge)
                    possible.forEachIndexed{i,d->
                        FilterChip(selected=i==deviceIdx,onClick={composeDeviceIdx=i;composeChannelIdx=0},
                            label={Text(d.optString("name"))})
                    }
                    if(current!=null)Text(ControllerNodePolicy.sendModeLabel(current),
                        color=if(ControllerNodePolicy.isDeveloperSend(current))MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.bodySmall)
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
                        Button(enabled=!sending && (submittedDraft.isEmpty() || submittedDraft==currentDraft) && current!=null && ControllerNodePolicy.canSend(current) && recipient.isNotBlank() && message.isNotBlank(),
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
