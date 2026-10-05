package com.blackkcold.simhub;

import android.content.Context;
import android.database.Cursor;
import android.provider.BaseColumns;
import android.provider.Telephony;
import org.json.JSONObject;

public final class SmsHistorySync {
    private SmsHistorySync(){}
    public static int sync(Context c,int maxMessages){
        AgentConfig cfg=new AgentConfig(c); if(!cfg.isEnrolled())return 0; long since=cfg.lastHistorySync(); long maxDate=since; int count=0;
        String[] proj={BaseColumns._ID,Telephony.Sms.ADDRESS,Telephony.Sms.BODY,Telephony.Sms.DATE,Telephony.Sms.TYPE,Telephony.Sms.SUBSCRIPTION_ID};
        String sel=since>0?Telephony.Sms.DATE+">?":null; String[] args=since>0?new String[]{String.valueOf(since)}:null;
        try(Cursor cur=c.getContentResolver().query(Telephony.Sms.CONTENT_URI,proj,sel,args,Telephony.Sms.DATE+" ASC")){
            if(cur==null)return 0; while(cur.moveToNext()&&count<maxMessages){
                long id=cur.getLong(0);String address=cur.getString(1),body=cur.getString(2);long date=cur.getLong(3);int type=cur.getInt(4),sub=cur.getInt(5);maxDate=Math.max(maxDate,date);
                String direction=(type==Telephony.Sms.MESSAGE_TYPE_SENT||type==Telephony.Sms.MESSAGE_TYPE_OUTBOX)?"out":"in"; OtpParser.Result otp=OtpParser.parse(body);String contact=ContactResolver.lookup(c,address);
                JSONObject payload=new JSONObject().put("direction",direction).put(direction.equals("in")?"sender":"recipient",address==null?"":address).put("body",body==null?"":body).put("occurredAt",date/1000).put("subscriptionId",sub).put("providerType",type);
                if(contact!=null)payload.put("contactName",contact);if(otp.detected&&direction.equals("in"))payload.put("otp",new JSONObject().put("value",otp.value).put("confidence",otp.confidence));
                EventQueue.queue(c,"sms-provider-"+id,"sms.history",date/1000,sub,otp.detected&&direction.equals("in"),payload,new JSONObject().put("providerType",type)); count++;
            }
        }catch(Exception ignored){}
        if(maxDate>since)cfg.setLastHistorySync(maxDate); return count;
    }
    public static int syncMmsMetadata(Context c,int maxMessages){
        int count=0; String[] proj={BaseColumns._ID,Telephony.Mms.DATE,Telephony.Mms.MESSAGE_BOX,Telephony.Mms.SUBJECT,"sub_id"};
        try(Cursor cur=c.getContentResolver().query(Telephony.Mms.CONTENT_URI,proj,null,null,Telephony.Mms.DATE+" DESC")){
            if(cur==null)return 0; while(cur.moveToNext()&&count<maxMessages){long id=cur.getLong(0),date=cur.getLong(1);int box=cur.getInt(2),sub=cur.getInt(4);String subject=cur.getString(3);JSONObject p=new JSONObject().put("direction",box==Telephony.Mms.MESSAGE_BOX_SENT?"out":"in").put("subject",subject==null?"":subject).put("occurredAt",date).put("subscriptionId",sub).put("transport","mms-metadata-only");EventQueue.queue(c,"mms-provider-"+id,"mms.history",date,sub,false,p,new JSONObject().put("metadataOnly",true));count++;}}
        catch(Exception ignored){} return count;
    }
}
