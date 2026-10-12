package com.blackkcold.simhub

import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

/**
 * Shared native Compose shell for controller and node modes.
 *
 * The verified-origin WebView owns existing cryptographic sessions and WebAuthn.
 * Its bounded decrypted SMS snapshots are displayed by NativeControllerInbox.
 * There is no JS-to-native SMS Provider, filesystem, or Node Key API.
 */
@Composable
fun ControllerWorkspace(visible:Boolean,revision:Int,onScan:()->Unit,modifier:Modifier=Modifier) {
    val context=LocalContext.current
    val inbox=remember { ControllerInboxState() }
    var profiles by remember(revision) { mutableStateOf(ControllerProfiles.list(context)) }
    var selected by remember(revision) { mutableStateOf(ControllerProfiles.selected(context)) }
    var showAdd by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var loadedOrigin by remember { mutableStateOf<String?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var passkeyAvailable by remember { mutableStateOf(false) }
    var tab by remember(selected) { mutableIntStateOf(0) }
    val main=Handler(Looper.getMainLooper())

    fun callPage(expression:String) { web?.evaluateJavascript(expression,null) }
    fun changeTab(next:Int) {
        tab=next
        val view=when(next){1->"devices";2->"settings";4->"send";else->"inbox"}
        if(next!=3)callPage("window.SimHubNativeShell?.openView('"+view+"')")
    }
    fun openOrigin(origin:String) {
        ControllerProfiles.select(context,origin)
        selected=origin
        inbox.clear()
        error=""
        tab=0
    }
    LaunchedEffect(selected){ inbox.clear() }
    LaunchedEffect(inbox.unlocked) {
        if(inbox.unlocked&&tab==3)changeTab(0)
    }
    BackHandler(visible && tab!=0){
        if(web?.canGoBack()==true && tab==3)web?.goBack() else changeTab(0)
    }
    Column(modifier.fillMaxSize()) {
        if(visible) {
            Row(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=5.dp),
                verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement=Arrangement.spacedBy(8.dp),
                    verticalAlignment=Alignment.CenterVertically){
                    profiles.forEach{origin->
                        FilterChip(selected=selected==origin,onClick={openOrigin(origin)},
                            label={Text(Uri.parse(origin).host?:origin,maxLines=1,
                                overflow=TextOverflow.Ellipsis)})
                    }
                }
                IconButton(onClick={showAdd=true},modifier=Modifier.size(44.dp)){
                    HubIcon(R.drawable.ic_hub_add,Modifier.size(22.dp),description="添加管理中心")
                }
            }
        }
        if(selected==null) {
            if(visible)Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                contentAlignment=Alignment.Center){
                ElevatedCard(Modifier.fillMaxWidth().widthIn(max=520.dp)){
                    Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
                        HubIcon(R.drawable.ic_hub_shield,Modifier.size(40.dp),
                            tint=MaterialTheme.colorScheme.primary)
                        Text("连接管理中心",style=MaterialTheme.typography.titleLarge)
                        Text("在网页管理后台选择「连接管理手机」，扫描二维码导入 HTTPS 管理地址。",
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick=onScan,modifier=Modifier.fillMaxWidth()){
                            Text("扫描管理中心二维码")
                        }
                        OutlinedButton(onClick={showAdd=true},modifier=Modifier.fillMaxWidth()){
                            Text("粘贴链接 / 输入地址")
                        }
                    }
                }
            }else Spacer(Modifier.weight(1f))
        } else {
            if(visible)Row(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=1.dp),
                verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(6.dp)){
                Text("管理空间 · "+(Uri.parse(selected).host?:""),
                    modifier=Modifier.weight(1f),maxLines=1,
                    overflow=TextOverflow.Ellipsis,
                    style=MaterialTheme.typography.labelMedium,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick={
                    if(tab==0)callPage("window.SimHubNativeShell?.refresh()")
                    else web?.reload()
                }) {Text("刷新")}
                TextButton(onClick={confirmRemove=true}) {Text("移除")}
            }
            if(visible && tab==3){
                Text(if(passkeyAvailable)
                    "已接入 Android 系统通行密钥；管理域名还需配置签名证书关联"
                    else "当前 WebView 不支持系统通行密钥，可使用密码与 OTP 登录",
                    modifier=Modifier.padding(horizontal=14.dp,vertical=3.dp),
                    color=MaterialTheme.colorScheme.onSurfaceVariant,
                    style=MaterialTheme.typography.labelSmall)
            }
            if(error.isNotEmpty() && visible)
                Text(error,color=MaterialTheme.colorScheme.error,
                    modifier=Modifier.padding(horizontal=16.dp,vertical=6.dp),
                    style=MaterialTheme.typography.bodySmall)

            Box(Modifier.weight(1f).fillMaxWidth()){
                // WebView stays attached across Compose navigation and maintains the
                // user's WebCrypto/HttpOnly session. Native SMS is rendered above it.
                key(profiles.joinToString("|")) {
                AndroidView(factory={ctx->
                    WebView(ctx).apply{
                        settings.apply {
                            javaScriptEnabled=true
                            domStorageEnabled=true
                            allowFileAccess=false
                            allowContentAccess=false
                            javaScriptCanOpenWindowsAutomatically=false
                            setSupportMultipleWindows(false)
                            mixedContentMode=android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            safeBrowsingEnabled=true
                        }
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this,false)
                        if(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)){
                            WebSettingsCompat.setWebAuthenticationSupport(settings,
                                WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP)
                            passkeyAvailable=
                                WebSettingsCompat.getWebAuthenticationSupport(settings)!=
                                WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_NONE
                        }
                        if(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)){
                            val allowed=ControllerProfiles.list(ctx).toSet()
                            WebViewCompat.addWebMessageListener(
                                this,"SimHubNativeInbox",allowed,
                                WebViewCompat.WebMessageListener { _,message,sourceOrigin,isMainFrame,_->
                                    val origin=sourceOrigin.toString().trimEnd('/')
                                    val payload=message.data
                                    if(!isMainFrame || origin!=selected || payload==null)return@WebMessageListener
                                    if(payload.length>450_000)return@WebMessageListener
                                    main.post {
                                        if(selected==origin)inbox.accept(payload)
                                    }
                                })
                        } else {
                            error="系统 WebView 不支持安全短信数据通道，请升级 Android System WebView"
                        }
                        webChromeClient=WebChromeClient()
                        webViewClient=object:WebViewClient(){
                            private fun approved(request:Uri):Boolean {
                                val approvedOrigin=ControllerLinks.normalize(selected?:"")?:return false
                                val parsed=Uri.parse(approvedOrigin)
                                return request.scheme=="https" &&
                                    request.host.equals(parsed.host,true) &&
                                    (if(request.port==-1)443 else request.port)==
                                    (if(parsed.port==-1)443 else parsed.port)
                            }
                            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
                                if(approved(request.url))return false
                                error="已阻止打开其他管理域名的链接"
                                return true
                            }
                            override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse?{
                                if(approved(request.url))return null
                                return WebResourceResponse("text/plain","UTF-8",403,"Forbidden",
                                    mapOf("Cache-Control" to "no-store"),
                                    ByteArrayInputStream(ByteArray(0)))
                            }
                            override fun onReceivedSslError(view:WebView,handler:SslErrorHandler,err:SslError){
                                handler.cancel()
                                inbox.clear()
                                error="TLS 证书校验失败，已拒绝加载管理中心"
                            }
                            override fun onPageFinished(view:WebView,url:String){
                                canGoBack=view.canGoBack()
                                if(url.startsWith(selected?:"\u0000")){
                                    main.postDelayed({
                                        val target=when(tab){1->"devices";2->"settings";4->"send";else->"inbox"}
                                        view.evaluateJavascript("window.SimHubNativeShell?.openView('"+target+"')",null)
                                        view.evaluateJavascript("window.SimHubNativeShell?.snapshot()",null)
                                    },250)
                                }
                            }
                        }
                    }.also{web=it}
                },modifier=Modifier.fillMaxSize(),
                    update={view->
                        view.visibility=if(visible && tab!=0)android.view.View.VISIBLE
                            else android.view.View.INVISIBLE
                        if(visible && loadedOrigin!=selected && selected!=null){
                            loadedOrigin=selected
                            inbox.clear()
                            view.loadUrl(selected!!+"/?native=1")
                        }
                    },onRelease={view->view.stopLoading();view.destroy();web=null;inbox.clear()})
                }
                if(visible && tab==0){
                    Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){
                        NativeControllerInbox(inbox,
                            onRefresh={callPage("window.SimHubNativeShell?.refresh()")},
                            onOlder={callPage("window.SimHubNativeShell?.loadOlder()")},
                            onNewer={callPage("window.SimHubNativeShell?.loadNewer()")},
                            onOpenLogin={changeTab(3)},
                            onWrite={changeTab(4)})
                    }
                }
            }
        }
        if(visible && selected!=null){
            NavigationBar(containerColor=MaterialTheme.colorScheme.surface){
                listOf(Triple(0,R.drawable.ic_hub_messages,"短信"),
                    Triple(1,R.drawable.ic_hub_sim,"设备"),
                    Triple(2,R.drawable.ic_hub_settings,"设置")).forEach{(index,icon,label)->
                    NavigationBarItem(
                        selected=tab==index||(index==0&&tab in setOf(3,4)),
                        onClick={changeTab(index)},
                        icon={HubIcon(icon,Modifier.size(23.dp))},
                        label={Text(label)},alwaysShowLabel=true)
                }
            }
        }
    }
    if(showAdd)AlertDialog(onDismissRequest={showAdd=false;inputError=""},
        icon={HubIcon(R.drawable.ic_hub_shield,Modifier.size(26.dp))},
        title={Text("添加管理中心")},
        text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("扫描二维码或粘贴 HTTPS 管理地址。登录和 Vault 密钥仍独立验证。",
                style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(value=pasted,onValueChange={pasted=it;inputError=""},
                label={Text("管理端链接")},singleLine=true,modifier=Modifier.fillMaxWidth())
            if(inputError.isNotEmpty())Text(inputError,color=MaterialTheme.colorScheme.error)
            OutlinedButton(onClick={showAdd=false;onScan()},modifier=Modifier.fillMaxWidth()){
                Text("扫描二维码")
            }
        }},
        confirmButton={TextButton(onClick={
            val origin=ControllerProfiles.add(context,pasted)
            if(origin==null)inputError="请输入不含凭据的 HTTPS 管理域名"
            else {profiles=ControllerProfiles.list(context);openOrigin(origin);pasted="";showAdd=false}
        }){Text("添加")}},
        dismissButton={TextButton(onClick={showAdd=false}){Text("取消")}})
    if(confirmRemove && selected!=null)AlertDialog(onDismissRequest={confirmRemove=false},
        title={Text("移除管理空间？")},
        text={Text("只移除管理入口，不删除服务器或短信。请先在管理页面退出登录，以清理残留会话。")},
        confirmButton={TextButton(onClick={
            ControllerProfiles.remove(context,selected!!)
            profiles=ControllerProfiles.list(context)
            selected=ControllerProfiles.selected(context)
            loadedOrigin=null;web?.loadUrl("about:blank")
            inbox.clear();confirmRemove=false
        }){Text("移除")}},
        dismissButton={TextButton(onClick={confirmRemove=false}){Text("取消")}})
}
