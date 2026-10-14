package com.blackkcold.simhub.p2p;

import android.content.Context;
import org.json.*;

/** Independent durable events for P2P peers; never mutates the Relay upload cursor. */
public final class PeerEvents {
    public static boolean stage(Context context,String id,String kind,long occurred,JSONObject payload){
        if(!kind.startsWith("sms.")&&!kind.equals("sim.profile"))return false;
        PeerStore store=PeerStore.get(context);JSONArray peers=store.list();boolean found=false,all=true;
        for(int i=0;i<peers.length();i++)try{
            JSONObject p=peers.getJSONObject(i);if(!"NODE".equals(p.optString("localRole"))||!"ACTIVE".equals(p.optString("pairState"))||!p.optString("permissions").contains("read"))continue;
            found=true;all=store.event(p.getString("id"),id,kind,occurred,payload)&&all;
        }catch(Exception e){all=false;}
        return found&&all;
    }
    private PeerEvents(){}
}
