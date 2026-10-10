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
    var simFilter by mutableStateOf("")
    var showAllSms by mutableStateOf(false)
    var enrollmentLink by mutableStateOf("")
    var serverUrl by mutableStateOf("")
}
@Composable
fun HubIcon(id:Int,modifier:Modifier=Modifier,tint:Color=LocalContentColor.current,description:String?=null) {
    Icon(painterResource(id),contentDescription=description,modifier=modifier,tint=tint)
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
fun HubApp(snapshot:HubSnapshot?,loading:Boolean,pairing:PairingDisplay?,fold:FoldingFeature?,
           controller:HubController,tools:HubToolsState,incomingId:Int=0,incomingRecipient:String="",incomingBody:String=""){
    val ui:HubViewModel=viewModel()
    val context=LocalContext.current
    LaunchedEffect(incomingId){
        if(incomingId>0 && SmsSendPolicy.canSend(context)){
            ui.tab=1;ui.thread="__new__";ui.newRecipient=incomingRecipient
            ui.drafts["__new__"]=incomingBody
        }
    }
    BackHandler(enabled=tools.compatibilityOpen || ui.tab!=0 || ui.thread!=null || ui.simDetail>=0){
        if(tools.compatibilityOpen)controller.closeCompatibility()
        else if(ui.tab==1 && ui.thread!=null)ui.thread=null
        else if(ui.tab==2 && ui.simDetail>=0)ui.simDetail=-1
        else ui.tab=0
    }
    HubTheme {
        BoxWithConstraints(Modifier.fillMaxSize()){
            val expanded=maxWidth>=840.dp
            val phoneHeight=maxHeight<540.dp
            val foldVertical=fold!=null && fold.orientation==FoldingFeature.Orientation.VERTICAL && fold.isSeparating
            val rail=HubLayoutPolicy.navigationRail(maxWidth.value.toInt(),maxHeight.value.toInt(),foldVertical)
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
                                HubIcon(R.drawable.ic_hub_sync,Modifier.size(22.dp),description=stringResource(R.string.hub_refresh))
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
                            0->HubHomeAdaptive(snapshot,loading,ui,controller)
                            1->HubMessages(snapshot,ui,controller,expanded,foldVertical,fold)
                            2->HubSimScreen(snapshot,ui,controller,expanded)
                            else->HubSettingsV2(snapshot,pairing,ui,controller,tools,expanded)
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
