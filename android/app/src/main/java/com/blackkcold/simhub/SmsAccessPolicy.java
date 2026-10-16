package com.blackkcold.simhub;

import android.Manifest;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.pm.PackageManager;

/** Store channel defaults to an actual SMS handler; GitHub keeps companion mode. */
public final class SmsAccessPolicy {
    public static boolean rolePermitsAccess(Context c){
        if(!BuildConfig.STORE_CHANNEL)return true;
        RoleManager roles=c.getSystemService(RoleManager.class);return roles!=null&&roles.isRoleHeld(RoleManager.ROLE_SMS);
    }
    public static boolean canRead(Context c){return rolePermitsAccess(c)&&c.checkSelfPermission(Manifest.permission.READ_SMS)==PackageManager.PERMISSION_GRANTED;}
    private SmsAccessPolicy(){}
}
