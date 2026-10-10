package com.blackkcold.simhub;

import android.Manifest;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.pm.PackageManager;

/** SMS transmission is a capability, not a consequence of SEND_SMS permission alone. */
public final class SmsSendPolicy {
    static boolean allowed(boolean smsRoleHeld, boolean permissionGranted, boolean developerOverride) {
        return permissionGranted && (smsRoleHeld || developerOverride);
    }

    public static boolean canSend(Context context) {
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) return false;
        RoleManager roles = context.getSystemService(RoleManager.class);
        boolean defaultSms = roles != null && roles.isRoleHeld(RoleManager.ROLE_SMS);
        return allowed(defaultSms, true, DeveloperSettings.isForceSmsEnabled(context));
    }

    public static void requireCanSend(Context context) {
        if (!canSend(context)) throw new SecurityException("sms_send_disabled_by_mode");
    }

    private SmsSendPolicy() {}
}
