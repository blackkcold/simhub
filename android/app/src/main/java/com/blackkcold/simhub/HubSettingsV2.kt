package com.blackkcold.simhub

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
    var remoteOtaAllowed by mutableStateOf(false)
    var remoteOtaWifiOnly by mutableStateOf(true)
    var forceRemoteOta by mutableStateOf(false)
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
    var shellRunning by mutableStateOf(false)
    var shellCommand by mutableStateOf("")
    var shellOutput by mutableStateOf("")

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
        if(full)
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
    selected:Boolean,onSelect:()->Unit,modifier:Modifier=Modifier
){
    val scheme=MaterialTheme.colorScheme
    Surface(onClick=onSelect,modifier=modifier.heightIn(min=104.dp)
        .semantics { this.selected=selected },shape=RoundedCornerShape(16.dp),
        color=if(selected)scheme.secondaryContainer else scheme.surface,
        border=BorderStroke(if(selected)2.dp else 1.dp,
            if(selected)scheme.primary else scheme.outlineVariant)){
        Column(Modifier.fillMaxWidth().padding(horizontal=5.dp,vertical=12.dp),
            verticalArrangement=Arrangement.spacedBy(8.dp),
            horizontalAlignment=Alignment.CenterHorizontally){
            HubIcon(icon,Modifier.size(27.dp),
                tint=if(selected)scheme.primary else scheme.onSurfaceVariant)
            Text(title,style=MaterialTheme.typography.labelMedium,
                fontWeight=if(selected)FontWeight.Bold else FontWeight.Medium,
                maxLines=2,overflow=TextOverflow.Ellipsis)
            if(selected)Text("✓",style=MaterialTheme.typography.labelSmall,
                color=scheme.primary) else Spacer(Modifier.height(16.dp))
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
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            EnergyModeChoice(EnergyPolicy.ECO,hubLabel("极致省电","Eco"),
                "", "",R.drawable.ic_hub_eco,tools.energyMode==EnergyPolicy.ECO,
                {controller.setEnergyMode(EnergyPolicy.ECO)},Modifier.weight(1f))
            EnergyModeChoice(EnergyPolicy.BALANCED,hubLabel("智能均衡","Balanced"),
                "", "",R.drawable.ic_hub_balanced,tools.energyMode==EnergyPolicy.BALANCED,
                {controller.setEnergyMode(EnergyPolicy.BALANCED)},Modifier.weight(1f))
            EnergyModeChoice(EnergyPolicy.REALTIME,hubLabel("实时优先","Realtime"),
                "", "",R.drawable.ic_hub_realtime,tools.energyMode==EnergyPolicy.REALTIME,
                {controller.setEnergyMode(EnergyPolicy.REALTIME)},Modifier.weight(1f))
        }
        val policyDescription=when(tools.energyMode){
            EnergyPolicy.ECO->hubLabel("极致省电：远程命令约每 14 分钟检查。",
                "Eco: commands checked about every 14 minutes.")
            EnergyPolicy.REALTIME->hubLabel("实时优先：约 15 秒长轮询，耗电可能较高。",
                "Realtime: approximately 15s long polls; higher power use.")
            else->hubLabel("智能均衡：活动约 45 秒，待机约 2 分钟检查命令。",
                "Balanced: commands checked ~45s active, ~2min idle.")
        }
        Text(policyDescription,style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
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
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxWidth()){
            OutlinedButton(onClick={controller.syncHistory(false)},
                enabled=state?.enrolled==true,modifier=Modifier.weight(1f)){
                Text(hubLabel("最近 100 条","Latest 100"),maxLines=1)
            }
            OutlinedButton(onClick={controller.syncHistory(true)},
                enabled=state?.enrolled==true,modifier=Modifier.weight(1f)){
                Text(hubLabel("更早 100 条","Previous 100"),maxLines=1)
            }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            SettingsMetric(R.drawable.ic_hub_sync,hubLabel("待上传","Queued"),
                state?.pending?.toString()?:"—",Modifier.weight(1f))
            SettingsMetric(R.drawable.ic_hub_tasks,hubLabel("待确认 ACK","Pending ACK"),
                state?.state?.optInt("pendingCommandAcks",0)?.toString()?:"—",
                Modifier.weight(1f))
            SettingsMetric(R.drawable.ic_hub_network,hubLabel("最近同步","Last sync"),
                state?.lastSync?.takeIf{it>0L}?.let{
                    java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                        .format(java.util.Date(it*1000))
                }?:"—",Modifier.weight(1f))
        }
        if(state?.transportError?.isNotBlank()==true)
            Text("Relay: "+state.transportError.take(160),color=MaterialTheme.colorScheme.error,
                style=MaterialTheme.typography.bodySmall)
        if(state?.providerError?.isNotBlank()==true)
            Text("SMS Provider: "+state.providerError.take(160),
                color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
        var showDetails by rememberSaveable { mutableStateOf(false) }
        OutlinedButton(onClick={showDetails=!showDetails},modifier=Modifier.fillMaxWidth()){
            Text(if(showDetails)hubLabel("收起技术指标","Hide technical diagnostics")
                else hubLabel("展开任务与诊断指标","Show tasks & diagnostics"))
        }
        if(showDetails){
        val relayPhase=RelayUiState.phase(state?.enrolled==true,
            state?.resetPending==true,state?.resetRecoveryRequired==true)
        if(RelayUiState.showTransportDiagnostics(relayPhase)){
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
            val polls=energy.optLong("commandPolls",0L)
            val failures=energy.optLong("commandPollErrors",0L)
            if(polls>0L){
                val healthy=((polls-failures).coerceAtLeast(0L).toFloat()/polls.toFloat()).coerceIn(0f,1f)
                LinearProgressIndicator(progress={ healthy },modifier=Modifier.fillMaxWidth(),
                    color=MaterialTheme.colorScheme.primary)
                Text(hubLabel("命令检查成功占比（累计计数）","Command checks succeeded (cumulative)"),
                    style=MaterialTheme.typography.labelSmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            InfoRow(hubLabel("维护 / 补偿扫描次数","Maintenance / reconciliation"),
                energy.optLong("maintenanceRuns",0L).toString()+" / "+energy.optLong("reconciliationRuns",0L))
        }
        HorizontalDivider(Modifier.padding(vertical=12.dp))

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
        }else{
            Text(when(relayPhase){
                RelayUiState.Phase.UNPAIRED->hubLabel(
                    "未配对 Relay；历史连接错误和上传指标已隐藏。",
                    "Relay is unpaired; previous connection and upload diagnostics are hidden.")
                RelayUiState.Phase.RESET_PENDING->hubLabel(
                    "解绑确认进行中。查看上方的配对状态和重试操作。",
                    "Reset confirmation pending; use the enrollment card above.")
                RelayUiState.Phase.RECOVERY->hubLabel(
                    "解绑需要恢复；旧 Relay 错误不代表当前连接仍在工作。",
                    "Reset recovery required; historical errors are not active connectivity.")
                RelayUiState.Phase.ACTIVE->""
            },style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(state?.providerError?.isNotBlank()==true)
            Text("SMS Provider: "+state.providerError,color=MaterialTheme.colorScheme.error)
        }
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

/** Shown only on Android 16+ or after an explicit developer override. */
@Composable
private fun RemoteOtaCard(state:HubSnapshot?,tools:HubToolsState,controller:HubController,modifier:Modifier){
    val native=android.os.Build.VERSION.SDK_INT>=36
    if(!native && !tools.forceRemoteOta)return
    ToolSection(hubLabel("远程应用更新","Remote app updates"),modifier){
        Row(verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){
                Text(hubLabel("允许管理服务器发起 APK 更新","Allow remote APK updates"),
                    fontWeight=FontWeight.Medium)
                Text(hubLabel("仅允许官方同签名的新版本；系统可能要求手动确认安装。",
                    "Official, identically signed newer APKs only; Android may require confirmation."),
                    style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked=tools.remoteOtaAllowed,onCheckedChange=controller::setRemoteOtaAllowed)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){
                Text(hubLabel("仅 Wi-Fi 下载远程更新","Download remote updates over Wi-Fi only"))
                Text(hubLabel("默认开启；电量低于 25% 且未充电时暂停更新。",
                    "On by default; updates pause below 25% battery unless charging."),
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked=tools.remoteOtaWifiOnly,onCheckedChange=controller::setRemoteOtaWifiOnly)
        }
        Spacer(Modifier.height(10.dp))
        StatePill(
            if(native)hubLabel("Android 16+ · 平台门槛通过","Android 16+ · platform eligible")
            else hubLabel("开发者强制测试 · 非原生适配","Developer test override · not natively eligible"),native)
        val otaState=state?.state
        val installGranted=otaState?.optBoolean("remoteOtaInstallPermission",false)==true
        if(!installGranted){
            Spacer(Modifier.height(10.dp))
            Text(hubLabel("尚未获得系统安装来源授权；服务器暂不能发起安装。",
                "Install-source access has not been granted; remote installation is unavailable."),
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
            OutlinedButton(onClick=controller::openInstallSourceSettings,modifier=Modifier.fillMaxWidth()){
                Text(hubLabel("打开系统安装权限设置","Open system install permissions"))
            }
        }
        val phase=otaState?.optString("remoteOtaStage","idle").orEmpty()
        if(phase.isNotBlank() && phase!="idle"){
            Spacer(Modifier.height(9.dp))
            val visibleStage=when(phase){
                "checking"->hubLabel("检查版本","Checking")
                "downloading"->hubLabel("下载中","Downloading")
                "verified"->hubLabel("已校验","Verified")
                "installing"->hubLabel("安装中","Installing")
                "awaiting_confirmation"->hubLabel("等待设备确认","Awaiting device approval")
                "verification_pending"->hubLabel("状态待核实","Verification pending")
                "succeeded"->hubLabel("已完成","Completed")
                "failed"->hubLabel("更新失败","Failed")
                else->phase
            }
            InfoRow(hubLabel("远程更新任务状态","Remote update task"),visibleStage)
            val reason=otaState?.optString("remoteOtaError","").orEmpty()
            if(reason.isNotBlank())Text(reason,style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(8.dp))
        Text(hubLabel("服务端可下发更新命令，但无法保证免确认安装或后台弹出界面。安装成功后仅尝试自动恢复原有中继服务。",
            "The server may request updates; silent installation and foreground UI launches are not guaranteed. Relay recovery is best effort."),
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
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
        if(tools.developer){
            HorizontalDivider(Modifier.padding(vertical=10.dp))
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f)){
                    Text(hubLabel("强制启用远程 OTA 功能入口（仅测试）",
                        "Force remote OTA feature gate (testing only)"))
                    Text(hubLabel("允许 Android 16 以下设备测试远程命令；不绕过系统安装权限或确认。",
                        "Exposes the feature below Android 16; never bypasses installer security."),
                        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked=tools.forceRemoteOta,onCheckedChange=controller::setDeveloperRemoteOtaOverride)
            }
        }
        OutlinedButton(onClick=controller::exportDiagnostics,modifier=Modifier.fillMaxWidth()){
            Text(hubLabel("导出诊断 ZIP","Export diagnostic ZIP"))
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


@Composable
private fun UpdatePreferencesCard(tools:HubToolsState,controller:HubController,modifier:Modifier){
    ToolSection(hubLabel("应用版本与自动更新","Version & automatic updates"),modifier){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            HubIcon(R.drawable.ic_hub_sync,Modifier.size(30.dp),
                tint=MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)){
                Text("SIM Hub "+BuildConfig.VERSION_NAME,
                    style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                Text(hubLabel("当前安装版本","Installed app version"),
                    style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("+"+BuildConfig.VERSION_CODE,style=MaterialTheme.typography.labelSmall)
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

        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick=controller::checkOta,modifier=Modifier.fillMaxWidth()){
            Text(hubLabel("检查正式版本","Check stable releases"))
        }
        if(tools.ota.isNotBlank()){
            Spacer(Modifier.height(8.dp))
            Text(tools.ota,style=MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Settings hub: keep the bottom navigation unchanged, but show only useful
 * status and five task-oriented destinations until a category is selected.
 * Navigation state belongs to HubViewModel so tab/fold changes preserve it.
 */
private const val SETTINGS_HOME=0
private const val SETTINGS_CONNECTION=1
private const val SETTINGS_RUNTIME=2
private const val SETTINGS_SHARING=3
private const val SETTINGS_UPDATES=4
private const val SETTINGS_ADVANCED=5

@Composable
private fun SettingsMenuEntry(icon:Int,title:String,summary:String,status:String="",onClick:()->Unit) {
    Surface(onClick=onClick,modifier=Modifier.fillMaxWidth().heightIn(min=68.dp),
        color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(18.dp),
        tonalElevation=1.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=12.dp),
            verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer,
                RoundedCornerShape(13.dp)),contentAlignment=Alignment.Center){
                HubIcon(icon,Modifier.size(23.dp),
                    tint=MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){
                Text(title,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
                Text(summary,style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,
                    overflow=TextOverflow.Ellipsis)
            }
            if(status.isNotBlank()) Text(status,style=MaterialTheme.typography.labelSmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
            HubIcon(R.drawable.ic_hub_chevron,Modifier.size(18.dp),
                tint=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NodeStatusOverview(state:HubSnapshot?,tools:HubToolsState,controller:HubController){
    val enrolled=state?.enrolled==true
    val blocked=state?.resetPending==true
    val recovering=state?.resetRecoveryRequired==true
    val connectionLabel=when {
        recovering->hubLabel("解绑异常 · 需恢复","Reset blocked · recovery needed")
        blocked->hubLabel("正在解除配对","Unpair confirmation pending")
        !enrolled->hubLabel("尚未配对","Not paired")
        tools.relayAlive->hubLabel("命令监听运行中","Command listener running")
        else->hubLabel("已配对 · 前台监听未运行","Paired · listener not running")
    }
    HubCard(Modifier.fillMaxWidth()){
        Row(verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(10.dp)){
            HubIcon(R.drawable.ic_hub_sim,Modifier.size(28.dp),
                tint=MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)){
                Text(hubLabel("本机 SIM 节点","Local SIM node"),
                    style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                Text(connectionLabel,style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatePill(if(enrolled&&!blocked)hubLabel("已配对","Paired")
                else hubLabel("需处理","Needs attention"),enrolled&&!blocked)
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            SettingsMetric(R.drawable.ic_hub_shield,
                hubLabel("短信权限","SMS access"),
                if(state==null) "—" else if(state.smsRead && state.smsReceive)
                    hubLabel("已授权","Granted") else hubLabel("需授权","Action needed"),
                Modifier.weight(1f))
            SettingsMetric(R.drawable.ic_hub_balanced,
                hubLabel("能耗策略","Energy policy"),
                when(tools.energyMode){
                    EnergyPolicy.ECO->hubLabel("省电","Eco")
                    EnergyPolicy.REALTIME->hubLabel("实时","Realtime")
                    else->hubLabel("均衡","Balanced")
                },Modifier.weight(1f))
            SettingsMetric(R.drawable.ic_hub_sync,
                hubLabel("待上传","Queued"),
                state?.pending?.toString()?:"—",Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick=controller::syncNow,enabled=enrolled&&!blocked,
            modifier=Modifier.fillMaxWidth()){
            HubIcon(R.drawable.ic_hub_sync,Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(hubLabel("立即检查命令并同步短信","Check commands & sync SMS"))
        }
        val error=state?.transportError.orEmpty().ifBlank {
            state?.providerError.orEmpty()
        }
        if(error.isNotBlank() && enrolled && !blocked){
            Spacer(Modifier.height(8.dp))
            Text(hubLabel("需要处理：","Attention: ")+error.take(160),
                color=MaterialTheme.colorScheme.error,
                style=MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SettingsMetric(icon:Int,title:String,value:String,modifier:Modifier=Modifier) {
    Column(modifier.background(MaterialTheme.colorScheme.surfaceVariant,
        RoundedCornerShape(12.dp)).padding(horizontal=7.dp,vertical=10.dp),
        horizontalAlignment=Alignment.CenterHorizontally,
        verticalArrangement=Arrangement.spacedBy(5.dp)){
        HubIcon(icon,Modifier.size(19.dp),tint=MaterialTheme.colorScheme.primary)
        Text(value,style=MaterialTheme.typography.labelMedium,
            fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
        Text(title,style=MaterialTheme.typography.labelSmall,
            color=MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}

@Composable
private fun SettingsDestinations(state:HubSnapshot?,tools:HubToolsState,onSelect:(Int)->Unit){
    val enrolled=state?.enrolled==true
    SettingsMenuEntry(R.drawable.ic_hub_link,
        hubLabel("连接与权限","Connection & permissions"),
        hubLabel("配对 · 短信接管 · 系统权限","Pairing · SMS mode · system access"),
        if(state?.resetPending==true)hubLabel("待恢复","Pending")
        else if(enrolled)hubLabel("已配对","Paired") else hubLabel("未配对","Unpaired"),
        {onSelect(SETTINGS_CONNECTION)})
    SettingsMenuEntry(R.drawable.ic_hub_balanced,
        hubLabel("运行与同步","Runtime & synchronization"),
        hubLabel("能耗策略 · 前台连接 · 历史同步","Energy · foreground relay · SMS history"),
        when(tools.energyMode){
            EnergyPolicy.ECO->hubLabel("省电","Eco")
            EnergyPolicy.REALTIME->hubLabel("实时","Realtime")
            else->hubLabel("均衡","Balanced")
        },{onSelect(SETTINGS_RUNTIME)})
    SettingsMenuEntry(R.drawable.ic_hub_share,
        hubLabel("共享与隐私","Sharing & privacy"),
        hubLabel("共享短信池 · 端到端加密授权","Shared SMS pool · encryption"),
        if(tools.poolApproved)hubLabel("已授权","Approved")
        else if(tools.poolEnabled)hubLabel("待批准","Pending")
        else hubLabel("关闭","Off"),{onSelect(SETTINGS_SHARING)})
    SettingsMenuEntry(R.drawable.ic_hub_update,
        hubLabel("应用更新","App updates"),
        hubLabel("检查版本 · 自动更新 · 远程 OTA","Version · auto update · remote OTA"),
        BuildConfig.VERSION_NAME,{onSelect(SETTINGS_UPDATES)})
    SettingsMenuEntry(R.drawable.ic_hub_terminal,
        hubLabel("系统与高级工具","System & advanced tools"),
        hubLabel("语言 · 日志 · 兼容实验室","Language · logs · compatibility"),
        onClick={onSelect(SETTINGS_ADVANCED)})
}

@Composable
private fun SettingsCategoryHeader(page:Int,onBack:()->Unit){
    val title=when(page){
        SETTINGS_CONNECTION->hubLabel("连接与权限","Connection & permissions")
        SETTINGS_RUNTIME->hubLabel("运行与同步","Runtime & synchronization")
        SETTINGS_SHARING->hubLabel("共享与隐私","Sharing & privacy")
        SETTINGS_UPDATES->hubLabel("应用更新","App updates")
        else->hubLabel("系统与高级工具","System & advanced tools")
    }
    Row(Modifier.fillMaxWidth().heightIn(min=52.dp),
        verticalAlignment=Alignment.CenterVertically,
        horizontalArrangement=Arrangement.spacedBy(12.dp)){
        IconButton(onClick=onBack){
            HubIcon(R.drawable.ic_hub_back,Modifier.size(22.dp),
                description=hubLabel("返回设置总览","Back to settings overview"))
        }
        Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
    }
}

private fun LazyListScope.settingsCategoryContent(page:Int,state:HubSnapshot?,
    pairing:PairingDisplay?,ui:HubViewModel,controller:HubController,tools:HubToolsState){
    when(page){
        SETTINGS_CONNECTION->{
            item(key="pairing"){PairedNodeCard(state,pairing,ui,controller,Modifier.fillMaxWidth())}
            item(key="permissions"){PermissionsCard(state,controller,Modifier.fillMaxWidth())}
        }
        SETTINGS_RUNTIME->item(key="runtime"){
            RelayRuntimeCard(state,controller,tools,Modifier.fillMaxWidth())
        }
        SETTINGS_SHARING->item(key="sharing"){
            SharingCard(state,tools,controller,Modifier.fillMaxWidth())
        }
        SETTINGS_UPDATES->{
            item(key="updates"){UpdatePreferencesCard(tools,controller,Modifier.fillMaxWidth())}
            item(key="remote_ota"){RemoteOtaCard(state,tools,controller,Modifier.fillMaxWidth())}
        }
        SETTINGS_ADVANCED->{
            item(key="preferences"){PreferencesCard(tools,controller,Modifier.fillMaxWidth())}
            item(key="diagnostics"){DiagnosticsCard(state,tools,controller,Modifier.fillMaxWidth())}
            item(key="lab"){CompatibilityEntry(controller,Modifier.fillMaxWidth())}
        }
    }
}

/** A short single-column home, and independently scrolling master/detail on wide screens. */
@Composable
fun HubSettingsV2(state:HubSnapshot?,pairing:PairingDisplay?,ui:HubViewModel,
                  controller:HubController,tools:HubToolsState,wide:Boolean){
    if(tools.compatibilityOpen){
        HubCompatibilityScreen(state,tools,controller,wide)
        return
    }
    val page=ui.nodeSettingsPage
    val goHome={ui.nodeSettingsPage=SETTINGS_HOME}
    BoxWithConstraints(Modifier.fillMaxSize()){
        val twoPanes=wide && maxWidth>=740.dp && maxHeight>=480.dp
        if(twoPanes) {
            Row(Modifier.fillMaxSize().padding(16.dp),
                horizontalArrangement=Arrangement.spacedBy(16.dp)){
                LazyColumn(Modifier.weight(0.95f).fillMaxHeight(),
                    verticalArrangement=Arrangement.spacedBy(10.dp)){
                    item(key="p2p"){PeerEntryCard()}
                    item(key="status"){NodeStatusOverview(state,tools,controller)}
                    item(key="destinations"){SettingsDestinations(state,tools){ui.nodeSettingsPage=it}}
                    item(key="padding"){Spacer(Modifier.height(10.dp))}
                }
                LazyColumn(Modifier.weight(1.05f).fillMaxHeight(),
                    verticalArrangement=Arrangement.spacedBy(12.dp)){
                    if(page==SETTINGS_HOME) {
                        item(key="hint"){
                            HubCard(Modifier.fillMaxWidth()){
                                Text(hubLabel("选择左侧项目进行配置","Choose a category to configure"),
                                    style=MaterialTheme.typography.titleMedium)
                                Text(hubLabel("常用状态与立即同步已在左侧。详细设置按需打开。",
                                    "Status and immediate sync are on the left. Open only the settings you need."),
                                    style=MaterialTheme.typography.bodySmall,
                                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }else{
                        item(key="header"){SettingsCategoryHeader(page,goHome)}
                        settingsCategoryContent(page,state,pairing,ui,controller,tools)
                    }
                }
            }
        }else{
            LazyColumn(Modifier.fillMaxSize(),
                contentPadding=PaddingValues(16.dp),
                verticalArrangement=Arrangement.spacedBy(12.dp)){
                if(page==SETTINGS_HOME){
                    item(key="p2p"){PeerEntryCard()}
                    item(key="status"){NodeStatusOverview(state,tools,controller)}
                    item(key="destinations"){SettingsDestinations(state,tools){ui.nodeSettingsPage=it}}
                }else{
                    item(key="header"){SettingsCategoryHeader(page,goHome)}
                    settingsCategoryContent(page,state,pairing,ui,controller,tools)
                }
            }
        }
    }
}
