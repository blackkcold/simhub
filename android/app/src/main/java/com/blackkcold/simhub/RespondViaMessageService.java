package com.blackkcold.simhub;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.telephony.SubscriptionManager;
import java.util.UUID;

public final class RespondViaMessageService extends Service {
    @Override public int onStartCommand(Intent i,int flags,int startId){AgentExecutors.io().execute(()->{try{if(i!=null&&i.getData()!=null){String to=i.getData().getSchemeSpecificPart();String text=i.getStringExtra(Intent.EXTRA_TEXT);if(text!=null&&!text.isBlank())SmsSender.send(this,"respond-"+UUID.randomUUID(),SubscriptionManager.getDefaultSmsSubscriptionId(),to,text);}}catch(Exception ignored){}finally{stopSelf(startId);}});return START_NOT_STICKY;}
    @Override public IBinder onBind(Intent i){return null;}
}
