package com.blackkcold.simhub

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

@Composable
fun HubSimScreen(state:HubSnapshot?,ui:HubViewModel,controller:HubController,wide:Boolean){
    val array=state?.state?.optJSONArray("subscriptions")
    val sims=remember(array){if(array==null)emptyList()else(0 until array.length()).mapNotNull{array.optJSONObject(it)}}
    if(ui.simDetail>=sims.size)ui.simDetail=-1
    Column(Modifier.fillMaxSize().padding(horizontal=18.dp,vertical=16.dp)){
        HubSectionTitle(stringResource(R.string.hub_sim_overview))
        if(sims.isEmpty()){
            HubCard(Modifier.fillMaxWidth()){
                Text(stringResource(R.string.hub_no_sim))
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick=controller::requestAccess){
                    Text(stringResource(R.string.hub_grant_permissions))
                }
            }
        }else {
            val current=sims.getOrNull(ui.simDetail)
            if(wide && current!=null){
                Row(Modifier.fillMaxSize(),horizontalArrangement=Arrangement.spacedBy(14.dp)){
                    LazyColumn(Modifier.weight(.46f),verticalArrangement=Arrangement.spacedBy(12.dp)){
                        itemsIndexed(sims){ index,item -> SimSummary(item,index,ui.simDetail==index){ui.simDetail=index}}
                    }
                    SimDetails(current,ui.simDetail,controller,Modifier.weight(.54f))
                }
            }else if(current!=null){
                SimDetails(current,ui.simDetail,controller,Modifier.fillMaxWidth()){ui.simDetail=-1}
            }else{
                LazyColumn(verticalArrangement=Arrangement.spacedBy(12.dp),
                    contentPadding=PaddingValues(bottom=28.dp)){
                    itemsIndexed(sims){ index,item -> SimSummary(item,index,false){ui.simDetail=index}}
                    item{
                        OutlinedButton(onClick=controller::openNetworkSettings,
                            modifier=Modifier.fillMaxWidth()){
                            Text(stringResource(R.string.hub_system_network))
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun SimSummary(item:JSONObject,index:Int,selected:Boolean,onClick:()->Unit){
    val slot=item.optInt("slotIndex",index)+1
    val active=item.optString("serviceState")=="IN_SERVICE"
    Card(onClick=onClick,shape=RoundedCornerShape(20.dp),
        colors=CardDefaults.cardColors(containerColor=if(selected)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        modifier=Modifier.fillMaxWidth().animateContentSize()){
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(11.dp)){
            Row(verticalAlignment=Alignment.CenterVertically){
                HubIcon(R.drawable.ic_hub_sim,Modifier.size(29.dp),MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)){
                    Text(item.optString("carrierName","SIM"),fontWeight=FontWeight.SemiBold,
                        style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text((item.optString("localSimTag","").takeIf{it.isNotBlank()}?.plus(" · ") ?: "")+"${item.optString("displayName","SIM")} · SIM $slot",
                        color=MaterialTheme.colorScheme.onSurfaceVariant,
                        style=MaterialTheme.typography.bodySmall)
                }
                StatePill(stringResource(if(active)R.string.hub_ready else R.string.hub_no_service),active)
            }
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                Text(item.optString("networkType","—"),fontWeight=FontWeight.Medium)
                Text("${item.optInt("signalLevel",-1).takeIf{it>=0}?.let{"$it/4"} ?: "—"}",
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
@Composable
private fun SimDetails(item:JSONObject,index:Int,controller:HubController,modifier:Modifier,onBack:(()->Unit)?=null){
    HubCard(modifier){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            if(onBack!=null)TextButton(onClick=onBack){Text("‹",fontSize=25.sp)}
            Text(stringResource(R.string.hub_sim_details)+" · SIM ${item.optInt("slotIndex",index)+1}",
                fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium)
        }
        HorizontalDivider(Modifier.padding(vertical=10.dp))
        val channelId=item.optString("channelId","")
        val revision=item.optLong("channelRevision",1)
        var tag by remember(channelId,revision,item.optString("localSimTag","")){
            mutableStateOf(item.optString("localSimTag",""))
        }
        var number by remember(channelId,revision,item.optString("localSimTail","")){
            mutableStateOf(item.optString("localSimTail",""))
        }
        InfoRow(stringResource(R.string.hub_sim_overview),item.optString("displayName","SIM"))
        if(channelId.isNotBlank()){
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value=tag,onValueChange={if(it.length<=40)tag=it},
                label={Text("SIM 标签（例如：主力号码）")},singleLine=true,
                modifier=Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(value=number,onValueChange={if(it.length<=24)number=it},
                label={Text("SIM 号码或尾号（仅存最后 4 位）")},singleLine=true,
                modifier=Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick={controller.setSimTag(channelId,revision,tag,number)},
                modifier=Modifier.fillMaxWidth()){Text("保存 SIM 标签")}
        }
        InfoRow(stringResource(R.string.hub_connected_server),item.optString("carrierName","—"))
        InfoRow(stringResource(R.string.hub_network),item.optString("networkType","—"))
        InfoRow(stringResource(R.string.hub_service),item.optString("serviceState","—"))
        InfoRow(stringResource(R.string.hub_roaming),stringResource(if(item.optBoolean("roaming"))R.string.hub_yes else R.string.hub_no))
        for(key in listOf("signalDbm","signalRsrp","signalRsrq","signalSinr")){
            if(item.has(key) && !item.isNull(key))InfoRow(key,item.optString(key))
        }
        InfoRow("Channel ID",item.optString("channelId","—").take(18)+"…")
        Spacer(Modifier.height(14.dp))
        OutlinedButton(onClick=controller::openNetworkSettings,Modifier.fillMaxWidth()){
            Text(stringResource(R.string.hub_system_network))
        }
    }
}
@Composable
fun HubSettings(state:HubSnapshot?,pairing:PairingDisplay?,ui:HubViewModel,
                controller:HubController,wide:Boolean){
    val paired=state?.enrolled==true
    val scroll=androidx.compose.foundation.rememberScrollState()
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal=18.dp,vertical=16.dp),
        verticalArrangement=Arrangement.spacedBy(16.dp)){
        if(!paired){
            HubSectionTitle(stringResource(R.string.hub_setup))
            HubCard(Modifier.fillMaxWidth()){
                Text(stringResource(R.string.hub_setup_help),
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Button(onClick=controller::scan,Modifier.fillMaxWidth()){
                    HubIcon(R.drawable.ic_hub_qr,Modifier.size(18.dp),MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp));Text(stringResource(R.string.hub_scan))
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(ui.enrollmentLink,{ui.enrollmentLink=it},
                    label={Text(stringResource(R.string.hub_paste))},modifier=Modifier.fillMaxWidth(),
                    singleLine=true)
                TextButton(onClick={controller.enrollLink(ui.enrollmentLink)},
                    enabled=ui.enrollmentLink.startsWith("simhub://enroll")){
                    Text(stringResource(R.string.hub_enroll))
                }
                HorizontalDivider(Modifier.padding(vertical=12.dp),color=MaterialTheme.colorScheme.outlineVariant)
                OutlinedTextField(ui.serverUrl,{ui.serverUrl=it},
                    label={Text(stringResource(R.string.hub_enter_relay))},
                    placeholder={Text("https://node.example.com")},
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),
                    modifier=Modifier.fillMaxWidth(),singleLine=true)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick={controller.startPairing(ui.serverUrl)},
                    enabled=ui.serverUrl.startsWith("https://"),modifier=Modifier.fillMaxWidth()){
                    Text(stringResource(R.string.hub_generate_code))
                }
                AnimatedVisibility(visible=pairing!=null){
                    Column(Modifier.padding(top=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                        if(pairing!=null){
                            Text(pairing.code.chunked(4).joinToString(" "),fontSize=28.sp,
                                fontWeight=FontWeight.Bold,letterSpacing=3.sp)
                            Text(stringResource(R.string.hub_pair_fingerprint)+" · "+pairing.fingerprint)
                            Text(if(pairing.waiting)stringResource(R.string.hub_pair_wait)
                                 else pairing.error.ifBlank{stringResource(R.string.pair_done)},
                                color=MaterialTheme.colorScheme.onSurfaceVariant)
                            if(pairing.waiting)LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }else{
            HubSectionTitle(stringResource(R.string.hub_connected_server))
            HubCard(Modifier.fillMaxWidth()){
                StatePill(stringResource(R.string.hub_connected),true)
                Spacer(Modifier.height(12.dp))
                Text(state?.device.orEmpty(),fontWeight=FontWeight.SemiBold)
                Text(state?.server.orEmpty(),style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HubSectionTitle(stringResource(R.string.hub_permissions))
        HubCard(Modifier.fillMaxWidth()){
            InfoRow(stringResource(R.string.hub_sms_role),stringResource(
                if(state?.smsRole==true)R.string.hub_yes else R.string.hub_no))
            InfoRow("READ_SMS",stringResource(if(state?.smsRead==true)R.string.hub_yes else R.string.hub_no))
            InfoRow("SEND_SMS",stringResource(if(state?.smsSend==true)R.string.hub_yes else R.string.hub_no))
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick=controller::requestSmsRole,Modifier.fillMaxWidth()){
                Text(stringResource(R.string.hub_grant_role))
            }
            OutlinedButton(onClick=controller::requestAccess,Modifier.fillMaxWidth()){
                Text(stringResource(R.string.hub_grant_permissions))
            }
        }
        HubSectionTitle(stringResource(R.string.hub_runtime))
        HubCard(Modifier.fillMaxWidth()){
            Row(verticalAlignment=Alignment.CenterVertically){
                Column(Modifier.weight(1f)){
                    Text(stringResource(R.string.hub_realtime),fontWeight=FontWeight.SemiBold)
                    Text(stringResource(R.string.hub_realtime_desc),
                        style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked=state?.realtime==true,onCheckedChange=controller::setRealtime,enabled=paired)
            }
            HorizontalDivider(Modifier.padding(vertical=12.dp))
            OutlinedButton(onClick={controller.syncHistory(false)},enabled=paired,
                modifier=Modifier.fillMaxWidth()){
                Text(stringResource(R.string.hub_sync_recent))
            }
            Spacer(Modifier.height(7.dp))
            OutlinedButton(onClick={controller.syncHistory(true)},enabled=paired,
                modifier=Modifier.fillMaxWidth()){
                Text(stringResource(R.string.hub_sync_older))
            }
            Text(stringResource(R.string.hub_sync_help),style=MaterialTheme.typography.labelSmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HubSectionTitle(stringResource(R.string.hub_more))
        HubCard(Modifier.fillMaxWidth()){
            OutlinedButton(onClick=controller::advanced,modifier=Modifier.fillMaxWidth()){
                Text(stringResource(R.string.hub_advanced))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick=controller::openNetworkSettings,modifier=Modifier.fillMaxWidth()){
                Text(stringResource(R.string.hub_system_network))
            }
            Spacer(Modifier.height(10.dp))
            InfoRow(stringResource(R.string.hub_version),BuildConfig.VERSION_NAME)
            Text(stringResource(R.string.hub_fold_mode),
                style=MaterialTheme.typography.labelSmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(18.dp))
    }
}
