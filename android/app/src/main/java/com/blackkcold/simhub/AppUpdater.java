package com.blackkcold.simhub;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Opt-in APK update path. Never downgrades or changes Android signing identity. */
public final class AppUpdater {
    static final String PREF="simhub_app_update_v1";
    static final String CERT="633f600f7ec0ac5cdcdcffb536ade4beec8312cc083487a225c8c308771fd8d3";
    static final long CHECK_INTERVAL=24L*60*60*1000;
    private static final long MAX_APK=80L*1024*1024;
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    public interface Callback { void onResult(JSONObject info, String error); }
    public interface Progress { void onProgress(String message); }
    private AppUpdater(){}

    public static SharedPreferences prefs(Context c){
        return c.getSharedPreferences(PREF,Context.MODE_PRIVATE);
    }
    public static boolean shouldCheck(Context c){
        SharedPreferences p=prefs(c);
        return p.getBoolean("autoCheck",true) &&
            System.currentTimeMillis()-p.getLong("lastCheck",0)>=CHECK_INTERVAL;
    }
    public static void ignore(Context c,int versionCode){
        prefs(c).edit().putInt("ignored",versionCode).apply();
    }
    public static int ignored(Context c){return prefs(c).getInt("ignored",-1);}

    public static void check(Context c,boolean manual,Callback callback){
        Context app=c.getApplicationContext();
        IO.execute(()->{
            try{
                JSONObject info=ReleaseUpdateSource.latest();
                int version=info.optInt("versionCode",0);
                boolean available=info.optBoolean("available") &&
                    version>BuildConfig.VERSION_CODE && version>0;
                if(!manual && version==ignored(app))available=false;
                info.put("available",available);
                prefs(app).edit().putLong("lastCheck",System.currentTimeMillis()).apply();
                MAIN.post(()->callback.onResult(info,null));
            }catch(Exception error){
                MAIN.post(()->callback.onResult(null,error.getMessage()));
            }
        });
    }
    private static String hex(byte[] bytes){
        StringBuilder out=new StringBuilder();
        for(byte value:bytes)out.append(String.format(Locale.ROOT,"%02x",value&255));
        return out.toString();
    }
    private static String digest(byte[] bytes)throws Exception{
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static void verifyApk(Context c,File apk,JSONObject metadata)throws Exception{
        PackageManager pm=c.getPackageManager();
        PackageInfo candidate=pm.getPackageArchiveInfo(apk.getAbsolutePath(),PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo installed=pm.getPackageInfo(c.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);
        if(candidate==null||candidate.signingInfo==null||installed.signingInfo==null)
            throw new SecurityException("APK signature unavailable");
        if(!c.getPackageName().equals(candidate.packageName) ||
            candidate.getLongVersionCode()!=metadata.getLong("versionCode") ||
            candidate.getLongVersionCode()<=installed.getLongVersionCode())
            throw new SecurityException("APK package or version mismatch");
        android.content.pm.Signature[] next=candidate.signingInfo.getApkContentsSigners();
        android.content.pm.Signature[] current=installed.signingInfo.getApkContentsSigners();
        if(next.length!=1||current.length!=1)throw new SecurityException("Unexpected APK certificate count");
        if(!CERT.equals(digest(next[0].toByteArray())) ||
            !CERT.equals(digest(current[0].toByteArray())))
            throw new SecurityException("APK signing certificate does not match SIM Hub release");
    }
    /** JobScheduler invokes this on its existing worker thread, not the UI thread. */
    public static void checkInBackground(Context context){
        Context c=context.getApplicationContext();
        if(!shouldCheck(c))return;
        try{
            JSONObject info=ReleaseUpdateSource.latest();
            prefs(c).edit().putLong("lastCheck",System.currentTimeMillis()).apply();
            int code=info.optInt("versionCode",0);
            if(!info.optBoolean("available")||code<=BuildConfig.VERSION_CODE||code==ignored(c))return;
            if(prefs(c).getBoolean("autoDownload",false))
                downloadInternal(c,info,msg->AppLogger.i(c,"AppUpdater",msg),false);
        }catch(Exception error){
            AppLogger.e(c,"AppUpdater","Background update check unavailable",error);
        }
    }
    public static void downloadAndInstall(Context context,JSONObject metadata,Progress callback){
        Context app=context.getApplicationContext();
        IO.execute(()->downloadInternal(context,metadata,callback,false));
    }
    public static void downloadRemote(Context c,JSONObject metadata){
        IO.execute(()->downloadInternal(c,metadata,message->AppLogger.i(c,"RemoteOTA",message),true));
    }
    private static void downloadInternal(Context context,JSONObject metadata,Progress callback,boolean remote){
        Context app=context.getApplicationContext();
            File file=null;
            try{
                int code=metadata.getInt("versionCode");
                if(code<=BuildConfig.VERSION_CODE)throw new SecurityException("Version is not newer");
                String sha=metadata.getString("sha256");
                String url=metadata.getString("url");
                String version=metadata.getString("versionName");
                String expectedPrefix="https://github.com/blackkcold/simhub/releases/download/v"+version+"/";
                if(!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+") ||
                   !url.startsWith(expectedPrefix) || !url.endsWith(".apk") ||
                   !sha.matches("[a-f0-9]{64}"))throw new SecurityException("Untrusted update metadata");
                if(remote)RemoteOta.stage(app,"downloading");
                if(!pmCanInstall(app)){
                    if(remote){RemoteOta.fail(app,"install_sources_permission_required");return;}
                    MAIN.post(()->callback.onProgress("需要授权此应用安装更新，请在系统设置中允许后重试"));
                    MAIN.post(()->{
                        Intent grant=new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:"+app.getPackageName()));
                        if(context instanceof Activity) ((Activity)context).startActivity(grant);
                    });
                    return;
                }
                MAIN.post(()->callback.onProgress("正在下载 APK…"));
                file=File.createTempFile("simhub-update-",".apk",app.getCacheDir());
                HttpURLConnection conn=(HttpURLConnection)new URL(url).openConnection();
                conn.setConnectTimeout(12000);conn.setReadTimeout(25000);
                conn.setInstanceFollowRedirects(true);
                MessageDigest md=MessageDigest.getInstance("SHA-256");
                long size=0;
                try(InputStream in=conn.getInputStream();FileOutputStream out=new FileOutputStream(file)){
                    byte[] bytes=new byte[65536];
                    int n;
                    while((n=in.read(bytes))!=-1){
                        if(remote&&RemoteOta.cancelRequested(app))throw new java.io.IOException("remote_ota_cancelled");
                        size+=n;
                        if(size>MAX_APK)throw new SecurityException("APK too large");
                        md.update(bytes,0,n);out.write(bytes,0,n);
                    }
                }finally{conn.disconnect();}
                if(size<10000 || !sha.equals(hex(md.digest())))
                    throw new SecurityException("APK SHA-256 mismatch");
                MAIN.post(()->callback.onProgress("下载完成，正在验证安装包…"));
                verifyApk(app,file,metadata);
                if(remote&&RemoteOta.cancelRequested(app))throw new java.io.IOException("remote_ota_cancelled");
                if(remote)RemoteOta.stage(app,"verified");
                MAIN.post(()->callback.onProgress("安装已提交至 Android 系统"));
                if(remote&&RemoteOta.cancelRequested(app))throw new java.io.IOException("remote_ota_cancelled");
                if(remote)RemoteOta.stage(app,"installing");
                install(app,file);
            }catch(Exception error){
                if(remote)RemoteOta.fail(app,RemoteOta.cancelRequested(app)?"update_cancelled":
                    (error instanceof SecurityException?"update_security_check_failed":"update_download_or_install_failed"));
                MAIN.post(()->callback.onProgress("更新失败："+error.getMessage()));
            }finally{
                // Session has already copied the APK into PackageInstaller staging.
                if(file!=null)file.delete();
            }
    }
    static boolean pmCanInstall(Context c){
        return Build.VERSION.SDK_INT<26 || c.getPackageManager().canRequestPackageInstalls();
    }
    private static void install(Context c,File file)throws Exception{
        PackageInstaller installer=c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(c.getPackageName());
        p.setSize(file.length());
        if(Build.VERSION.SDK_INT>=31)
            p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id=installer.createSession(p);
        boolean committed=false;
        try(PackageInstaller.Session session=installer.openSession(id)){
            try(InputStream input=new java.io.FileInputStream(file);
                java.io.OutputStream output=session.openWrite("simhub-update.apk",0,file.length())){
                byte[] buf=new byte[65536];int n;
                while((n=input.read(buf))!=-1)output.write(buf,0,n);
                session.fsync(output);
            }
            Intent callback=new Intent(c,UpdateInstallReceiver.class);
            callback.setAction("com.blackkcold.simhub.UPDATE_STATUS");
            callback.putExtra("sessionId",id);
            PendingIntent pending=PendingIntent.getBroadcast(c,id,callback,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE);
            session.commit(pending.getIntentSender());
            committed=true;
        }finally{
            if(!committed)installer.abandonSession(id);
        }
    }
    static void notifyInstallAction(Context c,Intent approval){
        String channel="simhub_updates";
        NotificationManager manager=c.getSystemService(NotificationManager.class);
        if(manager==null)return;
        manager.createNotificationChannel(new NotificationChannel(channel,
            "SIM Hub 更新",NotificationManager.IMPORTANCE_HIGH));
        approval.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pending=PendingIntent.getActivity(c,2717,approval,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        manager.notify(2717,new Notification.Builder(c,channel)
            .setSmallIcon(R.drawable.ic_simhub)
            .setContentTitle("确认安装 SIM Hub 更新")
            .setContentText("Android 需要你确认安装更新")
            .setAutoCancel(true).setContentIntent(pending).build());
    }
}
