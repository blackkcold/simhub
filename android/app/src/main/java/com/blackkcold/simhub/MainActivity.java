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
import android.provider.Telephony;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private EditText enrollLink;private TextView status,detail;private final java.util.concurrent.ExecutorService exec=Executors.newSingleThreadExecutor();
    @Override protected void onCreate(Bundle b){super.onCreate(b);setContentView(R.layout.activity_main);enrollLink=findViewById(R.id.enrollLink);status=findViewById(R.id.status);detail=findViewById(R.id.detail);wire();handleIntent(getIntent());refreshLocal();}
    @Override protected void onNewIntent(Intent i){super.onNewIntent(i);setIntent(i);handleIntent(i);}
    private void wire(){
        findViewById(R.id.enrollButton).setOnClickListener(v->doEnroll());findViewById(R.id.smsRoleButton).setOnClickListener(v->requestSmsRole());findViewById(R.id.permissionButton).setOnClickListener(v->requestCorePermissions());findViewById(R.id.contactButton).setOnClickListener(v->requestPermissions(new String[]{Manifest.permission.READ_CONTACTS},2202));
        findViewById(R.id.startRelayButton).setOnClickListener(v->{if(!new AgentConfig(this).isEnrolled()){toast("Enroll first");return;}RelayForegroundService.start(this);refreshLocal();});findViewById(R.id.stopRelayButton).setOnClickListener(v->{RelayForegroundService.stop(this);refreshLocal();});
        findViewById(R.id.syncButton).setOnClickListener(v->exec.execute(()->{int n=SmsHistorySync.sync(this,5000);SyncJobService.scheduleNow(this);runOnUiThread(()->toast("Queued "+n+" SMS for sync"));}));
        findViewById(R.id.refreshButton).setOnClickListener(v->exec.execute(()->{try{JSONObject s=StateCollector.collect(this);String pretty=s.toString(2);if(new AgentConfig(this).isEnrolled())new ApiClient(this).putState();runOnUiThread(()->{detail.setText(pretty);refreshLocal();});}catch(Exception e){runOnUiThread(()->toast(e.getMessage()));}}));
        findViewById(R.id.otaButton).setOnClickListener(v->exec.execute(()->{try{JSONObject o=new ApiClient(this).ota();runOnUiThread(()->showOta(o));}catch(Exception e){runOnUiThread(()->toast(e.getMessage()));}}));
    }
    private void handleIntent(Intent i){if(i!=null&&Intent.ACTION_VIEW.equals(i.getAction())&&i.getData()!=null&&"simhub".equals(i.getData().getScheme()))enrollLink.setText(i.getData().toString());}
    private void doEnroll(){String link=enrollLink.getText().toString().trim();if(link.isEmpty()){toast("Paste or open the enrollment link first");return;}exec.execute(()->{try{EnrollmentManager.enroll(this,link);runOnUiThread(()->{enrollLink.setText("");toast("Enrollment complete");refreshLocal();});}catch(Exception e){runOnUiThread(()->toast(e.getMessage()));}});}
    private void requestSmsRole(){RoleManager rm=getSystemService(RoleManager.class);if(rm.isRoleAvailable(RoleManager.ROLE_SMS)&&!rm.isRoleHeld(RoleManager.ROLE_SMS))startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_SMS),2101);else toast("SIM Hub already holds the SMS role");}
    private void requestCorePermissions(){ArrayList<String> p=new ArrayList<>();for(String x:new String[]{Manifest.permission.RECEIVE_SMS,Manifest.permission.SEND_SMS,Manifest.permission.READ_SMS,Manifest.permission.READ_PHONE_STATE,Manifest.permission.READ_PHONE_NUMBERS})if(checkSelfPermission(x)!=PackageManager.PERMISSION_GRANTED)p.add(x);if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.POST_NOTIFICATIONS);if(p.isEmpty())toast("Core permissions already granted");else requestPermissions(p.toArray(new String[0]),2201);}
    private void refreshLocal(){AgentConfig cfg=new AgentConfig(this);RoleManager rm=getSystemService(RoleManager.class);boolean role=rm.isRoleAvailable(RoleManager.ROLE_SMS)&&rm.isRoleHeld(RoleManager.ROLE_SMS);status.setText((cfg.isEnrolled()?"Enrolled: "+cfg.deviceName()+"\n"+cfg.server():"Not enrolled")+"\nDefault SMS role: "+(role?"yes":"no")+"\nAlways-on relay: "+(cfg.alwaysOn()?"enabled":"disabled")+"\nPending encrypted events: "+LocalStore.get(this).pendingEventCount());try{detail.setText(StateCollector.collect(this).toString(2));}catch(Exception ignored){}}
    private void showOta(JSONObject o){if(!o.optBoolean("available")){toast("No OTA metadata published");return;}String msg="Version "+o.optString("versionName","?")+"\n"+o.optString("notes","");new AlertDialog.Builder(this).setTitle("Agent update").setMessage(msg).setNegativeButton("Close",null).setPositiveButton("Open download",(d,w)->{String url=o.optString("url","");if(url.startsWith("https://"))startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}).show();}
    private void toast(String s){Toast.makeText(this,s==null?"Error":s,Toast.LENGTH_LONG).show();}
    @Override protected void onDestroy(){exec.shutdownNow();super.onDestroy();}
}
