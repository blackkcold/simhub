package com.blackkcold.simhub

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ControllerHistorySearchDialog(
    client:ControllerSecureClient,initial:ControllerSearchFilter,onDismiss:()->Unit,
    onReply:(ControllerSms)->Unit,canCompose:Boolean
){
    val scope=rememberCoroutineScope()
    var query by remember{mutableStateOf(initial.query.take(200))}
    var otpOnly by remember{mutableStateOf(initial.otpOnly)}
    var sourceOnly by remember{mutableStateOf(initial.sourceId.isNotBlank())}
    var progress by remember{mutableStateOf(ControllerSearchProgress())}
    var phase by remember{mutableStateOf("idle")}
    var error by remember{mutableStateOf("")}
    var generation by remember{mutableIntStateOf(0)}
    var job by remember{mutableStateOf<Job?>(null)}
    var expanded by remember{mutableStateOf<String?>(null)}
    val running=phase=="running"
    fun cancel(){
        generation+=1
        job?.cancel();job=null
        if(running)phase="cancelled"
    }
    fun clearResults(){
        generation+=1;job?.cancel();job=null
        progress=ControllerSearchProgress();phase="idle";error="";expanded=null
    }
    fun start(){
        cancel()
        val scan=generation
        val filter=ControllerSearchFilter(query.trim(),otpOnly,if(sourceOnly)initial.sourceId else "")
        progress=ControllerSearchProgress();error="";phase="running";expanded=null
        job=scope.launch{
            try{
                withContext(Dispatchers.IO){
                    val vault=client.vault()
                    try{
                        val settings=try{ControllerVaultSettings(client).load().settings}catch(_:Exception){null}
                        ControllerHistoryScanner(get={path->client.api(path)},
                            decrypt={event,node->client.decryptEvent(event,node,vault)},
                            decorate={node->if(settings==null)node else ControllerVaultSettingsCrypto.decorate(node,settings)})
                            .scan(filter){update->
                                withContext(Dispatchers.Main){
                                    if(generation==scan){progress=update;if(update.complete)phase="complete"}
                                }
                            }
                    }finally{vault.fill(0)}
                }
            }catch(cancelled:CancellationException){throw cancelled}
            catch(_:ControllerSearchScopeChanged){
                if(generation==scan){progress=ControllerSearchProgress();phase="scope_changed"}
            }catch(failure:Exception){
                if(generation==scan){
                    phase="failed"
                    // Neither server-provided messages nor plaintext query text are
                    // logged or persisted by the scanner.
                    error=when(failure){
                        is ControllerSecureClient.ApiFailure->"HTTP ${failure.status} · ${failure.code.take(80)}"
                        is java.io.IOException->"network_unavailable"
                        is SecurityException->"authorization_or_key_unavailable"
                        else->"history_response_unavailable"
                    }
                    if(failure is SecurityException||failure is ControllerSecureClient.ApiFailure&&failure.status in listOf(401,403))
                        progress=ControllerSearchProgress()
                }
            }
        }
    }
    // Completed or stopped results still honor later purge/re-enrollment signals.
    // Closing/backgrounding disposes this reader and drops all plaintext rows.
    LaunchedEffect(progress.scopeFingerprint,running,generation){
        val identity=progress.scopeFingerprint
        val scan=generation
        if(identity.isBlank()||running)return@LaunchedEffect
        while(true){
            delay(15000)
            try{
                val latest=withContext(Dispatchers.IO){
                    val rows=client.api("/api/v1/devices").getJSONArray("devices")
                    ControllerHistoryScope.fingerprint((0 until rows.length()).map{rows.getJSONObject(it)})
                }
                if(generation!=scan)return@LaunchedEffect
                if(latest!=identity){progress=ControllerSearchProgress();phase="scope_changed";break}
                if(error=="privacy_status_refresh_pending")error=""
            }catch(cancelled:CancellationException){throw cancelled}
            catch(failure:Exception){
                if(failure is SecurityException||failure is ControllerSecureClient.ApiFailure&&failure.status in listOf(401,403)){
                    progress=ControllerSearchProgress();phase="failed";error="authorization_unavailable";break
                }
                error="privacy_status_refresh_pending"
            }
        }
    }
    DisposableEffect(Unit){onDispose{job?.cancel()}}
    fun close(){cancel();progress=ControllerSearchProgress();query="";onDismiss()}
    fun stamp(seconds:Long)=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(seconds*1000))
    Dialog(onDismissRequest=::close,properties=DialogProperties(
        usePlatformDefaultWidth=false,securePolicy=SecureFlagPolicy.SecureOn)){
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
                verticalArrangement=Arrangement.spacedBy(10.dp)){
                Row(Modifier.fillMaxWidth()){
                    Text(hubLabel("搜索所有可用历史","Search all available history"),
                        style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                    TextButton(onClick=::close){Text(hubLabel("关闭","Close"))}
                }
                Text(hubLabel("逐页读取 Relay 保留的短信并在本机搜索。搜索词不会发送给 Relay；关闭后清除本次结果。",
                    "Read Relay-retained SMS in pages and search on this phone. Search terms stay local; closing clears these results."),
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(query,{query=it.take(200);clearResults()},enabled=!running,singleLine=true,
                    modifier=Modifier.fillMaxWidth(),label={Text(hubLabel("正文、号码、联系人或来源","Body, number, contact or source"))})
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    FilterChip(selected=otpOnly,onClick={if(!running){otpOnly=!otpOnly;clearResults()}},enabled=!running,
                        label={Text(hubLabel("仅验证码","Codes only"))})
                    if(initial.sourceId.isNotBlank())FilterChip(selected=sourceOnly,onClick={if(!running){sourceOnly=!sourceOnly;clearResults()}},enabled=!running,
                        label={Text(hubLabel("原筛选来源","Selected source"))})
                    if(running)OutlinedButton(onClick=::cancel){Text(hubLabel("停止扫描","Stop scan"))}
                    else Button(onClick=::start){Text(hubLabel("开始完整扫描","Start full scan"))}
                }
                if(running)LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(when(phase){
                    "complete"->if(progress.unreadable==0L)
                        hubLabel("扫描完成 · 已搜索本次范围内全部可用短信","Scan complete · all available SMS in this scan was searched")
                        else hubLabel("扫描完成 · 无法解密的短信未被搜索","Scan complete · unreadable SMS could not be searched")
                    "cancelled"->hubLabel("扫描已停止 · 当前结果不完整","Scan stopped · results are partial")
                    "failed"->hubLabel("扫描中断 · 当前结果不完整","Scan interrupted · results are partial")
                    "scope_changed"->hubLabel("设备配对、撤销或短信清除状态已变化，结果已清除。请重新扫描。",
                        "Enrollment, revocation or SMS purge state changed. Results were cleared; start a fresh scan.")
                    "running"->hubLabel("正在扫描 · 当前结果不完整","Scanning · results are partial")
                    else->hubLabel("尚未扫描 · 常规收件箱仅搜索已加载窗口","Not scanned · the regular inbox searches only its loaded window")
                },fontWeight=FontWeight.SemiBold,color=if(phase in listOf("failed","scope_changed"))
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                if(error.isNotBlank())Text(error,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.error)
                Text(hubLabel("已扫描 ${progress.scanned} 条 · 匹配 ${progress.matched} 条 · 无法解密 ${progress.unreadable} 条",
                    "Scanned ${progress.scanned} · matched ${progress.matched} · unreadable ${progress.unreadable}"),
                    style=MaterialTheme.typography.bodySmall)
                if(progress.oldestAt>0)Text(stamp(progress.oldestAt)+" — "+stamp(progress.newestAt),
                    style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(progress.watermark>0)Text(hubLabel("范围截止扫描开始时的上传记录；扫描后新增的上传需重新搜索。",
                    "Coverage ends at the upload watermark captured when this scan started. Rerun to include later uploads."),
                    style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(progress.matched>progress.results.size)Text(hubLabel(
                    "已统计全部匹配，列表仅保留最新 ${progress.results.size} 条。可缩小搜索条件。",
                    "All matches are counted; the list keeps the newest ${progress.results.size}. Narrow the search to see other matches."),
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp)){
                    items(progress.results,key={it.id}){sms->
                        HubCard(Modifier.fillMaxWidth()){
                            Text(sms.sender,fontWeight=FontWeight.SemiBold)
                            Text(sms.device+" · "+sms.sim+" · "+stamp(sms.timestamp),
                                style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(sms.body,style=MaterialTheme.typography.bodyMedium,
                                maxLines=if(expanded==sms.id)Int.MAX_VALUE else 4)
                            if(sms.bodyTruncated)Text(hubLabel("长短信仅显示前 4,000 字符；搜索已检查全文。",
                                "Only the first 4,000 characters are displayed; the full text was searched."),
                                style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                                TextButton(onClick={expanded=if(expanded==sms.id)null else sms.id}){
                                    Text(if(expanded==sms.id)hubLabel("收起","Collapse") else hubLabel("展开","Expand"))
                                }
                                if(canCompose&&sms.address.isNotBlank())TextButton(onClick={close();onReply(sms)}){
                                    Text(hubLabel("使用此来源回复","Reply from this source"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
