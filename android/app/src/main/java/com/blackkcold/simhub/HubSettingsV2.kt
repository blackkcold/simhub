package com.blackkcold.simhub

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The only settings and advanced tools surface. Legacy MainActivity is not reachable.
 * All actions call HubController, never perform network, SMS Provider or file IO on the UI thread.
 */
class HubToolsState {
    var energyMode by mutableStateOf(EnergyPolicy.BALANCED)
    var relayAlive by mutableStateOf(false)
    var developer by mutableStateOf(false)
    var forceSms by mutableStateOf(false)
    var logs by mutableStateOf("")
    var diagnostics by mutableStateOf("")
    var ota by mutableStateOf("")
    var autoCheckUpdates by mutableStateOf(true)
    var autoDownloadUpdates by mutableStateOf(false)
    var installedAt by mutableStateOf("")
    var language by mutableIntStateOf(0)
    var poolEnabled by mutableStateOf(false)
    var poolApproved by mutableStateOf(false)
    var poolStatus by mutableStateOf("未开启")
    var compatibilityOpen by mutableStateOf(false)
    var compatibilityStatus by mutableStateOf<org.json.JSONObject?>(null)
    var compatibilityBusy by mutableStateOf(false)
    var compatibilityError by mutableStateOf("")
    var compatibilityNote by mutableStateOf("")
    var compatibilityAudit by mutableStateOf("")

}

