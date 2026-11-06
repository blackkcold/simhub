package com.blackkcold.simhub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

internal data class RecoveryHint(val code:String,val zh:String,val en:String)

/** Recommendations require reported evidence; missing fields never imply healthy operation. */
internal object NodeRecoveryGuidance {
    fun queueAgeSeconds(state:JSONObject,oldestKey:String,countKey:String):Long? {
        if(!state.has(countKey)||state.optLong(countKey)<=0)return null
        val oldest=state.optLong(oldestKey,0)
        val sample=state.optLong("sampledAt",0)
        return if(oldest>0&&sample>=oldest)sample-oldest else null
    }

    fun hints(device:JSONObject,nowSeconds:Long=System.currentTimeMillis()/1000):List<RecoveryHint> {
        val state=device.optJSONObject("state")?:JSONObject()
        val result=mutableListOf<RecoveryHint>()
        if(device.optBoolean("revoked")||device.optBoolean("resetRequested")||device.optBoolean("resetPending"))
            result+=RecoveryHint("lifecycle","此节点正在撤销或解绑。在节点的配对卡片完成清理确认；不要继续发送命令。",
                "This node is being revoked or reset. Complete cleanup in its enrollment card before sending commands.")
        if(state.optBoolean("pendingTokenRotation"))
            result+=RecoveryHint("token_recovery","连接 Relay 并执行一次同步以恢复凭据轮换。候选凭据即使显示过期也会先与服务器核实；不要先清除配对。",
                "Connect to Relay and run sync to reconcile token rotation. Even an expired candidate is checked with the server; keep the enrollment until recovery completes.")
        if(listOf("smsReadPermission","smsReceivePermission").any{state.has(it)&&!state.isNull(it)&&!state.optBoolean(it)})
            result+=RecoveryHint("sms_permission","在节点上授予短信读取和接收权限，然后补扫最近短信。",
                "Grant SMS read and receive access on the node, then rescan recent messages.")
        if(state.optString("network")=="OFFLINE")
            result+=RecoveryHint("offline","节点报告无网络。恢复网络后同步；待上传事件会保留在本机。",
                "The node reported no network. Restore connectivity and sync; queued events remain on the node.")
        if(state.optLong("nextRetryAt",state.optLong("nextSyncAllowedAt",0))>nowSeconds)
            result+=RecoveryHint("backoff","同步正在退避等待。可等待下一次重试，或在检查网络和 Relay 地址后从节点手动同步。",
                "Sync is waiting for its retry time. Wait, or verify the network and Relay address before a manual sync on the node.")
        if(state.optLong("pendingCommandAcks",0)>0)
            result+=RecoveryHint("ack_pending","命令回执尚未上传；先同步并查询原命令状态。重新创建发送任务可能重复发送短信。",
                "Command receipts are awaiting upload. Sync and inspect the original command; a new send task may duplicate an SMS.")
        if(state.optString("lastEventQueueError").isNotBlank())
            result+=RecoveryHint("queue_failure","事件入队失败。检查本机剩余存储和脱敏诊断日志，恢复后重新补扫短信。",
                "An event could not be queued. Check device storage and redacted diagnostics, then rescan messages after recovery.")
        ota(state)?.let{result+=it}
        return result
    }

    fun ota(state:JSONObject):RecoveryHint? = when {
        state.optString("remoteOtaStage")=="awaiting_confirmation"->RecoveryHint("ota_confirmation",
            "在节点上完成 Android 安装确认；确认前不会报告更新成功。",
            "Complete Android's installation prompt on the node. Success is reported only after the installed version is verified.")
        state.optString("remoteOtaStage")=="verification_pending"->RecoveryHint("ota_verification",
            "安装结果尚未核实。打开节点应用检查实际版本并同步状态，暂勿重复下发更新。",
            "The installation result is unverified. Open the node app, check the installed version and sync before requesting another update.")
        state.optString("remoteOtaError") in setOf("update_interrupted_before_install","install_session_unavailable")->RecoveryHint("ota_interrupted",
            "原更新已中断。确认节点当前版本和安装权限后，可重新发起更新；旧下载不会继续标记成功。",
            "The update was interrupted. Check the installed version and installation permission, then request a new update if needed.")
        state.optString("remoteOtaError")=="update_cancelled"->RecoveryHint("ota_cancelled",
            "更新已取消。如需继续，请重新发起一次更新。",
            "The update was cancelled. Start a new update when ready.")
        else->null
    }
}

@Composable
internal fun NodeRecoveryDetails(device:JSONObject,showOta:Boolean=true){
    val state=device.optJSONObject("state")?:JSONObject()
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)){
        listOf(Triple("oldestPendingEventAt","pendingEvents",hubLabel("最早待上传事件","Oldest queued event")),
            Triple("oldestPendingAckAt","pendingCommandAcks",hubLabel("最早待上传回执","Oldest queued receipt"))).forEach{(key,count,label)->
            if(state.has(key)){
                InfoRow(label,ControllerHealthFacts.reportedTime(state,key))
                NodeRecoveryGuidance.queueAgeSeconds(state,key,count)?.let{age->
                    val elapsed=if(age<60)"${age}s" else if(age<3600)"${age/60}m" else "${age/3600}h ${(age%3600)/60}m"
                    Text(hubLabel("截至采样时已等待 ","Waiting at snapshot: ")+elapsed,
                        style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if(state.optLong("nextRetryAt",0)>0)
            InfoRow(hubLabel("下一次同步重试","Next sync retry"),ControllerHealthFacts.reportedTime(state,"nextRetryAt"))
        if(state.optBoolean("pendingTokenRotation"))
            InfoRow(hubLabel("候选凭据截止时间","Candidate credential expiry"),ControllerHealthFacts.reportedTime(state,"tokenRotationExpiresAt"))
        NodeRecoveryGuidance.hints(device).filter{showOta||!it.code.startsWith("ota_")}.forEach{hint->
            Text(hubLabel(hint.zh,hint.en),style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
