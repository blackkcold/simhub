package com.blackkcold.simhub;

import android.content.Context;
import android.database.Cursor;
import android.provider.BaseColumns;
import android.provider.Telephony;
import android.Manifest;
import android.content.pm.PackageManager;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Separate newest-message polling from resumable, user-requested older-history backfill. */
public final class SmsHistorySync {
    private SmsHistorySync(){}
    private static final int PAGE = 100;
    private static final String[] PROJECTION = {BaseColumns._ID,Telephony.Sms.ADDRESS,Telephony.Sms.BODY,
            Telephony.Sms.DATE,Telephony.Sms.TYPE,Telephony.Sms.SUBSCRIPTION_ID};

    private static final class SmsRow {
        final long id,date; final String address,body; final int type,sub;
        SmsRow(Cursor cur) {
            id=cur.getLong(0);address=cur.getString(1);body=cur.getString(2);
            date=cur.getLong(3);type=cur.getInt(4);sub=cur.isNull(5)?-1:cur.getInt(5);
        }
    }

    private static boolean enqueue(Context c,SmsRow row) throws Exception {
        if(row.type==Telephony.Sms.MESSAGE_TYPE_DRAFT)return true;
        String direction=row.type==Telephony.Sms.MESSAGE_TYPE_INBOX?"in":"out";
        OtpParser.Result otp=direction.equals("in")?OtpParser.parse(row.body):new OtpParser.Result(false,null,0f);
        String contact=ContactResolver.lookup(c,row.address);
        JSONObject payload=new JSONObject().put("direction",direction)
                .put(direction.equals("in")?"sender":"recipient",row.address==null?"":row.address)
                .put("body",row.body==null?"":row.body).put("occurredAt",row.date/1000)
                .put("subscriptionId",row.sub).put("providerType",row.type).put("providerId",row.id);
        ChannelIdentity.Channel channel=ChannelIdentity.forHistoricalSubscription(c,row.sub,row.date);
        if(channel!=null)payload.put("channelId",channel.channelId).put("channelRevision",channel.revision);
        if(contact!=null)payload.put("contactName",contact);
        if(otp.detected&&direction.equals("in"))
            payload.put("otp",new JSONObject().put("value",otp.value).put("confidence",otp.confidence));
        return EventQueue.queue(c,"sms-provider-"+row.id,"sms.history",row.date/1000,row.sub,
                otp.detected&&direction.equals("in"),payload,new JSONObject().put("providerType",row.type).put("history",true));
    }

    /**
     * First use imports the most recent 100, not the oldest 5,000.
     * Later invocations consume new messages in ascending (date,id) order.
     */
    private static boolean canRead(Context c){
        if(c.checkSelfPermission(Manifest.permission.READ_SMS)==PackageManager.PERMISSION_GRANTED)return true;
        new AgentConfig(c).recordSmsProviderError("READ_SMS_PERMISSION_MISSING");return false;
    }
    private static void scanFailed(Context c,Exception error,String operation){
        String reason=error instanceof SecurityException?"SMS_PROVIDER_SECURITY_EXCEPTION":error.getClass().getSimpleName();
        new AgentConfig(c).recordSmsProviderError(reason);
        AppLogger.e(c,"SmsSync",operation+" failed",error);
    }
    public static synchronized int sync(Context c,int requested) {
        if(!canRead(c))return -1;
        AgentConfig cfg=new AgentConfig(c);if(!cfg.isEnrolled())return 0;
        int limit=Math.max(1,Math.min(requested,PAGE));
        boolean initial=!cfg.historyInitialized();
        long date=cfg.historyDate(),id=cfg.historyId();
        String selection=initial?null:"("+Telephony.Sms.DATE+">?) OR ("+Telephony.Sms.DATE+"=? AND "+BaseColumns._ID+">?)";
        String[] args=initial?null:new String[]{String.valueOf(date),String.valueOf(date),String.valueOf(id)};
        String order=Telephony.Sms.DATE+(initial?" DESC, ":" ASC, ")+BaseColumns._ID+(initial?" DESC":" ASC");
        int count=0;
        try(Cursor cur=c.getContentResolver().query(Telephony.Sms.CONTENT_URI,PROJECTION,selection,args,order)) {
            if(cur==null)return 0;
            List<SmsRow> rows=new ArrayList<>();
            while(rows.size()< (initial?PAGE:limit) && cur.moveToNext())rows.add(new SmsRow(cur));
            if(initial)Collections.reverse(rows);
            for(SmsRow row:rows){
                if(!enqueue(c,row))break;
                cfg.setHistoryCursor(row.date,row.id);
                if(row.type!=Telephony.Sms.MESSAGE_TYPE_DRAFT)count++;
            }
            if(initial && count==rows.stream().filter(row->row.type!=Telephony.Sms.MESSAGE_TYPE_DRAFT).count()) {
                if(!rows.isEmpty())cfg.setHistoryBackfillCursor(rows.get(0).date,rows.get(0).id);
                cfg.setHistoryInitialized(true);
            }
            if(count>0)AppLogger.i(c,"SmsSync","Incremental history queued="+count+" initial="+initial);
        }catch(Exception error){scanFailed(c,error,"History scan");return -1;}
        cfg.clearSmsProviderError();return count;
    }

