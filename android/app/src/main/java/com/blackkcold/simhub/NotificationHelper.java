package com.blackkcold.simhub;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

public final class NotificationHelper {
    public static final String CHANNEL="simhub_relay";
    public static final String SMS_CHANNEL="simhub_messages";
    public static final String ACTION_OPEN_SMS="com.blackkcold.simhub.OPEN_LOCAL_SMS";
    private static final int SMS_NOTIFICATION_ID=2103;
    private NotificationHelper(){}

    public static void ensureChannels(Context c){
        NotificationManager nm=c.getSystemService(NotificationManager.class);
        if(nm==null)return;
        NotificationChannel relay=new NotificationChannel(CHANNEL,c.getString(R.string.relay_channel),NotificationManager.IMPORTANCE_LOW);
        relay.setDescription(c.getString(R.string.relay_channel_description));
        nm.createNotificationChannel(relay);
        NotificationChannel messages=new NotificationChannel(SMS_CHANNEL,c.getString(R.string.sms_notification_channel),NotificationManager.IMPORTANCE_DEFAULT);
        messages.setDescription(c.getString(R.string.sms_notification_description));
        messages.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        nm.createNotificationChannel(messages);
    }

    static boolean defaultSmsHandler(Context c){
        RoleManager roles=c.getSystemService(RoleManager.class);
        return roles!=null&&roles.isRoleAvailable(RoleManager.ROLE_SMS)&&roles.isRoleHeld(RoleManager.ROLE_SMS);
    }

    /** No sender, body, contact or OTP is ever passed into the notification. */
    static Notification incomingSmsNotification(Context c){
        ensureChannels(c);
        Intent view=new Intent(c,HubActivity.class).setAction(ACTION_OPEN_SMS)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent intent=PendingIntent.getActivity(c,3,view,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(c,SMS_CHANNEL).setSmallIcon(R.drawable.ic_simhub)
                .setContentTitle(c.getString(R.string.sms_notification_title))
                .setContentText(c.getString(R.string.sms_notification_body))
                .setCategory(Notification.CATEGORY_MESSAGE).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true).setContentIntent(intent).build();
    }

    static void postIncomingSms(Context c){
        boolean permission=Build.VERSION.SDK_INT<33||c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED;
        if(!SmsNotificationPolicy.shouldNotify(defaultSmsHandler(c),permission))return;
        try{
            NotificationManager nm=c.getSystemService(NotificationManager.class);
            if(nm!=null)nm.notify(SMS_NOTIFICATION_ID,incomingSmsNotification(c));
        }catch(SecurityException ignored){/* SMS processing does not depend on notification permission. */}
    }

    public static void clearIncomingSms(Context c){
        NotificationManager nm=c.getSystemService(NotificationManager.class);
        if(nm!=null)nm.cancel(SMS_NOTIFICATION_ID);
    }
    public static void postRemoteReset(Context c){
        try{
            NotificationManager nm=c.getSystemService(NotificationManager.class);
            if(nm==null)return;
            String alerts="simhub_alerts";
            nm.createNotificationChannel(new NotificationChannel(alerts,c.getString(R.string.app_name),NotificationManager.IMPORTANCE_DEFAULT));
            Intent view=new Intent(c,HubActivity.class);
            PendingIntent intent=PendingIntent.getActivity(c,2,view,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            nm.notify(2102,new Notification.Builder(c,alerts)
                .setSmallIcon(R.drawable.ic_simhub)
                .setContentTitle(c.getString(R.string.remote_reset_title))
                .setContentText(c.getString(R.string.remote_reset_done))
                .setAutoCancel(true).setContentIntent(intent).build());
        }catch(SecurityException ignored){/* Permission denied: the activity still displays reset status. */}
    }
    public static Notification relay(Context c){
        ensureChannels(c);
        Intent view=new Intent(c,HubActivity.class);
        PendingIntent intent=PendingIntent.getActivity(c,1,view,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(c,CHANNEL).setSmallIcon(R.drawable.ic_simhub)
                .setContentTitle(c.getString(R.string.app_name)).setContentText(c.getString(R.string.relay_running))
                .setOngoing(true).setContentIntent(intent).build();
    }
}
