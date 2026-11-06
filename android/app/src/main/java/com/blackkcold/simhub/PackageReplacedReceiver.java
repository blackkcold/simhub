package com.blackkcold.simhub;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Android's own-package replacement signal, not a background Activity launch. */
public final class PackageReplacedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent receivedIntent) {
        if (context == null || receivedIntent == null) return;
        // An explicit broadcast need not match the manifest intent filter.
        // Only Android's own-package action may enter the recovery path.
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(receivedIntent.getAction())) {
            recoverAfterOwnPackageReplacement(context);
        }
    }

    private static void recoverAfterOwnPackageReplacement(Context context) {
        RemoteOta.reconcileInstalledVersion(context);
        DeviceMetadataSync.requestFull(context);
        SyncJobService.schedule(context);
        SyncJobService.scheduleNow(context);
        AgentConfig cfg = new AgentConfig(context);
        if (cfg.isEnrolled() && cfg.alwaysOn()) {
            try {
                RelayForegroundService.resume(context);
            } catch (Exception error) {
                AppLogger.e(context, "RemoteOTA", "Foreground relay recovery deferred to scheduler", error);
            }
        }
    }
}