    /**
     * Rolling reconciliation for delayed Android 17 SMS/OTP visibility.
     * A monotonically advancing history cursor alone permanently loses SMS rows
     * initially filtered by the OS. Replay the last six hours in bounded pages,
     * restart at the window beginning when exhausted, and deduplicate by provider ID.
     * No clock-based inference can force protected OTPs to become visible early.
     */
    public static synchronized int reconcileRecent(Context c,int requested){
        if(!canRead(c))return -1;
        AgentConfig cfg=new AgentConfig(c);
        if(!cfg.isEnrolled())return 0;
        final int limit=Math.max(1,Math.min(PAGE,requested));
        final long cutoff=SmsReconcilePolicy.cutoff(System.currentTimeMillis());
        long lastDate=cfg.reconcileDate(),lastId=cfg.reconcileId();
        if(SmsReconcilePolicy.restart(lastDate,cutoff)){lastDate=0;lastId=-1;cfg.resetReconcileCursor();}
        String selection=Telephony.Sms.DATE+">=?"+
            (lastDate>0?" AND (("+Telephony.Sms.DATE+">?) OR ("+
                Telephony.Sms.DATE+"=? AND "+BaseColumns._ID+">?))":"");
        List<String> args=new ArrayList<>();
        args.add(String.valueOf(cutoff));
        if(lastDate>0){args.add(String.valueOf(lastDate));args.add(String.valueOf(lastDate));args.add(String.valueOf(lastId));}
        int scanned=0;
        try(Cursor cur=c.getContentResolver().query(Telephony.Sms.CONTENT_URI,PROJECTION,selection,
                args.toArray(new String[0]),Telephony.Sms.DATE+" ASC, "+BaseColumns._ID+" ASC")){
            if(cur==null){cfg.recordSmsProviderError("RECONCILE_PROVIDER_NULL");return -1;}
            while(scanned<limit&&cur.moveToNext()){
                SmsRow row=new SmsRow(cur);
                if(!enqueue(c,row))break; // Do not advance if durable queue rejected it.
                cfg.setReconcileCursor(row.date,row.id);
                scanned++;
            }
            if(SmsReconcilePolicy.passComplete(scanned,limit))cfg.resetReconcileCursor(); // Replay any newly unhidden older rows.
            cfg.clearSmsProviderError();
        }catch(Exception error){scanFailed(c,error,"Delayed SMS reconciliation");return -1;}
        cfg.recordSmsReconcile(scanned);
        if(DeveloperSettings.isEnabled(c))AppLogger.i(c,"SmsSync","Rolling reconciliation inspected="+scanned+" max="+limit+" (no OTP content logged)");
        return scanned;
    }

