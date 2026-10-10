package com.blackkcold.simhub;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.app.role.RoleManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.telephony.CellInfo;
import android.telephony.CellSignalStrength;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.List;

public final class StateCollector {
    private StateCollector(){}

    public static JSONObject collect(Context c){
        JSONObject o=new JSONObject();
        try{
            AgentConfig cfg=new AgentConfig(c);
            o.put("androidVersion",Build.VERSION.RELEASE)
                    .put("sdk",Build.VERSION.SDK_INT)
                    .put("model",Build.MANUFACTURER+" "+Build.MODEL)
                    .put("appVersion",BuildConfig.VERSION_NAME)
                    .put("energyMode",EnergyPolicy.mode(c))
                    .put("effectiveEnergyMode",EnergyPolicy.effectiveMode(c))
                    .put("foregroundRelay",RelayForegroundService.isRunning())
                    .put("foregroundRelayRequested",cfg.alwaysOn())
                    .put("energyStats",EnergyPolicy.metrics(c))
                    .put("lastCommandFetchAt",cfg.lastCommandFetchAt())
                    .put("lastCommandFetchCount",cfg.lastCommandFetchCount())
                    .put("lastCommandFetchError",cfg.lastCommandFetchError())
                    .put("smsChangeGeneration",cfg.smsChangeGeneration())
                    .put("smsScannedGeneration",cfg.smsScannedGeneration())
                    .put("network",network(c))
                    .put("pendingEvents",LocalStore.get(c).pendingEventCount())
                    .put("pendingCommandAcks",LocalStore.get(c).pendingCommandAckCount())
                    .put("pendingEventTasks",LocalStore.get(c).pendingEventTaskPreview(10))
                    .put("uploadedEventReceipts",LocalStore.get(c).uploadedEventCount())
                    .put("lastEventUploadAt",cfg.lastUploadAt())
                    .put("lastEventUploadCount",cfg.lastUploadCount())
                    .put("uploadedEventTotal",cfg.uploadedTotal())
                    .put("lastUploadAttemptAt",cfg.lastUploadAttemptAt())
                    .put("lastUploadError",cfg.lastUploadError())
                    .put("lastEventQueueError",cfg.lastEventQueueError())
                    .put("lastSmsProviderChangeAt",cfg.lastSmsProviderChangeAt())
                    .put("lastSmsBroadcastAt",cfg.lastSmsBroadcastAt())
                    .put("lastReconcileAt",cfg.lastReconcileAt())
                    .put("lastReconcileCount",cfg.lastReconcileCount())
                    .put("queueFailures",cfg.queueFailures())
                    .put("lastQueueFailureAt",cfg.lastQueueFailureAt())
                    .put("lastSmsReceivedAt",cfg.lastSmsReceivedAt())
                    .put("lastSyncSuccessAt",cfg.lastSyncSuccessAt())
                    .put("lastSyncError",cfg.lastSyncError())
                    .put("smsProviderError",cfg.smsProviderError())
                    .put("nextSyncAllowedAt",cfg.nextSyncAllowedAt()/1000)
                    .put("syncBackoffFailures",cfg.syncBackoffFailures());

            JSONObject remoteOta=RemoteOta.state(c);
            java.util.Iterator<String> otaFields=remoteOta.keys();
            while(otaFields.hasNext()){
                String key=otaFields.next();
                o.put(key,remoteOta.opt(key));
            }
            Intent bat=c.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if(bat!=null){
                int level=bat.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),scale=bat.getIntExtra(BatteryManager.EXTRA_SCALE,100),status=bat.getIntExtra(BatteryManager.EXTRA_STATUS,-1);
                o.put("batteryPct",level>=0?Math.round(level*100f/Math.max(1,scale)):JSONObject.NULL)
                        .put("charging",status==BatteryManager.BATTERY_STATUS_CHARGING||status==BatteryManager.BATTERY_STATUS_FULL);
            }


            o.put("dataFallbackEnabled",cfg.dataFallbackEnabled())
                    .put("dataFallbackChannelId",cfg.dataFallbackChannel())
                    .put("dataFallbackStatus",NetworkFailoverPolicy.status(c));
            try {
                ConnectivityManager cm=c.getSystemService(ConnectivityManager.class);
                NetworkCapabilities caps=cm.getNetworkCapabilities(cm.getActiveNetwork());
                o.put("internetValidated",caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                        .put("wifiConnected",caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                        .put("cellularConnected",caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
            }catch(Exception ignored){}

            RoleManager role=c.getSystemService(RoleManager.class);
            boolean smsRole=role!=null&&role.isRoleAvailable(RoleManager.ROLE_SMS)&&role.isRoleHeld(RoleManager.ROLE_SMS);
            boolean recvPermission=c.checkSelfPermission(Manifest.permission.RECEIVE_SMS)==PackageManager.PERMISSION_GRANTED;
            boolean readPermission=c.checkSelfPermission(Manifest.permission.READ_SMS)==PackageManager.PERMISSION_GRANTED;
            boolean sendPermission=c.checkSelfPermission(Manifest.permission.SEND_SMS)==PackageManager.PERMISSION_GRANTED;
            o.put("smsRoleHeld",smsRole).put("smsMode",smsRole?"default":"companion")
                    .put("smsReceivePermission",recvPermission).put("smsReadPermission",readPermission)
                    .put("smsSendPermission",sendPermission)
                    .put("smsSendAllowed",SmsSendPolicy.canSend(c));
            JSONArray subscriptions=new JSONArray(),channels=new JSONArray(),phoneNumbers=new JSONArray();
            if(c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)==PackageManager.PERMISSION_GRANTED){
                SubscriptionManager sm=c.getSystemService(SubscriptionManager.class);
                List<SubscriptionInfo> list=sm.getActiveSubscriptionInfoList();
                if(list!=null)for(SubscriptionInfo si:list){
                    int sub=si.getSubscriptionId();
                    ChannelIdentity.Channel ch=ChannelIdentity.describe(c,si);
                    JSONObject x=new JSONObject()
                            .put("subscriptionId",sub)
                            .put("channelId",ch.channelId)
                            .put("channelRevision",ch.revision)
                            .put("slotIndex",si.getSimSlotIndex())
                            .put("carrierName",String.valueOf(si.getCarrierName()))
                            .put("displayName",String.valueOf(si.getDisplayName()))
                            .put("isEmbedded",si.isEmbedded())
                            .put("opportunistic",si.isOpportunistic());
                    TelephonyManager tm=c.getSystemService(TelephonyManager.class).createForSubscriptionId(sub);
                    try{
                        SignalStrength sig=tm.getSignalStrength();
                        x.put("signalLevel",sig==null?JSONObject.NULL:sig.getLevel());
                        if(sig!=null)putRadioMetrics(x,sig);
                    }catch(Exception ignored){}
                    try{
                        ServiceState ss=tm.getServiceState();
                        x.put("serviceState",service(ss)).put("roaming",tm.isNetworkRoaming()).put("networkType",networkType(tm.getDataNetworkType()));
                    }catch(Exception ignored){}
                    // Keep telephone numbers out of Relay-visible state. Encrypt below with the Node Key.
                    String number="";
                    if(c.checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS)==PackageManager.PERMISSION_GRANTED){
                        try{number=Build.VERSION.SDK_INT>=33?sm.getPhoneNumber(sub):si.getNumber();}catch(Exception ignored){}
                    }
                    phoneNumbers.put(new JSONObject().put("channelId",ch.channelId).put("subscriptionId",sub)
                            .put("number",number==null?"":number.trim()));
                    subscriptions.put(x);
                    channels.put(new JSONObject(x.toString()).put("id",ch.channelId).put("localId",String.valueOf(sub)).put("kind","android-sim").put("revision",ch.revision));
                }
            }
            o.put("smsReceiveOperational",readPermission&&recvPermission&&subscriptions.length()>0);
            o.put("smsSendOperational",SmsSendPolicy.canSend(c)&&subscriptions.length()>0);
            o.put("smsOperational",readPermission&&recvPermission&&subscriptions.length()>0);
            o.put("subscriptions",subscriptions)
                    .put("channels",channels)
                    .put("nodeType","android")
                    .put("capabilities",(SmsSendPolicy.canSend(c)?new JSONArray().put("sms.receive").put("sms.send"):new JSONArray().put("sms.receive")).put("sms.history").put("signal.basic").put("signal.radio").put("dual-sim"));
            if(cfg.isEnrolled()){
                CryptoBox crypt=new CryptoBox(c);
                o.put("cryptoKeyId",crypt.keyId()).put("cryptoKeyMode",cfg.hasIndependentNodeKey()?"node":"legacy-derived");
                long stamp=System.currentTimeMillis()/1000;
                String eventId="inventory-"+java.util.UUID.randomUUID();
                JSONObject secret=new JSONObject().put("numbers",phoneNumbers);
                o.put("encryptedSimNumbers",new JSONObject().put("eventId",eventId).put("occurredAt",stamp)
                        .put("ciphertext",crypt.encryptEvent(secret,eventId,"device.sim_inventory",stamp,"-1",false)));
            }
        }catch(SecurityException error){
            AppLogger.e(c,"StateCollector","Telephony permission denied",error);
            try{o.put("stateCollectionError","TELEPHONY_PERMISSION_DENIED");}catch(Exception ignored){}
        }catch(Exception error){
            AppLogger.e(c,"StateCollector","Partial device state collected",error);
            try{o.put("stateCollectionError",error.getClass().getSimpleName());}catch(Exception ignored){}
        }
        return o;
    }

