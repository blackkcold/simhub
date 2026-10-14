package com.blackkcold.simhub.p2p;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.net.nsd.*;
import android.net.ConnectivityManager;
import android.net.Network;
import androidx.core.app.NotificationCompat;
import com.blackkcold.simhub.R;
import org.json.*;
import java.net.*;
import java.util.*;

/** User-started listener. No boot auto-start or background service launch. */
public final class PeerService extends Service {
    private PeerNodeServer server;
    private NsdManager nsd;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private NsdManager.RegistrationListener registration;
    private static volatile PeerService running;
    private static volatile String failure="";
    public static boolean enabled(){return running!=null;}
    public static String error(){return failure;}
    public static void start(Context c){c.startForegroundService(new Intent(c,PeerService.class));}
    public static void stop(Context c){c.stopService(new Intent(c,PeerService.class));}
    public static JSONArray endpoints()throws Exception{
        JSONArray a=new JSONArray();PeerService service=running;if(service==null||service.server==null)return a;
        List<InetAddress> addresses=new ArrayList<>();
        for(NetworkInterface iface:Collections.list(NetworkInterface.getNetworkInterfaces())){
            if(!iface.isUp()||iface.isLoopback())continue;
            for(InetAddress address:Collections.list(iface.getInetAddresses())){
                if(address.isLoopbackAddress()||address.isMulticastAddress()||address.isLinkLocalAddress())continue;
                addresses.add(address);
            }
        }
        addresses.sort(Comparator.comparingInt(address->address.isSiteLocalAddress()?0:1));
        for(InetAddress address:addresses)if(a.length()<12)a.put(new JSONObject().put("host",address.getHostAddress()).put("port",service.server.port()));
        return a;
    }
    public void onCreate(){super.onCreate();
        NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("simhub-p2p","P2P device access",NotificationManager.IMPORTANCE_LOW));
        Intent open=new Intent(this,com.blackkcold.simhub.PeerActivity.class);PendingIntent pi=PendingIntent.getActivity(this,741,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        startForeground(741,new NotificationCompat.Builder(this,"simhub-p2p").setSmallIcon(R.drawable.ic_simhub).setContentTitle("SIM Hub · P2P").setContentText("用户已开启设备直连 · 仅接受已配对设备").setContentIntent(pi).setOngoing(true).build());
        try{
            running=this;failure="";
            boolean localAllowed=Build.VERSION.SDK_INT<37||checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")==PackageManager.PERMISSION_GRANTED;
            if(localAllowed){
            server=new PeerNodeServer(PeerStore.get(this));
            new Thread(server::run,"simhub-peer-listener").start();
            nsd=getSystemService(NsdManager.class);NsdServiceInfo info=new NsdServiceInfo();info.setServiceName("simhub_"+PeerStore.get(this).myId().substring(0,16));info.setServiceType("_simhub-peer._tcp.");info.setPort(server.port());
            registration=new NsdManager.RegistrationListener(){public void onRegistrationFailed(NsdServiceInfo i,int e){}public void onUnregistrationFailed(NsdServiceInfo i,int e){}public void onServiceRegistered(NsdServiceInfo i){}public void onServiceUnregistered(NsdServiceInfo i){}};
            nsd.registerService(info,NsdManager.PROTOCOL_DNS_SD,registration);
            }
            connectivity=getSystemService(ConnectivityManager.class);
            networkCallback=new ConnectivityManager.NetworkCallback(){public void onAvailable(Network n){PeerCloudConnector.networkChangedAll();}public void onLost(Network n){PeerCloudConnector.networkChangedAll();}};
            connectivity.registerDefaultNetworkCallback(networkCallback);
            refresh();
        }catch(Exception e){failure=e instanceof SecurityException?"local_network_permission_required":"listener_start_failed";stopSelf();}
    }
    private void refresh(){com.blackkcold.simhub.AgentExecutors.io().execute(()->{
        JSONArray peers=PeerStore.get(this).list();for(int i=0;i<peers.length();i++)try{
            JSONObject p=peers.getJSONObject(i);if(!"REVOKED".equals(p.optString("pairState"))&&p.optBoolean("publicDiscoveryEnabled"))PeerCloudConnector.ensure(this,p.getString("id"));
        }catch(Exception ignored){}
    });}
    public int onStartCommand(Intent i,int flags,int id){refresh();return START_NOT_STICKY;}
    public void onDestroy(){running=null;PeerCloudConnector.closeAll();PeerInternetConnector.closeAll();if(connectivity!=null&&networkCallback!=null)try{connectivity.unregisterNetworkCallback(networkCallback);}catch(Exception ignored){}if(server!=null)server.close();if(nsd!=null&&registration!=null)try{nsd.unregisterService(registration);}catch(Exception ignored){}stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
    public IBinder onBind(Intent i){return null;}
}
