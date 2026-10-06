package com.blackkcold.simhub;

import android.content.Context;
import android.content.SharedPreferences;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

public final class ChannelIdentity {
    private static final String PREF="simhub_channel_identity";
    public static final class Channel {
        public final String channelId;
        public final long revision;
        public final int subscriptionId;
        public final int slotIndex;
        Channel(String id,long rev,int subId,int slot){channelId=id;revision=rev;subscriptionId=subId;slotIndex=slot;}
    }

    public static Channel describe(Context c,SubscriptionInfo s){
        SharedPreferences p=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        String anchor=anchor(s);
        String prefix="channel."+anchor+".";
        String id=p.getString(prefix+"id",null);
        long rev=p.getLong(prefix+"revision",0);
        String previous=p.getString(prefix+"fingerprint","");
        String current=fingerprint(s);
        if(id==null||id.isBlank()){id=UUID.randomUUID().toString();rev=1;}
        else if(!previous.isEmpty()&&!previous.equals(current))rev=Math.max(1,rev+1);
        else if(rev<=0)rev=1;
        p.edit().putString(prefix+"id",id).putLong(prefix+"revision",rev).putString(prefix+"fingerprint",current).apply();
        return new Channel(id,rev,s.getSubscriptionId(),s.getSimSlotIndex());
    }

    public static Channel resolve(Context c,String channelId,long expectedRevision){
        try{
            SubscriptionManager sm=c.getSystemService(SubscriptionManager.class);
            List<SubscriptionInfo> list=sm.getActiveSubscriptionInfoList();
            if(list==null)return null;
            for(SubscriptionInfo s:list){
                Channel ch=describe(c,s);
                if(ch.channelId.equals(channelId)&&(expectedRevision<=0||ch.revision==expectedRevision))return ch;
            }
        }catch(Exception ignored){}
        return null;
    }

    private static String anchor(SubscriptionInfo s){
        int slot=s.getSimSlotIndex();
        if(slot>=0)return "slot-"+slot;
        try{int card=s.getCardId();if(card>=0)return "card-"+card;}catch(Exception ignored){}
        return "embedded-"+hash(rawFingerprint(s)).substring(0,16);
    }

    private static String fingerprint(SubscriptionInfo s){
        String iccid="";
        try{iccid=s.getIccId();}catch(Exception ignored){}
        if(iccid!=null&&!iccid.isBlank())return hash("iccid:"+iccid);
        return hash(rawFingerprint(s));
    }

    private static String rawFingerprint(SubscriptionInfo s){
        String mcc="",mnc="";
        try{mcc=String.valueOf(s.getMccString());mnc=String.valueOf(s.getMncString());}catch(Exception ignored){}
        return s.getSimSlotIndex()+"|"+mcc+"|"+mnc+"|"+String.valueOf(s.getCarrierName())+"|"+String.valueOf(s.getDisplayName())+"|"+s.isEmbedded();
    }

    private static String hash(String value){
        try{
            byte[] d=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder b=new StringBuilder();for(byte x:d)b.append(String.format("%02x",x));
            return b.toString();
        }catch(Exception e){return Integer.toHexString(value.hashCode());}
    }
    private ChannelIdentity(){}
}
