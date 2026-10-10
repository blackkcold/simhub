package com.blackkcold.simhub

import android.Manifest
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.app.AlertDialog
import android.app.role.RoleManager
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.CompanionDeviceManager
import android.content.IntentSender
import android.provider.Settings
import androidx.activity.result.IntentSenderRequest
import rikka.shizuku.Shizuku
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
    fun setEnergyMode(mode:String)
    fun syncHistory(older:Boolean)
    fun sendSms(subId:Int,to:String,body:String,onSuccess:()->Unit)
    fun requestContacts()
    fun resetEnrollment()
    fun checkOta()
    fun setAutoCheckUpdates(value:Boolean)
    fun setAutoDownloadUpdates(value:Boolean)
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
    fun openCompatibility()
    fun closeCompatibility()
    fun refreshCompatibility()
    fun probeSmsProvider()
    fun reconcileSmsNow()
    fun requestShizukuAuthorization()
    fun openShizukuManager()
    fun copyAdbDiagnostics()
    fun startCompanionAssociation()
    fun removeCompanionAssociations()
    fun openBatteryOptimization()
    fun refreshCompatibilityAudit()
    fun clearCompatibilityAudit()
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
            if(!selfChange && AgentConfig(this@HubActivity).isEnrolled()){
                AgentConfig(this@HubActivity).recordSmsProviderChange()
                SyncJobService.scheduleNow(applicationContext)
            }
        }
    }
    private val companionLauncher=registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()){result->
        CompatibilityAudit.record(this,"companion_confirmation",
            if(result.resultCode==RESULT_OK)"accepted" else "cancelled")
        refreshCompatibility()
    }
    private val shizukuPermissionListener=Shizuku.OnRequestPermissionResultListener {requestCode,result->
        if(requestCode==12012){
            CompatibilityAudit.record(this,"shizuku_permission",
                if(result==PackageManager.PERMISSION_GRANTED)"granted" else "denied")
            AppLogger.i(this,"Compatibility","Shizuku permission result received")
            refreshCompatibility()
        }
    }
    private val shizukuBinderListener=Shizuku.OnBinderReceivedListener {
        if(tools.compatibilityOpen)refreshCompatibility()
    }
    private val shizukuDeadListener=Shizuku.OnBinderDeadListener {
        if(tools.compatibilityOpen)refreshCompatibility()
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
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener)
        Shizuku.addBinderDeadListener(shizukuDeadListener)
        UiLocale.apply(this)
        tools.energyMode=EnergyPolicy.mode(this)
        tools.developer=DeveloperSettings.isEnabled(this)
        tools.language=UiLocale.index(this)
        tools.autoCheckUpdates=AppUpdater.prefs(this).getBoolean("autoCheck",true)
        tools.autoDownloadUpdates=AppUpdater.prefs(this).getBoolean("autoDownload",false)
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
    override fun onDestroy(){
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.removeBinderReceivedListener(shizukuBinderListener)
        Shizuku.removeBinderDeadListener(shizukuDeadListener)
        super.onDestroy()
    }
    override fun onStart(){
        super.onStart()
        val poolFilter=IntentFilter(SharedPoolClient.ACTION_CACHE_UPDATED)
        if(Build.VERSION.SDK_INT>=33)registerReceiver(poolObserver,poolFilter,Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(poolObserver,poolFilter)
        try{contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI,true,smsObserver)}
        catch(error:SecurityException){AppLogger.e(this,"HubSms","SMS observer permission denied",error)}
        if(AgentConfig(this).isEnrolled()&&AppUpdater.shouldCheck(this)){
            AppUpdater.check(applicationContext,false){info,error->
                if(error==null && info!=null && info.optBoolean("available")){
                    val version=info.optString("versionName")
                    tools.ota="发现新版本 v"+version
                    if(tools.autoDownloadUpdates &&
                        info.optInt("versionCode")!=AppUpdater.ignored(this@HubActivity)){
                        AppUpdater.downloadAndInstall(this@HubActivity,info){message->tools.ota=message}
                    }
                }
            }
        }
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
        if(tools.compatibilityOpen)refreshCompatibility()
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
    override fun setEnergyMode(mode:String){
        try{
            EnergyPolicy.setMode(this,mode)
            EnergyPolicy.resetSchedule(this)
            tools.energyMode=mode
            SyncJobService.scheduleNow(this)
            // Restart the opt-in foreground service so no stale timer survives.
            if(AgentConfig(this).alwaysOn()){
                stopService(Intent(this,RelayForegroundService::class.java))
                RelayForegroundService.resume(this)
            }
            refresh()
        }catch(error:Exception){toast(UiErrors.message(this,error))}
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
        smsLimit=(smsLimit+50).coerceAtMost(2000)
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
                refresh();toast(getString(if(value)R.string.hub_sharing_requested else R.string.hub_sharing_disabled))
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
                refresh();toast(getString(R.string.hub_sim_tag_saved))
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
    override fun setAutoCheckUpdates(value:Boolean){
        tools.autoCheckUpdates=value
        AppUpdater.prefs(this).edit().putBoolean("autoCheck",value).apply()
    }
    override fun setAutoDownloadUpdates(value:Boolean){
        tools.autoDownloadUpdates=value
        AppUpdater.prefs(this).edit().putBoolean("autoDownload",value).apply()
    }
    override fun checkOta(){
        if(!AgentConfig(this).isEnrolled()){
            toast("请先配对 SIM Hub 服务器");return
        }
        tools.ota="正在检查新版本…"
        AppUpdater.check(applicationContext,true){info,error->
            if(error!=null){tools.ota="检查失败："+error;return@check}
            if(info==null||!info.optBoolean("available")){
                tools.ota=getString(R.string.update_none);return@check
            }
            val code=info.optInt("versionCode")
            val version=info.optString("versionName","?")
            val note=info.optString("notes","")
            tools.ota="新版本 v"+version+" · "+note
            val ignored=AppUpdater.ignored(this@HubActivity)==code
            AlertDialog.Builder(this@HubActivity)
                .setTitle(R.string.update_title)
                .setMessage(tools.ota+(if(ignored)"\n此版本已忽略，可重新安装。" else ""))
                .setNeutralButton(R.string.close,null)
                .setNegativeButton(if(ignored)"取消忽略" else "忽略此版本"){_,_->
                    AppUpdater.ignore(this@HubActivity,if(ignored)-1 else code)
                    tools.ota=if(ignored)"已恢复此版本提醒" else "已忽略 v"+version
                }
                .setPositiveButton("下载并安装"){_,_->
                    AppUpdater.downloadAndInstall(this@HubActivity,info){message->tools.ota=message}
                }.show()
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
    private fun auditCompatibility(action:String,outcome:String){
        CompatibilityAudit.record(this,action,outcome)
        AppLogger.i(this,"Compatibility","action="+action+" outcome="+outcome)
        tools.compatibilityAudit=CompatibilityAudit.export(this)
    }
    override fun openCompatibility(){
        tools.compatibilityOpen=true
        tools.compatibilityAudit=CompatibilityAudit.export(this)
        refreshCompatibility()
    }
    override fun closeCompatibility(){
        tools.compatibilityOpen=false
        tools.compatibilityError=""
        tools.compatibilityNote=""
    }
    override fun refreshCompatibility(){
        lifecycleScope.launch {
            try {
                tools.compatibilityBusy=true
                tools.compatibilityStatus=withContext(Dispatchers.IO){
                    CompatibilityManager.inspect(applicationContext,false)
                }
                tools.compatibilityError=""
            }catch(error:Exception){
                tools.compatibilityError=hubError("inspect_failed")
                auditCompatibility("capability_inspection","failed")
            }finally{tools.compatibilityBusy=false}
        }
    }
    private fun hubError(code:String):String = when(code){
        "inspect_failed"->"系统能力检测失败，请导出诊断日志。"
        else->"系统拒绝了此次操作，请查看诊断记录。"
    }
    override fun probeSmsProvider(){
        lifecycleScope.launch {
            tools.compatibilityBusy=true
            try{
                val result=withContext(Dispatchers.IO){CompatibilityManager.inspect(applicationContext,true)}
                tools.compatibilityStatus=result
                val outcome=result.optString("providerProbe","query_failed")
                auditCompatibility("provider_probe",outcome)
                tools.compatibilityNote="SMS Provider: "+outcome
                tools.compatibilityError=""
            }catch(error:Exception){
                auditCompatibility("provider_probe","failed")
                tools.compatibilityError=hubError("probe_failed")
            }finally{tools.compatibilityBusy=false}
        }
    }
    override fun reconcileSmsNow(){
        if(!AgentConfig(this).isEnrolled())return
        lifecycleScope.launch {
            tools.compatibilityBusy=true
            try{
                val count=withContext(Dispatchers.IO){
                    val result=SmsHistorySync.reconcileRecent(applicationContext,100)
                    if(result>=0)SyncJobService.scheduleNow(applicationContext)
                    result
                }
                auditCompatibility("manual_reconcile",if(count>=0)"completed" else "provider_failed")
                tools.compatibilityNote=if(count>=0)
                    "已扫描 "+count+" 条本地短信记录。待上传队列将通过加密 Relay 发送。"
                    else "短信数据库拒绝扫描；请检查权限和状态。"
            }catch(error:Exception){
                auditCompatibility("manual_reconcile","failed")
                tools.compatibilityError=hubError("reconcile_failed")
            }finally{tools.compatibilityBusy=false}
        }
    }
    override fun requestShizukuAuthorization(){
        try{
            if(!Shizuku.pingBinder()){
                tools.compatibilityError="请先启动 Shizuku 服务，再申请独立授权。"
                auditCompatibility("shizuku_request","service_unavailable")
                return
            }
            if(Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED){
                auditCompatibility("shizuku_request","already_granted")
                refreshCompatibility()
                return
            }
            auditCompatibility("shizuku_request","requested")
            Shizuku.requestPermission(12012)
        }catch(error:Exception){
            auditCompatibility("shizuku_request","failed")
            tools.compatibilityError="Shizuku 授权请求失败，请检查管理器状态。"
        }
    }
    override fun openShizukuManager(){
        try {
            val launch=packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if(launch!=null){
                startActivity(launch)
                auditCompatibility("shizuku_manager","opened")
            }else{
                startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://shizuku.rikka.app/")))
                auditCompatibility("shizuku_manager","official_website")
            }
        }catch(error:Exception){
            auditCompatibility("shizuku_manager","failed")
            tools.compatibilityError=hubError("shizuku_open_failed")
        }
    }
    override fun copyAdbDiagnostics(){
        val uid=android.os.Process.myUid()
        val commands=listOf(
            "adb shell getprop ro.build.version.sdk",
            "adb shell getprop ro.product.manufacturer",
            "adb shell cmd role get-role-holders android.app.role.SMS",
            "adb shell cmd appops get --uid "+uid+" READ_OTP_SMS",
            "adb shell dumpsys package com.blackkcold.simhub"
        ).joinToString("\n")
        val clip=ClipData.newPlainText("SIM Hub read-only diagnostics",commands)
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        auditCompatibility("copy_adb_diagnostics","copied")
        tools.compatibilityNote="已复制只读 ADB 命令。使用 Rish 时去掉 adb shell 前缀。"
    }
    override fun startCompanionAssociation(){
        if(!CompatibilityPolicy.canOfferSelfManagedAssociation(
                Build.VERSION.SDK_INT,AgentConfig(this).isEnrolled())){
            tools.compatibilityError="需要 Android 13 以上系统以及已配对的 Relay。"
            return
        }
        if(CompatibilityManager.associationCount(this)>0){
            tools.compatibilityNote="已存在 SIM Hub 关联。如需重新测试，先手动解除关联。"
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Android 系统伴侣关联")
            .setMessage("将向 Android 请求自管理伴侣关联，需要系统授权确认。它不会自动读取 OTP，也不保证获得验证码实时访问豁免。仅对自己管理的可信设备进行关联。")
            .setNegativeButton("取消",null)
            .setPositiveButton("继续") { _,_ ->
                try{
                    val cdm=getSystemService(CompanionDeviceManager::class.java)
                        ?:throw IllegalStateException("Companion service unavailable")
                    val name=AgentConfig(this).deviceName().take(40)
                    val request=AssociationRequest.Builder()
                        .setSelfManaged(true)
                        .setDisplayName("SIM Hub · "+name)
                        .setSingleDevice(true).build()
                    auditCompatibility("companion_association","requested")
                    cdm.associate(request,mainExecutor,object:CompanionDeviceManager.Callback(){
                        override fun onAssociationPending(intentSender:IntentSender){
                            companionLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                        }
                        override fun onAssociationCreated(associationInfo:AssociationInfo){
                            auditCompatibility("companion_association","created")
                            refreshCompatibility()
                        }
                        override fun onFailure(errorMessage:CharSequence?){
                            auditCompatibility("companion_association","failed")
                            tools.compatibilityError="关联失败，可能被厂商系统拒绝；请导出诊断日志。"
                        }
                    })
                }catch(error:Exception){
                    auditCompatibility("companion_association","failed")
                    tools.compatibilityError="无法申请系统关联："+error.javaClass.simpleName
                }
            }.show()
    }
    override fun removeCompanionAssociations(){
        AlertDialog.Builder(this)
            .setTitle("解除系统伴侣关联")
            .setMessage("只解除本应用创建的自管理 CDM 关联，不会删除 SIM Hub Relay 配对、短信或加密密钥。")
            .setNegativeButton("取消",null)
            .setPositiveButton("解除"){_,_->
                try{
                    val removed=CompatibilityManager.removeSimHubAssociations(this)
                    auditCompatibility("companion_disassociate",if(removed)"completed" else "none")
                    refreshCompatibility()
                }catch(error:Exception){
                    auditCompatibility("companion_disassociate","failed")
                    tools.compatibilityError=hubError("companion_remove_failed")
                }
            }.show()
    }
    override fun openBatteryOptimization(){
        try{
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            auditCompatibility("battery_settings","opened")
        }catch(error:Exception){
            try{startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:"+packageName)))
                auditCompatibility("battery_settings","app_details")
            }catch(second:Exception){auditCompatibility("battery_settings","failed")}
        }
    }
    override fun refreshCompatibilityAudit(){
        tools.compatibilityAudit=CompatibilityAudit.export(this)
    }
    override fun clearCompatibilityAudit(){
        AlertDialog.Builder(this)
            .setTitle("清空兼容性审计记录")
            .setMessage("仅删除本机诊断操作元数据，不影响短信、配对或已授予的权限。")
            .setNegativeButton("取消",null)
            .setPositiveButton("清空"){_,_->
                CompatibilityAudit.clear(this)
                tools.compatibilityAudit=CompatibilityAudit.export(this)
            }.show()
    }

    private fun toast(msg:String){Toast.makeText(this,msg,Toast.LENGTH_SHORT).show()}
}
