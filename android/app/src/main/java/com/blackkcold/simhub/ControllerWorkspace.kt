package com.blackkcold.simhub

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
                        onSwitchNode:()->Unit,modifier:Modifier=Modifier){
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var profiles by remember(revision){mutableStateOf(ControllerProfiles.list(context))}
    var selected by remember(revision){mutableStateOf(ControllerProfiles.selected(context))}
    val client=remember(selected){selected?.let{ControllerSecureClient(context.applicationContext,it)}}
    val inbox=remember(selected){ControllerInboxState()}
    var tab by remember(selected){mutableIntStateOf(0)}
    var menu by remember{mutableStateOf(false)}
    var add by remember{mutableStateOf(false)}
    var addUrl by remember{mutableStateOf("")}
    var pairing by remember(selected){mutableStateOf<ControllerSecureClient.PairStatus?>(null)}
    var error by remember(selected){mutableStateOf("")}
    var busy by remember(selected){mutableStateOf(false)}
    var authorized by remember(selected){mutableStateOf(client?.hasGrant()==true)}
    var devices by remember(selected){mutableStateOf<List<JSONObject>>(emptyList())}
    var page by remember(selected){mutableIntStateOf(0)}
    var cursors by remember(selected){mutableStateOf(listOf<Pair<Long,Long>>())}
    var unreadable by remember(selected){mutableIntStateOf(0)}
    var showWrite by remember{mutableStateOf(false)}
    var showReset by remember{mutableStateOf(false)}
    var selectedDevice by remember{mutableStateOf<JSONObject?>(null)}
    var actionError by remember{mutableStateOf("")}
    var serverVersion by remember{mutableStateOf("—")}

    fun chooseOrigin(value:String){
        ControllerProfiles.select(context,value)
        selected=value;tab=0
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
                    val all=d.optJSONArray("devices")?:JSONArray()
                    val list=(0 until all.length()).mapNotNull{all.optJSONObject(it)}
                    val requested=if(newer)0 else if(older)page+1 else page
                    val cursor=if(requested>0)cursors.getOrNull(requested-1) else null
                    val suffix=if(cursor!=null)
                        "&beforeTime=${cursor.first}&beforeSeq=${cursor.second}" else ""
                    val events=api.api("/api/v1/events?order=occurred&limit=100"+suffix)
                    val rows=events.optJSONArray("events")?:JSONArray()
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
                            val inbound=item.optString("kind")=="sms.received"
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
                        .put("hasNewer",requested>0)
                    arrayOf(d,list,events,response,requested,nextTime,nextSeq)
                }
                @Suppress("UNCHECKED_CAST")
                devices=data[1] as List<JSONObject>
                unreadable=(data[3] as JSONObject).optInt("unreadable")
                inbox.accept((data[3] as JSONObject).toString())
                val loaded=data[4] as Int
                val nextTime=data[5] as Long
                val nextSeq=data[6] as Long
                if(newer){page=0;cursors=emptyList()}
                else if(older){page=loaded}
                if(nextTime>0&&nextSeq>0){
                    cursors=cursors.take(page)+listOf(nextTime to nextSeq)
                }
                authorized=true;error=""
            }catch(e:Exception){error=e.message?:"无法连接管理中心"}
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
    BackHandler(visible && tab!=0){tab=0}
    if(!visible)return
    Column(modifier.fillMaxSize()){
        Row(Modifier.fillMaxWidth().padding(start=16.dp,end=8.dp,top=4.dp,bottom=4.dp),
            verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){
                Text(when(tab){1->"设备";2->"设置";else->"短信"},
                    style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
                Text(selected?.let{Uri.parse(it).host}?:"尚未连接管理中心",
                    style=MaterialTheme.typography.labelSmall,maxLines=1,
                    overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(authorized)IconButton(onClick={load(newer=true)}){
                HubIcon(R.drawable.ic_hub_sync,Modifier.size(22.dp),description="刷新")
            }
            Box{
                IconButton(onClick={menu=true}){Text("⋮",style=MaterialTheme.typography.titleLarge)}
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}){
                    DropdownMenuItem(text={Text("切换至 SIM 节点")},onClick={menu=false;onSwitchNode()})
                    profiles.forEach{origin->
                        DropdownMenuItem(text={Text(Uri.parse(origin).host?:origin)},
                            onClick={menu=false;chooseOrigin(origin)})
                    }
                    DropdownMenuItem(text={Text("添加管理空间")},onClick={menu=false;add=true})
                }
            }
        }
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
        if(selected==null){
            Box(Modifier.weight(1f),contentAlignment=Alignment.Center){
                Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally){
                    HubIcon(R.drawable.ic_hub_shield,Modifier.size(44.dp))
                    Spacer(Modifier.height(18.dp))
                    Text("连接管理中心",style=MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick=onScan){Text("扫描配置二维码")}
                    TextButton(onClick={add=true}){Text("输入管理地址")}
                }
            }
        }else if(!authorized){
            Box(Modifier.weight(1f),contentAlignment=Alignment.Center){
                Column(Modifier.padding(22.dp).widthIn(max=460.dp),
                    verticalArrangement=Arrangement.spacedBy(14.dp)){
                    HubIcon(R.drawable.ic_hub_shield,Modifier.size(40.dp))
                    Text("安全授权管理终端",style=MaterialTheme.typography.titleLarge)
                    Text("首次需要在已解锁的网页管理后台确认手机指纹并授权。以后将使用本机密钥直接连接，不需要 Vault 密码。",
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(pairing!=null){
                        Text("配对码  ${pairing!!.code}",
                            style=MaterialTheme.typography.headlineMedium)
                        Text("在网页：连接管理手机 → 待授权管理终端，核对配对码后确认。",
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }else{
                        Button(enabled=!busy,onClick={
                            busy=true;scope.launch {
                                try{pairing=withContext(Dispatchers.IO){
                                    client!!.beginPairing(android.os.Build.MANUFACTURER+" "+android.os.Build.MODEL)
                                };error=""}catch(e:Exception){error=e.message?:"无法发起配对"}
                                finally{busy=false}
                            }
                        },modifier=Modifier.fillMaxWidth()){Text("申请管理终端授权")}
                    }
                    if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
                }
            }
        }else{
            if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error,
                modifier=Modifier.padding(horizontal=16.dp,vertical=6.dp))
            if(tab==0){
                Box(Modifier.weight(1f)){
                    NativeControllerInbox(inbox,
                        onRefresh={load(newer=true)},
                        onOlder={load(older=true)},
                        onNewer={load(newer=true)},
                        onOpenLogin={load(newer=true)},
                        onWrite={showWrite=true})
                    if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }else if(tab==1){
                LazyColumn(Modifier.weight(1f),
                    contentPadding=PaddingValues(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                    items(devices,key={it.optString("id")}){d->
                        ElevatedCard(Modifier.fillMaxWidth()){
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
                                TextButton(onClick={selectedDevice=d}){Text("设备详情与操作")}
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
                        Text("安全与权限",style=MaterialTheme.typography.titleMedium)
                        Text("解除本地授权会清除该管理空间的本机身份密钥与加密 Vault。服务器端撤销请在网页管理后台完成。",
                            color=MaterialTheme.colorScheme.onSurfaceVariant,
                            style=MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick={showReset=true}){Text("清除此手机的管理授权")}
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
            NavigationBar(containerColor=MaterialTheme.colorScheme.surface){
                listOf(Triple(0,R.drawable.ic_hub_messages,"短信"),
                    Triple(1,R.drawable.ic_hub_sim,"设备"),
                    Triple(2,R.drawable.ic_hub_settings,"设置")).forEach{(index,icon,label)->
                    NavigationBarItem(selected=tab==index,onClick={tab=index},
                        icon={HubIcon(icon,Modifier.size(24.dp))},label={Text(label)},alwaysShowLabel=true)
                }
            }
        }
    }
    if(add)AlertDialog(onDismissRequest={add=false},title={Text("添加管理空间")},
        text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
            OutlinedTextField(addUrl,{addUrl=it},label={Text("HTTPS 管理地址")},
                singleLine=true,modifier=Modifier.fillMaxWidth())
            TextButton(onClick={add=false;onScan()}){Text("扫描配置二维码")}
        }},confirmButton={TextButton(onClick={
            val origin=ControllerProfiles.add(context,addUrl)
            if(origin==null)error="请输入合法的 HTTPS 管理地址"
            else{profiles=ControllerProfiles.list(context);chooseOrigin(origin);addUrl="";add=false}
        }){Text("添加")}},dismissButton={TextButton(onClick={add=false}){Text("取消")}})
    if(showReset)AlertDialog(onDismissRequest={showReset=false},
        title={Text("清除本机授权？")},
        text={Text("删除本机授权密钥与缓存。服务器仍保留该终端记录，建议同时在网页撤销。不会影响 SIM 节点的本地短信。")},
        confirmButton={TextButton(onClick={
            client?.localReset();authorized=false;inbox.clear();devices=emptyList();showReset=false
        }){Text("清除")}},
        dismissButton={TextButton(onClick={showReset=false}){Text("取消")}})
    selectedDevice?.let{d->
        AlertDialog(onDismissRequest={selectedDevice=null},
            title={Text(d.optString("name","设备详情"))},
            text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                Text("设备 ID：${d.optString("id")}")
                Text("版本：${d.optString("appVersion")}")
                Text("连接：${if(d.optBoolean("online"))"在线" else "离线"}")
                Text("最近同步：${d.optLong("lastSeenAt")}")
                Text("远程同步使用原有端到端加密指令，操作异步排队并等待设备 ACK。",
                    style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(actionError.isNotEmpty())Text(actionError,color=MaterialTheme.colorScheme.error)
                Button(onClick={
                    scope.launch{
                        try{
                            withContext(Dispatchers.IO){
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
                            }
                            actionError="同步任务已入队，等待设备执行"
                        }catch(e:Exception){actionError=e.message?:"指令失败"}
                    }
                }){Text("下发同步最近短信")}
            }},
            confirmButton={TextButton(onClick={selectedDevice=null;actionError=""}){Text("关闭")}})
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
        AlertDialog(onDismissRequest={showWrite=false},title={Text("发送加密短信")},
            text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
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
                    OutlinedTextField(recipient,{recipient=it},label={Text("收件人号码")})
                    OutlinedTextField(message,{message=it},label={Text("短信内容")},minLines=3)
                    OutlinedTextField(stepUp,{stepUp=it},label={Text("二次验证 OTP（敏感操作）")},
                        singleLine=true)
                }
            }},confirmButton={TextButton(enabled=current!=null && recipient.isNotBlank() && message.isNotBlank(),
                onClick={
                    scope.launch{
                        try {
                            withContext(Dispatchers.IO){
                                // Server always independently enforces second factor for sms.send.
                                if(stepUp.isNotEmpty())client!!.api("/api/v1/auth/elevate","POST",
                                    JSONObject().put("totp",stepUp))
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
                            }
                            showWrite=false;error="已提交加密发送任务，等待节点 ACK"
                        }catch(e:Exception){error=e.message?:"发送失败";showWrite=false}
                    }
                }){Text("确认发送")}},dismissButton={TextButton(onClick={showWrite=false}){Text("取消")}})
    }
}
