package com.blackkcold.simhub;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
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
        JSONObject o=new JSONObject();try{
            o.put("androidVersion",Build.VERSION.RELEASE).put("sdk",Build.VERSION.SDK_INT).put("model",Build.MANUFACTURER+" "+Build.MODEL).put("appVersion",BuildConfig.VERSION_NAME).put("network",network(c)).put("pendingEvents",LocalStore.get(c).pendingEventCount());
            Intent bat=c.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));if(bat!=null){int level=bat.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),scale=bat.getIntExtra(BatteryManager.EXTRA_SCALE,100),status=bat.getIntExtra(BatteryManager.EXTRA_STATUS,-1);o.put("batteryPct",level>=0?Math.round(level*100f/Math.max(1,scale)):JSONObject.NULL).put("charging",status==BatteryManager.BATTERY_STATUS_CHARGING||status==BatteryManager.BATTERY_STATUS_FULL);}
            JSONArray arr=new JSONArray();
            if(c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)==PackageManager.PERMISSION_GRANTED){SubscriptionManager sm=c.getSystemService(SubscriptionManager.class);List<SubscriptionInfo> list=sm.getActiveSubscriptionInfoList();if(list!=null)for(SubscriptionInfo s:list){JSONObject x=new JSONObject();int sub=s.getSubscriptionId();x.put("subscriptionId",sub).put("slotIndex",s.getSimSlotIndex()).put("carrierName",String.valueOf(s.getCarrierName())).put("displayName",String.valueOf(s.getDisplayName())).put("isEmbedded",s.isEmbedded()).put("opportunistic",s.isOpportunistic());TelephonyManager tm=c.getSystemService(TelephonyManager.class).createForSubscriptionId(sub);try{SignalStrength sig=tm.getSignalStrength();x.put("signalLevel",sig==null?JSONObject.NULL:sig.getLevel());}catch(Exception ignored){}try{ServiceState ss=tm.getServiceState();x.put("serviceState",service(ss));x.put("roaming",tm.isNetworkRoaming());x.put("networkType",networkType(tm.getDataNetworkType()));}catch(Exception ignored){}arr.put(x);}}
            o.put("subscriptions",arr);
        }catch(Exception ignored){}return o;
    }
    private static String network(Context c){try{ConnectivityManager cm=c.getSystemService(ConnectivityManager.class);NetworkCapabilities n=cm.getNetworkCapabilities(cm.getActiveNetwork());if(n==null)return "OFFLINE";if(n.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))return "WIFI";if(n.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))return "CELLULAR";if(n.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))return "ETHERNET";if(n.hasTransport(NetworkCapabilities.TRANSPORT_VPN))return "VPN";return "OTHER";}catch(Exception e){return "UNKNOWN";}}
    private static String service(ServiceState s){if(s==null)return "UNKNOWN";return switch(s.getState()){case ServiceState.STATE_IN_SERVICE->"IN_SERVICE";case ServiceState.STATE_OUT_OF_SERVICE->"OUT_OF_SERVICE";case ServiceState.STATE_EMERGENCY_ONLY->"EMERGENCY_ONLY";case ServiceState.STATE_POWER_OFF->"POWER_OFF";default->"UNKNOWN";};}
    private static String networkType(int t){return switch(t){case TelephonyManager.NETWORK_TYPE_NR->"5G";case TelephonyManager.NETWORK_TYPE_LTE->"LTE";case TelephonyManager.NETWORK_TYPE_HSPAP,TelephonyManager.NETWORK_TYPE_HSPA,TelephonyManager.NETWORK_TYPE_UMTS->"3G";case TelephonyManager.NETWORK_TYPE_EDGE,TelephonyManager.NETWORK_TYPE_GPRS,TelephonyManager.NETWORK_TYPE_GSM->"2G";default->String.valueOf(t);};}
}
