package com.blackkcold.simhub

import android.content.Intent
import android.net.Uri
import android.net.http.SslError
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
import java.io.ByteArrayInputStream

/**
 * The same Compose chrome as the local Node. A controller session is retained in
 * the existing WebView while switching surfaces; no credentials cross to Node.
 */
@Composable
fun ControllerWorkspace(visible:Boolean,revision:Int,onScan:()->Unit,modifier:Modifier=Modifier) {
    val context=LocalContext.current
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

    BackHandler(visible && canGoBack) { web?.goBack() }
    LaunchedEffect(visible,web) {
        // Leave the active tab and its Vault snapshot in place, but suspend
        // WebView timers while the user is working in the native SIM node.
        if(visible)web?.onResume() else web?.onPause()
    }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=8.dp),
            verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement=Arrangement.spacedBy(8.dp),
                verticalAlignment=Alignment.CenterVertically) {
                profiles.forEach { origin ->
                    FilterChip(selected=selected==origin,onClick={
                        ControllerProfiles.select(context,origin)
                        selected=origin
                        error=""
                    },label={Text(Uri.parse(origin).host ?: origin,maxLines=1,
                        overflow=TextOverflow.Ellipsis)})
                }
            }
            IconButton(onClick={showAdd=true},modifier=Modifier.size(44.dp)) {
                HubIcon(R.drawable.ic_hub_add,Modifier.size(22.dp),description="添加管理中心")
            }
        }
        if(selected==null) {
            Box(Modifier.fillMaxSize().padding(16.dp),contentAlignment=Alignment.Center){
                ElevatedCard(Modifier.fillMaxWidth().widthIn(max=520.dp)) {
                    Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
                        HubIcon(R.drawable.ic_hub_shield,Modifier.size(40.dp),
                            tint=MaterialTheme.colorScheme.primary)
                        Text("连接管理中心",style=MaterialTheme.typography.titleLarge)
                        Text("在电脑端管理后台点击「连接管理手机」，扫描二维码即可添加；也可以粘贴 HTTPS 管理地址。二维码不含密码或密钥。",
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick=onScan,modifier=Modifier.fillMaxWidth()) {
                            HubIcon(R.drawable.ic_hub_add,Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("扫描管理中心二维码")
                        }
                        OutlinedButton(onClick={showAdd=true},modifier=Modifier.fillMaxWidth()){
                            Text("粘贴链接 / 输入地址")
                        }
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=2.dp),
                verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Text("管理空间 · "+(Uri.parse(selected).host ?: ""),modifier=Modifier.weight(1f),
                    maxLines=1,overflow=TextOverflow.Ellipsis,
                    style=MaterialTheme.typography.labelMedium,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick={web?.reload()}) { Text("刷新") }
                TextButton(onClick={
                    val uri=Uri.parse(selected)
                    context.startActivity(Intent(Intent.ACTION_VIEW,uri))
                }) { Text("浏览器打开") }
                TextButton(onClick={confirmRemove=true}) { Text("移除") }
            }
            if(error.isNotEmpty()){
                Text(error,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(16.dp),
                    style=MaterialTheme.typography.bodySmall)
            }
            AndroidView(factory={ctx->
                WebView(ctx).apply{
                    settings.apply{
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
                    webChromeClient=WebChromeClient()
                    webViewClient=object:WebViewClient(){
                        private fun approved(request:Uri):Boolean {
                            val valid=ControllerLinks.normalize(selected ?: "") ?:return false
                            val checked=Uri.parse(valid)
                            return request.scheme=="https" &&
                                request.host.equals(checked.host,true) &&
                                (if(request.port==-1)443 else request.port)==
                                    (if(checked.port==-1)443 else checked.port)
                        }
                        override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
                            if(approved(request.url))return false
                            error="已阻止跳转至其他服务器。请在管理空间中明确添加该域名。"
                            return true
                        }
                        override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse? {
                            if(approved(request.url))return null
                            return WebResourceResponse("text/plain","UTF-8",403,"Forbidden",
                                mapOf("Cache-Control" to "no-store"),
                                ByteArrayInputStream(ByteArray(0)))
                        }
                        override fun onReceivedSslError(view:WebView,handler:SslErrorHandler,sslError:SslError){
                            handler.cancel()
                            error="管理中心 TLS 证书验证失败，已阻止连接"
                        }
                        override fun onPageFinished(view:WebView,url:String) {
                            canGoBack=view.canGoBack()
                            if(ControllerLinks.normalize(url)?.let{it==selected}==true)error=""
                        }
                    }
                }.also { web=it }
            },modifier=Modifier.fillMaxSize().weight(1f),update={view->
                view.visibility=if(visible)android.view.View.VISIBLE else android.view.View.INVISIBLE
                if(visible && loadedOrigin!=selected && selected!=null){
                    loadedOrigin=selected
                    view.loadUrl(selected!!+"/")
                }
            },onRelease={view->view.stopLoading();view.destroy();web=null})
        }
    }
    if(showAdd) {
        AlertDialog(onDismissRequest={showAdd=false;inputError=""},
            icon={HubIcon(R.drawable.ic_hub_shield,Modifier.size(26.dp))},
            title={Text("添加管理中心")},
            text={
                Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Text("扫描 Web 管理端生成的配置二维码，或直接粘贴配置链接。只导入 HTTPS 服务器地址，不导入登录凭据。",
                        style=MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value=pasted,onValueChange={pasted=it;inputError=""},
                        label={Text("管理端链接")},placeholder={Text("https://admin.example.com")},
                        singleLine=true,modifier=Modifier.fillMaxWidth())
                    if(inputError.isNotEmpty())Text(inputError,color=MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick={showAdd=false;onScan()},modifier=Modifier.fillMaxWidth()) {
                        Text("扫描二维码")
                    }
                }
            },
            confirmButton={TextButton(onClick={
                val link=ControllerProfiles.add(context,pasted)
                if(link==null) inputError="链接无效：只支持不含凭据的 HTTPS 管理域名"
                else {
                    profiles=ControllerProfiles.list(context)
                    selected=link;pasted="";showAdd=false;error=""
                }
            }){Text("添加")}},
            dismissButton={TextButton(onClick={showAdd=false}){Text("取消")}}
        )
    }
    if(confirmRemove && selected!=null) {
        AlertDialog(onDismissRequest={confirmRemove=false},
            title={Text("移除管理空间？")},
            text={Text("这只移除本机的管理入口，不会删除服务器、SIM 节点或短信。浏览器缓存与登录会话可能仍保留，请根据需要先在管理中心退出登录。")},
            confirmButton={TextButton(onClick={
                ControllerProfiles.remove(context,selected!!)
                profiles=ControllerProfiles.list(context)
                selected=ControllerProfiles.selected(context)
                loadedOrigin=null;web?.loadUrl("about:blank");confirmRemove=false
            }){Text("移除")}},
            dismissButton={TextButton(onClick={confirmRemove=false}){Text("取消")}}
        )
    }
}
