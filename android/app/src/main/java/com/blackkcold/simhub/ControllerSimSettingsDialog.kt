package com.blackkcold.simhub

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun ControllerSimSettingsDialog(api:ControllerSecureClient,device:JSONObject,channel:JSONObject,
                                        onClose:()->Unit,onSaved:(ControllerVaultSettingsSnapshot)->Unit){
    val deviceId=device.optString("id");val channelId=ControllerSendTarget.channelId(channel)
    val revision=ControllerSendTarget.revision(channel)
    val identity=ControllerVaultSettingsCrypto.key(deviceId,channelId,revision)
    val scope=rememberCoroutineScope()
    var alias by remember(identity){mutableStateOf("")}
    var phone by remember(identity){mutableStateOf("")}
    var loaded by remember(identity){mutableStateOf(false)}
    var busy by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    LaunchedEffect(api,identity){
        try{
            val snapshot=withContext(Dispatchers.IO){ControllerVaultSettings(api).load()}
            alias=snapshot.settings.getJSONObject("channelAliases").optString(identity)
            phone=snapshot.settings.getJSONObject("phoneOverrides").optString(identity)
            loaded=true
        }catch(_:Exception){error="加密标签暂不可读取，请刷新中继后重试"}
    }
    AlertDialog(onDismissRequest={if(!busy)onClose()},title={Text(hubLabel("编辑私密 SIM 标签","Edit private SIM labels"))},
        text={Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text(device.optString("name")+" · "+channel.optString("displayName").ifBlank{channelId})
            Text(hubLabel("与网页管理端端到端加密同步。标签只用于显示，不改变号码、SIM 身份或发送路由。",
                "End-to-end encrypted sync with Web. Labels do not change the SIM or sending route."),style=MaterialTheme.typography.bodySmall)
            if(!loaded&&error.isEmpty())LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedTextField(alias,{alias=it.take(80)},enabled=loaded&&!busy,singleLine=true,
                label={Text(hubLabel("SIM 别名","SIM alias"))},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(phone,{phone=it.take(32)},enabled=loaded&&!busy,singleLine=true,
                label={Text(hubLabel("手动显示号码","Display phone override"))},modifier=Modifier.fillMaxWidth())
            Text(hubLabel("留空可清除覆盖值。","Leave empty to clear an override."),style=MaterialTheme.typography.bodySmall)
            if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
        }},confirmButton={TextButton(enabled=loaded&&!busy&&revision>0,onClick={
            busy=true;error="";scope.launch{
                try{
                    val saved=withContext(Dispatchers.IO){ControllerVaultSettings(api).update(deviceId,channelId,revision,alias.trim(),phone.trim())}
                    onSaved(saved)
                }catch(_:Exception){error="加密标签未确认保存，请重试；其他 SIM 标签未覆盖"}
                finally{busy=false}
            }
        }){Text(hubLabel("加密保存","Save encrypted"))}},
        dismissButton={TextButton(enabled=!busy,onClick=onClose){Text(hubLabel("取消","Cancel"))}})
}
