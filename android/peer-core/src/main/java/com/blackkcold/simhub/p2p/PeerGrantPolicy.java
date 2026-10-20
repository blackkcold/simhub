package com.blackkcold.simhub.p2p;

import org.json.JSONObject;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

/** A peer may expose and consume services independently. A transport is never a grant. */
public final class PeerGrantPolicy {
    private PeerGrantPolicy(){}
    public static final String READ="read,history,state";
    public static final String READ_SEND="read,history,state,send";

    public static boolean valid(String grants){
        return "".equals(grants)||READ.equals(grants)||READ_SEND.equals(grants);
    }

    public static boolean allows(JSONObject peer, String action, boolean serving) {
        if(peer==null||"REVOKED".equals(peer.optString("pairState")))return false;
        String grants;
        if(peer.optInt("protocolVersion",1)>=2){
            grants=peer.optString(serving?"grantsToRemote":"grantsFromRemote","");
        }else{
            String role=peer.optString("localRole");
            if(serving&&!"NODE".equals(role))return false;
            if(!serving&&!"CONTROLLER".equals(role))return false;
            grants=peer.optString("permissions","");
        }
        if(!valid(grants))return false;
        Set<String> permissions=new HashSet<>(Arrays.asList(grants.split(",")));
        return switch(action) {
            case "device.state" -> permissions.contains("state");
            case "sms.page" -> permissions.contains("read")&&permissions.contains("history");
            case "events.page","events.ack" -> permissions.contains("read");
            case "sms.send","command.status" -> permissions.contains("send");
            default -> false;
        };
    }
}
