package com.blackkcold.simhub

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.layout.FoldingFeature
import java.text.DateFormat
import java.util.Date

private val Violet=Color(0xFF574FEA)
private val Plum=Color(0xFFACA9FF)
private val LightBg=Color(0xFFF6F7FB)
private val DarkBg=Color(0xFF0F1321)

class HubViewModel:ViewModel(){
    var tab by mutableStateOf(0)
    var thread by mutableStateOf<String?>(null)
    var simDetail by mutableIntStateOf(-1)
    val drafts=mutableStateMapOf<String,String>()
    var newRecipient by mutableStateOf("")
    var search by mutableStateOf("")
    var otpOnly by mutableStateOf(false)
    var showAllSms by mutableStateOf(false)
    var enrollmentLink by mutableStateOf("")
    var serverUrl by mutableStateOf("")
}
@Composable
fun HubIcon(id:Int,modifier:Modifier=Modifier,tint:Color=LocalContentColor.current) {
    Icon(painterResource(id),contentDescription=null,modifier=modifier,tint=tint)
}
@Composable
private fun HubTheme(content:@Composable ()->Unit){
    val dark=androidx.compose.foundation.isSystemInDarkTheme()
    val lightColors=lightColorScheme(primary=Violet,onPrimary=Color.White,secondary=Violet,background=LightBg,
        surface=Color.White,onSurface=Color(0xFF18223A),surfaceVariant=Color(0xFFF0F2FB),outlineVariant=Color(0xFFE1E5F0))
    val darkColors=darkColorScheme(primary=Plum,onPrimary=Color(0xFF262254),background=DarkBg,
        surface=Color(0xFF181D2D),onSurface=Color(0xFFF1F2FA),surfaceVariant=Color(0xFF292E40),outlineVariant=Color(0xFF383E50))
    MaterialTheme(colorScheme=if(dark)darkColors else lightColors,
        shapes=Shapes(small=RoundedCornerShape(12.dp),medium=RoundedCornerShape(16.dp),
            large=RoundedCornerShape(20.dp),extraLarge=RoundedCornerShape(24.dp)),
        content=content)
}
private val navIcons=listOf(R.drawable.ic_hub_home,R.drawable.ic_hub_messages,R.drawable.ic_hub_sim,R.drawable.ic_hub_settings)
private val navText=listOf(R.string.hub_home,R.string.hub_sms,R.string.hub_sim,R.string.hub_settings)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubApp(snapshot:HubSnapshot?,loading:Boolean,pairing:PairingDisplay?,fold:FoldingFeature?,controller:HubController){
    val ui:HubViewModel=viewModel()
    BackHandler(enabled=ui.tab!=0 || ui.thread!=null || ui.simDetail>=0){
        if(ui.tab==1 && ui.thread!=null)ui.thread=null
        else if(ui.tab==2 && ui.simDetail>=0)ui.simDetail=-1
        else ui.tab=0
    }
    HubTheme {
        BoxWithConstraints(Modifier.fillMaxSize()){
            val expanded=maxWidth>=840.dp
            val phoneHeight=maxHeight<540.dp
            val rail=expanded && !phoneHeight
            val foldVertical=fold!=null && fold.orientation==FoldingFeature.Orientation.VERTICAL && fold.isSeparating
            Scaffold(
                containerColor=MaterialTheme.colorScheme.background,
                contentWindowInsets=WindowInsets.safeDrawing,
                topBar={
                    TopAppBar(
                        title={Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
                            Box(Modifier.size(35.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)),
                                contentAlignment=Alignment.Center){
                                HubIcon(R.drawable.ic_hub_sim,Modifier.size(21.dp),MaterialTheme.colorScheme.onPrimary)
                            }
                            Column {
                                Text("SIM Hub",fontWeight=FontWeight.Bold,fontSize=18.sp)
                                Text(stringResource(navText[ui.tab]),style=MaterialTheme.typography.labelSmall,
                                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }},
                        actions={
                            IconButton(onClick=controller::refresh){
                                HubIcon(R.drawable.ic_hub_sync,Modifier.size(22.dp))
                            }
                        },
                        colors=TopAppBarDefaults.topAppBarColors(containerColor=MaterialTheme.colorScheme.surface))
                },
                bottomBar={
                    if(!rail)NavigationBar(containerColor=MaterialTheme.colorScheme.surface){
                        navIcons.forEachIndexed { i,id ->
                            NavigationBarItem(selected=ui.tab==i,onClick={ui.tab=i},
                                icon={HubIcon(id,Modifier.size(23.dp))},
                                label={Text(stringResource(navText[i]),maxLines=1)},
                                alwaysShowLabel=true)
                        }
                    }
                }
            ){ padding ->
                Row(Modifier.fillMaxSize().padding(padding)){
                    if(rail)NavigationRail(containerColor=MaterialTheme.colorScheme.surface){
                        Spacer(Modifier.height(12.dp))
                        navIcons.forEachIndexed { i,id ->
                            NavigationRailItem(selected=ui.tab==i,onClick={ui.tab=i},
                                icon={HubIcon(id,Modifier.size(23.dp))},label={Text(stringResource(navText[i]))})
                        }
                    }
                    AnimatedContent(
                        targetState=ui.tab,
                        modifier=Modifier.weight(1f).fillMaxHeight(),
                        transitionSpec={fadeIn(tween(190)) togetherWith fadeOut(tween(130))},
                        label="tabContent"
                    ){ page ->
                        when(page){
                            0->HubHome(snapshot,loading,ui,controller,expanded)
                            1->HubMessages(snapshot,ui,controller,expanded,foldVertical,fold)
                            2->HubSimScreen(snapshot,ui,controller,expanded)
                            else->HubSettings(snapshot,pairing,ui,controller,expanded)
                        }
                    }
                }
            }
        }
    }
}
@Composable
fun HubCard(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit){
    Card(modifier=modifier.animateContentSize(animationSpec=tween(210)),
        colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),
        shape=MaterialTheme.shapes.large,
        elevation=CardDefaults.cardElevation(defaultElevation=1.dp)){Column(Modifier.padding(18.dp),content=content)}
}
@Composable
fun StatePill(label:String,healthy:Boolean){
    val fg=if(healthy)Color(0xFF168358) else Color(0xFFC58723)
    val bg=if(healthy)Color(0xFF18855B).copy(alpha=.10f) else Color(0xFFB87820).copy(alpha=.11f)
    Row(Modifier.background(bg,CircleShape).padding(horizontal=12.dp,vertical=7.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
        Box(Modifier.size(7.dp).background(fg,CircleShape))
        Text(label,fontSize=12.sp,color=fg,fontWeight=FontWeight.Medium)
    }
}
@Composable
fun InfoRow(label:String,value:String){
    Row(Modifier.fillMaxWidth().padding(vertical=7.dp),horizontalArrangement=Arrangement.SpaceBetween,
        verticalAlignment=Alignment.CenterVertically){
        Text(label,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium)
        Text(value,style=MaterialTheme.typography.bodyMedium,fontWeight=FontWeight.Medium,
            maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}
@Composable
fun HubSectionTitle(text:String,modifier:Modifier=Modifier){
    Text(text,modifier=modifier.padding(bottom=12.dp),fontSize=18.sp,fontWeight=FontWeight.SemiBold)
}
@Composable
fun HubHome(state:HubSnapshot?,loading:Boolean,ui:HubViewModel,controller:HubController,wide:Boolean){
    LazyColumn(modifier=Modifier.fillMaxSize(),
        contentPadding=PaddingValues(start=20.dp,end=20.dp,top=20.dp,bottom=28.dp),
        verticalArrangement=Arrangement.spacedBy(18.dp)){
        item {
            HubCard {
                val healthy=state?.enrolled==true && state.smsRole && state.smsRead && state.transportError.isBlank()
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
                    horizontalArrangement=Arrangement.SpaceBetween){
                    Text(stringResource(if(healthy)R.string.hub_ready else R.string.hub_attention),
                        fontWeight=FontWeight.SemiBold,fontSize=21.sp)
                    StatePill(stringResource(
                        if(state?.enrolled!=true)R.string.hub_not_paired
                        else if(healthy)R.string.hub_connected else R.string.hub_attention),healthy)
                }
                Spacer(Modifier.height(8.dp))
                Text(if(state?.enrolled==true)state.device else stringResource(R.string.hub_setup_help),
                    style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                InfoRow(stringResource(R.string.hub_last_sync),state?.lastSync?.takeIf{it>0}?.let{
                    DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(it*1000))
                } ?: "—")
                InfoRow(stringResource(R.string.hub_pending),(state?.pending?:0).toString())
                if(state?.transportError?.isNotBlank()==true || state?.providerError?.isNotBlank()==true)
                    Text(state.transportError.ifBlank { state.providerError },
                        color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(9.dp))
                if(state?.enrolled!=true)Button(onClick={ui.tab=3},Modifier.fillMaxWidth()){
                    HubIcon(R.drawable.ic_hub_qr,Modifier.size(19.dp))
                    Spacer(Modifier.width(10.dp));Text(stringResource(R.string.hub_setup))
                }
                else OutlinedButton(onClick=controller::refresh,Modifier.fillMaxWidth()){
                    HubIcon(R.drawable.ic_hub_sync,Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp));Text(stringResource(R.string.hub_refresh))
                }
            }
        }
        if(state!=null && (!state.smsRole || !state.smsRead || !state.smsSend))item{
            HubCard {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    HubIcon(R.drawable.ic_hub_shield,Modifier.size(20.dp),MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.hub_permission_missing),fontWeight=FontWeight.SemiBold)
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick={controller::requestSmsRole},Modifier.fillMaxWidth()){
                    Text(stringResource(R.string.hub_grant_role))
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick={controller::requestAccess},Modifier.fillMaxWidth()){
                    Text(stringResource(R.string.hub_grant_permissions))
                }
            }
        }
        item{
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,
                verticalAlignment=Alignment.CenterVertically){
                HubSectionTitle(stringResource(R.string.hub_sim_overview),Modifier.padding(0.dp))
                TextButton(onClick={ui.tab=2}){Text(stringResource(R.string.hub_view_all))}
            }
            val sims=state?.state?.optJSONArray("subscriptions")
            if(sims==null || sims.length()==0) HubCard {Text(stringResource(R.string.hub_no_sim)) }
            else {
                Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
                    for(i in 0 until minOf(4,sims.length())){
                        val sim=sims.optJSONObject(i) ?: continue
                        HubCard {
                            Row(verticalAlignment=Alignment.CenterVertically){
                                HubIcon(R.drawable.ic_hub_sim,Modifier.size(27.dp),MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)){
                                    Text(sim.optString("carrierName",sim.optString("displayName","SIM")),
                                        fontWeight=FontWeight.SemiBold)
                                    Text("SIM ${sim.optInt("slotIndex",i)+1}  ·  ${sim.optString("networkType","—")}",
                                        color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
                                }
                                StatePill(stringResource(if(sim.optString("serviceState")=="IN_SERVICE")
                                    R.string.hub_ready else R.string.hub_no_service),
                                    sim.optString("serviceState")=="IN_SERVICE")
                            }
                        }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,
                verticalAlignment=Alignment.CenterVertically){
                HubSectionTitle(stringResource(R.string.hub_sms_overview),Modifier.padding(0.dp))
                TextButton(onClick={ui.tab=1}){Text(stringResource(R.string.hub_view_all))}
            }
            val recent=state?.sms?.take(3).orEmpty()
            if(recent.isEmpty())HubCard{Text(stringResource(R.string.hub_empty_sms),
                color=MaterialTheme.colorScheme.onSurfaceVariant)}
            else HubCard {
                recent.forEachIndexed { idx, sms ->
                    if(idx>0)HorizontalDivider(Modifier.padding(vertical=10.dp),color=MaterialTheme.colorScheme.outlineVariant)
                    Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()){
                        HubIcon(R.drawable.ic_hub_messages,Modifier.size(21.dp),MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(sms.from,fontWeight=FontWeight.SemiBold)
                            Text(sms.text,maxLines=1,overflow=TextOverflow.Ellipsis,
                                color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
