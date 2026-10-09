package com.blackkcold.simhub

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
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
            }else if(wide){
                LazyVerticalGrid(columns=GridCells.Adaptive(290.dp),
                    horizontalArrangement=Arrangement.spacedBy(12.dp),
                    verticalArrangement=Arrangement.spacedBy(12.dp),
                    contentPadding=PaddingValues(bottom=28.dp)){
                    gridItemsIndexed(sims){index,item->
                        SimSummary(item,index,false){ui.simDetail=index}
                    }
                    item {
                        OutlinedButton(onClick=controller::openNetworkSettings,
                            modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.hub_system_network))}
                    }
                }
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