    private static void putRadioMetrics(JSONObject out,SignalStrength sig){
        try{
            for(CellSignalStrength cs:sig.getCellSignalStrengths()){
                int dbm=cs.getDbm();if(dbm!=CellInfo.UNAVAILABLE)out.put("signalDbm",dbm);
                if(cs instanceof CellSignalStrengthNr nr){
                    putAvailable(out,"signalRsrp",nr.getSsRsrp());
                    putAvailable(out,"signalRsrq",nr.getSsRsrq());
                    putAvailable(out,"signalSinr",nr.getSsSinr());
                    return;
                }
                if(cs instanceof CellSignalStrengthLte lte){
                    putAvailable(out,"signalRsrp",lte.getRsrp());
                    putAvailable(out,"signalRsrq",lte.getRsrq());
                    putAvailable(out,"signalSinr",lte.getRssnr());
                }
            }
        }catch(Exception ignored){}
    }

    private static void putAvailable(JSONObject out,String key,int value){if(value!=CellInfo.UNAVAILABLE)try{out.put(key,value);}catch(Exception ignored){}}
    private static String network(Context c){try{ConnectivityManager cm=c.getSystemService(ConnectivityManager.class);NetworkCapabilities n=cm.getNetworkCapabilities(cm.getActiveNetwork());if(n==null)return "OFFLINE";if(n.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))return "WIFI";if(n.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))return "CELLULAR";if(n.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))return "ETHERNET";if(n.hasTransport(NetworkCapabilities.TRANSPORT_VPN))return "VPN";return "OTHER";}catch(Exception e){return "UNKNOWN";}}
    private static String service(ServiceState s){if(s==null)return "UNKNOWN";return switch(s.getState()){case ServiceState.STATE_IN_SERVICE->"IN_SERVICE";case ServiceState.STATE_OUT_OF_SERVICE->"OUT_OF_SERVICE";case ServiceState.STATE_EMERGENCY_ONLY->"EMERGENCY_ONLY";case ServiceState.STATE_POWER_OFF->"POWER_OFF";default->"UNKNOWN";};}
    private static String networkType(int t){return switch(t){case TelephonyManager.NETWORK_TYPE_NR->"5G";case TelephonyManager.NETWORK_TYPE_LTE->"LTE";case TelephonyManager.NETWORK_TYPE_HSPAP,TelephonyManager.NETWORK_TYPE_HSPA,TelephonyManager.NETWORK_TYPE_UMTS->"3G";case TelephonyManager.NETWORK_TYPE_EDGE,TelephonyManager.NETWORK_TYPE_GPRS,TelephonyManager.NETWORK_TYPE_GSM->"2G";default->String.valueOf(t);};}
}
