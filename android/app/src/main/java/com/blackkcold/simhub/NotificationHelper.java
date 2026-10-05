package com.blackkcold.simhub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public final class NotificationHelper {
    public static final String CHANNEL="simhub_relay";
    public static void ensureChannels(Context c){if(Build.VERSION.SDK_INT>=26){NotificationManager nm=c.getSystemService(NotificationManager.class);NotificationChannel ch=new NotificationChannel(CHANNEL,c.getString(R.string.relay_channel),NotificationManager.IMPORTANCE_LOW);ch.setDescription("Persistent status for your personal SIM relay");nm.createNotificationChannel(ch);}}
    public static Notification relay(Context c){ensureChannels(c);Intent i=new Intent(c,MainActivity.class);PendingIntent pi=PendingIntent.getActivity(c,1,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);return new Notification.Builder(c,CHANNEL).setSmallIcon(R.drawable.ic_simhub).setContentTitle(c.getString(R.string.app_name)).setContentText(c.getString(R.string.relay_running)).setOngoing(true).setContentIntent(pi).build();}
}
