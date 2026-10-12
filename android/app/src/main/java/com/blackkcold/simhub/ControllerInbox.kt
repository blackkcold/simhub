package com.blackkcold.simhub

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/**
 * Native presentation of controller-decrypted SMS; never persists SMS or Vault secrets.
 * Payloads arrive exclusively from a verified HTTPS main frame via WebMessageListener.
 */
data class ControllerSms(
    val id:String,val thread:String,val sender:String,val body:String,val timestamp:Long,
    val inbound:Boolean,val device:String,val sim:String,val sourceId:String,val otp:String,val failed:Boolean
)
class ControllerInboxState {
    val messages=mutableStateListOf<ControllerSms>()
    var unlocked by mutableStateOf(false)
        private set
    var hasMore by mutableStateOf(false)
        private set
    var hasNewer by mutableStateOf(false)
        private set
    var warning by mutableStateOf("")
        private set
    fun clear() {
        messages.clear();unlocked=false;hasMore=false;hasNewer=false;warning=""
    }
    /** Returns false on *any* rejected payload. Callers must never advance an
     * event cursor until this method has accepted the complete batch. */
    @JvmOverloads fun accept(raw:String, append:Boolean=false, preferOlder:Boolean=false):Boolean {
        if(raw.length>450_000)return false
        try {
            val obj=JSONObject(raw)
            when(obj.optString("type")){
                "locked" -> { clear();return true }
                "snapshot" -> {
                    if(obj.optInt("v")!=1)return false
                    val rows=obj.optJSONArray("messages")?:return false
                    if(rows.length()>260)return false
                    val fresh=ArrayList<ControllerSms>(rows.length())
                    for(i in 0 until rows.length()){
                        val row=rows.optJSONObject(i)?:return false
                        fun str(name:String,max:Int)=row.optString(name,"").take(max)
                        val eventId=str("id",180)
                        if(eventId.isBlank())return false
                        val threadId=str("thread",160).ifBlank{eventId}
                        fresh.add(ControllerSms(
                            eventId,threadId,str("sender",100),
                            str("body",4000),row.optLong("timestamp",0),
                            row.optBoolean("inbound",true),str("device",100),
                            str("sim",90),str("sourceId",160),str("otp",20),row.optBoolean("failed",false)
                        ))
                    }
                    // Stable IDs keep loaded conversation history across refreshes and
                    // older-page loads; old pages never replace the visible conversation.
                    val merged=LinkedHashMap<String,ControllerSms>()
                    if(append && unlocked)for(item in messages)merged[item.id]=item
                    for(item in fresh)if(item.id.isNotEmpty())merged[item.id]=item
                    val sorted=merged.values.sortedWith(compareByDescending<ControllerSms>{it.timestamp}.thenByDescending{it.id})
                    // When reading deep history, evict newer entries rather than the
                    // older page the user explicitly requested. Latest navigation
                    // restores the recent window without an unbounded SMS cache.
                    val ordered=if(preferOlder)sorted.takeLast(1200) else sorted.take(1200)
                    messages.clear();messages.addAll(ordered)
                    unlocked=true
                    if(!obj.optBoolean("incremental")){
                        hasMore=obj.optBoolean("hasMore")
                        hasNewer=obj.optBoolean("hasNewer")
                    }
                    if(obj.optInt("unreadable")>0)
                        warning="部分短信无法解密，请检查历史 Node Key 或 Vault 密钥"
                    else if(!obj.optBoolean("incremental"))warning=""
                    return true
                }
            }
        }catch(_:Exception){ /* Malformed page data is ignored, not logged. */ }
        return false
    }
}

