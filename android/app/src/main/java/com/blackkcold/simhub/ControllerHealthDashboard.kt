package com.blackkcold.simhub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/**
 * Read-only native Compose overview of Relay-reported node snapshots.
 * No WebView, timers, platform permissions, background jobs or command state changes.
 * Unknown values are never substituted with zero or a successful health verdict.
 */
internal object ControllerHealthFacts {
    fun knownNumber(state:JSONObject,name:String):String =
        if(state.has(name)&&!state.isNull(name)) state.opt(name).toString() else "—"

    fun reportedTime(state:JSONObject,name:String):String {
        val seconds=state.optLong(name,0)
        return if(seconds>0) DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT)
            .format(Date(seconds*1000)) else "—"
    }

    fun permission(state:JSONObject,name:String):String =
        if(!state.has(name)||state.isNull(name)) "—"
        else if(state.optBoolean(name)) "已授权" else "未授权"

    fun batteryPercent(state:JSONObject):Int? {
        if(!state.has("batteryPct")||state.isNull("batteryPct"))return null
        val n=state.optInt("batteryPct",-1)
        return if(n in 0..100)n else null
    }
}

@Composable
internal fun ControllerHealthSummary(device:JSONObject){
    val state=device.optJSONObject("state")?:JSONObject()
    val battery=ControllerHealthFacts.batteryPercent(state)
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
        BoxWithConstraints(Modifier.fillMaxWidth()){
            if(maxWidth>=600.dp){
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    ControllerMetric(hubLabel("设备电量","Battery"),battery?.let{"$it%"}?:"—",
                        R.drawable.ic_hub_battery,Modifier.weight(1f))
                    ControllerMetric(hubLabel("短信数量","Messages"),
                        if(device.has("smsCount"))device.optString("smsCount") else "—",
                        R.drawable.ic_hub_messages,Modifier.weight(1f))
                    ControllerMetric(hubLabel("待上传事件","Pending events"),
                        ControllerHealthFacts.knownNumber(state,"pendingEvents"),
                        R.drawable.ic_hub_sync,Modifier.weight(1f))
                }
            }else{
                Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){
                        ControllerMetric(hubLabel("设备电量","Battery"),battery?.let{"$it%"}?:"—",
                            R.drawable.ic_hub_battery,Modifier.weight(1f))
                        ControllerMetric(hubLabel("短信数量","Messages"),
                            if(device.has("smsCount"))device.optString("smsCount") else "—",
                            R.drawable.ic_hub_messages,Modifier.weight(1f))
                    }
                    ControllerMetric(hubLabel("待上传事件","Pending events"),
                        ControllerHealthFacts.knownNumber(state,"pendingEvents"),
                        R.drawable.ic_hub_sync,Modifier.fillMaxWidth())
                }
            }
        }
        HubCard(Modifier.fillMaxWidth()){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                Box(Modifier.size(76.dp),contentAlignment=Alignment.Center){
                    if(battery!=null)CircularProgressIndicator(
                        progress={battery/100f},modifier=Modifier.fillMaxSize(),
                        strokeWidth=6.dp,color=MaterialTheme.colorScheme.primary)
                    else CircularProgressIndicator(progress={0f},modifier=Modifier.fillMaxSize(),
                        strokeWidth=6.dp,color=MaterialTheme.colorScheme.outlineVariant)
                    Text(battery?.let{"$it%"}?:"—",fontWeight=FontWeight.Bold,
                        style=MaterialTheme.typography.titleMedium)
                }
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){
                    Text(hubLabel("设备连接与电量","Connection & power"),
                        style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                    Text((state.optString("network").ifBlank{"—"})+" · "+
                        if(state.optBoolean("charging"))hubLabel("充电中","Charging")
                        else if(state.has("charging"))hubLabel("未充电","Not charging")
                        else hubLabel("充电状态未知","Charging unknown"),
                        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(!device.optBoolean("online")||device.optBoolean("revoked"))
                        Text(hubLabel("离线：显示最近上报的状态","Offline: showing last reported status"),
                            style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.error)
                }
            }
            InfoRow(hubLabel("应用版本","App version"),device.optString("appVersion","—"))
            InfoRow(hubLabel("蜂窝数据回退","Data fallback"),
                if(state.optBoolean("dataFallbackEnabled"))state.optString("dataFallbackStatus","已启用")
                else if(state.has("dataFallbackEnabled"))hubLabel("未启用","Disabled") else "—")
        }
    }
}

