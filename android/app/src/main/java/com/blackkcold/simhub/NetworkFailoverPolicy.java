package com.blackkcold.simhub;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.telephony.SubscriptionManager;

/**
 * Unprivileged data failover: Android routes traffic from Wi-Fi to the
 * preconfigured default mobile data SIM automatically. This class validates
 * the desired SIM; it never attempts privileged data-SIM switching.
 */
public final class NetworkFailoverPolicy {
    private static volatile ConnectivityManager.NetworkCallback callback;
    private NetworkFailoverPolicy(){}
    public static boolean enabled(Context c){return new AgentConfig(c).dataFallbackEnabled();}
    public static String preferredChannel(Context c){return new AgentConfig(c).dataFallbackChannel();}
    public static String status(Context c){
        AgentConfig cfg=new AgentConfig(c);
        if(!cfg.dataFallbackEnabled())return "disabled";
        ChannelIdentity.Channel selected=ChannelIdentity.resolve(c,cfg.dataFallbackChannel(),cfg.dataFallbackRevision());
        if(selected==null)return "selected_sim_unavailable";
        int active=SubscriptionManager.getDefaultDataSubscriptionId();
        if(active!=selected.subscriptionId)return "configure_default_data_sim_in_system_settings";
        ConnectivityManager cm=c.getSystemService(ConnectivityManager.class);
        Network network=cm==null?null:cm.getActiveNetwork();
        NetworkCapabilities caps=cm==null||network==null?null:cm.getNetworkCapabilities(network);
        if(caps==null)return "no_active_network";
        if(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))return "wifi_primary_data_sim_ready";
        if(caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))return "cellular_fallback_active";
        return "network_route_other";
    }
    public static void watch(Context context){
        final Context c=context.getApplicationContext();
        try {
            ConnectivityManager cm=c.getSystemService(ConnectivityManager.class);
            if(cm==null||callback!=null)return;
            callback=new ConnectivityManager.NetworkCallback(){
                @Override public void onAvailable(Network network){if(enabled(c))SyncJobService.scheduleNow(c);}
                @Override public void onLost(Network network){if(enabled(c))SyncJobService.scheduleNow(c);}
                @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities caps){if(enabled(c))SyncJobService.scheduleNow(c);}
            };
            cm.registerDefaultNetworkCallback(callback);
        }catch(RuntimeException e){AppLogger.w(c,"NetworkFallback","Network callback unavailable");}
    }
}
