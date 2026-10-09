package com.blackkcold.simhub;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
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
        // A legacy provider row can only be attributed to a SIM after the same
        // identity/revision had been observed. Older history remains unverified.
        long firstSeen=p.getLong(prefix+"first_seen",0L);
        if(firstSeen<=0||!previous.equals(current))firstSeen=System.currentTimeMillis();
        if(!p.edit().putString(prefix+"id",id).putLong(prefix+"revision",rev).putString(prefix+"fingerprint",current).putLong(prefix+"first_seen",firstSeen).commit())throw new IllegalStateException("SIM identity revision not durable");
        return new Channel(id,rev,s.getSubscriptionId(),s.getSimSlotIndex());
    }

    public static Channel forSubscription(Context c,int subscriptionId){
        try{
            SubscriptionManager sm=c.getSystemService(SubscriptionManager.class);
            List<SubscriptionInfo> list=sm.getActiveSubscriptionInfoList();
            if(list!=null)for(SubscriptionInfo si:list){
                if(si.getSubscriptionId()==subscriptionId)return describe(c,si);
            }
        }catch(Exception ignored){}
        return null;
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

    public static Channel forHistoricalSubscription(Context c,int subscriptionId,long occurredAtMillis){
        if(subscriptionId<0||occurredAtMillis<=0)return null;
        try{
            SubscriptionManager sm=c.getSystemService(SubscriptionManager.class);
            List<SubscriptionInfo> list=sm.getActiveSubscriptionInfoList();
            if(list==null)return null;
            SharedPreferences p=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);
            for(SubscriptionInfo s:list){
                if(s.getSubscriptionId()!=subscriptionId)continue;
                Channel ch=describe(c,s);
                long firstSeen=p.getLong("channel."+anchor(s)+".first_seen",0L);
                // Allow 2 seconds for provider and system timestamp ordering only.
                if(firstSeen>0&&occurredAtMillis+2000>=firstSeen)return ch;
            }
        }catch(SecurityException e){AppLogger.w(c,"ChannelIdentity","Historical identity permission denied");}
        catch(Exception e){AppLogger.w(c,"ChannelIdentity","Historical SIM identity unavailable");}
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
        return hash(identityMaterial(s,iccid));
    }

    static String identityMaterial(SubscriptionInfo s,String iccid){
        String mcc="",mnc="",number="",carrierName="",displayName="";
        int cardId=-1,portIndex=-1,carrierId=-1,subId=-1,slot=-1;
        boolean embedded=false,opportunistic=false;
        try{subId=s.getSubscriptionId();}catch(Exception ignored){}
        try{slot=s.getSimSlotIndex();}catch(Exception ignored){}
        try{cardId=s.getCardId();}catch(Exception ignored){}
        if(Build.VERSION.SDK_INT>=33)try{portIndex=s.getPortIndex();}catch(Exception ignored){}
        try{carrierId=s.getCarrierId();}catch(Exception ignored){}
        try{mcc=String.valueOf(s.getMccString());mnc=String.valueOf(s.getMncString());}catch(Exception ignored){}
        try{number=String.valueOf(s.getNumber());}catch(Exception ignored){}
        try{carrierName=String.valueOf(s.getCarrierName());}catch(Exception ignored){}
        try{displayName=String.valueOf(s.getDisplayName());}catch(Exception ignored){}
        try{embedded=s.isEmbedded();}catch(Exception ignored){}
        try{opportunistic=s.isOpportunistic();}catch(Exception ignored){}
        return "slot="+slot
                +"|card="+cardId
                +"|port="+portIndex
                +"|sub="+subId
                +"|carrierId="+carrierId
                +"|iccid="+(iccid==null?"":iccid)
                +"|mcc="+mcc
                +"|mnc="+mnc
                +"|embedded="+embedded
                +"|opportunistic="+opportunistic;
    }

    private static String rawFingerprint(SubscriptionInfo s){
        return identityMaterial(s,"");
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
