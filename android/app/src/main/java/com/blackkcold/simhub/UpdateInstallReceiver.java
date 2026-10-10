package com.blackkcold.simhub;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

public final class UpdateInstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent intent) {
        int status=intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE);
        if(status==PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent approve=Build.VERSION.SDK_INT>=33
                ? intent.getParcelableExtra(Intent.EXTRA_INTENT,Intent.class)
                : (Intent)intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if(approve!=null){if(RemoteOta.active(c))RemoteOta.installApprovalRequired(c);AppUpdater.notifyInstallAction(c,approve);}
        }else if(status==PackageInstaller.STATUS_SUCCESS) {
            NotificationManager manager=c.getSystemService(NotificationManager.class);
            if(manager!=null)manager.cancel(2717);
            RemoteOta.onPackageReplaced(c);
        }else {
            AppLogger.e(c,"AppUpdater","Install status: "+status,null);
            if(RemoteOta.active(c))RemoteOta.fail(c,"installer_status_"+status);
        }
    }
}
