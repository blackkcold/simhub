package com.blackkcold.simhub

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal object ControllerSendFacts {
    fun summary(row:JSONObject):String {
        return when(row.optString("error")){
            "receipt_unavailable"->"曾确认接收；目前回执不可用，仅查询，禁止重发"
            "original_request_expired"->"原请求已过期；请核对短信结果，不会重新发送"
            else->if(row.optBoolean("observed"))ControllerCommandProgress.describe(
                JSONObject().put("type","sms.send").put("state",row.optString("state"))
                    .put("result",row.optJSONObject("result")?:JSONObject()))
                else when(row.optString("phase")){
                    "prepared"->"已加密保存，尚未提交"
                    "submitting","uncertain"->"接收回执待确认；先查询，再按原请求重试"
                    else->"等待状态确认"
                }
        }
    }
}

@Composable
internal fun ControllerSendCenter(api:ControllerSecureClient,tick:Int,onOpen:(String)->Unit){
    val coordinator=remember(api){ControllerSendCoordinator.forClient(api)}
    val scope=rememberCoroutineScope()
    var rows by remember(api){mutableStateOf<List<JSONObject>>(emptyList())}
    var error by remember(api){mutableStateOf("")}
    var expanded by remember{mutableStateOf(false)}
    var refreshing by remember{mutableStateOf("")}
    LaunchedEffect(api,tick){
        try{rows=withContext(Dispatchers.IO){coordinator.list()};error=""}
        catch(_:Exception){error="本机加密发送记录暂不可读，请保留授权并重试"}
    }
    HubCard(Modifier.fillMaxWidth()){
        Text(hubLabel("发送中心","Send center"),fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.titleMedium)
        Text(hubLabel("草稿、固定发送路由及回执在本机加密保存，应用重启后可继续核验。",
            "Encrypted drafts, fixed sending routes and receipts survive app restarts."),style=MaterialTheme.typography.bodySmall)
        if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
        if(rows.isEmpty())Text(hubLabel("暂无本机发送任务","No local send intents"))
        rows.take(if(expanded)100 else 5).forEach{row->
            val id=row.optString("id");val draft=ControllerSendDraft.from(row.optJSONObject("draft"))
            HorizontalDivider()
            Text(row.optString("deviceName")+" · "+draft.recipient,style=MaterialTheme.typography.labelLarge)
            Text(ControllerSendFacts.summary(row),style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                TextButton(onClick={onOpen(id)}){Text(hubLabel("查看任务","Open intent"))}
                TextButton(enabled=refreshing.isEmpty(),onClick={
                    refreshing=id;scope.launch{
                        try{
                            withContext(Dispatchers.IO){coordinator.refresh(id)}
                            rows=withContext(Dispatchers.IO){coordinator.list()};error=""
                        }catch(_:Exception){error="状态查询失败；原任务已保留，未重发"}
                        finally{refreshing=""}
                    }
                }){Text(hubLabel("仅查询状态","Check status only"))}
            }
        }
        if(rows.size>5)TextButton(onClick={expanded=!expanded}){
            Text(if(expanded)hubLabel("收起","Show fewer") else hubLabel("全部 ${rows.size} 条任务","All ${rows.size} intents"))
        }
    }
}

