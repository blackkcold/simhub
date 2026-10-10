package com.blackkcold.simhub;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.role.RoleManager;
import android.companion.AssociationInfo;
import android.companion.CompanionDeviceManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.provider.Telephony;
import android.database.Cursor;
import org.json.JSONObject;
import rikka.shizuku.Shizuku;

import java.util.List;

/**
 * Read-only capability snapshot. Shizuku authorization here grants access to the
 * Shizuku API, NOT Android SMS permissions and NOT Android 17 OTP exemptions.
 * This class never invokes shell commands or changes role/AppOps/package state.
 */
public final class CompatibilityManager {
    private CompatibilityManager(){}

    public static JSONObject inspect(Context context,boolean providerProbe){
        JSONObject state=new JSONObject();
        try{
            state.put("manufacturer",Build.MANUFACTURER);
            state.put("model",Build.MODEL);
            state.put("sdk",Build.VERSION.SDK_INT);
            state.put("targetSdk",context.getApplicationInfo().targetSdkVersion);
            state.put("otpPolicy",CompatibilityPolicy.otpProtectionNotice(Build.VERSION.SDK_INT,context.getApplicationInfo().targetSdkVersion));
            RoleManager role=context.getSystemService(RoleManager.class);
            state.put("defaultSmsRole",role!=null&&role.isRoleHeld(RoleManager.ROLE_SMS));
            state.put("readSms",granted(context,Manifest.permission.READ_SMS));
            state.put("receiveSms",granted(context,Manifest.permission.RECEIVE_SMS));
            state.put("sendSms",granted(context,Manifest.permission.SEND_SMS));
            state.put("packageUid",Process.myUid());
            AgentConfig cfg=new AgentConfig(context);
            state.put("lastSmsBroadcastAt",cfg.lastSmsBroadcastAt());
            state.put("lastReconcileAt",cfg.lastReconcileAt());
            state.put("lastReconcileCount",cfg.lastReconcileCount());
            state.put("lastRelaySyncAt",cfg.lastSyncSuccessAt());
            state.put("lastRelaySyncError",cfg.lastSyncError());
            try{
                AppOpsManager ops=context.getSystemService(AppOpsManager.class);
                int mode=ops.unsafeCheckOpNoThrow("android:read_otp_sms",Process.myUid(),context.getPackageName());
                state.put("otpAppOp",AppOpsManager.modeToName(mode));
            }catch(Exception notSupported){state.put("otpAppOp","not_queryable");}
            state.put("shizukuInstalled",isInstalled(context,"moe.shizuku.privileged.api"));
            boolean binder=false;
            try{binder=Shizuku.pingBinder();}catch(Throwable unavailable){}
            state.put("shizukuRunning",binder);
            if(binder){
                try{
                    boolean allowed=Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED;
                    state.put("shizukuAuthorized",allowed);
                    if(allowed){
                        state.put("shizukuServiceUid",Shizuku.getUid());
                        state.put("shizukuApiVersion",Shizuku.getVersion());
                    }
                }catch(Exception permissionError){state.put("shizukuAuthorized",false);}
            }else state.put("shizukuAuthorized",false);
            state.put("associationSupported",Build.VERSION.SDK_INT>=33);
            state.put("associations",associationCount(context));
            if(providerProbe){
                if(!granted(context,Manifest.permission.READ_SMS))state.put("providerProbe","permission_missing");
                else try(Cursor cursor=context.getContentResolver().query(Telephony.Sms.CONTENT_URI,
                        new String[]{Telephony.Sms._ID},null,null,Telephony.Sms.DATE+" DESC")){
                    if(cursor==null)state.put("providerProbe","null_cursor");
                    else state.put("providerProbe",cursor.moveToFirst()?"accessible_has_rows":"accessible_empty");
                }catch(SecurityException denied){state.put("providerProbe","security_exception");}
                catch(Exception failure){state.put("providerProbe","query_failed");}
            }
        }catch(Exception failure){try{state.put("inspectError","partial_snapshot");}catch(Exception ignored){}}
        return state;
    }

    private static boolean isInstalled(Context context,String packageName){
        try{context.getPackageManager().getPackageInfo(packageName,0);return true;}
        catch(PackageManager.NameNotFoundException absent){return false;}
        catch(Exception denied){return false;}
    }
    private static boolean granted(Context c,String permission){
        return c.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED;
    }
    public static int associationCount(Context c){
        if(Build.VERSION.SDK_INT<33)return 0;
        try{
            CompanionDeviceManager manager=c.getSystemService(CompanionDeviceManager.class);
            if(manager==null)return 0;
            int count=0;
            List<AssociationInfo> associations=manager.getMyAssociations();
            if(associations!=null)for(AssociationInfo item:associations){
                if(item.isSelfManaged() && item.getDisplayName()!=null &&
                        item.getDisplayName().toString().startsWith("SIM Hub · "))count++;
            }
            return count;
        }catch(Exception unsupported){return 0;}
    }
    public static boolean removeSimHubAssociations(Context c){
        if(Build.VERSION.SDK_INT<33)return false;
        CompanionDeviceManager manager=c.getSystemService(CompanionDeviceManager.class);
        if(manager==null)return false;
        boolean removed=false;
        for(AssociationInfo item:manager.getMyAssociations()){
            if(item.isSelfManaged() && item.getDisplayName()!=null &&
                    item.getDisplayName().toString().startsWith("SIM Hub · ")){
                manager.disassociate(item.getId());
                removed=true;
            }
        }
        return removed;
    }
}