    /** Explicitly rescan the newest N SMS; stable provider event IDs prevent duplicates. */
    public static synchronized int syncRecent(Context c,int requested){
        if(!canRead(c))return -1;
        AgentConfig cfg=new AgentConfig(c);if(!cfg.isEnrolled())return 0;
        int limit=Math.max(1,Math.min(PAGE,requested)),count=0;
        LocalStore store=LocalStore.get(c);
        int queueBefore=store.pendingEventCount();
        try(Cursor cur=c.getContentResolver().query(Telephony.Sms.CONTENT_URI,PROJECTION,
                null,null,Telephony.Sms.DATE+" DESC, "+BaseColumns._ID+" DESC")){
            if(cur==null){cfg.recordSyncError("SMS provider query returned null");return -1;}
            while(count<limit&&cur.moveToNext()){
                SmsRow row=new SmsRow(cur);
                if(row.type==Telephony.Sms.MESSAGE_TYPE_DRAFT)continue;
                if(!enqueue(c,row)){cfg.recordSyncError("History queue full");return -1;}
                count++;
            }
            cfg.clearSmsProviderError();
            if(count>0){int newQueued=Math.max(0,store.pendingEventCount()-queueBefore);AppLogger.i(c,"SmsSync","Recent history inspected="+count+" newlyQueued="+newQueued+" previouslyQueuedOrUploaded="+Math.max(0,count-newQueued));}
        }catch(Exception error){scanFailed(c,error,"Recent history");return -1;}
        return count;
    }

    /** Explicit older history, descending in batches; marker only advances after durable queueing. */
    public static synchronized int syncOlder(Context c,int requested) {
        if(!canRead(c))return -1;
        AgentConfig cfg=new AgentConfig(c);if(!cfg.isEnrolled())return 0;
        if(!cfg.historyInitialized())sync(c,PAGE);
        if(!cfg.historyInitialized())return -1;
        long date=cfg.historyBackfillDate(),id=cfg.historyBackfillId();
        if(date==0)return 0;
        int limit=Math.max(1,Math.min(requested,PAGE)),count=0;
        String selection="("+Telephony.Sms.DATE+"<?) OR ("+Telephony.Sms.DATE+"=? AND "+BaseColumns._ID+"<?)";
        String[] args={String.valueOf(date),String.valueOf(date),String.valueOf(id)};
        try(Cursor cur=c.getContentResolver().query(Telephony.Sms.CONTENT_URI,PROJECTION,selection,args,
                Telephony.Sms.DATE+" DESC, "+BaseColumns._ID+" DESC")) {
            if(cur==null){cfg.recordSyncError("SMS provider query returned null");return -1;}
            while(count<limit&&cur.moveToNext()){
                SmsRow row=new SmsRow(cur);
                if(!enqueue(c,row)){cfg.recordSyncError("History queue full");return -1;}
                cfg.setHistoryBackfillCursor(row.date,row.id);
                if(row.type!=Telephony.Sms.MESSAGE_TYPE_DRAFT)count++;
            }
            cfg.clearSmsProviderError();
            if(count>0)AppLogger.i(c,"SmsSync","Older history queued="+count);
        }catch(Exception error){scanFailed(c,error,"Older history");return -1;}
        return count;
    }

    public static int syncMmsMetadata(Context c,int maxMessages){
        int count=0;String[] proj={BaseColumns._ID,Telephony.Mms.DATE,Telephony.Mms.MESSAGE_BOX,Telephony.Mms.SUBJECT,"sub_id"};
        try(Cursor cur=c.getContentResolver().query(Telephony.Mms.CONTENT_URI,proj,null,null,Telephony.Mms.DATE+" DESC")){
            if(cur==null)return 0;
            while(cur.moveToNext()&&count<maxMessages){long id=cur.getLong(0),date=cur.getLong(1);int box=cur.getInt(2),sub=cur.getInt(4);String subject=cur.getString(3);JSONObject p=new JSONObject().put("direction",box==Telephony.Mms.MESSAGE_BOX_SENT?"out":"in").put("subject",subject==null?"":subject).put("occurredAt",date).put("subscriptionId",sub).put("transport","mms-metadata-only");if(EventQueue.queue(c,"mms-provider-"+id,"mms.history",date,sub,false,p,new JSONObject().put("metadataOnly",true)))count++;}
            if(count>0)AppLogger.i(c,"MmsSync","MMS metadata queued count="+count);
        }catch(Exception error){AppLogger.e(c,"MmsSync","MMS metadata scan failed",error);}
        return count;
    }
}
