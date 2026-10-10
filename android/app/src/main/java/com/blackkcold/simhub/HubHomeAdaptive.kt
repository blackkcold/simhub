package com.blackkcold.simhub

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
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

/** Single responsive dashboard across compact phones, book foldables and wide windows. */
@Composable
fun HubHomeAdaptive(state:HubSnapshot?,loading:Boolean,ui:HubViewModel,controller:HubController){
    LazyVerticalGrid(
        columns=GridCells.Adaptive(minSize=320.dp),
        modifier=Modifier.fillMaxSize(),
        contentPadding=PaddingValues(16.dp),
        horizontalArrangement=Arrangement.spacedBy(14.dp),
        verticalArrangement=Arrangement.spacedBy(14.dp)
    ){
        item{
            HubCard {
                Text(hubLabel("设备与 Relay","Device & Relay"),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                val now=System.currentTimeMillis()/1000
                val ttl=if(state?.realtime==true)180L else 3600L
                val online=state?.enrolled==true&&state.smsRead&&state.smsReceive&&
                    state.transportError.isBlank()&&state.lastSync>0&&now>=state.lastSync&&
                    now-state.lastSync<=ttl
                StatePill(if(online)hubLabel("Relay 已连接","Relay connected") else hubLabel("需要检查","Needs attention"),online)
                Spacer(Modifier.height(8.dp))
                Text(state?.device.orEmpty().ifBlank{hubLabel("未连接设备","No device connected")},
                    style=MaterialTheme.typography.bodyMedium)
                InfoRow(hubLabel("最后同步","Last sync"),state?.lastSync?.takeIf{it>0}?.let{
                    DateFormat.getDateTimeInstance().format(Date(it*1000))
                }?:"—")
                InfoRow(hubLabel("待处理事件","Pending events"),(state?.pending?:0).toString())
                if(state?.transportError?.isNotBlank()==true)
                    Text(state.transportError,style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.error)
                OutlinedButton(onClick=controller::refresh,modifier=Modifier.fillMaxWidth()){
                    Text(hubLabel("刷新设备状态","Refresh device status"))
                }
            }
        }
        item{
            HubCard {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,
                    verticalAlignment=Alignment.CenterVertically){
                    Text(hubLabel("SIM 卡","SIM cards"),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                    TextButton(onClick={ui.tab=2}){Text(hubLabel("全部","View all"))}
                }
                val sims=state?.state?.optJSONArray("subscriptions")
                if(sims==null||sims.length()==0)Text(hubLabel("没有检测到 SIM","No SIM detected"))
                else for(index in 0 until minOf(sims.length(),4)){
                    val s=sims.optJSONObject(index)?:continue
                    val online=s.optString("serviceState")=="IN_SERVICE"
                    Row(Modifier.fillMaxWidth().padding(vertical=7.dp),verticalAlignment=Alignment.CenterVertically){
                        HubIcon(R.drawable.ic_hub_sim,Modifier.size(21.dp),MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)){
                            Text(s.optString("localSimTag","").ifBlank{s.optString("displayName","SIM")},
                                maxLines=1,overflow=TextOverflow.Ellipsis)
                            Text("SIM ${s.optInt("slotIndex",index)+1} · ${s.optString("carrierName","—")}",
                                style=MaterialTheme.typography.bodySmall,
                                color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StatePill(if(online)hubLabel("正常","Operational") else hubLabel("不可用","Unavailable"),online)
                    }
                }
            }
        }
        item{
            HubCard {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,
                    verticalAlignment=Alignment.CenterVertically){
                    Text(hubLabel("最近短信","Recent SMS"),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                    TextButton(onClick={ui.tab=1}){Text(hubLabel("全部","View all"))}
                }
                val recent=state?.sms.orEmpty().take(5)
                if(recent.isEmpty())Text(hubLabel("暂无短信","No messages"))
                else recent.forEachIndexed{index,sms->
                    if(index>0)HorizontalDivider(Modifier.padding(vertical=7.dp))
                    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(3.dp)){
                        Text(sms.from,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(sms.text,style=MaterialTheme.typography.bodySmall,
                            color=MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(listOf(sms.sourceDeviceName,sms.simTag,sms.simTail.takeIf{it.isNotEmpty()}?.let{hubLabel("尾号 ","Ending ")+it} ?: "")
                            .filter{it.isNotBlank()}.joinToString(" · "),
                            style=MaterialTheme.typography.labelSmall,
                            color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
                    }
                }
            }
        }
        item{
            HubCard {
                Text(hubLabel("快速操作","Quick actions"),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp),
                    modifier=Modifier.fillMaxWidth()){
                    OutlinedButton(onClick={ui.tab=3},modifier=Modifier.weight(1f)){
                        Text(hubLabel("设置","Settings"))
                    }
                    OutlinedButton(onClick={controller.syncHistory(false)},
                        enabled=state?.enrolled==true,modifier=Modifier.weight(1f)){
                        Text(hubLabel("同步短信","Sync SMS"))
                    }
                }
                Spacer(Modifier.height(8.dp))
                if(state?.smsRead!=true||state.smsReceive!=true){
                    Button(onClick=controller::requestAccess,modifier=Modifier.fillMaxWidth()){
                        Text(hubLabel("检查短信权限","Check SMS permissions"))
                    }
                }
            }
        }
    }
}
