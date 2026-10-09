package com.blackkcold.simhub

import androidx.compose.animation.AnimatedVisibility
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
    var developer by mutableStateOf(false)
    var logs by mutableStateOf("")
    var diagnostics by mutableStateOf("")
    var ota by mutableStateOf("")
    var installedAt by mutableStateOf("")
    var language by mutableIntStateOf(0)
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
    ToolSection("设备配对与连接",modifier){
        if(state?.enrolled==true){
            StatePill("已配对",true)
            Spacer(Modifier.height(10.dp))
            Text(state.device,fontWeight=FontWeight.Medium)
            Text(state.server,color=MaterialTheme.colorScheme.onSurfaceVariant,
                style=MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick=controller::resetEnrollment,modifier=Modifier.fillMaxWidth()){
                Text("解除配对并同步重置")
            }
        }else{
            Text("扫描管理后台生成的配对二维码，或使用短码连接。",
                color=MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Button(onClick=controller::scan,modifier=Modifier.fillMaxWidth()){
                Text("扫描二维码")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value=ui.enrollmentLink,onValueChange={ui.enrollmentLink=it},
                label={Text("配对链接")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedButton(onClick={controller.enrollLink(ui.enrollmentLink)},
                enabled=ui.enrollmentLink.startsWith("simhub://enroll"),
                modifier=Modifier.fillMaxWidth()){Text("确认配对")}
            HorizontalDivider(Modifier.padding(vertical=12.dp))
            OutlinedTextField(value=ui.serverUrl,onValueChange={ui.serverUrl=it},
                label={Text("Relay HTTPS 地址")},singleLine=true,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),
                modifier=Modifier.fillMaxWidth())
            OutlinedButton(onClick={controller.startPairing(ui.serverUrl)},
                enabled=ui.serverUrl.startsWith("https://"),
                modifier=Modifier.fillMaxWidth()){Text("生成配对短码")}
            if(pairing!=null){
                Spacer(Modifier.height(10.dp))
                Text(pairing.code.chunked(4).joinToString(" "),fontSize=26.sp,fontWeight=FontWeight.Bold)
                Text("指纹 · "+pairing.fingerprint,style=MaterialTheme.typography.labelSmall)
                Text(if(pairing.waiting)"等待管理员确认" else pairing.error.ifBlank{"配对完成"},
                    style=MaterialTheme.typography.bodySmall)
                if(pairing.waiting)LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=9.dp))
            }
        }
    }
}

@Composable
private fun PermissionsCard(state:HubSnapshot?,controller:HubController,modifier:Modifier){
    ToolSection("短信与系统权限",modifier){
        InfoRow("默认短信应用",if(state?.smsRole==true)"已授权" else "未授权")
        InfoRow("读取短信",if(state?.smsRead==true)"已授权" else "未授权")
        InfoRow("发送短信",if(state?.smsSend==true)"已授权" else "未授权")
        OutlinedButton(onClick=controller::requestSmsRole,modifier=Modifier.fillMaxWidth()){
            Text("设置默认短信应用")
        }
        Spacer(Modifier.height(7.dp))
        OutlinedButton(onClick=controller::requestAccess,modifier=Modifier.fillMaxWidth()){
            Text("授予必要权限")
        }
        Spacer(Modifier.height(7.dp))
        OutlinedButton(onClick=controller::requestContacts,modifier=Modifier.fillMaxWidth()){
            Text("授权读取联系人（可选）")
        }
    }
}

@Composable
private fun RelayRuntimeCard(state:HubSnapshot?,controller:HubController,modifier:Modifier){
    ToolSection("运行与短信同步",modifier){
        Row(verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){
                Text("低延迟前台连接",fontWeight=FontWeight.Medium)
                Text("开启后保持 Relay 控制命令监听；系统可能限制后台运行。",
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked=state?.realtime==true,onCheckedChange=controller::setRealtime,
                enabled=state?.enrolled==true)
        }
        HorizontalDivider(Modifier.padding(vertical=12.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxWidth()){
            OutlinedButton(onClick={controller.syncHistory(false)},enabled=state?.enrolled==true,
                modifier=Modifier.weight(1f)){Text("最近 100 条",maxLines=1)}
            OutlinedButton(onClick={controller.syncHistory(true)},enabled=state?.enrolled==true,
                modifier=Modifier.weight(1f)){Text("更早 100 条",maxLines=1)}
        }
        InfoRow("待上传事件",(state?.pending?:0).toString())
        InfoRow("最近同步",state?.lastSync?.takeIf{it>0}?.let{
            java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it*1000))
        }?:"—")
        if(state?.transportError?.isNotBlank()==true)
            Text("Relay: "+state.transportError,color=MaterialTheme.colorScheme.error)
        if(state?.providerError?.isNotBlank()==true)
            Text("SMS Provider: "+state.providerError,color=MaterialTheme.colorScheme.error)
        OutlinedButton(onClick=controller::refreshDiagnostics,modifier=Modifier.fillMaxWidth()){
            Text("采集并同步设备状态")
        }
    }
}

