package com.blackkcold.simhub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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

/** Components intentionally remain presentation-only; actions run in ControllerWorkspace. */
@Composable
internal fun ControllerMetric(title:String,value:String,icon:Int,modifier:Modifier=Modifier){
    HubCard(modifier){
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer,
                RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center){
                HubIcon(icon,Modifier.size(21.dp),MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f)){
                Text(value,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,maxLines=1)
                Text(title,style=MaterialTheme.typography.labelSmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2)
            }
        }
    }
}

@Composable
internal fun ControllerTaskRow(command:JSONObject){
    val state=command.optString("state","queued")
    val error=state in listOf("failed","rejected","expired")
    val done=state in listOf("sent","delivered","succeeded")
    Row(Modifier.fillMaxWidth().padding(vertical=9.dp),
        horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.Top){
        Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.surfaceVariant,
            RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center){
            HubIcon(if(error)R.drawable.ic_hub_shield else if(done)R.drawable.ic_hub_sync else R.drawable.ic_hub_tasks,
                Modifier.size(20.dp),if(error)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
            Text(command.optString("type").replace('.',' '),fontWeight=FontWeight.SemiBold,
                style=MaterialTheme.typography.bodyMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(ControllerCommandProgress.describe(command),
                style=MaterialTheme.typography.bodySmall,
                color=if(error)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT)
                .format(Date(command.optLong("createdAt")*1000)),
                style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StatePill(when(state){"queued"->hubLabel("排队","Queued");"dispatched"->hubLabel("已领取","Dispatched");
            "submitted"->hubLabel("已提交","Submitted");"succeeded"->hubLabel("完成","Completed");
            "sent"->hubLabel("已发送","Sent");"delivered"->hubLabel("送达","Delivered");
            "failed"->hubLabel("失败","Failed");"rejected"->hubLabel("拒绝","Rejected");
            "expired"->hubLabel("过期","Expired");else->state.take(10)},
            done)
    }
}

@Composable
internal fun ControllerDeviceCard(device:JSONObject,onClick:()->Unit){
    val state=device.optJSONObject("state")?:JSONObject()
    val online=device.optBoolean("online") && !device.optBoolean("revoked")
    Card(onClick=onClick,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),
        colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)){
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(11.dp)){
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer,
                    RoundedCornerShape(14.dp)),contentAlignment=Alignment.Center){
                    HubIcon(R.drawable.ic_hub_devices,Modifier.size(24.dp),MaterialTheme.colorScheme.primary)
                }
                Column(Modifier.weight(1f)){
                    Text(device.optString("name","SIM 节点"),fontWeight=FontWeight.SemiBold,
                        style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(device.optString("model").ifBlank{device.optString("nodeType","Android")},
                        color=MaterialTheme.colorScheme.onSurfaceVariant,
                        style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                StatePill(if(online)"在线" else "离线",online)
            }
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)){
                HubIcon(R.drawable.ic_hub_sim,Modifier.size(16.dp),MaterialTheme.colorScheme.primary)
                Text(ControllerNodePolicy.channels(device).length().toString()+" SIM",style=MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                HubIcon(R.drawable.ic_hub_battery,Modifier.size(16.dp))
                Text(state.optString("batteryPct","—")+"%",style=MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(7.dp))
                HubIcon(R.drawable.ic_hub_network,Modifier.size(16.dp))
                Text(state.optString("network","—"),style=MaterialTheme.typography.labelMedium,
                    maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Text(ControllerNodePolicy.sendModeLabel(device),modifier=Modifier.weight(1f),
                    color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall,maxLines=2)
                Text(hubLabel("详情 ›","Details ›"),color=MaterialTheme.colorScheme.primary,
                    style=MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
internal fun ControllerDeviceDetails(device:JSONObject,busy:Boolean,lastStatus:String,
    error:String,onClose:()->Unit,onAction:(String)->Unit,onManage:(String)->Unit,onAdvanced:()->Unit,
    locationSample:ControllerLocationSample?,locationStatus:String,locationLoading:Boolean,
    onLocationLoad:()->Unit,onLocationOpen:()->Unit){
    val state=device.optJSONObject("state")?:JSONObject()
    val channels=ControllerNodePolicy.channels(device)
    val isAndroid=device.optString("nodeType")!="modem"
    val otaStage=state.optString("remoteOtaStage","idle")
    val installed=device.optInt("appVersionCode",state.optInt("appVersionCode",0))
    val target=state.optInt("remoteOtaTargetCode",0)
    val pendingVersion=otaStage in listOf("checking","downloading","verified","installing",
        "awaiting_confirmation","verification_pending") && target>0 && installed>=target
    val staleInstall=otaStage=="installing" && state.optLong("remoteOtaUpdatedAt",0)>0 &&
        System.currentTimeMillis()/1000-state.optLong("remoteOtaUpdatedAt")>=900
    val statusLabel=when {
        pendingVersion->hubLabel("目标版本已安装 · 等待回执","Target version installed · awaiting ACK")
        staleInstall || otaStage=="verification_pending"->hubLabel("状态待核实","Verification pending")
        else->when(otaStage) {
            "checking"->hubLabel("检查版本","Checking")
            "downloading"->hubLabel("下载中","Downloading")
            "verified"->hubLabel("已校验","Verified")
            "installing"->hubLabel("安装中","Installing")
            "awaiting_confirmation"->hubLabel("等待设备确认","Awaiting approval")
            "succeeded"->hubLabel("已完成","Completed")
            "failed"->hubLabel("更新失败","Failed")
            else->hubLabel("空闲","Idle")
        }
    }
    val otaReady=isAndroid && state.optBoolean("remoteOtaSupported") &&
        (state.optInt("sdk")>=36 || state.optBoolean("remoteOtaDeveloperOverride")) &&
        state.optBoolean("remoteOtaAuthorized") && state.optBoolean("remoteOtaInstallPermission")
    Column(Modifier.fillMaxSize().imePadding()){
        Row(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=10.dp),
            verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick=onClose){HubIcon(R.drawable.ic_hub_home,description=hubLabel("返回","Back"))}
            Column(Modifier.weight(1f)){
                Text(device.optString("name","设备详情"),style=MaterialTheme.typography.titleLarge,
                    fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(device.optString("model"),style=MaterialTheme.typography.labelSmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatePill(if(device.optBoolean("online"))"在线" else "离线",device.optBoolean("online"))
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(16.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp)){
            item { ControllerHealthSummary(device) }
            item { ControllerHealthCommunication(device) }
            if(isAndroid)item {
                ControllerLocationCard(device,locationSample,locationStatus,locationLoading,
                    onLocationLoad,onLocationOpen,{onAction("location-once")})
            }
            item{
                HubCard(Modifier.fillMaxWidth()){
                    HubSectionTitle(hubLabel("SIM 通道","SIM channels"))
                    if(channels.length()==0)Text(hubLabel("暂无可用通道","No available channels"))
                    for(i in 0 until channels.length()){
                        val c=channels.optJSONObject(i)?:continue
                        if(i>0)HorizontalDivider(Modifier.padding(vertical=8.dp))
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)){
                            HubIcon(R.drawable.ic_hub_sim,Modifier.size(22.dp),MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)){
                                Text(c.optString("alias").ifBlank{c.optString("displayName","SIM "+(i+1))},
                                    fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                                Text("Revision "+c.optInt("revision",c.optInt("channelRevision",1))+" · "+c.optString("carrierName","—"),
                                    style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            item { ControllerHealthSync(device) }
            item { ControllerHealthTimeline(device) }
            item { ControllerHealthDiagnostics(device) }
            item{
                HubCard(Modifier.fillMaxWidth()){
                    HubSectionTitle(hubLabel("远程操作","Remote actions"))
                    if(lastStatus.isNotBlank())Text(lastStatus,color=MaterialTheme.colorScheme.primary,
                        style=MaterialTheme.typography.bodySmall)
                    if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error,
                        style=MaterialTheme.typography.bodySmall)
                    Button(enabled=!busy,onClick={onAction("sync-recent")},modifier=Modifier.fillMaxWidth()){
                        HubIcon(R.drawable.ic_hub_sync,Modifier.size(19.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(hubLabel("同步最近短信","Sync recent messages"))
                    }
                    OutlinedButton(enabled=!busy,onClick={onAction("sync-older")},modifier=Modifier.fillMaxWidth()){
                        Text(hubLabel("同步更早短信","Sync older messages"))}
                    if(isAndroid && installed>=50)OutlinedButton(
                        enabled=!busy,onClick={onAction("restore-history")},
                        modifier=Modifier.fillMaxWidth()){
                        HubIcon(R.drawable.ic_hub_shield,Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(hubLabel("恢复本机短信历史（需验证）","Restore local SMS history"))
                    }
                    OutlinedButton(enabled=!busy,onClick={onAction("refresh")},modifier=Modifier.fillMaxWidth()){
                        Text(hubLabel("刷新设备状态","Refresh device state"))}
                    OutlinedButton(enabled=!busy,onClick={onAction("diagnostics")},modifier=Modifier.fillMaxWidth()){
                        Text(hubLabel("发起设备诊断","Request diagnostics"))}
                }
            }
            if(isAndroid)item{
                HubCard(Modifier.fillMaxWidth()){
                    HubSectionTitle(hubLabel("应用远程更新","Remote app update"))
                    Text(if(state.optBoolean("remoteOtaSupported"))
                        hubLabel("安装是否需要确认由安卓系统决定。","Android may require confirmation.")
                        else hubLabel("当前设备未报告支持远程更新。","Remote updates not supported."),
                        style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                    InfoRow(hubLabel("当前版本","Installed version"),"v"+device.optString("appVersion","—"))
                    InfoRow(hubLabel("更新状态","Status"),statusLabel)
                    Button(onClick={onAction("ota-install")},enabled=otaReady&&!busy,
                        modifier=Modifier.fillMaxWidth()){Text(hubLabel("更新到最新签名版本","Install latest signed version"))}
                    if(state.optString("remoteOtaStage") in listOf("checking","downloading","verified"))
                        TextButton(onClick={onAction("ota-cancel")},enabled=otaReady&&!busy){
                            Text(hubLabel("取消尚未提交的更新","Cancel pending update"))}
                }
            }
            item{
                HubCard(Modifier.fillMaxWidth()){
                    HubSectionTitle(hubLabel("设备管理","Device management"))
                    OutlinedButton(onClick={onManage("rename")},modifier=Modifier.fillMaxWidth()){
                        Text(hubLabel("修改设备名称","Rename device"))
                    }
                    if(isAndroid)OutlinedButton(onClick={onManage("network")},modifier=Modifier.fillMaxWidth()){
                        Text(hubLabel("备用数据 SIM 策略","Mobile data fallback policy"))
                    }
                    HorizontalDivider(Modifier.padding(vertical=10.dp))
                    Text(hubLabel("危险操作需要管理员二次验证，无法撤销。",
                        "Destructive actions require step-up authentication and cannot be undone."),
                        style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick={onManage("purge")},modifier=Modifier.fillMaxWidth()){
                        Text(hubLabel("清理该设备中继短信","Purge device SMS from relay"))
                    }
                    OutlinedButton(onClick={onManage("revoke")},modifier=Modifier.fillMaxWidth()){
                        Text(hubLabel("撤销设备授权","Revoke node authorization"))
                    }
                    OutlinedButton(onClick={onManage("delete")},modifier=Modifier.fillMaxWidth()){
                        Text(hubLabel("强制删除设备…","Force delete device…"))
                    }
                }
            }
            item{
                OutlinedButton(onClick=onAdvanced,modifier=Modifier.fillMaxWidth()){
                    HubIcon(R.drawable.ic_hub_shield,Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(hubLabel("更多高级管理（HTTPS）","More controls (HTTPS)"))
                }
            }
        }
    }
}
