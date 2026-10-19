package com.blackkcold.simhub;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.Manifest;
import android.content.pm.PackageManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.SignalStrength;
import org.json.JSONArray;
import org.json.JSONObject;

/** Low-cost, permission-conservative telemetry collected only on existing sync/events. */
public final class DeviceTelemetry {
    private DeviceTelemetry(){}
    public static JSONObject capture(Context context) {
        Context c=context.getApplicationContext();
        JSONObject out=new JSONObject();
        try {
            out.put("sampledAt",System.currentTimeMillis()/1000L);
            Intent battery=c.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if(battery!=null){
                int level=battery.getIntExtra(BatteryManager.EXTRA_LEVEL,-1);
                int scale=battery.getIntExtra(BatteryManager.EXTRA_SCALE,-1);
                if(level>=0&&scale>0&&level<=scale)
                    out.put("batteryPct",Math.round(level*100f/scale));
                int status=battery.getIntExtra(BatteryManager.EXTRA_STATUS,-1);
                if(status!=-1)out.put("charging",status==BatteryManager.BATTERY_STATUS_CHARGING
                        ||status==BatteryManager.BATTERY_STATUS_FULL);
            }
            ConnectivityManager cm=c.getSystemService(ConnectivityManager.class);
            if(cm==null)return out;
            NetworkCapabilities caps=cm.getNetworkCapabilities(cm.getActiveNetwork());
            if(caps==null){appendSignals(c,out);return out;}
            boolean wifi=caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            out.put("wifiConnected",wifi);
            out.put("cellularConnected",caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
            out.put("internetValidated",caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
            if(wifi){
                WifiInfo info=caps.getTransportInfo() instanceof WifiInfo
                    ?(WifiInfo)caps.getTransportInfo():null;
                if(info==null){
                    WifiManager manager=(WifiManager)c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    if(manager!=null)try{info=manager.getConnectionInfo();}catch(SecurityException ignored){}
                }
                if(info!=null){
                    int rssi=info.getRssi();
                    if(rssi>=-126&&rssi<=0){
                        out.put("wifiRssiDbm",rssi);
                        out.put("wifiSignalLevel",WifiManager.calculateSignalLevel(rssi,5));
                    }
                }
            }
            appendSignals(c,out);
        }catch(SecurityException denied){
            // Unknown signal is not signal level zero. SMS processing remains unaffected.
        }catch(Exception ignored){
            // Platform/OEM telemetry failures are intentionally non-fatal.
        }
        return out;
    }
    private static void appendSignals(Context c,JSONObject out){
        if(c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)!=PackageManager.PERMISSION_GRANTED)return;
        try{
            SubscriptionManager sm=c.getSystemService(SubscriptionManager.class);
            TelephonyManager base=c.getSystemService(TelephonyManager.class);
            if(sm==null||base==null)return;
            java.util.List<SubscriptionInfo> subs=sm.getActiveSubscriptionInfoList();
            if(subs==null)return;
            JSONArray signals=new JSONArray();
            for(SubscriptionInfo info:subs){
                if(signals.length()>=8)break;
                ChannelIdentity.Channel id=ChannelIdentity.describe(c,info);
                SignalStrength signal=base.createForSubscriptionId(info.getSubscriptionId()).getSignalStrength();
                JSONObject entry=new JSONObject().put("channelId",id.channelId).put("channelRevision",id.revision);
                if(signal!=null&&signal.getLevel()>=0&&signal.getLevel()<=4)
                    entry.put("signalLevel",signal.getLevel());
                signals.put(entry);
            }
            out.put("signals",signals);
        }catch(Exception ignored){}
    }
}