@Composable
private fun DiagnosticsCard(state:HubSnapshot?,tools:HubToolsState,controller:HubController,modifier:Modifier){
    ToolSection("高级工具与诊断",modifier){
        Row(verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(1f)){
                Text("开发者模式",fontWeight=FontWeight.Medium)
                Text("启用脱敏日志采集与问题排查",
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked=tools.developer,onCheckedChange=controller::setDeveloperEnabled)
        }
        HorizontalDivider(Modifier.padding(vertical=12.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxWidth()){
            OutlinedButton(onClick=controller::checkOta,modifier=Modifier.weight(1f)){
                Text("检查更新")
            }
            OutlinedButton(onClick=controller::exportDiagnostics,modifier=Modifier.weight(1f)){
                Text("导出诊断 ZIP")
            }
        }
        if(tools.ota.isNotBlank()){
            Spacer(Modifier.height(8.dp))
            Text(tools.ota,style=MaterialTheme.typography.bodySmall)
        }
        if(tools.developer){
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxWidth()){
                OutlinedButton(onClick=controller::viewLogs,modifier=Modifier.weight(1f)){Text("查看日志")}
                OutlinedButton(onClick=controller::clearLogs,modifier=Modifier.weight(1f)){Text("清理日志")}
            }
            if(tools.logs.isNotBlank()){
                Spacer(Modifier.height(8.dp))
                Text(tools.logs.takeLast(24000),style=MaterialTheme.typography.bodySmall,
                    modifier=Modifier.fillMaxWidth().heightIn(max=280.dp)
                        .verticalScroll(rememberScrollState()))
            }
            OutlinedButton(onClick=controller::refreshDiagnostics,modifier=Modifier.fillMaxWidth()){
                Text("查看设备状态 JSON")
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
    ToolSection("应用与系统",modifier){
        Text("显示语言",style=MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        var optionsOpen by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick={optionsOpen=true},modifier=Modifier.fillMaxWidth()){
                Text(listOf("跟随系统","简体中文","English").getOrElse(tools.language){"跟随系统"})
            }
            DropdownMenu(expanded=optionsOpen,onDismissRequest={optionsOpen=false}){
                listOf("跟随系统","简体中文","English").forEachIndexed { index,label->
                    DropdownMenuItem(text={Text(label)},onClick={
                        optionsOpen=false;controller.setLanguage(index)
                    })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick=controller::openNetworkSettings,modifier=Modifier.fillMaxWidth()){
            Text("打开系统移动网络设置")
        }
        HorizontalDivider(Modifier.padding(vertical=12.dp))
        InfoRow("版本号",BuildConfig.VERSION_NAME)
        InfoRow("安装 / 更新日期",tools.installedAt.ifBlank{"—"})
        InfoRow("构建版本",BuildConfig.VERSION_CODE.toString())
    }
}

/** Responsive two-column settings; no transition to any legacy activity. */
@Composable
fun HubSettingsV2(state:HubSnapshot?,pairing:PairingDisplay?,ui:HubViewModel,
                  controller:HubController,tools:HubToolsState,wide:Boolean){
    BoxWithConstraints(Modifier.fillMaxSize()){
        val twoColumns=wide && maxWidth>=740.dp && maxHeight>=480.dp
        if(twoColumns){
            Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
                horizontalArrangement=Arrangement.spacedBy(16.dp)){
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(14.dp)){
                    PairedNodeCard(state,pairing,ui,controller,Modifier.fillMaxWidth())
                    PermissionsCard(state,controller,Modifier.fillMaxWidth())
                    PreferencesCard(tools,controller,Modifier.fillMaxWidth())
                }
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(14.dp)){
                    RelayRuntimeCard(state,controller,Modifier.fillMaxWidth())
                    DiagnosticsCard(state,tools,controller,Modifier.fillMaxWidth())
                }
            }
        }else{
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement=Arrangement.spacedBy(14.dp)){
                PairedNodeCard(state,pairing,ui,controller,Modifier.fillMaxWidth())
                PermissionsCard(state,controller,Modifier.fillMaxWidth())
                RelayRuntimeCard(state,controller,Modifier.fillMaxWidth())
                DiagnosticsCard(state,tools,controller,Modifier.fillMaxWidth())
                PreferencesCard(tools,controller,Modifier.fillMaxWidth())
            }
        }
    }
}
