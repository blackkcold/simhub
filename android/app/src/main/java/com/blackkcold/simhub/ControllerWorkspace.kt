package com.blackkcold.simhub

import android.net.Uri
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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
    var showReset by remember{mutableStateOf(false)}
    var resetCredential by remember{mutableStateOf("")}
    var selectedDevice by remember{mutableStateOf<JSONObject?>(null)}
    var actionError by remember{mutableStateOf("")}
    var serverVersion by remember{mutableStateOf("—")}
    var secondFactorTotp by remember(selected){mutableStateOf(true)}
    var lastCommandId by remember(selected){mutableStateOf("")}
    var lastCommandState by remember(selected){mutableStateOf("")}
    var lastCommandDevice by remember(selected){mutableStateOf("")}

    fun chooseOrigin(value:String){
        ControllerProfiles.select(context,value)
        selected=value;onSelectTab(0)
    }
    fun load(older:Boolean=false,newer:Boolean=false){
        val api=client?:return
        if(!api.hasGrant())return
        if(busy)return
        busy=true
        scope.launch {
            try{
                val data=withContext(Dispatchers.IO){
                    val d=api.api("/api/v1/devices")
                    val auth=api.api("/api/v1/auth/check")
                    val all=d.optJSONArray("devices")?:JSONArray()
                    val list=(0 until all.length()).mapNotNull{all.optJSONObject(it)}
                    val incremental=newer && lastEventSeq>0
                    val requested=if(newer)0 else if(older)page+1 else page
                    val cursor=if(requested>0)cursors.getOrNull(requested-1) else null
                    val suffix=if(cursor!=null)
                        "&beforeTime=${cursor.first}&beforeSeq=${cursor.second}" else ""
                    val events=if(incremental)
                        api.api("/api/v1/events?since=$lastEventSeq&limit=1000")
                        else api.api("/api/v1/events?order=occurred&limit=100"+suffix)
                    val rows=events.optJSONArray("events")?:JSONArray()
                    val newestSeq=(0 until rows.length()).maxOfOrNull{rows.optJSONObject(it)?.optLong("seq",0L)?:0L}?:0L
                    val vault=api.vault()
                    val messages=JSONArray()
                    var failure=0
                    try{
                        val byId=list.associateBy{it.optString("id")}
                        for(i in 0 until rows.length()){
                            val item=rows.optJSONObject(i)?:continue
                            if(!item.optString("kind").startsWith("sms."))continue
                            val node=byId[item.optString("deviceId")]
                            var plaintext=JSONObject()
                            var failed=false
                            try{
                                if(node==null)throw SecurityException("Unknown node")
                                plaintext=api.decryptEvent(item,node,vault)
                            }catch(_:Exception){failed=true;failure++}
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
                            // Preserve source identity for traceability; never assign a
                            // historical record to the currently inserted SIM.
                            val otp=plaintext.optJSONObject("otp")?.optString("value").orEmpty()
                            val thread=address.trim().replace(" ","").lowercase()
                            messages.put(JSONObject().put("id",item.optString("deviceId")+":"+item.optString("eventId"))
                                .put("thread",thread).put("sender",plaintext.optString("contactName").ifBlank{address})
                                .put("body",if(failed)"" else plaintext.optString("body"))
                                .put("timestamp",item.optLong("occurredAt")).put("inbound",inbound)
                                .put("device",node?.optString("name")?:"未知设备")
                                .put("sim",provenance).put("otp",otp).put("failed",failed))
                        }
                    }finally{vault.fill(0)}
                    val nextTime=events.optLong("nextBeforeTime",0)
                    val nextSeq=events.optLong("nextBeforeSeq",0)
                    val response=JSONObject().put("type","snapshot").put("v",1).put("messages",messages)
                        .put("unreadable",failure).put("hasMore",events.optBoolean("hasMore"))
                        .put("hasNewer",requested>0).put("incremental",incremental)
                    arrayOf(d,list,events,response,requested,nextTime,nextSeq,auth.optBoolean("totpRequired",true),newestSeq,incremental)
                }
                @Suppress("UNCHECKED_CAST")
                devices=data[1] as List<JSONObject>
                secondFactorTotp=data[7] as Boolean
                if(!(data[9] as Boolean)) unreadable=(data[3] as JSONObject).optInt("unreadable")
                else unreadable+=(data[3] as JSONObject).optInt("unreadable")
                lastEventSeq=maxOf(lastEventSeq,data[8] as Long)
                inbox.accept((data[3] as JSONObject).toString(),append=older || (data[9] as Boolean))
                val loaded=data[4] as Int
                val nextTime=data[5] as Long
                val nextSeq=data[6] as Long
                if(newer && !(data[9] as Boolean)){page=0;cursors=emptyList()}
                else if(older){page=loaded}
                if(!(data[9] as Boolean) && nextTime>0&&nextSeq>0){
                    cursors=cursors.take(page)+listOf(nextTime to nextSeq)
                }
                authorized=true;error=""
            }catch(e:Exception){
                error=e.message?:"无法连接管理中心"
                if(e is SecurityException){
                    authorized=false;inbox.clear();devices=emptyList()
                    lastCommandId="";lastCommandState="";lastEventSeq=0
                }
            }
            finally{busy=false}
        }
    }
    LaunchedEffect(selected,visible){
        if(visible && client?.hasGrant()==true){authorized=true;load(newer=true)}
    }
    LaunchedEffect(selected,visible,authorized){
        if(visible && authorized)while(true){
            delay(60000)
            if(!busy)load(newer=true)
        }
    }
    LaunchedEffect(pairing,selected,visible){
        if(!visible)return@LaunchedEffect
        val api=client?:return@LaunchedEffect
        while(pairing!=null && !api.hasGrant()){
            delay(2400)
            try{
                if(withContext(Dispatchers.IO){api.pollPairing()}){
                    authorized=true;pairing=null;load(newer=true);break
                }
            }catch(e:Exception){error=e.message?:"配对失败";pairing=null;break}
        }
    }
    LaunchedEffect(refreshTick){if(refreshTick>0 && visible && authorized)load(newer=true)}
    // Queue acceptance is not device execution. Poll the Relay's immutable ACK state.
    LaunchedEffect(selected,lastCommandId,visible){
        if(!visible || lastCommandId.isBlank())return@LaunchedEffect
        val api=client?:return@LaunchedEffect
        repeat(40){
            delay(3000)
            try {
                val statuses=withContext(Dispatchers.IO){
                    api.api("/api/v1/commands/recent?limit=50").optJSONArray("commands")
                }?:JSONArray()
                val found=(0 until statuses.length()).mapNotNull{statuses.optJSONObject(it)}
                    .firstOrNull{it.optString("commandId")==lastCommandId}
                if(found!=null){
                    val state=found.optString("state","queued")
                    lastCommandState=when(state){
                        "queued"->"等待设备连接并领取任务"
                        "dispatched"->"设备已领取任务，等待执行确认"
                        "submitted"->"已提交到系统短信服务"
                        "sent"->"系统已发送，等待运营商回执"
                        "delivered"->"运营商已确认送达"
                        "succeeded"->"任务执行成功"
                        "failed"->"任务执行失败："+found.optJSONObject("result")?.optString("reason").orEmpty()
                        "rejected"->"任务被设备拒绝"
                        "expired"->"任务已过期，没有执行"
                        else->"当前状态：$state"
                    }
                    if(state in setOf("delivered","succeeded","failed","rejected","expired"))
                        return@LaunchedEffect
                }
            }catch(e:Exception){
                lastCommandState="无法更新任务进度："+(e.message?:"网络不可用")
            }
        }
    }
    if(!visible)return
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
                            authorized=true;load(newer=true)
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
                            OutlinedButton(onClick={load(newer=true)},
                                modifier=Modifier.fillMaxWidth()) { Text(hubLabel("刷新加密数据","Refresh encrypted data")) }
                        }
                    }
                    if(lastCommandId.isNotBlank())item{
                        HubCard(Modifier.fillMaxWidth()){
                            HubSectionTitle("最近下发的指令")
                            Text(lastCommandState.ifBlank{"状态查询中"},style=MaterialTheme.typography.bodyMedium)
                            Text("设备：$lastCommandDevice · 命令："+lastCommandId.take(8),
                                style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
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
                        onRefresh={load(newer=true)},
                        onOlder={load(older=true)},
                        onNewer={load(newer=true)},
                        onOpenLogin={load(newer=true)},
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
                        Text("解除本地授权会清除该管理空间的本机身份密钥与加密 Vault。服务器端撤销请在网页管理后台完成。",
                            color=MaterialTheme.colorScheme.onSurfaceVariant,
                            style=MaterialTheme.typography.bodySmall)
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
        var recipient by remember{mutableStateOf("")}
        var message by remember{mutableStateOf("")}
        var deviceIdx by remember{mutableIntStateOf(0)}
        var channelIdx by remember{mutableIntStateOf(0)}
        val possible=devices.filter{d->
            val st=d.optJSONObject("state")?:JSONObject()
            val channels=st.optJSONArray("channels")
            d.optBoolean("online") && channels!=null && channels.length()>0 &&
                (st.optBoolean("smsSendAllowed") || d.optString("nodeType")=="modem")
        }
        val current=possible.getOrNull(deviceIdx)
        val channels=current?.optJSONObject("state")?.optJSONArray("channels")?:JSONArray()
        var stepUp by remember{mutableStateOf("")}
        AlertDialog(onDismissRequest={showWrite=false},title={Text(hubLabel("发送加密短信","Send encrypted SMS"))},
            text={Column(Modifier.heightIn(max=430.dp).verticalScroll(rememberScrollState()),
                verticalArrangement=Arrangement.spacedBy(8.dp)){
                if(possible.isEmpty())Text("当前没有允许发送短信的在线 SIM 节点。")
                else{
                    Text("发送设备",style=MaterialTheme.typography.labelLarge)
                    possible.forEachIndexed{i,d->
                        FilterChip(selected=i==deviceIdx,onClick={deviceIdx=i;channelIdx=0},
                            label={Text(d.optString("name"))})
                    }
                    Text("来源 SIM",style=MaterialTheme.typography.labelLarge)
                    for(i in 0 until channels.length()){
                        val ch=channels.optJSONObject(i)?:continue
                        FilterChip(selected=i==channelIdx,onClick={channelIdx=i},
                            label={Text(ch.optString("displayName").ifBlank{ch.optString("id")})})
                    }
                    OutlinedTextField(recipient,{recipient=it},label={Text(hubLabel("收件人号码","Recipient number"))})
                    OutlinedTextField(message,{message=it},label={Text(hubLabel("短信内容","Message"))},minLines=3)
                    OutlinedTextField(stepUp,{stepUp=it},label={Text(if(secondFactorTotp)"二次验证 TOTP" else "管理员令牌（敏感操作）")},
                        singleLine=true,visualTransformation=PasswordVisualTransformation())
                }
            }},confirmButton={TextButton(enabled=current!=null && recipient.isNotBlank() && message.isNotBlank(),
                onClick={
                    scope.launch{
                        try {
                            val queuedId=withContext(Dispatchers.IO){
                                // Server always independently enforces second factor for sms.send.
                                if(stepUp.isNotEmpty())client!!.api("/api/v1/auth/elevate","POST",
                                    JSONObject().put(if(secondFactorTotp)"totp" else "adminToken",stepUp))
                                val channel=channels.optJSONObject(channelIdx)?:throw IllegalStateException("SIM 未选择")
                                val id=UUID.randomUUID().toString()
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
                        }catch(e:Exception){error=e.message?:"发送失败";showWrite=false}
                    }
                }){Text(hubLabel("确认发送","Confirm send"))}},dismissButton={TextButton(onClick={showWrite=false}){Text(hubLabel("取消","Cancel"))}})
    }
}