@Composable
private fun ToolSection(title:String, modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit){
    HubCard(modifier){
        Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun PairedNodeCard(state:HubSnapshot?,pairing:PairingDisplay?,ui:HubViewModel,
                            controller:HubController,modifier:Modifier){
    ToolSection(hubLabel("设备配对与连接","Device pairing & connection"),modifier){
        if(state?.resetPending==true){
            StatePill(
                if(state.resetRecoveryRequired)
                    hubLabel("解绑异常 · 需要恢复","Reset blocked · recovery required")
                else hubLabel("正在等待解绑确认","Awaiting reset confirmation"),false)
            Spacer(Modifier.height(10.dp))
            Text(state.server,style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(
                if(state.resetRecoveryRequired)
                    hubLabel("服务器凭据或状态无法确认。本地密钥尚未清除，请重试校验或选择本机强制重置。",
                        "Relay status or credentials could not be verified. Local keys remain. Retry or confirm a local-only reset.")
                else hubLabel("短信同步已暂停。等待服务器确认后才会清除本地配对信息。",
                    "SMS syncing is paused. Local enrollment will be cleared only after Relay confirmation."),
                style=MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick=controller::retryReset,modifier=Modifier.fillMaxWidth()){
                Text(hubLabel("重新验证并完成解绑","Retry reset confirmation"))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick=controller::forceLocalReset,modifier=Modifier.fillMaxWidth()){
                Text(hubLabel("本机强制重置…","Force local reset…"))
            }
        }else if(state?.enrolled==true){
            StatePill(hubLabel("已配对","Paired"),true)
            Spacer(Modifier.height(10.dp))
            Text(state.device,fontWeight=FontWeight.Medium)
            Text(state.server,color=MaterialTheme.colorScheme.onSurfaceVariant,
                style=MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick=controller::resetEnrollment,modifier=Modifier.fillMaxWidth()){
                Text(hubLabel("解除配对并同步重置","Unpair and reset"))
            }
        }else{
            Text(hubLabel("扫描管理后台生成的配对二维码，或使用短码连接。","Scan the management QR code or pair using a short code."),
                color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Button(onClick=controller::scan,modifier=Modifier.fillMaxWidth()){
                Text(hubLabel("扫描二维码","Scan QR code"))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value=ui.enrollmentLink,onValueChange={ui.enrollmentLink=it},
                label={Text(hubLabel("配对链接","Pairing link"))},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedButton(onClick={controller.enrollLink(ui.enrollmentLink)},
                enabled=ui.enrollmentLink.startsWith("simhub://enroll"),
                modifier=Modifier.fillMaxWidth()){Text(hubLabel("确认配对","Confirm pairing"))}
            HorizontalDivider(Modifier.padding(vertical=12.dp))
            OutlinedTextField(value=ui.serverUrl,onValueChange={ui.serverUrl=it},
                label={Text(hubLabel("Relay HTTPS 地址","Relay HTTPS address"))},singleLine=true,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),
                modifier=Modifier.fillMaxWidth())
            OutlinedButton(onClick={controller.startPairing(ui.serverUrl)},
                enabled=ui.serverUrl.startsWith("https://"),
                modifier=Modifier.fillMaxWidth()){Text(hubLabel("生成配对短码","Generate pairing code"))}
            if(pairing!=null){
                Spacer(Modifier.height(10.dp))
                Text(pairing.code.chunked(4).joinToString(" "),fontSize=26.sp,fontWeight=FontWeight.Bold)
                Text(hubLabel("指纹 · ","Fingerprint · ")+pairing.fingerprint,style=MaterialTheme.typography.labelSmall)
                Text(if(pairing.waiting)hubLabel("等待管理员确认","Awaiting admin approval") else pairing.error.ifBlank{hubLabel("配对完成","Pairing complete")},
                    style=MaterialTheme.typography.bodySmall)
                if(pairing.waiting)LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=9.dp))
            }
        }
    }
}

@Composable
private fun PermissionsCard(state:HubSnapshot?,controller:HubController,modifier:Modifier){
    ToolSection(hubLabel("短信与系统权限","SMS & system permissions"),modifier){
        val full=state?.smsRole==true
        StatePill(if(full)hubLabel("完整短信模式","Full SMS handler mode")
            else hubLabel("不接管短信模式","Non-default SMS companion mode"),true)
        Spacer(Modifier.height(8.dp))
        Text(if(full)
            hubLabel("SIM Hub 当前负责系统短信收发与入库。","SIM Hub is the system SMS handler and writes received messages.")
            else hubLabel("保留系统信息为默认应用；SIM Hub 在授权后通过短信广播和系统短信数据库同步。","Keep the system Messages app as default; SIM Hub listens for SMS events and reads the SMS database when permitted."),
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        InfoRow(hubLabel("读取短信","Read SMS"),if(state?.smsRead==true)hubLabel("已授权","Granted") else hubLabel("未授权","Not granted"))
        InfoRow(hubLabel("接收短信广播","Receive SMS broadcasts"),if(state?.smsReceive==true)hubLabel("已授权","Granted") else hubLabel("未授权","Not granted"))
        if(full || tools.developer&&tools.forceSms)
            InfoRow(hubLabel("发送短信","Send SMS"),if(state?.smsSend==true)hubLabel("已授权","Granted") else hubLabel("未授权","Not granted"))
        if(!full)Text(hubLabel("Android 17 可能对非默认应用延迟开放验证码短信约 3 小时，SIM Hub 不会绕过此保护。若系统拒绝 READ_SMS 或 RECEIVE_SMS，需检查厂商权限策略。","Android 17 may delay access to OTP SMS by about three hours for non-default apps; SIM Hub does not bypass this protection. If READ_SMS or RECEIVE_SMS is denied, check OEM permission policy."),
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick=controller::requestSmsRole,modifier=Modifier.fillMaxWidth()){
            Text(hubLabel("改用完整短信模式（可选）","Use full SMS handler mode (optional)"))
        }
        Spacer(Modifier.height(7.dp))
        OutlinedButton(onClick=controller::requestAccess,modifier=Modifier.fillMaxWidth()){
            Text(hubLabel("授予必要权限","Grant required permissions"))
        }
        Spacer(Modifier.height(7.dp))
        OutlinedButton(onClick=controller::requestContacts,modifier=Modifier.fillMaxWidth()){
            Text(hubLabel("授权读取联系人（可选）","Allow contacts (optional)"))
        }
    }
}

/** Whole-surface radio cards; never truncate the profile label on narrow phones. */
@Composable
private fun EnergyModeChoice(
    value:String,title:String,summary:String,cadence:String,icon:Int,
    selected:Boolean,onSelect:()->Unit
){
    val scheme=MaterialTheme.colorScheme
    Surface(
        onClick=onSelect,
        modifier=Modifier.fillMaxWidth().heightIn(min=108.dp).semantics { this.selected=selected },
        shape=RoundedCornerShape(18.dp),
        color=if(selected)scheme.secondaryContainer else scheme.surface,
        border=BorderStroke(if(selected)2.dp else 1.dp,
            if(selected)scheme.primary else scheme.outlineVariant)
    ){
        Row(
            Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=14.dp),
            horizontalArrangement=Arrangement.spacedBy(12.dp),
            verticalAlignment=Alignment.CenterVertically
        ){
            Box(
                Modifier.size(48.dp).background(
                    if(selected)scheme.primary.copy(alpha=0.10f) else scheme.surfaceVariant,
                    RoundedCornerShape(14.dp)),
                contentAlignment=Alignment.Center
            ){
                HubIcon(icon,Modifier.size(26.dp),
                    tint=if(selected)scheme.primary else scheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){
                Text(title,style=MaterialTheme.typography.titleSmall,
                    fontWeight=FontWeight.SemiBold)
                Text(summary,style=MaterialTheme.typography.bodySmall,
                    color=scheme.onSurfaceVariant)
                Text(cadence,style=MaterialTheme.typography.labelSmall,
                    color=if(selected)scheme.primary else scheme.onSurfaceVariant)
            }
            RadioButton(selected=selected,onClick=null)
        }
    }
}

@Composable
private fun RelayRuntimeCard(state:HubSnapshot?,controller:HubController,tools:HubToolsState,modifier:Modifier){
    ToolSection(hubLabel("运行与短信同步","Runtime & synchronization"),modifier){
        Text(hubLabel("后台能耗管理","Background energy policy"),
            fontWeight=FontWeight.SemiBold)
        Text(hubLabel(
            "选择能耗与远程控制响应策略。短信接收始终优先处理；具体执行时间仍受 Android 后台限制。",
            "Choose a battery/remote-command strategy. SMS ingestion stays prioritized; Android may delay background work."),
            style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
            EnergyModeChoice(
                EnergyPolicy.ECO,hubLabel("极致省电","Eco"),
                hubLabel("降低后台网络频率，适合备用手机。","Minimize background network work."),
                hubLabel("远程命令约每 14 分钟检查","Commands about every 14 minutes"),
                R.drawable.ic_hub_eco,tools.energyMode==EnergyPolicy.ECO,
                {controller.setEnergyMode(EnergyPolicy.ECO)})
            EnergyModeChoice(
                EnergyPolicy.BALANCED,hubLabel("智能均衡","Balanced"),
                hubLabel("短信按事件上传，远程命令根据屏幕状态调整。","Event-driven SMS, adaptive remote command checks."),
                hubLabel("活动约 45 秒 · 待机约 2 分钟","Active ~45s · Idle ~2min"),
                R.drawable.ic_hub_balanced,tools.energyMode==EnergyPolicy.BALANCED,
                {controller.setEnergyMode(EnergyPolicy.BALANCED)})
            EnergyModeChoice(
                EnergyPolicy.REALTIME,hubLabel("实时优先","Realtime"),
                hubLabel("保持命令长轮询，响应更快，但耗电较高。","Long-poll commands for lower latency at higher power cost."),
                hubLabel("约 15 秒长轮询","Up to 15s long polling"),
                R.drawable.ic_hub_realtime,tools.energyMode==EnergyPolicy.REALTIME,
                {controller.setEnergyMode(EnergyPolicy.REALTIME)})
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){
                Text(hubLabel("低延迟前台连接","Low-latency foreground relay"),fontWeight=FontWeight.Medium)
                Text(hubLabel("开启后保持 Relay 控制命令监听；系统可能限制后台运行。","Keeps command listening active; Android may limit background runtime."),
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked=state?.realtime==true,onCheckedChange=controller::setRealtime,
                enabled=state?.enrolled==true)
        }
        if(state?.enrolled==true){
            val connected=tools.relayAlive
            val requested=state.realtime
            val transportText=when{
                connected->hubLabel("前台命令监听运行中","Foreground command listener running")
                requested->hubLabel("已启用但服务未运行；可重新启用前台连接","Enabled but service not running; retry foreground connection")
                else->hubLabel("前台连接已关闭，指令由系统后台任务检查","Foreground connection off; commands rely on OS background jobs")
            }
            Text(transportText,
                style=MaterialTheme.typography.bodySmall,
                color=if(requested&&!connected)MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        Button(onClick=controller::syncNow,
            enabled=state?.enrolled==true,modifier=Modifier.fillMaxWidth()){
            HubIcon(R.drawable.ic_hub_sync,Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(hubLabel("立即检查命令并同步短信","Check commands & sync SMS"))
        }
        val commandAt=state?.state?.optLong("lastCommandFetchAt",0L)?:0L
        InfoRow(hubLabel("上次成功检查命令","Last successful command check"),
            if(commandAt>0)java.text.DateFormat.getDateTimeInstance()
                .format(java.util.Date(commandAt*1000)) else "—")
        InfoRow(hubLabel("上次获取命令数","Commands fetched last check"),
            (state?.state?.optInt("lastCommandFetchCount",0)?:0).toString())
        val commandError=state?.state?.optString("lastCommandFetchError","").orEmpty()
        if(commandError.isNotBlank())
            Text(hubLabel("命令连接错误：","Command connection error: ")+commandError,
                style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.error)
        val receivedGeneration=state?.state?.optLong("smsChangeGeneration",0L)?:0L
        val scannedGeneration=state?.state?.optLong("smsScannedGeneration",0L)?:0L
        if(receivedGeneration>scannedGeneration)
            Text(hubLabel("有尚未确认扫描的短信变化，后台将自动重试。",
                "SMS Provider changes are awaiting a confirmed scan."),
                style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
        val energy=state?.state?.optJSONObject("energyStats")
        if(energy!=null){
            InfoRow(hubLabel("有效运行档位","Effective power mode"),
                when(energy.optString("effectiveMode","balanced")){
                    EnergyPolicy.ECO->hubLabel("极致省电","Eco")
                    EnergyPolicy.REALTIME->hubLabel("实时优先","Realtime")
                    else->hubLabel("智能均衡","Balanced")
                })
            InfoRow(hubLabel("命令检查次数 / 失败","Command checks / errors"),
                energy.optLong("commandPolls",0L).toString()+" / "+energy.optLong("commandPollErrors",0L))
            InfoRow(hubLabel("维护 / 补偿扫描次数","Maintenance / reconciliation"),
                energy.optLong("maintenanceRuns",0L).toString()+" / "+energy.optLong("reconciliationRuns",0L))
        }
        HorizontalDivider(Modifier.padding(vertical=12.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxWidth()){
            OutlinedButton(onClick={controller.syncHistory(false)},enabled=state?.enrolled==true,
                modifier=Modifier.weight(1f)){Text(hubLabel("最近 100 条","Latest 100"),maxLines=1)}
            OutlinedButton(onClick={controller.syncHistory(true)},enabled=state?.enrolled==true,
                modifier=Modifier.weight(1f)){Text(hubLabel("更早 100 条","Previous 100"),maxLines=1)}
        }
        InfoRow(hubLabel("待上传事件","Queued events"),(state?.pending?:0).toString())
        InfoRow(hubLabel("已上传回执（本机）","Uploaded receipts on device"),
            (state?.state?.optInt("uploadedEventReceipts",0)?:0).toString())
        val tasks=state?.state?.optJSONArray("pendingEventTasks")
        if(tasks!=null&&tasks.length()>0){
            Text(hubLabel("待处理事件预览（仅任务类型与入队时间）","Pending tasks (type and time only)"),
                style=MaterialTheme.typography.labelMedium)
            for(i in 0 until tasks.length()){
                val t=tasks.optJSONObject(i)?:continue
                val whenQueued=t.optLong("queuedAt",0L)
                InfoRow(t.optString("kind","unknown"),
                    if(whenQueued>0)java.text.DateFormat.getTimeInstance().format(java.util.Date(whenQueued*1000)) else "—")
            }
        }
        InfoRow(hubLabel("待上传命令 ACK","Pending command ACKs"),
            (state?.state?.optInt("pendingCommandAcks",0)?:0).toString())
        InfoRow(hubLabel("上次事件上传","Last event upload"),
            state?.state?.optLong("lastEventUploadAt",0L)?.takeIf{it>0L}?.let{
                java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it*1000))
            }?:"—")
        InfoRow(hubLabel("上次上传数量","Last uploaded count"),
            (state?.state?.optInt("lastEventUploadCount",0)?:0).toString())
        if(state?.state?.optString("lastUploadError","")?.isNotBlank()==true)
            Text("Upload: "+state.state.optString("lastUploadError"),color=MaterialTheme.colorScheme.error)
        if(state?.state?.optString("lastEventQueueError","")?.isNotBlank()==true)
            Text("Queue: "+state.state.optString("lastEventQueueError"),color=MaterialTheme.colorScheme.error)
        Text(hubLabel("队列为 0 不代表没有读取短信；已上传的事件会立即从待上传队列移除并保留去重回执。","A zero queue does not mean SMS was not scanned; acknowledged events leave the queue and retain upload receipts."),
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)

        InfoRow(hubLabel("最近同步","Last sync"),state?.lastSync?.takeIf{it>0}?.let{
            java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it*1000))
        }?:"—")
        if(state?.transportError?.isNotBlank()==true)
            Text("Relay: "+state.transportError,color=MaterialTheme.colorScheme.error)
        if(state?.providerError?.isNotBlank()==true)
            Text("SMS Provider: "+state.providerError,color=MaterialTheme.colorScheme.error)
        OutlinedButton(onClick=controller::refreshDiagnostics,modifier=Modifier.fillMaxWidth()){
            Text(hubLabel("采集并同步设备状态","Collect and sync status"))
        }
    }
}

@Composable
private fun SharingCard(state:HubSnapshot?,tools:HubToolsState,controller:HubController,modifier:Modifier){
    ToolSection(hubLabel("共享短信池","Shared SMS pool"),modifier){
        Row(verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){
                Text(hubLabel("允许同一管理池设备共享短信","Share SMS with approved devices"),fontWeight=FontWeight.Medium)
                Text(hubLabel("默认关闭。开启后需 Web 管理员授权；通信始终使用端到端加密。","Off by default. Requires Web admin approval and end-to-end encryption."),
                    style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked=tools.poolEnabled,onCheckedChange=controller::toggleSharing,
                enabled=state?.enrolled==true)
        }
        Spacer(Modifier.height(8.dp))
        StatePill(
            if(state?.enrolled!=true)hubLabel("未配对","Not paired")
            else if(!tools.poolEnabled)hubLabel("未开启","Disabled")
            else if(tools.poolApproved)hubLabel("已授权 · 端到端加密","Approved · end-to-end encrypted")
            else hubLabel("等待 Web 管理员授权","Awaiting Web admin approval"),
            tools.poolApproved)
        Text(hubLabel("首次上传最近 100 条；更多历史按需加载。关闭后清除本机共享缓存，不删除原始短信。","Uploads the latest 100 on first grant; load older history on demand. Turning off clears the cache, not original SMS."),
            style=MaterialTheme.typography.labelSmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DiagnosticsCard(state:HubSnapshot?,tools:HubToolsState,controller:HubController,modifier:Modifier){
    ToolSection(hubLabel("高级工具与诊断","Advanced tools & diagnostics"),modifier){
        Row(verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){
                Text(hubLabel("开发者模式","Developer mode"),fontWeight=FontWeight.Medium)
                Text(hubLabel("启用脱敏日志采集与问题排查","Capture redacted logs for troubleshooting"),
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked=tools.developer,onCheckedChange=controller::setDeveloperEnabled)
        }
        HorizontalDivider(Modifier.padding(vertical=12.dp))
        Row(verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){
                Text(hubLabel("自动检查更新","Automatic update checks"))
                Text(hubLabel("每天检查一次正式版本","Check stable releases daily"),
                    style=MaterialTheme.typography.bodySmall)
            }
            Switch(checked=tools.autoCheckUpdates,onCheckedChange=controller::setAutoCheckUpdates)
        }
        Row(verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){
                Text(hubLabel("自动下载安装","Automatic download & installation"))
                Text(hubLabel("需要时由 Android 系统确认安装","Android may require approval"),
                    style=MaterialTheme.typography.bodySmall)
            }
            Switch(checked=tools.autoDownloadUpdates,onCheckedChange=controller::setAutoDownloadUpdates)
        }
        HorizontalDivider(Modifier.padding(vertical=12.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxWidth()){
            OutlinedButton(onClick=controller::checkOta,modifier=Modifier.weight(1f)){
                Text(hubLabel("检查更新","Check for updates"))
            }
            OutlinedButton(onClick=controller::exportDiagnostics,modifier=Modifier.weight(1f)){
                Text(hubLabel("导出诊断 ZIP","Export diagnostic ZIP"))
            }
        }
        if(tools.ota.isNotBlank()){
            Spacer(Modifier.height(8.dp))
            Text(tools.ota,style=MaterialTheme.typography.bodySmall)
        }
        if(tools.developer){
            if(state?.smsRole==false){
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    Column(Modifier.weight(1f)){
                        Text(hubLabel("强制开启发送短信（仅测试）","Allow SMS sending (testing only)"))
                        Text(hubLabel("仅非接管模式。1 小时自动失效；关闭开发者模式立即撤销。","Companion mode only. Expires after 1 hour; disabling developer mode revokes it."),
                            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked=tools.forceSms,onCheckedChange=controller::setDeveloperSmsOverride)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxWidth()){
                OutlinedButton(onClick=controller::viewLogs,modifier=Modifier.weight(1f)){Text(hubLabel("查看日志","View logs"))}
                OutlinedButton(onClick=controller::clearLogs,modifier=Modifier.weight(1f)){Text(hubLabel("清理日志","Clear logs"))}
            }
            if(tools.logs.isNotBlank()){
                Spacer(Modifier.height(8.dp))
                Text(tools.logs.takeLast(24000),style=MaterialTheme.typography.bodySmall,
                    modifier=Modifier.fillMaxWidth().heightIn(max=280.dp)
                        .verticalScroll(rememberScrollState()))
            }
            OutlinedButton(onClick=controller::refreshDiagnostics,modifier=Modifier.fillMaxWidth()){
                Text(hubLabel("查看设备状态 JSON","Show device state JSON"))
            }
            if(tools.diagnostics.isNotBlank()){
                Text(tools.diagnostics,style=MaterialTheme.typography.bodySmall,
                    modifier=Modifier.fillMaxWidth().heightIn(max=300.dp)
                        .verticalScroll(rememberScrollState()))
            }
        }
    }
}

@Composable
private fun PreferencesCard(tools:HubToolsState,controller:HubController,modifier:Modifier){
    ToolSection(hubLabel("应用与系统","App & system"),modifier){
        Text(hubLabel("显示语言","Language"),style=MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        var optionsOpen by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick={optionsOpen=true},modifier=Modifier.fillMaxWidth()){
                Text(listOf(hubLabel("跟随系统","System default"),hubLabel("简体中文","Simplified Chinese"),"English").getOrElse(tools.language){hubLabel("跟随系统","System default")})
            }
            DropdownMenu(expanded=optionsOpen,onDismissRequest={optionsOpen=false}){
                listOf(hubLabel("跟随系统","System default"),hubLabel("简体中文","Simplified Chinese"),"English").forEachIndexed { index,label->
                    DropdownMenuItem(text={Text(label)},onClick={
                        optionsOpen=false;controller.setLanguage(index)
                    })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick=controller::openNetworkSettings,modifier=Modifier.fillMaxWidth()){
            Text(hubLabel("打开系统移动网络设置","Open mobile network settings"))
        }
        HorizontalDivider(Modifier.padding(vertical=12.dp))
        InfoRow(hubLabel("版本号","Version"),BuildConfig.VERSION_NAME)
        InfoRow(hubLabel("安装 / 更新日期","Installed / updated"),tools.installedAt.ifBlank{"—"})
        InfoRow(hubLabel("构建版本","Build"),BuildConfig.VERSION_CODE.toString())
    }
}

@Composable
private fun CompatibilityEntry(controller:HubController,modifier:Modifier){
    ToolSection(hubLabel("系统兼容实验室（高级可选）","System compatibility lab (optional)"),modifier){
        Text(hubLabel("独立管理 Shizuku、伴侣设备关联、系统短信探测及脱敏日志。不会影响普通配对与短信权限。",
            "Separate Shizuku, companion pairing, SMS diagnostics and redacted audit. Normal pairing and SMS settings remain unchanged."),
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Button(onClick=controller::openCompatibility,modifier=Modifier.fillMaxWidth()){
            Text(hubLabel("进入兼容实验室","Open compatibility lab"))
        }
    }
}

/** Responsive two-column settings; no transition to any legacy activity. */
@Composable
fun HubSettingsV2(state:HubSnapshot?,pairing:PairingDisplay?,ui:HubViewModel,
                  controller:HubController,tools:HubToolsState,wide:Boolean){
    if(tools.compatibilityOpen){
        HubCompatibilityScreen(state,tools,controller,wide)
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()){
        val twoColumns=wide && maxWidth>=740.dp && maxHeight>=480.dp
        if(twoColumns){
            Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
                horizontalArrangement=Arrangement.spacedBy(16.dp)){
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(14.dp)){
                    PairedNodeCard(state,pairing,ui,controller,Modifier.fillMaxWidth())
                    PermissionsCard(state,controller,Modifier.fillMaxWidth())
                    CompatibilityEntry(controller,Modifier.fillMaxWidth())
                    PreferencesCard(tools,controller,Modifier.fillMaxWidth())
                }
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(14.dp)){
                    RelayRuntimeCard(state,controller,tools,Modifier.fillMaxWidth())
                    SharingCard(state,tools,controller,Modifier.fillMaxWidth())
                    DiagnosticsCard(state,tools,controller,Modifier.fillMaxWidth())
                }
            }
        }else{
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement=Arrangement.spacedBy(14.dp)){
                PairedNodeCard(state,pairing,ui,controller,Modifier.fillMaxWidth())
                PermissionsCard(state,controller,Modifier.fillMaxWidth())
                CompatibilityEntry(controller,Modifier.fillMaxWidth())
                RelayRuntimeCard(state,controller,tools,Modifier.fillMaxWidth())
                SharingCard(state,tools,controller,Modifier.fillMaxWidth())
                DiagnosticsCard(state,tools,controller,Modifier.fillMaxWidth())
                PreferencesCard(tools,controller,Modifier.fillMaxWidth())
            }
        }
    }
}
