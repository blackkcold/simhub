package com.blackkcold.simhub;
import android.app.Notification;import android.app.NotificationChannel;import android.app.NotificationManager;import android.app.PendingIntent;import android.content.Context;import android.content.Intent;import android.os.Build;
public final class NotificationHelper {
    public static final String CHANNEL="simhub_relay";
    public static void ensureChannels(Context c){if(Build.VERSION.SDK_INT>=26){NotificationManager nm=c.getSystemService(NotificationManager.class);NotificationChannel ch=new NotificationChannel(CHANNEL,c.getString(R.string.relay_channel),NotificationManager.IMPORTANCE_LOW);ch.setDescription(c.getString(R.string.relay_channel_description));nm.createNotificationChannel(ch);}}
    public static void postRemoteReset(Context c){
        try{
            NotificationManager nm=c.getSystemService(NotificationManager.class);
            if(nm==null)return;
            String alerts="simhub_alerts";
            nm.createNotificationChannel(new NotificationChannel(alerts,c.getString(R.string.app_name),NotificationManager.IMPORTANCE_DEFAULT));
            Intent view=new Intent(c,MainActivity.class);
            PendingIntent intent=PendingIntent.getActivity(c,2,view,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            nm.notify(2102,new Notification.Builder(c,alerts)
                .setSmallIcon(R.drawable.ic_simhub)
                .setContentTitle(c.getString(R.string.remote_reset_title))
                .setContentText(c.getString(R.string.remote_reset_done))
                .setAutoCancel(true).setContentIntent(intent).build());
        }catch(SecurityException ignored){/* Permission denied: the activity still displays reset status. */}
    }
    public static Notification relay(Context c){ensureChannels(c);Intent i=new Intent(c,MainActivity.class);PendingIntent pi=PendingIntent.getActivity(c,1,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);return new Notification.Builder(c,CHANNEL).setSmallIcon(R.drawable.ic_simhub).setContentTitle(c.getString(R.string.app_name)).setContentText(c.getString(R.string.relay_running)).setOngoing(true).setContentIntent(pi).build();}
}