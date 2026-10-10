package com.blackkcold.simhub

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.window.layout.FoldingFeature
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun HubMessages(state:HubSnapshot?,ui:HubViewModel,controller:HubController,
                wide:Boolean,verticalHinge:Boolean,fold:FoldingFeature?){
    val threads=state?.threads.orEmpty()
    val large=wide || verticalHinge
    val horizontal=fold!=null&&fold.isSeparating&&fold.orientation==FoldingFeature.Orientation.HORIZONTAL
    if(large) {
        Row(Modifier.fillMaxSize().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){
            SmsConversationList(threads,state,ui,controller,Modifier.weight(if(verticalHinge).5f else .37f).fillMaxHeight())
            if(verticalHinge) Spacer(Modifier.width(12.dp))
            SmsConversationDetail(state,ui,controller,Modifier.weight(if(verticalHinge).5f else .63f).fillMaxHeight())
        }
    }else if(horizontal){
        // Tabletop mode: list above the horizontal hinge; conversation below.
        BoxWithConstraints(Modifier.fillMaxSize()){
            if(HubLayoutPolicy.tabletopPane(maxHeight.value.toInt(),true)){
                Column(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
                    SmsConversationList(threads,state,ui,controller,Modifier.weight(.42f).fillMaxWidth())
                    SmsConversationDetail(state,ui,controller,Modifier.weight(.58f).fillMaxWidth())
                }
            }else if(ui.thread==null)SmsConversationList(threads,state,ui,controller,Modifier.fillMaxSize())
            else SmsConversationDetail(state,ui,controller,Modifier.fillMaxSize())
        }
    }else{
        if(ui.thread==null)SmsConversationList(threads,state,ui,controller,Modifier.fillMaxSize())
        else SmsConversationDetail(state,ui,controller,Modifier.fillMaxSize())
    }
}
@Composable
private fun SmsConversationList(threads:List<HubThread>,state:HubSnapshot?,ui:HubViewModel,
                                controller:HubController,modifier:Modifier){
    Column(modifier.padding(16.dp)){
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween,
            modifier=Modifier.fillMaxWidth()){
            Text(stringResource(R.string.hub_conversations),style=MaterialTheme.typography.titleLarge,
                fontWeight=FontWeight.Bold)
            FilledIconButton(onClick={ui.thread="__new__";ui.newRecipient=""}){
                HubIcon(R.drawable.ic_hub_add,Modifier.size(23.dp),MaterialTheme.colorScheme.onPrimary,stringResource(R.string.hub_new_sms))
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value=ui.search,onValueChange={ui.search=it},
            label={Text(stringResource(R.string.hub_search),maxLines=1)},
            singleLine=true,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
            FilterChip(selected=!ui.otpOnly,onClick={ui.otpOnly=false},
                label={Text(stringResource(R.string.hub_all))})
            FilterChip(selected=ui.otpOnly,onClick={ui.otpOnly=true},
                label={Text(stringResource(R.string.hub_codes))})
        }
        if(state?.smsRead!=true&&threads.isEmpty()){
            HubCard(Modifier.fillMaxWidth()){
                Text(stringResource(R.string.hub_sms_permissions))
                Spacer(Modifier.height(10.dp))
                Button(onClick=controller::requestAccess){
                    Text(stringResource(R.string.hub_grant_permissions))
                }
            }
        }else {
            val display=threads.filter {
                val search=ui.search.trim()
                (search.isEmpty() || it.address.contains(search,true) || it.latest.text.contains(search,true)) &&
                    (!ui.otpOnly || OtpParser.parse(it.latest.text).detected)
            }
            if(display.isEmpty())Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){
                Text(stringResource(R.string.hub_empty_sms),
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(bottom=20.dp)){
                items(display,key={it.key}){thread->
                    val selected=ui.thread==thread.key
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if(selected)MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,RoundedCornerShape(16.dp))
                            .clickable { ui.thread=thread.key }
                            .padding(horizontal=12.dp,vertical=13.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ){
                        Box(Modifier.size(42.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant,CircleShape),
                            contentAlignment=Alignment.Center){
                            HubIcon(R.drawable.ic_hub_messages,Modifier.size(21.dp),
                                MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)){
                            Row(verticalAlignment=Alignment.CenterVertically){
                                Text(thread.address,Modifier.weight(1f),maxLines=1,
                                    overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.SemiBold)
                                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(thread.latest.date)),
                                    style=MaterialTheme.typography.labelSmall,
                                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(thread.latest.text,style=MaterialTheme.typography.bodySmall,
                                color=MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines=1,overflow=TextOverflow.Ellipsis)
                            Text(listOfNotNull(thread.latest.sourceDeviceName.takeIf{it.isNotBlank()},
                                thread.latest.simTag.takeIf{it.isNotBlank()} ?: (if(thread.latest.historicalUnverified)stringResource(R.string.hub_historical_sim) else stringResource(R.string.hub_sim_slot,thread.subscription)),
                                thread.latest.simTail.takeIf{it.isNotBlank()}?.let{stringResource(R.string.hub_sim_tail,it)}).joinToString(" · "),
                                color=MaterialTheme.colorScheme.onSurfaceVariant,
                                style=MaterialTheme.typography.labelSmall)
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                }
                item{
                    if((state?.visibleWindow?:0)>=2000){
                        Text(stringResource(R.string.hub_cache_limit),
                            style=MaterialTheme.typography.labelSmall,
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }else{
                        TextButton(onClick=controller::loadMoreSms,modifier=Modifier.fillMaxWidth()){
                            Text(stringResource(R.string.hub_load_more))
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun SmsConversationDetail(state:HubSnapshot?,ui:HubViewModel,
                                  controller:HubController,modifier:Modifier){
    val key=ui.thread
    if(key==null){
        Box(modifier,contentAlignment=Alignment.Center){
        Column(horizontalAlignment=Alignment.CenterHorizontally){
            HubIcon(R.drawable.ic_hub_messages,Modifier.size(52.dp),MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.hub_empty_conversation),
                color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        }
        return
    }
    val new=key=="__new__"
    val thread=state?.threads?.find{it.key==key}
    val history=if(new)emptyList() else state?.sms.orEmpty()
        .filter{HubSnapshot.keyFor(it.from,it.subscription,it.sourceDeviceId,it.channelId,it.channelRevision)==key}.sortedBy{it.date}
    val context=LocalContext.current
    val subscriptions=remember(state?.smsRead,state?.smsSend){
        HubRepository.activeSubscriptions(context)
    }
    var selectedSim by remember(key,subscriptions){mutableIntStateOf(
        thread?.subscription?.takeIf{old->subscriptions.any{it.first==old}}
            ?:subscriptions.firstOrNull()?.first?:-1)}
    var dropdown by remember { mutableStateOf(false) }
    var newTo by remember(key){ mutableStateOf(if(new)ui.newRecipient else thread?.address.orEmpty()) }
    val draft=ui.drafts[key].orEmpty()
    val isRemote=thread?.latest?.shared==true
    val canSend=!isRemote&&(new||thread?.latest?.historicalUnverified==false)&&
        state?.smsSend==true&&
        subscriptions.any{it.first==selectedSim}
    val listState=rememberLazyListState()
    LaunchedEffect(key){if(history.isNotEmpty())listState.scrollToItem(history.lastIndex)}
    HubCard(modifier){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            if(!new){
                TextButton(onClick={ui.thread=null},contentPadding=PaddingValues(0.dp)){
                    Text("‹",fontSize=26.sp)
                }
                Spacer(Modifier.width(8.dp))
            } else if(key=="__new__"){
                TextButton(onClick={ui.thread=null},contentPadding=PaddingValues(0.dp)){
                    Text("‹",fontSize=26.sp)
                }
                Spacer(Modifier.width(8.dp))
            }
            Column(Modifier.weight(1f)){
                Text(if(new)stringResource(R.string.hub_new_sms) else thread?.address.orEmpty(),
                    style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,
                    maxLines=1,overflow=TextOverflow.Ellipsis)
                if(!new)Text(stringResource(R.string.hub_conversations),
                    style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(Modifier.padding(vertical=8.dp),color=MaterialTheme.colorScheme.outlineVariant)
        if(new)OutlinedTextField(newTo,{newTo=it;ui.newRecipient=it},
            label={Text(stringResource(R.string.hub_recipient))},singleLine=true,
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone),
            modifier=Modifier.fillMaxWidth())
        if(!new)LazyColumn(state=listState,modifier=Modifier.weight(1f),
            verticalArrangement=Arrangement.spacedBy(11.dp),contentPadding=PaddingValues(vertical=14.dp)){
            items(history,key={it.id}){sms->
                SmsBubble(sms,controller)
            }
        }else Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Box{
                TextButton(onClick={dropdown=true},enabled=!isRemote){
                    HubIcon(R.drawable.ic_hub_sim,Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(subscriptions.find{it.first==selectedSim}?.second ?: stringResource(R.string.hub_choose_sim),
                        maxLines=1)
                }
                DropdownMenu(expanded=dropdown,onDismissRequest={dropdown=false}){
                    subscriptions.forEach{(id,label)->
                        DropdownMenuItem(text={Text(label)},onClick={selectedSim=id;dropdown=false})
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Bottom,
            horizontalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedTextField(
                value=draft,onValueChange={if(it.length<=4000)ui.drafts[key]=it},
                enabled=!isRemote,
                modifier=Modifier.weight(1f),minLines=1,maxLines=4,
                label={Text(stringResource(R.string.hub_message_placeholder))},
                shape=RoundedCornerShape(17.dp)
            )
            FilledIconButton(
                enabled=canSend&&draft.isNotBlank()&&(if(new)newTo else thread?.address.orEmpty()).isNotBlank(),
                onClick={
                    val to=if(new)newTo else thread?.address.orEmpty()
                    controller.sendSms(selectedSim,to,draft) { ui.drafts.remove(key) }
                },modifier=Modifier.size(52.dp)
            ){
                HubIcon(R.drawable.ic_hub_send,Modifier.size(22.dp),MaterialTheme.colorScheme.onPrimary,stringResource(R.string.hub_send))
            }
        }
        if(!canSend)Text(if(isRemote)stringResource(R.string.hub_shared_readonly) else stringResource(R.string.hub_read_only),
            style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.error)
    }
}
@Composable
private fun SmsBubble(sms:HubSms,controller:HubController){
    val sent=sms.type!=android.provider.Telephony.Sms.MESSAGE_TYPE_INBOX
    val sendStatus=when(sms.type) {
        android.provider.Telephony.Sms.MESSAGE_TYPE_SENT -> R.string.hub_sms_sent_not_delivered
        android.provider.Telephony.Sms.MESSAGE_TYPE_FAILED -> R.string.hub_sms_failed
        android.provider.Telephony.Sms.MESSAGE_TYPE_QUEUED -> R.string.hub_sms_queued
        android.provider.Telephony.Sms.MESSAGE_TYPE_OUTBOX -> R.string.hub_sms_sending
        android.provider.Telephony.Sms.MESSAGE_TYPE_DRAFT -> R.string.hub_sms_draft
        else -> null
    }
    val otp=remember(sms.text){OtpParser.parse(sms.text)}
    Row(Modifier.fillMaxWidth(),horizontalArrangement=if(sent)Arrangement.End else Arrangement.Start){
        Column(Modifier.fillMaxWidth(.88f)
            .background(if(sent)MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(18.dp))
            .padding(horizontal=14.dp,vertical=11.dp)){
            Text(sms.text,style=MaterialTheme.typography.bodyMedium)
            if(!sent&&otp.detected&&otp.value!=null){
                Spacer(Modifier.height(8.dp))
                TextButton(onClick={otp.value?.let(controller::copyOtp)},
                    contentPadding=PaddingValues(0.dp)){
                    Text(stringResource(R.string.hub_copy_code)+" · "+otp.value)
                }
            }
            Spacer(Modifier.height(5.dp))
            FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),
                verticalArrangement=Arrangement.spacedBy(5.dp)){
                @Composable fun Tag(value:String){
                    Surface(shape=RoundedCornerShape(8.dp),
                        color=MaterialTheme.colorScheme.surface.copy(alpha=.35f)){
                        Text(value,Modifier.padding(horizontal=6.dp,vertical=3.dp),
                            style=MaterialTheme.typography.labelSmall,
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Tag(DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(sms.date)))
                Tag(stringResource(if(sent)R.string.hub_sent else R.string.hub_received))
                if(sendStatus!=null)Tag(stringResource(sendStatus))
                if(sms.sourceDeviceName.isNotBlank())Tag(sms.sourceDeviceName)
                if(sms.simTag.isNotBlank())Tag(sms.simTag)
                if(sms.simTail.isNotBlank())Tag(stringResource(R.string.hub_sim_tail,sms.simTail))
                else Tag(stringResource(if(sms.historicalUnverified)R.string.hub_historical_sim else R.string.hub_unknown_sim))
            }
        }
    }
}
