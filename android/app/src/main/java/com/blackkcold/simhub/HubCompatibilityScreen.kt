package com.blackkcold.simhub

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/**
 * Dedicated advanced compatibility surface, deliberately separated from normal
 * permissions, Relay and enrollment. Every button has a real controller action;
 * privileged platform changes are never silently issued in the background.
 */
@Composable
fun HubCompatibilityScreen(
    state:HubSnapshot?,tools:HubToolsState,controller:HubController,wide:Boolean
){
    val status=tools.compatibilityStatus
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement=Arrangement.spacedBy(14.dp)
    ){
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
            OutlinedButton(onClick=controller::closeCompatibility){
                Text(hubLabel("返回设置","Back to settings"))
            }
            Column(Modifier.weight(1f)){
                Text(hubLabel("系统兼容实验室","System compatibility lab"),
                    style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                Text(hubLabel("独立授权 · 单次测试 · 全程留痕","Separate permissions · manual tests · audit trail"),
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HubCard(Modifier.fillMaxWidth()){
            StatePill(hubLabel("所有实验均需主动操作","All tests require manual action"),true)
            Spacer(Modifier.height(8.dp))
            Text(hubLabel(
                "本页面只用于验证 Android 系统能力。Shizuku 的 ADB 权限不等于短信读取权限，也不能证明已获得 Android 17 的验证码豁免。不会自动停用系统信息、变更 AppOps 或关闭 OTP 保护。",
                "This screen diagnoses Android capabilities. Shizuku ADB access is NOT SMS access or proof of an Android 17 OTP exemption. It never disables OEM Messages, changes AppOps or switches off OTP protections."
            ),style=MaterialTheme.typography.bodyMedium)
        }
        if(tools.compatibilityBusy)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(tools.compatibilityError.isNotBlank()){
            Text(tools.compatibilityError,color=MaterialTheme.colorScheme.error,
                style=MaterialTheme.typography.bodyMedium)
        }
        if(tools.compatibilityNote.isNotBlank()){
            Text(tools.compatibilityNote,style=MaterialTheme.typography.bodySmall)
        }

        HubCard(Modifier.fillMaxWidth()){
            Text(hubLabel("01 · 系统与短信能力","01 · Android and SMS capabilities"),
                style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            CompatibilityValue(hubLabel("机型 / ROM","Model / ROM"),
                status?.optString("manufacturer","—").orEmpty()+" "+status?.optString("model","—").orEmpty())
            CompatibilityValue(hubLabel("系统 SDK / App target","Android SDK / app target"),
                "${status?.optInt("sdk",Build.VERSION.SDK_INT)} / ${status?.optInt("targetSdk",37)}")
            CompatibilityValue(hubLabel("默认信息角色","Default SMS role"),
                if(status?.optBoolean("defaultSmsRole")==true)hubLabel("SIM Hub 已接管","SIM Hub handles SMS") else hubLabel("原厂 / 其他信息应用","OEM / other SMS app"))
            CompatibilityValue("READ_SMS / RECEIVE_SMS / SEND_SMS",
                listOf("readSms","receiveSms","sendSms").joinToString(" / "){
                    if(status?.optBoolean(it)==true)hubLabel("允许","Yes") else hubLabel("未允许","No")
                })
            CompatibilityValue(hubLabel("OTP AppOp（只读）","OTP AppOp (read-only)"),
                status?.optString("otpAppOp","—") ?: "—")
            CompatibilityValue(hubLabel("验证码访问规则","OTP access policy"),
                when(status?.optString("otpPolicy","")){
                    "pre_android_17"->hubLabel("Android 17 之前；仍可能存在格式限制","Pre-Android 17; format-specific limits can apply")
                    "android_17_generic_otp_protection"->hubLabel("Android 17 通用 OTP 保护适用","Android 17 generic OTP protection applies")
                    else->hubLabel("存在系统/目标 SDK 的兼容性差异；不代表全部豁免","System/target compatibility differs; not a full exemption")
                })
            if(status?.has("providerProbe")==true)
                CompatibilityValue(hubLabel("短信数据库探测","SMS Provider probe"),
                    status.optString("providerProbe"))
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton(onClick=controller::refreshCompatibility,modifier=Modifier.weight(1f)){
                    Text(hubLabel("刷新能力","Refresh"),maxLines=1)
                }
                Button(onClick=controller::probeSmsProvider,modifier=Modifier.weight(1f)){
                    Text(hubLabel("测试数据库权限","Probe Provider"),maxLines=1)
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick=controller::reconcileSmsNow,enabled=state?.enrolled==true,
                modifier=Modifier.fillMaxWidth()){
                Text(hubLabel("手动补扫近期短信（最多100条/次）","Manually rescan recent SMS (max 100 per pass)"))
            }
            Text(hubLabel("探测不读取、不显示短信正文；补扫沿用现有端到端加密同步。",
                          "Probe does not display SMS content; reconciliation uses existing encrypted sync."),
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }

        HubCard(Modifier.fillMaxWidth()){
            Text(hubLabel("02 · Shizuku（独立可选授权）","02 · Shizuku (separate optional authorization)"),
                style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            CompatibilityValue(hubLabel("安装状态","Installed"),
                if(status?.optBoolean("shizukuInstalled")==true)hubLabel("已安装","Installed") else hubLabel("未检测到","Not detected"))
            CompatibilityValue(hubLabel("Binder 服务","Binder service"),
                if(status?.optBoolean("shizukuRunning")==true)hubLabel("运行中","Connected") else hubLabel("不可用","Unavailable"))
            CompatibilityValue(hubLabel("SIM Hub 的 Shizuku 授权","SIM Hub Shizuku permission"),
                if(status?.optBoolean("shizukuAuthorized")==true)hubLabel("已授权","Authorized") else hubLabel("未授权","Not authorized"))
            if(status?.optBoolean("shizukuAuthorized")==true)
                CompatibilityValue(hubLabel("服务身份 / API","Service UID / API"),
                    "${status.optInt("shizukuServiceUid",-1)} / ${status.optInt("shizukuApiVersion",-1)}")
            Text(hubLabel(
                "步骤：安装官方 Shizuku → 在开发者选项开启无线调试并启动服务 → 在此处单独授权 → 刷新验证。重启手机后，非 Root Shizuku 通常需要重新启动。",
                "Setup: install official Shizuku → start service using wireless debugging → grant SIM Hub here → refresh. Non-root Shizuku usually needs restarting after reboot."
            ),style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxWidth()){
                OutlinedButton(onClick=controller::openShizukuManager,modifier=Modifier.weight(1f)){
                    Text(hubLabel("打开 Shizuku","Open Shizuku"),maxLines=1)
                }
                Button(onClick=controller::requestShizukuAuthorization,
                    enabled=status?.optBoolean("shizukuRunning")==true &&
                        status.optBoolean("shizukuAuthorized")!=true,
                    modifier=Modifier.weight(1f)){
                    Text(hubLabel("单独申请授权","Grant access"),maxLines=1)
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick=controller::copyAdbDiagnostics,modifier=Modifier.fillMaxWidth()){
                Text(hubLabel("复制只读 ADB / Rish 检查命令","Copy read-only ADB / Rish diagnostics"))
            }
            Text(hubLabel(
                "Shizuku 授权后只显示状态和手动诊断；不自动执行 shell、停用系统组件或修改验证码安全策略。",
                "Authorization enables status checks only here. No automatic shell operations, package disabling or OTP policy changes."
            ),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }

        HubCard(Modifier.fillMaxWidth()){
            Text(hubLabel("03 · Android 伴侣设备关联","03 · Android Companion Device association"),
                style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            CompatibilityValue(hubLabel("CDM 系统支持","CDM availability"),
                if(status?.optBoolean("associationSupported")==true)hubLabel("可申请","Available") else hubLabel("系统不支持","Unsupported"))
            CompatibilityValue(hubLabel("SIM Hub 关联数","SIM Hub associations"),
                (status?.optInt("associations",0)?:0).toString())
            Text(hubLabel(
                "仅在已配对可信 Relay 后允许申请自管理关联，必须经 Android 系统确认。CDM 关联本身不证明获准实时读取任意第三方验证码。",
                "Only offer self-managed association after trusted Relay enrollment. Android requires confirmation. An association alone does NOT prove unrestricted real-time OTP access."
            ),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(9.dp))
            Button(onClick=controller::startCompanionAssociation,
                enabled=state?.enrolled==true&&Build.VERSION.SDK_INT>=33,
                modifier=Modifier.fillMaxWidth()){
                Text(hubLabel("由系统确认创建伴侣关联","Request companion association"))
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick=controller::removeCompanionAssociations,
                enabled=(status?.optInt("associations",0)?:0)>0,
                modifier=Modifier.fillMaxWidth()){
                Text(hubLabel("解除 SIM Hub 系统关联","Remove SIM Hub associations"))
            }
        }

        HubCard(Modifier.fillMaxWidth()){
            Text(hubLabel("04 · 厂商及 Google 兼容性","04 · OEM and Google compatibility"),
                style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(hubLabel(
                "vivo / OPPO / 华为：厂商推送 SDK 可以帮助自己的消息/命令到达，但不是读取所有运营商 OTP 的 API。请先检查自启动与后台电池管理。",
                "vivo / OPPO / Huawei: push SDKs help deliver the app's own events, not arbitrary carrier OTPs. Check background and battery policies first."
            ),style=MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick=controller::openBatteryOptimization,
                modifier=Modifier.fillMaxWidth()){
                Text(hubLabel("打开后台与电池优化设置","Open background/battery settings"))
            }
            Spacer(Modifier.height(8.dp))
            Text(hubLabel(
                "SMS Retriever、User Consent 与 WebOTP 只适用于符合其协议和授权的验证码，不构成跨应用 OTP 的通用 API。保持当前 SMS 广播 + Provider 补扫作为回退。",
                "SMS Retriever, User Consent and WebOTP work for eligible verification flows, not general third-party OTP access. Preserve SMS broadcasts and Provider reconciliation as fallback."
            ),style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
        }

        HubCard(Modifier.fillMaxWidth()){
            Text(hubLabel("05 · 兼容性审计记录","05 · Compatibility audit trail"),
                style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(hubLabel("独立于开发者日志，只保存操作类型、状态和时间，不保存号码、短信正文或验证码。",
                "Separate from developer logs: action, outcome and timestamp only; no numbers, SMS text or OTP."),
                style=MaterialTheme.typography.bodySmall)
            if(tools.compatibilityAudit.isNotBlank()){
                Spacer(Modifier.height(8.dp))
                Text(tools.compatibilityAudit.take(6500),style=MaterialTheme.typography.bodySmall,
                    modifier=Modifier.fillMaxWidth().heightIn(max=220.dp)
                        .verticalScroll(rememberScrollState()))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton(onClick=controller::refreshCompatibilityAudit,modifier=Modifier.weight(1f)){
                    Text(hubLabel("刷新记录","Refresh audit"))
                }
                OutlinedButton(onClick=controller::clearCompatibilityAudit,modifier=Modifier.weight(1f)){
                    Text(hubLabel("清除记录","Clear audit"))
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick=controller::exportDiagnostics,modifier=Modifier.fillMaxWidth()){
                Text(hubLabel("导出脱敏诊断 ZIP（含本模块日志）","Export redacted diagnostic ZIP"))
            }
        }
    }
}

@Composable
private fun CompatibilityValue(label:String,value:String){
    Column(Modifier.fillMaxWidth().padding(vertical=5.dp)){
        Text(label,style=MaterialTheme.typography.labelMedium,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value,style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.Medium)
    }
}
