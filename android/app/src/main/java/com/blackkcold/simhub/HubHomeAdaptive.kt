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
                Text("设备与 Relay",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                val now=System.currentTimeMillis()/1000
                val ttl=if(state?.realtime==true)180L else 3600L
                val online=state?.enrolled==true&&state.smsRole&&state.smsRead&&
                    state.transportError.isBlank()&&state.lastSync>0&&now>=state.lastSync&&
                    now-state.lastSync<=ttl
                StatePill(if(online)"Relay 已连接" else "需要检查",online)
                Spacer(Modifier.height(8.dp))
                Text(state?.device.orEmpty().ifBlank{"未连接设备"},
                    style=MaterialTheme.typography.bodyMedium)
                InfoRow("最后同步",state?.lastSync?.takeIf{it>0}?.let{
                    DateFormat.getDateTimeInstance().format(Date(it*1000))
                }?:"—")
                InfoRow("待处理事件",(state?.pending?:0).toString())
                if(state?.transportError?.isNotBlank()==true)
                    Text(state.transportError,style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.error)
                OutlinedButton(onClick=controller::refresh,modifier=Modifier.fillMaxWidth()){
                    Text("刷新设备状态")
                }
            }
        }
        item{
            HubCard {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,
                    verticalAlignment=Alignment.CenterVertically){
                    Text("SIM 卡",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                    TextButton(onClick={ui.tab=2}){Text("全部")}
                }
                val sims=state?.state?.optJSONArray("subscriptions")
                if(sims==null||sims.length()==0)Text("没有检测到 SIM")
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
                        StatePill(if(online)"正常" else "不可用",online)
                    }
                }
            }
        }
        item{
            HubCard {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,
                    verticalAlignment=Alignment.CenterVertically){
                    Text("最近短信",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                    TextButton(onClick={ui.tab=1}){Text("全部")}
                }
                val recent=state?.sms.orEmpty().take(5)
                if(recent.isEmpty())Text("暂无短信")
                else recent.forEachIndexed{index,sms->
                    if(index>0)HorizontalDivider(Modifier.padding(vertical=7.dp))
                    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(3.dp)){
                        Text(sms.from,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(sms.text,style=MaterialTheme.typography.bodySmall,
                            color=MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(listOf(sms.sourceDeviceName,sms.simTag,sms.simTail.takeIf{it.isNotEmpty()}?.let{"尾号 "+it} ?: "")
                            .filter{it.isNotBlank()}.joinToString(" · "),
                            style=MaterialTheme.typography.labelSmall,
                            color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
                    }
                }
            }
        }
        item{
            HubCard {
                Text("快速操作",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp),
                    modifier=Modifier.fillMaxWidth()){
                    OutlinedButton(onClick={ui.tab=3},modifier=Modifier.weight(1f)){
                        Text("设置")
                    }
                    OutlinedButton(onClick={controller.syncHistory(false)},
                        enabled=state?.enrolled==true,modifier=Modifier.weight(1f)){
                        Text("同步短信")
                    }
                }
                Spacer(Modifier.height(8.dp))
                if(state?.smsRead!=true||state?.smsRole!=true){
                    Button(onClick=controller::requestAccess,modifier=Modifier.fillMaxWidth()){
                        Text("检查短信权限")
                    }
                }
            }
        }
    }
}
