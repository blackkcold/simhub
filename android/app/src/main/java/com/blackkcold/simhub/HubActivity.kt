package com.blackkcold.simhub

import android.Manifest
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ClipDescription
import android.content.Intent
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

data class PairingDisplay(val code:String,val fingerprint:String,val waiting:Boolean,val error:String="")
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
    fun advanced()
    fun openNetworkSettings()
    fun loadMoreSms()
    fun copyOtp(code:String)
}
class HubActivity: ComponentActivity(), HubController {
    private var snapshot by mutableStateOf<HubSnapshot?>(null)
    private var loading by mutableStateOf(true)
    private var smsLimit=400
    private var pairing by mutableStateOf<PairingDisplay?>(null)
    private var fold by mutableStateOf<FoldingFeature?>(null)
    private var pendingTask:Job?=null
    private val smsRoleLauncher=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){refresh()}
    private val permissionsLauncher=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){refresh()}

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        UiLocale.apply(this)
        window.statusBarColor=android.graphics.Color.TRANSPARENT
        window.navigationBarColor=android.graphics.Color.TRANSPARENT
        setContent { HubApp(snapshot,loading,pairing,fold,this) }
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                WindowInfoTracker.getOrCreate(this@HubActivity).windowLayoutInfo(this@HubActivity)
                    .collectLatest { info -> fold=info.displayFeatures.filterIsInstance<FoldingFeature>()
                        .firstOrNull { it.isSeparating } }
            }
        }
        refresh()
    }
    override fun onResume(){super.onResume();refresh()}
    override fun onNewIntent(intent:Intent){
        super.onNewIntent(intent)
        if(intent.action==Intent.ACTION_VIEW && intent.data?.scheme=="simhub") {
            enrollLink(intent.data.toString())
        }
    }
    override fun refresh(){
        lifecycleScope.launch {
            try{
                val next=withContext(Dispatchers.IO){HubRepository.snapshot(applicationContext,smsLimit)}
                snapshot=next
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
                        pairing=PairingDisplay(session.code,session.fingerprint,true)
                        repeat(72){
                            delay(4000)
                            val complete=withContext(Dispatchers.IO){PairingManager.poll(applicationContext,session)}
                            if(complete){
                                pairing=PairingDisplay(session.code,session.fingerprint,false)
                                toast(getString(R.string.pair_done));refresh()
                                return@launch
                            }
                        }
                        pairing=PairingDisplay(session.code,session.fingerprint,false,getString(R.string.hub_pair_timeout))
                    }catch(e:Exception){
                        AppLogger.e(this@HubActivity,"Pairing","Code pairing failed",e)
                        pairing=PairingDisplay("","",false,UiErrors.message(this@HubActivity,e))
                    }
                }
            }.show()
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
    override fun loadMoreSms(){smsLimit=(smsLimit+400).coerceAtMost(10000);refresh()}
    override fun advanced(){startActivity(Intent(this,MainActivity::class.java))}
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
