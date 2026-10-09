package com.blackkcold.simhub

import android.Manifest
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ClipDescription
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.integration.android.IntentResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PairingDisplay(val code:String,val fingerprint:String,val waiting:Boolean,val error:String="")
/** Ephemeral ECDH key stays in process RAM across fold/unfold Activity recreation. */
private object ActivePairing {
    @Volatile var session:PairingManager.Session?=null
    @Volatile var expiresAt:Long=0L
}
interface HubController {
    fun refresh()
    fun requestAccess()
    fun requestSmsRole()
    fun scan()
    fun enrollLink(link:String)
    fun startPairing(server:String)
    fun setRealtime(value:Boolean)
    fun syncHistory(older:Boolean)
    fun sendSms(subId:Int,to:String,body:String,onSuccess:()->Unit)
    fun requestContacts()
    fun resetEnrollment()
    fun checkOta()
    fun exportDiagnostics()
    fun setDeveloperEnabled(value:Boolean)
    fun viewLogs()
    fun clearLogs()
    fun refreshDiagnostics()
    fun setLanguage(index:Int)
    fun toggleSharing(value:Boolean)
    fun setSimTag(channelId:String,revision:Long,label:String,number:String)
    fun openNetworkSettings()
    fun loadMoreSms()
    fun copyOtp(code:String)
}
class HubActivity: ComponentActivity(), HubController {
    private var snapshot by mutableStateOf<HubSnapshot?>(null)
    private val tools=HubToolsState()
    private var pendingDiagnosticFile:File?=null
    private var loading by mutableStateOf(true)
    private var smsLimit=100
    private var incomingId by mutableStateOf(0)
    private var incomingRecipient by mutableStateOf("")
    private var incomingBody by mutableStateOf("")
    private var pairing by mutableStateOf<PairingDisplay?>(null)
    private var fold by mutableStateOf<FoldingFeature?>(null)
    private var pendingTask:Job?=null
    private val refreshHandler=Handler(Looper.getMainLooper())
    private val refreshAfterChange=Runnable { refresh() }
    private val poolObserver=object:BroadcastReceiver(){
        override fun onReceive(context:Context?,intent:Intent?){
            if(intent?.action==SharedPoolClient.ACTION_CACHE_UPDATED){
                refreshHandler.removeCallbacks(refreshAfterChange)
                refreshHandler.postDelayed(refreshAfterChange,350)
            }
        }
    }
    private val smsObserver=object:ContentObserver(refreshHandler){
        override fun onChange(selfChange:Boolean){
            refreshHandler.removeCallbacks(refreshAfterChange)
            refreshHandler.postDelayed(refreshAfterChange,400)
        }
    }
    private val smsRoleLauncher=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){refresh()}
    private val permissionsLauncher=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){refresh()}
    private val contactsLauncher=registerForActivityResult(ActivityResultContracts.RequestPermission()){refresh()}
    private val exportLauncher=registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val source=pendingDiagnosticFile; pendingDiagnosticFile=null
        if(uri!=null && source!=null)lifecycleScope.launch {
            try{withContext(Dispatchers.IO){contentResolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } } ?: throw IllegalStateException("No export stream")}}
            catch(e:Exception){AppLogger.e(this@HubActivity,"Diagnostics","Export failed",e);toast(getString(R.string.diagnostic_export_failed))}
            finally{source.delete()}
        }
    }

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        UiLocale.apply(this)
        tools.developer=DeveloperSettings.isEnabled(this)
        tools.language=UiLocale.index(this)
        tools.installedAt=try { SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.getDefault()).format(Date(packageManager.getPackageInfo(packageName,0).lastUpdateTime)) } catch(_:Exception){"—"}
        window.statusBarColor=android.graphics.Color.TRANSPARENT
        window.navigationBarColor=android.graphics.Color.TRANSPARENT
        ActivePairing.session?.let{pairing=PairingDisplay(it.code,it.fingerprint,true)}
        handleComposeIntent(intent)
        setContent { HubApp(snapshot,loading,pairing,fold,this,tools,incomingId,incomingRecipient,incomingBody) }
        // ACTION_VIEW is delivered to onCreate for a cold-start browser QR link;
        // onNewIntent only handles an already running Activity.
        if(intent?.action==Intent.ACTION_VIEW &&
            intent.data?.scheme.equals("simhub",ignoreCase=true) &&
            intent.data?.host.equals("enroll",ignoreCase=true)) {
            enrollLink(intent.data.toString())
        }
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@HubActivity).windowLayoutInfo(this@HubActivity)
                    .collectLatest { info -> fold=info.displayFeatures.filterIsInstance<FoldingFeature>()
                        .firstOrNull { it.isSeparating } }
            }
        }
    }
    override fun onStart(){
        super.onStart()
        val poolFilter=IntentFilter(SharedPoolClient.ACTION_CACHE_UPDATED)
        if(Build.VERSION.SDK_INT>=33)registerReceiver(poolObserver,poolFilter,Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(poolObserver,poolFilter)
        try{contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI,true,smsObserver)}
        catch(error:SecurityException){AppLogger.e(this,"HubSms","SMS observer permission denied",error)}
    }
    override fun onStop(){
        try{unregisterReceiver(poolObserver)}catch(_:Exception){}
        try{contentResolver.unregisterContentObserver(smsObserver)}
        catch(_:Exception){}
        refreshHandler.removeCallbacks(refreshAfterChange)
        super.onStop()
    }
    override fun onResume(){
        super.onResume();refresh()
        val active=ActivePairing.session
        if(active!=null && pendingTask==null)pollSession(active)
    }
    override fun onNewIntent(intent:Intent){
        super.onNewIntent(intent)
        handleComposeIntent(intent)
        if(intent.action==Intent.ACTION_VIEW && intent.data?.scheme=="simhub") {
            enrollLink(intent.data.toString())
        }
    }
    private fun handleComposeIntent(intent:Intent?){
        if(intent?.action!=Intent.ACTION_SENDTO)return
        val scheme=intent.data?.scheme?.lowercase()
        if(scheme !in setOf("sms","smsto","mms","mmsto"))return
        incomingRecipient=intent.data?.schemeSpecificPart?.substringBefore("?").orEmpty()
        incomingBody=intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().take(4000)
        incomingId+=1
    }
    override fun refresh(){
        lifecycleScope.launch {
            try{
                val next=withContext(Dispatchers.IO){HubRepository.snapshot(applicationContext,smsLimit)}
                snapshot=next
                val pool=SharedPoolClient(applicationContext)
                tools.poolEnabled=pool.optedIn()
                tools.poolApproved=pool.approved()
                tools.poolStatus=pool.status()
            }catch(error:Exception){
                AppLogger.e(this@HubActivity,"Dashboard","State refresh failed",error)
                toast(getString(R.string.hub_refresh_error))
            }finally{loading=false}
        }
    }
    override fun requestAccess(){
        val missing=mutableListOf(Manifest.permission.READ_SMS,Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,Manifest.permission.READ_PHONE_STATE,Manifest.permission.READ_PHONE_NUMBERS)
            .filter{checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED}.toMutableList()
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        if(missing.isEmpty())toast(getString(R.string.permissions_granted))
        else permissionsLauncher.launch(missing.toTypedArray())
    }
    override fun requestSmsRole(){
        val rm=getSystemService(RoleManager::class.java)
        if(rm?.isRoleAvailable(RoleManager.ROLE_SMS)==true && !rm.isRoleHeld(RoleManager.ROLE_SMS))
            smsRoleLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_SMS))
        else toast(getString(R.string.sms_role_already))
    }
    override fun scan(){
        IntentIntegrator(this).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt(getString(R.string.scan_pair_qr)).setBeepEnabled(false)
            .setOrientationLocked(false).initiateScan()
    }
    @Deprecated("ZXing activity result compatibility")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        val result:IntentResult?=IntentIntegrator.parseActivityResult(requestCode,resultCode,data)
        if(result?.contents!=null)enrollLink(result.contents)
    }
    override fun enrollLink(link:String){
        val uri=try{Uri.parse(link.trim())}catch(_:Exception){null}
        if(uri?.scheme!="simhub" || uri.host!="enroll"){
            toast(getString(R.string.err_invalid_enrollment));return
        }
        val host=try{
            val relay=java.net.URI(uri.getQueryParameter("server") ?: "")
            if(relay.scheme!="https" || relay.host.isNullOrBlank())throw IllegalArgumentException()
            relay.host
        }catch(_:Exception){toast(getString(R.string.err_https_required));return}
        AlertDialog.Builder(this).setTitle(R.string.enrollment_confirm_title)
            .setMessage(getString(R.string.enrollment_confirm_message,host))
            .setNegativeButton(R.string.cancel,null)
            .setPositiveButton(R.string.enrollment_confirm_action){_,_->
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO){ EnrollmentManager.enroll(applicationContext,link) }
                        toast(getString(R.string.enrollment_complete));refresh()
                    }catch(e:Exception){
                        AppLogger.e(this@HubActivity,"Enrollment","Secure pairing failed",e)
                        toast(UiErrors.message(this@HubActivity,e))
                    }
                }
            }.show()
    }
    override fun startPairing(server:String){
        val host=try{
            val u=java.net.URI(server.trim())
            if(u.scheme!="https" || u.host.isNullOrBlank() || u.userInfo!=null)throw IllegalArgumentException()
            u.host
        }catch(_:Exception){toast(getString(R.string.err_https_required));return}
        AlertDialog.Builder(this).setTitle(R.string.enrollment_confirm_title)
            .setMessage(getString(R.string.pair_confirm_server,host))
            .setNegativeButton(R.string.cancel,null)
            .setPositiveButton(R.string.start_pair_code){_,_->
                pendingTask?.cancel()
                pendingTask=lifecycleScope.launch {
                    try{
                        val session=withContext(Dispatchers.IO){PairingManager.start(applicationContext,server)}
                        ActivePairing.session=session
                        ActivePairing.expiresAt=System.currentTimeMillis()+300000
                        pairing=PairingDisplay(session.code,session.fingerprint,true)
                        pollSession(session)
                    }catch(e:Exception){

                        AppLogger.e(this@HubActivity,"Pairing","Code pairing failed",e)
                        pairing=PairingDisplay("","",false,UiErrors.message(this@HubActivity,e))
                    }
                }
            }.show()
    }
    private fun pollSession(session:PairingManager.Session){
        pendingTask?.cancel()
        pendingTask=lifecycleScope.launch {
            try{
                while(ActivePairing.session===session && System.currentTimeMillis()<ActivePairing.expiresAt){
                    delay(3500)
                    val complete=withContext(Dispatchers.IO){PairingManager.poll(applicationContext,session)}
                    if(complete){
                        ActivePairing.session=null
                        pairing=PairingDisplay(session.code,session.fingerprint,false)
                        toast(getString(R.string.pair_done));refresh()
                        return@launch
                    }
                }
                if(ActivePairing.session===session){
                    ActivePairing.session=null
                    pairing=PairingDisplay(session.code,session.fingerprint,false,getString(R.string.hub_pair_timeout))
                }
            }catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(e:Exception){
                AppLogger.e(this@HubActivity,"Pairing","Code pairing interrupted",e)
                if(ActivePairing.session===session){
                    pairing=PairingDisplay(session.code,session.fingerprint,false,UiErrors.message(this@HubActivity,e))
                    // A network failure retains ephemeral credentials until expiry.
                }
            }finally { pendingTask=null }
        }
    }
    override fun setRealtime(value:Boolean){
        try{
            if(value){
                if(!AgentConfig(this).isEnrolled()){toast(getString(R.string.enroll_first));return}
                RelayForegroundService.start(this)
            }else RelayForegroundService.stop(this)
            refresh()
        }catch(e:Exception){
            AppLogger.e(this,"Relay","Foreground service toggle failed",e)
            toast(UiErrors.message(this,e))
        }
    }
    override fun syncHistory(older:Boolean){
        lifecycleScope.launch {
            try{
                val count=withContext(Dispatchers.IO){
                    val n=if(older)SmsHistorySync.syncOlder(applicationContext,100)
                          else SmsHistorySync.syncRecent(applicationContext,100)
                    SyncJobService.scheduleNow(applicationContext)
                    n
                }
                if(count<0)toast(getString(R.string.hub_provider_permission))
                else toast(getString(R.string.hub_history_queued,count))
                refresh()
            }catch(e:Exception){toast(UiErrors.message(this@HubActivity,e))}
        }
    }
    override fun sendSms(subId:Int,to:String,body:String,onSuccess:()->Unit){
        if(subId<0 || to.isBlank() || body.isBlank()){
            toast(getString(R.string.hub_compose_required));return
        }
        lifecycleScope.launch {
            try{
                withContext(Dispatchers.IO){SmsSender.send(applicationContext,"local-"+UUID.randomUUID(),subId,to.trim(),body)}
                onSuccess();toast(getString(R.string.hub_sms_submitted));refresh()
            }catch(e:Exception){
                AppLogger.e(this@HubActivity,"Compose","SMS submit failed",e)
                toast(UiErrors.message(this@HubActivity,e))
            }
        }
    }
    override fun loadMoreSms(){
        smsLimit=(smsLimit+50).coerceAtMost(1000)
        lifecycleScope.launch{
            try{withContext(Dispatchers.IO){SharedPoolClient(applicationContext).loadOlder()}}
            catch(e:Exception){AppLogger.e(this@HubActivity,"SharedPool","History load failed",e)}
            refresh()
        }
    }
    override fun toggleSharing(value:Boolean){
        lifecycleScope.launch{
            try{
                withContext(Dispatchers.IO){SharedPoolClient(applicationContext).setEnabled(value)}
                refresh();toast(if(value)"已申请共享，请在 Web 管理后台授权" else "共享已关闭，本机共享缓存已清理")
            }catch(e:Exception){toast(UiErrors.message(this@HubActivity,e));refresh()}
        }
    }
    override fun setSimTag(channelId:String,revision:Long,label:String,number:String){
        lifecycleScope.launch{
            try{
                withContext(Dispatchers.IO){
                    SimTagStore.set(applicationContext,channelId,revision,label,number)
                    val profile=SimTagStore.get(applicationContext,channelId,revision)
                    val payload=org.json.JSONObject().put("channelId",channelId).put("channelRevision",revision)
                        .put("tag",profile.optString("tag","")).put("tail",profile.optString("tail",""))
                    EventQueue.queue(applicationContext,"sim-profile-"+UUID.randomUUID(),"sim.profile",
                        System.currentTimeMillis()/1000,-1,false,payload,org.json.JSONObject())
                    SyncJobService.scheduleNow(applicationContext)
                }
                refresh();toast("SIM 标签已保存")
            }catch(e:Exception){toast(UiErrors.message(this@HubActivity,e))}
        }
    }
    override fun requestContacts(){
        if(checkSelfPermission(Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED)
            contactsLauncher.launch(Manifest.permission.READ_CONTACTS)
        else toast(getString(R.string.permissions_granted))
    }
    override fun resetEnrollment(){
        if(!AgentConfig(this).isEnrolled())return
        AlertDialog.Builder(this).setTitle(R.string.reset_title).setMessage(R.string.reset_message)
            .setNegativeButton(R.string.cancel,null)
            .setPositiveButton(R.string.reset){_,_->
                EnrollmentManager.requestReset(this);SyncJobService.scheduleNow(this);refresh()
                toast(getString(R.string.enrollment_reset_pending))
            }.show()
    }
    override fun checkOta(){
        lifecycleScope.launch {
            try{val info=withContext(Dispatchers.IO){ApiClient(applicationContext).ota()}
                if(info.optBoolean("available")){
                    tools.ota=getString(R.string.update_version,info.optString("versionName","?"),info.optString("notes",""))
                    val link=info.optString("url","")
                    AlertDialog.Builder(this@HubActivity).setTitle(R.string.update_title)
                        .setMessage(tools.ota).setNegativeButton(R.string.close,null)
                        .setPositiveButton(R.string.open_download){_,_->
                            if(link.startsWith("https://"))
                                startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(link)))
                        }.show()
                }else tools.ota=getString(R.string.update_none)
            }catch(e:Exception){toast(UiErrors.message(this@HubActivity,e))}
        }
    }
    override fun exportDiagnostics(){
        lifecycleScope.launch {
            try{val source=withContext(Dispatchers.IO){DiagnosticExporter.create(applicationContext)}
                pendingDiagnosticFile=source
                exportLauncher.launch("simhub-diagnostics-"+SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(Date())+".zip")
            }catch(e:Exception){AppLogger.e(this@HubActivity,"Diagnostics","Export preparation failed",e)
                toast(getString(R.string.diagnostic_export_failed))}
        }
    }
    override fun setDeveloperEnabled(value:Boolean){
        DeveloperSettings.setEnabled(this,value);tools.developer=value
        AppLogger.i(this,"Developer",if(value)"Diagnostic logging enabled" else "Diagnostic logging disabled")
        if(!value){tools.logs="";tools.diagnostics=""}else viewLogs()
    }
    override fun viewLogs(){if(!DeveloperSettings.isEnabled(this))return
        tools.logs=AppLogger.recent(this,24000).ifBlank{getString(R.string.logs_empty)}
    }
    override fun clearLogs(){AppLogger.clear(this);tools.logs=getString(R.string.logs_empty)
        toast(getString(R.string.logs_cleared))
    }
    override fun refreshDiagnostics(){
        lifecycleScope.launch{
            try{val json=withContext(Dispatchers.IO){
                val state=StateCollector.collect(applicationContext)
                if(AgentConfig(applicationContext).isEnrolled())ApiClient(applicationContext).putState()
                state.toString(2)
            }
            tools.diagnostics=if(DeveloperSettings.isEnabled(this@HubActivity))json else ""
            refresh()
            }catch(e:Exception){AppLogger.e(this@HubActivity,"Diagnostics","State refresh failed",e)
                toast(UiErrors.message(this@HubActivity,e))}
        }
    }
    override fun setLanguage(index:Int){
        val next=UiLocale.fromIndex(index)
        if(next==UiLocale.get(this))return
        UiLocale.set(this,next);UiLocale.apply(this);tools.language=index;recreate()
    }
    override fun openNetworkSettings(){
        try{startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS))}
        catch(_:Exception){toast(getString(R.string.generic_error))}
    }
    override fun copyOtp(code:String){
        val clip=ClipData.newPlainText("SIM Hub",code)
        if(Build.VERSION.SDK_INT>=33)clip.description.extras=android.os.PersistableBundle().apply{putBoolean(ClipDescription.EXTRA_IS_SENSITIVE,true)}
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        toast(getString(R.string.hub_code_copied))
    }
    private fun toast(msg:String){Toast.makeText(this,msg,Toast.LENGTH_SHORT).show()}
}