@Composable
internal fun ControllerSendDialog(api:ControllerSecureClient,devices:List<JSONObject>,totp:Boolean,
                                 resumeId:String?,reply:ControllerSendDraft?,tick:Int,
                                 onDismiss:()->Unit,onChanged:(JSONObject?)->Unit){
    val coordinator=remember(api){ControllerSendCoordinator.forClient(api)}
    val scope=rememberCoroutineScope()
    var draft by remember(api){mutableStateOf(ControllerSendDraft())}
    var intent by remember(api){mutableStateOf<JSONObject?>(null)}
    var loaded by remember(api){mutableStateOf(false)}
    var busy by remember{mutableStateOf(false)}
    var saved by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    var credential by remember{mutableStateOf("")}
    var confirmNew by remember{mutableStateOf(false)}
    var firstLoad by remember(api){mutableStateOf(true)}
    LaunchedEffect(api,resumeId,tick){
        if(busy)return@LaunchedEffect
        try{
            val snapshot=withContext(Dispatchers.IO){
                if(firstLoad&&resumeId!=null)coordinator.open(resumeId)
                if(firstLoad&&resumeId==null&&reply!=null&&coordinator.activeId().isEmpty()&&coordinator.draft().message.isBlank())
                    coordinator.startDraft(reply)
                val active=coordinator.activeId()
                coordinator.draft() to if(active.isBlank())null else coordinator.find(active)
            }
            draft=snapshot.first;intent=snapshot.second;loaded=true;saved=true;firstLoad=false
        }catch(_:Exception){error="无法恢复本机加密任务；请重试，勿重复发送"}
    }
    LaunchedEffect(draft,loaded){
        if(!loaded||intent!=null)return@LaunchedEffect
        saved=false;delay(250)
        try{withContext(Dispatchers.IO){coordinator.saveDraft(draft)};saved=true}
        catch(_:Exception){error="草稿尚未保存，发送前将再次核验本机存储"}
    }
    val frozen=intent!=null
    val route=ControllerSendTarget.resolve(devices,draft)
    val device=devices.firstOrNull{it.optString("id")==draft.deviceId}
    val channels=device?.let{ControllerNodePolicy.channels(it)}
    val peerRoute=if(intent!=null)intent!!.getJSONObject("request").optString("transport")=="peer"
        else device!=null&&runCatching{ControllerPeerSendTransport.linkedSender(api,draft.deviceId)!=null}.getOrDefault(false)
    fun refresh(){
        val id=intent?.optString("id")?:return
        busy=true;credential=""
        scope.launch{
            try{intent=withContext(Dispatchers.IO){coordinator.refresh(id)};error="";onChanged(intent)}
            catch(_:Exception){error="状态暂不可确认，原任务已保留且未重发"}
            finally{busy=false}
        }
    }
    fun close(){
        credential=""
        if(frozen||!loaded){onDismiss();return}
        val finalDraft=draft;busy=true
        scope.launch{
            try{withContext(Dispatchers.IO){coordinator.saveDraft(finalDraft)};saved=true;onDismiss()}
            catch(_:Exception){error="草稿未保存，请重试关闭"}
            finally{busy=false}
        }
    }
    Dialog(onDismissRequest={close()},properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){
            Column(Modifier.fillMaxSize().imePadding().padding(18.dp)){
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    Text(hubLabel("发送加密短信","Send encrypted SMS"),Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                    TextButton(onClick={close()}){Text(hubLabel("关闭","Close"))}
                }
                HorizontalDivider()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
                    if(!loaded)LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
                    if(intent!=null){
                        Text(ControllerSendFacts.summary(intent!!),fontWeight=FontWeight.SemiBold)
                        Text(hubLabel("已固定收件人、节点、SIM 版本和加密请求；修改内容请单独新建任务。",
                            "Recipient, node, SIM revision and encrypted request are fixed for this intent."),style=MaterialTheme.typography.bodySmall)
                    }else Text(if(saved)hubLabel("草稿已在本机加密保存","Draft encrypted and saved locally")
                        else hubLabel("正在加密保存草稿…","Saving encrypted draft…"),style=MaterialTheme.typography.bodySmall)
                    if(reply!=null&&reply.deviceId!=draft.deviceId)
                        Text(hubLabel("已有草稿或发送任务已恢复。选择“新建回复”后才会切换至会话来源。",
                            "Existing work was restored. Start a new reply to use the conversation source."),style=MaterialTheme.typography.bodySmall)
                    if(draft.deviceId.isNotEmpty()&&route==null)
                        Text(hubLabel("所选节点已离线、权限受限或 SIM 已变化；请核对后重新选择，系统未切换发送路由。",
                            "The selected route is unavailable or changed. No alternate SIM has been selected."),color=MaterialTheme.colorScheme.error)
                    Text(hubLabel("发送节点","Sending node"),style=MaterialTheme.typography.labelLarge)
                    devices.filter{ControllerNodePolicy.canSend(it)||it.optString("id")==draft.deviceId}.forEach{node->
                        FilterChip(selected=node.optString("id")==draft.deviceId,enabled=!frozen&&!busy&&ControllerNodePolicy.canSend(node),
                            onClick={draft=draft.copy(deviceId=node.optString("id"),channelId="",channelRevision=0)},
                            label={Text(node.optString("name"))})
                    }
                    if(device!=null)Text(ControllerNodePolicy.sendModeLabel(device),style=MaterialTheme.typography.bodySmall)
                    Text(hubLabel("发送 SIM","Sending SIM"),style=MaterialTheme.typography.labelLarge)
                    if(channels!=null)for(i in 0 until channels.length()){
                        val channel=channels.optJSONObject(i)?:continue
                        val id=ControllerSendTarget.channelId(channel);val revision=ControllerSendTarget.revision(channel)
                        FilterChip(selected=id==draft.channelId&&revision==draft.channelRevision,enabled=!frozen&&!busy,
                            onClick={draft=draft.copy(channelId=id,channelRevision=revision)},
                            label={Text(channel.optString("alias").ifBlank{channel.optString("displayName").ifBlank{id}})})
                    }
                    OutlinedTextField(draft.recipient,{draft=draft.copy(recipient=it.take(40))},enabled=loaded&&!frozen&&!busy,
                        modifier=Modifier.fillMaxWidth(),label={Text(hubLabel("收件人号码","Recipient number"))})
                    OutlinedTextField(draft.message,{draft=draft.copy(message=it.take(4000))},enabled=loaded&&!frozen&&!busy,
                        modifier=Modifier.fillMaxWidth(),label={Text(hubLabel("短信内容","Message"))},minLines=3)
                    if(peerRoute)Text(hubLabel("使用独立 P2P 发送授权。此任务固定同一对端、请求编号与截止时间；传输切换不会创建新任务。",
                        "Uses the independent P2P send grant. Peer, request ID and expiry remain fixed across transport changes."),
                        style=MaterialTheme.typography.bodySmall)
                    if(!peerRoute&&intent?.optBoolean("observed")!=true)OutlinedTextField(credential,{credential=it},enabled=!busy,
                        modifier=Modifier.fillMaxWidth(),singleLine=true,visualTransformation=PasswordVisualTransformation(),
                        label={Text(if(totp)hubLabel("二次验证 TOTP","TOTP") else hubLabel("管理员令牌","Admin token"))})
                    if(intent!=null)OutlinedButton(onClick={refresh()},enabled=!busy){Text(hubLabel("仅查询原任务状态","Check original intent status"))}
                    TextButton(enabled=loaded&&!busy,onClick={confirmNew=true}){
                        Text(if(reply!=null)hubLabel("新建回复","Start a new reply") else hubLabel("新建短信","Start a new message"))
                    }
                }
                val canSubmit=loaded&&!busy&&route!=null&&draft.recipient.isNotBlank()&&draft.message.isNotBlank()&&
                    intent?.optBoolean("observed")!=true&&(intent==null||intent!!.optLong("expiresAt")>System.currentTimeMillis()/1000)
                Button(enabled=canSubmit,onClick={
                    val auth=credential;credential="";busy=true;error=""
                    val frozenDraft=draft;val knownId=intent?.optString("id")
                    scope.launch{
                        try{
                            val prepared=if(knownId==null)withContext(Dispatchers.IO){coordinator.prepare(devices,frozenDraft)}
                                else intent!!
                            intent=prepared // Freeze UI before the first network side effect.
                            val id=prepared.getString("id")
                            intent=withContext(Dispatchers.IO){coordinator.submit(id,auth,totp)}
                            onChanged(intent)
                        }catch(failure:Exception){error=failure.message?:"发送任务已保留，请先核验状态";onChanged(intent)}
                        finally{busy=false}
                    }
                },modifier=Modifier.fillMaxWidth()){
                    Text(if(frozen)hubLabel("核验后按原请求重试","Check then retry original request") else hubLabel("确认发送","Confirm send"))
                }
            }
        }
    }
    if(confirmNew)AlertDialog(onDismissRequest={confirmNew=false},title={Text(hubLabel("新建独立草稿","Start a separate draft"))},
        text={Text(hubLabel("当前任务记录会保留。若之前的发送结果尚未确认，请先核对短信，避免重复发送。新草稿不会复制原短信正文。",
            "The current intent remains in Send center. Check any unknown outcome before sending again. The new draft starts with an empty message."))},
        confirmButton={TextButton(onClick={
            confirmNew=false;busy=true;credential=""
            scope.launch{
                try{
                    val next=reply?:ControllerSendDraft()
                    withContext(Dispatchers.IO){coordinator.startDraft(next)}
                    draft=next;intent=null;error="";saved=true;onChanged(null)
                }catch(_:Exception){error="新草稿未保存，原任务已保留"}
                finally{busy=false}
            }
        }){Text(hubLabel("新建草稿","Create draft"))}},
        dismissButton={TextButton(onClick={confirmNew=false}){Text(hubLabel("取消","Cancel"))}})
}
