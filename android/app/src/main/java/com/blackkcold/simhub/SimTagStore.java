package com.blackkcold.simhub;

import android.content.Context;
import org.json.JSONObject;

/** Per-channel/revision SIM label and masked number, protected by Android Keystore. */
public final class SimTagStore {
    private SimTagStore(){}
    private static String key(String channelId,long revision){
        if(channelId==null||channelId.isBlank())throw new IllegalArgumentException("Channel identity required");
        return "sim-tag-"+channelId+"-"+Math.max(1,revision);
    }
    public static JSONObject get(Context c,String channelId,long revision){
        if(channelId==null||channelId.isBlank())return new JSONObject();
        try{
            String raw=new SecretStore(c).getString(key(channelId,revision));
            return raw==null?new JSONObject():new JSONObject(raw);
        }catch(Exception e){
            AppLogger.e(c,"SimTags","Unable to read encrypted SIM label",e);
            return new JSONObject();
        }
    }
    public static void set(Context c,String channelId,long revision,String label,String number)throws Exception{
        String tag=label==null?"":label.trim();
        if(tag.length()>40)throw new IllegalArgumentException("SIM tag too long");
        String digits=number==null?"":number.replaceAll("[^0-9]","");
        if(!digits.isEmpty()&&digits.length()<4)throw new IllegalArgumentException("SIM number requires at least 4 digits");
        String tail=digits.isEmpty()?"":digits.substring(digits.length()-4);
        new SecretStore(c).putString(key(channelId,revision),
            new JSONObject().put("tag",tag).put("tail",tail).toString());
    }
}