@Composable
fun NativeControllerInbox(state:ControllerInboxState,onRefresh:()->Unit,
                          onOlder:()->Unit,onNewer:()->Unit,onOpenLogin:()->Unit,onWrite:()->Unit) {
    val context=LocalContext.current
    var query by remember { mutableStateOf("") }
    var otpOnly by remember { mutableStateOf(false) }
    var sourceFilter by remember { mutableStateOf("") }
    var activeThread by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled=activeThread!=null){ activeThread=null }
    val filtered=state.messages.filter { m ->
        (!otpOnly||m.otp.isNotBlank()) &&
        (sourceFilter.isEmpty() || sourceFilter==(m.sourceId.ifBlank{m.device+"|"+m.sim})) &&
        (query.isBlank() || listOf(m.sender,m.body,m.device,m.sim).any{it.contains(query,true)})
    }
    val sources=state.messages.distinctBy{it.sourceId.ifBlank{it.device+"|"+it.sim}}.map{(it.sourceId.ifBlank{it.device+"|"+it.sim}) to (it.device+" · "+it.sim)}
    val threads=filtered.groupBy{it.thread}.values.mapNotNull{items->
        items.maxByOrNull{it.timestamp}?.let{latest->latest to items.size}
    }.sortedByDescending{it.first.timestamp}
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)){
        if(!state.unlocked) {
            Box(Modifier.fillMaxSize().padding(22.dp),contentAlignment=Alignment.Center){
                HubCard(Modifier.fillMaxWidth().widthIn(max=520.dp)){
                    HubIcon(R.drawable.ic_hub_shield,Modifier.size(40.dp),
                        MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text("请先登录并解锁 Vault",style=MaterialTheme.typography.titleLarge)
                    Text("管理端短信会在当前手机本地解密后呈现。未授权时不会读取或缓存短信。",
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick=onOpenLogin,modifier=Modifier.fillMaxWidth()){Text(hubLabel("登录 / 解锁管理空间","Sign in / unlock"))}
                }
            }
            return@Column
        }
        Row(Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)){
                Text(if(activeThread==null)"管理短信" else "短信会话",
                    style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
                Text("${state.messages.count{!it.failed}} 条已解密 · ${state.messages.count{it.failed}} 条解密失败 · 不持久保存于本机",
                    style=MaterialTheme.typography.labelSmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick=onRefresh){Text(hubLabel("刷新","Refresh"))}
            if(activeThread==null)TextButton(onClick=onWrite){Text(hubLabel("发送","Send"))}
        }
        if(state.warning.isNotBlank()){
            Text(state.warning,modifier=Modifier.padding(horizontal=16.dp,vertical=7.dp),
                color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal=16.dp,vertical=4.dp),
            horizontalArrangement=Arrangement.spacedBy(8.dp)){
            FilterChip(selected=sourceFilter.isEmpty(),onClick={sourceFilter=""},label={Text(hubLabel("全部来源","All sources"))})
            sources.forEach{(key,label)->
                FilterChip(selected=sourceFilter==key,onClick={sourceFilter=key;activeThread=null},
                    label={Text(label,maxLines=1)})
            }
        }
        val key=activeThread
        if(key!=null){
            // Keep the same source and OTP/search constraints in the detail view.
            // Do not silently reveal messages from a different SIM or controller.
            val conversation=filtered.filter{it.thread==key}.sortedBy{it.timestamp}
            Row(Modifier.padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){
                TextButton(onClick={activeThread=null}){Text("‹ 返回")}
                Text(conversation.lastOrNull()?.sender.orEmpty(),fontWeight=FontWeight.SemiBold)
            }
            if(conversation.isEmpty())LaunchedEffect(key){activeThread=null}
            LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),
                verticalArrangement=Arrangement.spacedBy(10.dp)){
                items(conversation,key={it.id}){sms->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=if(sms.inbound)
                        Arrangement.Start else Arrangement.End){
                        Column(Modifier.fillMaxWidth(.88f)
                            .background(if(sms.inbound)MaterialTheme.colorScheme.surface
                                else MaterialTheme.colorScheme.primaryContainer,
                                RoundedCornerShape(18.dp)).padding(14.dp)){
                            Text(if(sms.failed)"[无法解密]" else sms.body,
                                style=MaterialTheme.typography.bodyMedium)
                            if(sms.inbound && sms.otp.isNotBlank()){
                                TextButton(onClick={
                                    val clip=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clipData=ClipData.newPlainText("OTP",sms.otp)
                                    if(android.os.Build.VERSION.SDK_INT>=33){
                                        clipData.description.extras=android.os.PersistableBundle().apply{
                                            putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE,true)
                                        }
                                    }
                                    clip.setPrimaryClip(clipData)
                                }){Text("复制验证码 · "+sms.otp)}
                            }
                            Text("${sms.device} · ${sms.sim} · "+
                                DateFormat.format("MM-dd HH:mm",sms.timestamp*1000).toString(),
                                style=MaterialTheme.typography.labelSmall,
                                color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if(state.hasNewer)item{
                    TextButton(onClick=onNewer,modifier=Modifier.fillMaxWidth()){
                        Text(hubLabel("查看较新短信","View newer messages"))
                    }
                }
                if(state.hasMore)item{
                    TextButton(onClick=onOlder,modifier=Modifier.fillMaxWidth()){Text(hubLabel("加载更早短信","Load earlier messages"))}
                }
            }
        }else{
            OutlinedTextField(query,{query=it},modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp),
                label={Text("搜索已加载短信、号码与来源")},singleLine=true)
            Row(Modifier.padding(horizontal=16.dp,vertical=7.dp),
                horizontalArrangement=Arrangement.spacedBy(8.dp)){
                FilterChip(selected=!otpOnly,onClick={otpOnly=false},label={Text(hubLabel("全部短信","All messages"))})
                FilterChip(selected=otpOnly,onClick={otpOnly=true},label={Text(hubLabel("验证码","Verification codes"))})
            }
            if(threads.isEmpty()){
                Box(Modifier.weight(1f),contentAlignment=Alignment.Center){
                    Text("暂无已解密短信",color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }else LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(horizontal=14.dp,vertical=6.dp),
                verticalArrangement=Arrangement.spacedBy(8.dp)){
                items(threads,key={it.first.thread}){(latest,count)->
                    HubCard(Modifier.fillMaxWidth().clickable{activeThread=latest.thread}){
                        Row(verticalAlignment=Alignment.CenterVertically){
                            Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.surfaceVariant,CircleShape),
                                contentAlignment=Alignment.Center) {
                                HubIcon(R.drawable.ic_hub_messages,Modifier.size(23.dp),
                                    tint=MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)){
                                Row(verticalAlignment=Alignment.CenterVertically){
                                    Text(latest.sender,modifier=Modifier.weight(1f),
                                        fontWeight=FontWeight.SemiBold,maxLines=1,
                                        overflow=TextOverflow.Ellipsis)
                                    Text(DateFormat.format("MM-dd HH:mm",latest.timestamp*1000).toString(),
                                        style=MaterialTheme.typography.labelSmall)
                                }
                                Text(if(latest.failed)"[无法解密]" else latest.body,maxLines=1,
                                    overflow=TextOverflow.Ellipsis,
                                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${latest.device} · ${latest.sim} · ${count} 条",
                                    style=MaterialTheme.typography.labelSmall,
                                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if(state.hasNewer)item{
                    TextButton(onClick=onNewer,modifier=Modifier.fillMaxWidth()){Text(hubLabel("查看较新短信","View newer messages"))}
                }
                if(state.hasMore)item{
                    TextButton(onClick=onOlder,modifier=Modifier.fillMaxWidth()){Text(hubLabel("加载更早短信","Load earlier messages"))}
                }
            }
        }
    }
}
