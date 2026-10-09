package com.blackkcold.simhub;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQ_SMS_ROLE=2101,REQ_CORE=2201,REQ_CONTACTS=2202,REQ_EXPORT_DIAGNOSTICS=2301;
    private EditText enrollLink;private TextView status,detail,simSummary;private Switch developerSwitch;private LinearLayout developerTools;private Spinner languageSpinner;
    private final java.util.concurrent.ExecutorService exec=Executors.newSingleThreadExecutor();private File pendingDiagnosticFile;
    @Override protected void onCreate(Bundle b){super.onCreate(b);UiLocale.apply(this);setContentView(R.layout.activity_main);enrollLink=findViewById(R.id.enrollLink);status=findViewById(R.id.status);detail=findViewById(R.id.detail);simSummary=findViewById(R.id.simSummary);developerSwitch=findViewById(R.id.developerSwitch);developerTools=findViewById(R.id.developerTools);languageSpinner=findViewById(R.id.languageSpinner);wire();wireLanguage();wireDeveloper();renderBuildInfo();handleIntent(getIntent());refreshLocal();AppLogger.i(this,"MainActivity","UI started");}
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);handleIntent(i);}
    private void wire(){
        findViewById(R.id.enrollButton).setOnClickListener(v->doEnroll());findViewById(R.id.smsRoleButton).setOnClickListener(v->requestSmsRole());findViewById(R.id.permissionButton).setOnClickListener(v->requestCorePermissions());findViewById(R.id.contactButton).setOnClickListener(v->requestPermissions(new String[]{Manifest.permission.READ_CONTACTS},REQ_CONTACTS));
        findViewById(R.id.mobileDataSettingsButton).setOnClickListener(v->{try{startActivity(new Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS));}catch(Exception e){toast(R.string.generic_error);}});
        findViewById(R.id.startRelayButton).setOnClickListener(v->{if(!new AgentConfig(this).isEnrolled()){toast(R.string.enroll_first);return;}AppLogger.i(this,"Relay","User requested always-on relay start");RelayForegroundService.start(this);refreshLocal();});
        findViewById(R.id.stopRelayButton).setOnClickListener(v->{AppLogger.i(this,"Relay","User requested always-on relay stop");RelayForegroundService.stop(this);refreshLocal();});
        findViewById(R.id.resetEnrollmentButton).setOnClickListener(v->new AlertDialog.Builder(this).setTitle(R.string.reset_title).setMessage(R.string.reset_message).setNegativeButton(R.string.cancel,null).setPositiveButton(R.string.reset,(d,w)->{AppLogger.w(this,"Enrollment","User requested bidirectional reset");EnrollmentManager.requestReset(this);refreshLocal();toast(R.string.enrollment_reset_pending);}).show());
        findViewById(R.id.syncButton).setOnClickListener(v->exec.execute(()->{int n=SmsHistorySync.syncRecent(this,100);SyncJobService.scheduleNow(this);AppLogger.i(this,"SmsSync","Manual history sync queued count="+n);runOnUiThread(()->toast(n>=0?getString(R.string.sync_queued,n):getString(R.string.generic_error)));}));
        findViewById(R.id.olderSyncButton).setOnClickListener(v->exec.execute(()->{
            int n=SmsHistorySync.syncOlder(this,100);
            SyncJobService.scheduleNow(this);
            AppLogger.i(this,"SmsSync","User-requested older history queued count="+n);
            runOnUiThread(()->toast(n>=0?getString(R.string.sync_queued,n):getString(R.string.generic_error)));
        }));
        findViewById(R.id.refreshButton).setOnClickListener(v->exec.execute(()->{try{JSONObject s=StateCollector.collect(this);String stateText=s.toString(2);if(new AgentConfig(this).isEnrolled())new ApiClient(this).putState();runOnUiThread(()->{if(DeveloperSettings.isEnabled(this))detail.setText(stateText);refreshLocal();});AppLogger.i(this,"State","Manual device state refresh succeeded");}catch(Exception e){AppLogger.e(this,"State","Manual device state refresh failed",e);runOnUiThread(()->toast(UiErrors.message(this,e)));}}));
        findViewById(R.id.otaButton).setOnClickListener(v->exec.execute(()->{try{JSONObject o=new ApiClient(this).ota();runOnUiThread(()->showOta(o));}catch(Exception e){AppLogger.e(this,"OTA","OTA check failed",e);runOnUiThread(()->toast(UiErrors.message(this,e)));}}));
    }
    private void renderBuildInfo(){
        try{
            long installedAt=getPackageManager().getPackageInfo(getPackageName(),0).lastUpdateTime;
            String installed=new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",java.util.Locale.getDefault()).format(new java.util.Date(installedAt));
            ((TextView)findViewById(R.id.versionInfo)).setText(getString(R.string.build_details,BuildConfig.VERSION_NAME,installed));
        }catch(Exception e){((TextView)findViewById(R.id.versionInfo)).setText("SIM Hub v"+BuildConfig.VERSION_NAME);}
    }
    private void wireLanguage(){ArrayList<String> labels=new ArrayList<>();labels.add(getString(R.string.language_system));labels.add(getString(R.string.language_zh_cn));labels.add(getString(R.string.language_en));languageSpinner.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));languageSpinner.setSelection(UiLocale.index(this),false);languageSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){@Override public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){String next=UiLocale.fromIndex(position);if(next.equals(UiLocale.get(MainActivity.this)))return;UiLocale.set(MainActivity.this,next);AppLogger.i(MainActivity.this,"Locale","Language changed to "+next);UiLocale.apply(MainActivity.this);recreate();}@Override public void onNothingSelected(android.widget.AdapterView<?> parent){}});}
    private void wireDeveloper(){boolean enabled=DeveloperSettings.isEnabled(this);developerSwitch.setChecked(enabled);setDeveloperToolsVisible(enabled);developerSwitch.setOnCheckedChangeListener((button,checked)->{if(checked){DeveloperSettings.setEnabled(this,true);AppLogger.i(this,"Developer","Developer logging enabled");toast(R.string.developer_enabled);}else{AppLogger.i(this,"Developer","Developer logging disabled");DeveloperSettings.setEnabled(this,false);toast(R.string.developer_disabled);}setDeveloperToolsVisible(checked);});findViewById(R.id.viewLogsButton).setOnClickListener(v->showRecentLogs());findViewById(R.id.clearLogsButton).setOnClickListener(v->{AppLogger.clear(this);detail.setText(R.string.logs_empty);toast(R.string.logs_cleared);});findViewById(R.id.exportLogsButton).setOnClickListener(v->exportDiagnostics());}
    private void setDeveloperToolsVisible(boolean visible){developerTools.setVisibility(visible?View.VISIBLE:View.GONE);if(visible)showRecentLogs();}
    private void showRecentLogs(){String logs=AppLogger.recent(this,24000);detail.setText(logs.isEmpty()?getString(R.string.logs_empty):logs);}
    private void exportDiagnostics(){exec.execute(()->{try{pendingDiagnosticFile=DiagnosticExporter.create(this);Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip").putExtra(Intent.EXTRA_TITLE,getString(R.string.diagnostic_export_name,new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",java.util.Locale.US).format(new java.util.Date())));runOnUiThread(()->{toast(R.string.diagnostic_export_ready);startActivityForResult(i,REQ_EXPORT_DIAGNOSTICS);});}catch(Exception e){AppLogger.e(this,"Diagnostics","Failed to prepare diagnostic package",e);runOnUiThread(()->toast(R.string.diagnostic_export_failed));}});}
    private void handleIntent(Intent i){if(i!=null&&Intent.ACTION_VIEW.equals(i.getAction())&&i.getData()!=null&&"simhub".equals(i.getData().getScheme())){enrollLink.setText(i.getData().toString());AppLogger.i(this,"Enrollment","Enrollment link opened");}}
    private final java.util.concurrent.atomic.AtomicBoolean enrolling=new java.util.concurrent.atomic.AtomicBoolean(false);
    private void doEnroll(){
        String link=enrollLink.getText().toString().trim();
        if(link.isEmpty()){toast(R.string.enrollment_link_first);return;}
        try{
            Uri u=Uri.parse(link);
            if(!"simhub".equalsIgnoreCase(u.getScheme())||!"enroll".equalsIgnoreCase(u.getHost()))throw new IllegalArgumentException("Invalid pairing package");
            java.net.URI target=java.net.URI.create(u.getQueryParameter("server"));
            if(!"https".equalsIgnoreCase(target.getScheme())||target.getHost()==null)throw new SecurityException("HTTPS required");
            // Explicit human verification: never auto-enroll a link delivered by
            // another Android app or an unverified custom-Scheme intent.
            new AlertDialog.Builder(this).setTitle(R.string.enrollment_confirm_title)
                .setMessage(getString(R.string.enrollment_confirm_message,target.getHost()))
                .setNegativeButton(R.string.cancel,null)
                .setPositiveButton(R.string.enrollment_confirm_action,(dialog,which)->startEnrollment(link)).show();
        }catch(Exception error){toast(R.string.err_invalid_enrollment);}
    }
    private void startEnrollment(String link){
        if(!enrolling.compareAndSet(false,true))return;
        final View button=findViewById(R.id.enrollButton);button.setEnabled(false);
        AppLogger.i(this,"Enrollment","Enrollment started");
        exec.execute(()->{
            try{
                EnrollmentManager.enroll(this,link);
                runOnUiThread(()->{enrollLink.setText("");toast(R.string.enrollment_complete);refreshLocal();});
                AppLogger.i(this,"Enrollment","Enrollment completed");
            }catch(Exception e){
                AppLogger.e(this,"Enrollment","Enrollment failed",e);
                runOnUiThread(()->toast(UiErrors.message(this,e)));
            }finally{enrolling.set(false);runOnUiThread(()->button.setEnabled(true));}
        });
    }
    private void requestSmsRole(){RoleManager rm=getSystemService(RoleManager.class);if(rm.isRoleAvailable(RoleManager.ROLE_SMS)&&!rm.isRoleHeld(RoleManager.ROLE_SMS))startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_SMS),REQ_SMS_ROLE);else toast(R.string.sms_role_already);}
    private void requestCorePermissions(){ArrayList<String> p=new ArrayList<>();for(String x:new String[]{Manifest.permission.RECEIVE_SMS,Manifest.permission.SEND_SMS,Manifest.permission.READ_SMS,Manifest.permission.READ_PHONE_STATE,Manifest.permission.READ_PHONE_NUMBERS})if(checkSelfPermission(x)!=PackageManager.PERMISSION_GRANTED)p.add(x);if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.POST_NOTIFICATIONS);if(p.isEmpty())toast(R.string.permissions_granted);else requestPermissions(p.toArray(new String[0]),REQ_CORE);}
    private void refreshLocal(){
        AgentConfig cfg=new AgentConfig(this);RoleManager rm=getSystemService(RoleManager.class);boolean role=rm.isRoleAvailable(RoleManager.ROLE_SMS)&&rm.isRoleHeld(RoleManager.ROLE_SMS);
        if(cfg.resetPending())status.setText(R.string.enrollment_reset_pending);else if(cfg.isEnrolled())status.setText(getString(R.string.status_enrolled,cfg.deviceName(),cfg.server(),getString(role?R.string.status_yes:R.string.status_no),getString(cfg.alwaysOn()?R.string.status_enabled:R.string.status_disabled),LocalStore.get(this).pendingEventCount()));else status.setText(cfg.remoteResetNotified()?R.string.remote_reset_done:R.string.status_not_enrolled);
        exec.execute(()->{JSONObject collected=StateCollector.collect(getApplicationContext());runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())renderSimSummary(collected);});});
        if(DeveloperSettings.isEnabled(this)&&detail.getText().length()==0)showRecentLogs();
    }
    private void renderSimSummary(JSONObject state){
        org.json.JSONArray sims=state.optJSONArray("subscriptions");
        if(sims==null||sims.length()==0){simSummary.setText(R.string.sim_status_empty);return;}
        StringBuilder out=new StringBuilder();
        for(int i=0;i<sims.length();i++){
            JSONObject x=sims.optJSONObject(i);if(x==null)continue;
            if(out.length()>0)out.append("\n\n");
            String display=x.optString("displayName","SIM"),carrier=x.optString("carrierName","");
            if(carrier.isBlank())carrier=display;
            int slot=Math.max(0,x.optInt("slotIndex",i))+1;
            String radio=x.optString("networkType","—");if(radio.isBlank())radio="—";
            String signal=x.isNull("signalLevel")?"—":String.valueOf(x.optInt("signalLevel"))+"/4";
            if(!x.isNull("signalDbm"))signal+=" · "+x.optInt("signalDbm")+" dBm";
            out.append(getString(R.string.sim_status_line,display,carrier,slot,serviceLabel(x.optString("serviceState","UNKNOWN")),radio,signal));
        }
        simSummary.setText(out.length()==0?getString(R.string.sim_status_empty):out.toString());
    }
    private String serviceLabel(String value){return switch(value){
        case "IN_SERVICE" -> getString(R.string.service_in);
        case "OUT_OF_SERVICE" -> getString(R.string.service_out);
        case "EMERGENCY_ONLY" -> getString(R.string.service_emergency);
        case "POWER_OFF" -> getString(R.string.service_power_off);
        default -> getString(R.string.service_unknown);
    };}
    private void showOta(JSONObject o){if(!o.optBoolean("available")){toast(R.string.update_none);return;}String msg=getString(R.string.update_version,o.optString("versionName","?"),o.optString("notes",""));new AlertDialog.Builder(this).setTitle(R.string.update_title).setMessage(msg).setNegativeButton(R.string.close,null).setPositiveButton(R.string.open_download,(d,w)->{String url=o.optString("url","");if(url.startsWith("https://"))startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}).show();}
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){super.onRequestPermissionsResult(requestCode,permissions,grantResults);AppLogger.i(this,"Permissions","Permission result request="+requestCode);refreshLocal();}
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(requestCode==REQ_SMS_ROLE){AppLogger.i(this,"Permissions","Default SMS role flow completed result="+resultCode);refreshLocal();return;}if(requestCode==REQ_EXPORT_DIAGNOSTICS&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null&&pendingDiagnosticFile!=null){Uri uri=data.getData();File source=pendingDiagnosticFile;exec.execute(()->{try(FileInputStream in=new FileInputStream(source);OutputStream out=getContentResolver().openOutputStream(uri,"w")){if(out==null)throw new IllegalStateException("No output stream");byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);AppLogger.i(this,"Diagnostics","Diagnostic package exported");runOnUiThread(()->toast(R.string.diagnostic_export_done));}catch(Exception e){AppLogger.e(this,"Diagnostics","Diagnostic package export failed",e);runOnUiThread(()->toast(R.string.diagnostic_export_failed));}finally{source.delete();pendingDiagnosticFile=null;}});}}
    private void toast(int resId){toast(getString(resId));}
    private void toast(String s){Toast.makeText(this,s==null||s.isBlank()?getString(R.string.generic_error):s,Toast.LENGTH_LONG).show();}
    @Override protected void onResume(){super.onResume();refreshLocal();}
    @Override protected void onDestroy(){exec.shutdownNow();super.onDestroy();}
}