@Composable
internal fun ControllerHealthCommunication(device:JSONObject){
    val s=device.optJSONObject("state")?:JSONObject()
    HubCard(Modifier.fillMaxWidth()){
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
            HubIcon(R.drawable.ic_hub_sim,Modifier.size(24.dp),MaterialTheme.colorScheme.primary)
            Text(hubLabel("SIM 与通信能力","SIM & messaging capability"),
                style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
        }
        Text(ControllerNodePolicy.sendModeLabel(device),style=MaterialTheme.typography.bodyMedium,
            color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.SemiBold)
        InfoRow(hubLabel("短信接收状态","SMS operation"),
            if(!s.has("smsOperational"))hubLabel("未验证","Unverified")
            else if(s.optBoolean("smsOperational"))hubLabel("短信可用","Available")
            else hubLabel("短信不可用","Unavailable"))
        InfoRow(hubLabel("短信读取权限","Read permission"),ControllerHealthFacts.permission(s,"smsReadPermission"))
        InfoRow(hubLabel("短信广播权限","Receive permission"),ControllerHealthFacts.permission(s,"smsReceivePermission"))
        InfoRow(hubLabel("系统短信发送权限","Send permission"),ControllerHealthFacts.permission(s,"smsSendPermission"))
        InfoRow(hubLabel("实际远程发送能力","Effective remote send"),
            if(ControllerNodePolicy.canSend(device))hubLabel("可用","Available")
            else hubLabel("只读或不可用","Read-only / unavailable"))
        if(s.optString("smsMode")=="companion"){
            Text(hubLabel("不接管模式不因系统发送权限已授权而自动开放发送。","Companion mode does not enable sending merely because SEND_SMS is granted."),
                style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ControllerHealthSync(device:JSONObject){
    val s=device.optJSONObject("state")?:JSONObject()
    HubCard(Modifier.fillMaxWidth()){
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
            HubIcon(R.drawable.ic_hub_sync,Modifier.size(23.dp),MaterialTheme.colorScheme.primary)
            Text(hubLabel("短信同步与确认","SMS sync & acknowledgements"),
                style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
        }
        Text(hubLabel("最近成功同步","Last successful sync")+" · "+ControllerHealthFacts.reportedTime(s,"lastSyncSuccessAt"),
            color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.bodyMedium,
            fontWeight=FontWeight.SemiBold)
        InfoRow(hubLabel("待上传事件","Pending events"),ControllerHealthFacts.knownNumber(s,"pendingEvents"))
        InfoRow(hubLabel("待上传命令 ACK","Pending command ACKs"),ControllerHealthFacts.knownNumber(s,"pendingCommandAcks"))
        InfoRow(hubLabel("累计确认上传","Confirmed uploads"),ControllerHealthFacts.knownNumber(s,"uploadedEventTotal"))
        InfoRow(hubLabel("上传回执","Upload receipts"),ControllerHealthFacts.knownNumber(s,"uploadedEventReceipts"))
        InfoRow(hubLabel("上次上传数量","Last batch size"),ControllerHealthFacts.knownNumber(s,"lastEventUploadCount"))
        Text(hubLabel("回执计数与累计确认可能不是同一口径，不据此计算成功率。","Receipt and total counters may differ in scope; no success rate is inferred."),
            style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ControllerHealthTimeline(device:JSONObject){
    val s=device.optJSONObject("state")?:JSONObject()
    val events=listOf(
        Triple("SMS Provider","lastSmsProviderChangeAt",R.drawable.ic_hub_sim),
        Triple(hubLabel("短信广播","SMS broadcast"),"lastSmsBroadcastAt",R.drawable.ic_hub_messages),
        Triple(hubLabel("历史补扫","History rescan"),"lastReconcileAt",R.drawable.ic_hub_sync),
        Triple(hubLabel("事件上传","Event upload"),"lastEventUploadAt",R.drawable.ic_hub_sync),
        Triple(hubLabel("同步完成","Sync completed"),"lastSyncSuccessAt",R.drawable.ic_hub_shield)
    ).filter{s.optLong(it.second,0)>0}.sortedByDescending{s.optLong(it.second,0)}
    HubCard(Modifier.fillMaxWidth()){
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
            HubIcon(R.drawable.ic_hub_tasks,Modifier.size(23.dp),MaterialTheme.colorScheme.primary)
            Text(hubLabel("最近事件时间线","Recent events"),style=MaterialTheme.typography.titleMedium,
                fontWeight=FontWeight.SemiBold)
        }
        if(events.isEmpty())Text(hubLabel("暂无事件时间数据","No reported activity yet"),
            color=MaterialTheme.colorScheme.onSurfaceVariant)
        events.forEach { event->
            Row(Modifier.fillMaxWidth().padding(vertical=6.dp),
                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                HubIcon(event.third,Modifier.size(18.dp),MaterialTheme.colorScheme.primary)
                Text(event.first,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,
                    maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(ControllerHealthFacts.reportedTime(s,event.second),
                    style=MaterialTheme.typography.labelSmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(hubLabel("当前仅有事件快照；不伪造历史趋势曲线。","Event snapshots only; no fabricated trend."),
            style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ControllerHealthDiagnostics(device:JSONObject){
    val s=device.optJSONObject("state")?:JSONObject()
    val tasks=s.optJSONArray("pendingEventTasks")
    val errors=listOf("lastUploadError","lastEventQueueError","lastSyncError",
        "smsProviderError","stateCollectionError").filter{s.optString(it).isNotBlank()}
    HubCard(Modifier.fillMaxWidth()){
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
            HubIcon(R.drawable.ic_hub_shield,Modifier.size(23.dp),MaterialTheme.colorScheme.primary)
            Text(hubLabel("任务与运行诊断","Tasks & diagnostics"),
                style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
        }
        InfoRow(hubLabel("待上传事件","Queued events"),ControllerHealthFacts.knownNumber(s,"pendingEvents"))
        InfoRow(hubLabel("待确认 ACK","Queued ACKs"),ControllerHealthFacts.knownNumber(s,"pendingCommandAcks"))
        if(tasks!=null && tasks.length()>0){
            Text(hubLabel("待执行事件","Pending event tasks"),fontWeight=FontWeight.SemiBold)
            for(i in 0 until minOf(tasks.length(),5)){
                val task=tasks.optJSONObject(i)?:continue
                InfoRow(task.optString("kind","—"),ControllerHealthFacts.reportedTime(task,"queuedAt"))
            }
        }
        if(errors.isEmpty()){
            Text(hubLabel("未报告同步或采集异常","No reported sync or collection errors"),
                color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.bodySmall)
        }else{
            errors.forEach{key->
                Text(key+" · "+s.optString(key),color=MaterialTheme.colorScheme.error,
                    style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}
