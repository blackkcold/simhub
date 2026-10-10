package com.blackkcold.simhub

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.net.http.SslError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/** Trusted-origin PWA container. No JavaScript bridge to Node secrets or SMS APIs. */
class ControllerActivity:Activity() {
    private val prefs by lazy { getSharedPreferences("simhub_controller_profiles_v1",MODE_PRIVATE) }
    private lateinit var root:LinearLayout
    private lateinit var content:LinearLayout
    private lateinit var title:TextView
    private var web:WebView?=null
    private var activeOrigin:String?=null

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        HubModes.surface(this,"controller")
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(247,248,252)) }
        val bar=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL;setPadding(16,12,12,12); setBackgroundColor(Color.WHITE) }
        title=TextView(this).apply {
            text="SIM Hub · 管理控制台";textSize=16f;setTextColor(Color.rgb(25,32,56))
            setPadding(8,8,8,8)
        }
        bar.addView(title,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        val switch=Button(this).apply { text="空间";contentDescription="切换管理空间";setOnClickListener { profilesMenu(this) } }
        bar.addView(switch)
        bar.addView(Button(this).apply { text="节点";contentDescription="切换至本机节点";setOnClickListener {
            HubModes.surface(this@ControllerActivity,"node")
            startActivity(Intent(this@ControllerActivity,HubActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }})
        root.addView(bar)
        content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        root.addView(content,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
        setContentView(root)
        val initial=prefs.getString("selected",null)
        if(initial.isNullOrBlank())showEmpty() else openProfile(initial)
    }

    private fun validOrigin(value:String):String? {
        val uri=runCatching { Uri.parse(value.trim()) }.getOrNull() ?: return null
        if(uri.scheme!="https" || uri.host.isNullOrBlank() || !uri.userInfo.isNullOrEmpty() ||
           !uri.query.isNullOrEmpty() || !uri.fragment.isNullOrEmpty() ||
           (uri.path!=null && uri.path!="" && uri.path!="/"))return null
        val host=uri.host!!.lowercase(java.util.Locale.ROOT)
        if(host=="localhost" || host.endsWith(".localhost"))return null
        return "https://"+host+(if(uri.port>0) ":"+uri.port else "")
    }
    private fun origins():List<String> {
        val arr=runCatching { JSONArray(prefs.getString("origins","[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { validOrigin(arr.optString(it)) }.distinct()
    }
    private fun store(origin:String) {
        val all=(origins()+origin).distinct()
        prefs.edit().putString("origins",JSONArray(all).toString()).putString("selected",origin).apply()
    }
    private fun profilesMenu(anchor:View) {
        PopupMenu(this,anchor).apply {
            origins().forEachIndexed { i,origin -> menu.add(0,i,0,origin) }
            menu.add(1,0,1,"+ 添加管理空间")
            menu.add(2,0,2,"在系统浏览器中打开")
            setOnMenuItemClickListener {
                when(it.groupId) {
                    0 -> openProfile(origins().getOrNull(it.itemId) ?: return@setOnMenuItemClickListener false)
                    1 -> addProfile()
                    2 -> activeOrigin?.let { url -> startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) }
                }
                true
            }
            show()
        }
    }
    private fun showEmpty() {
        content.removeAllViews();web?.destroy();web=null
        val wrap=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setPadding(36,80,36,20)
        }
        wrap.addView(TextView(this).apply {
            text="连接你的私有管理中心";textSize=22f;setTextColor(Color.rgb(25,32,56))
        })
        wrap.addView(TextView(this).apply {
            text="使用 HTTPS 管理域名。此模式不需要短信权限，也不会访问本机 Node Key。"
            textSize=14f;setPadding(0,22,0,22)
        })
        wrap.addView(Button(this).apply { text="添加管理空间";setOnClickListener { addProfile() } })
        content.addView(wrap)
    }
    private fun addProfile() {
        val input=EditText(this).apply {
            hint="https://admin.example.com";inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val wrap=LinearLayout(this).apply { setPadding(28,8,28,0);addView(input) }
        AlertDialog.Builder(this).setTitle("连接管理服务器")
            .setMessage("请填写私有管理中心的 HTTPS 域名。仅会加载当前选中的可信 Origin。")
            .setView(wrap).setNegativeButton("取消",null)
            .setPositiveButton("连接") { _,_ ->
                val origin=validOrigin(input.text.toString())
                if(origin==null) Toast.makeText(this,"请输入有效的 HTTPS 管理域名",Toast.LENGTH_LONG).show()
                else { store(origin);openProfile(origin) }
            }.show()
    }
    private fun permitted(uri:Uri):Boolean {
        val source=activeOrigin ?: return false
        val allowed=Uri.parse(source)
        return uri.scheme=="https" &&
            uri.host.equals(allowed.host,ignoreCase=true) &&
            (if(uri.port==-1)443 else uri.port)==(if(allowed.port==-1)443 else allowed.port)
    }
    private fun openProfile(origin:String) {
        val valid=validOrigin(origin) ?: run { showEmpty();return }
        activeOrigin=valid;store(valid)
        title.text="管理控制台 · "+Uri.parse(valid).host
        web?.stopLoading();content.removeAllViews();web?.destroy();web=null
        val w=WebView(this)
        w.settings.apply {
            javaScriptEnabled=true
            domStorageEnabled=true
            allowFileAccess=false
            allowContentAccess=false
            javaScriptCanOpenWindowsAutomatically=false
            setSupportMultipleWindows(false)
            mixedContentMode=android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if(android.os.Build.VERSION.SDK_INT>=26)safeBrowsingEnabled=true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(w,false)
        w.webViewClient=object:WebViewClient() {
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
                if(permitted(request.url))return false
                Toast.makeText(this@ControllerActivity,"已阻止打开非当前管理空间的链接",Toast.LENGTH_SHORT).show()
                return true
            }
            override fun onReceivedSslError(view:WebView,handler:SslErrorHandler,error:SslError) {
                handler.cancel()
                Toast.makeText(this@ControllerActivity,"TLS 证书验证失败，已拒绝连接",Toast.LENGTH_LONG).show()
            }
        }
        web=w
        content.addView(w,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
        w.loadUrl(valid+"/")
    }
    @Deprecated("Use OnBackPressedDispatcher in future Compose migration")
    override fun onBackPressed() {
        val view=web
        if(view!=null && view.canGoBack())view.goBack() else super.onBackPressed()
    }
    override fun onDestroy() {
        web?.stopLoading()
        content.removeAllViews()
        web?.destroy()
        web=null
        super.onDestroy()
    }
}
