package com.blackkcold.simhub;

import org.json.JSONObject;
import java.io.IOException;

/** A consumed event is not necessarily stored: privacy fences can suppress it. */
public final class EventUploadReceipt {
    private EventUploadReceipt(){}
    public static String disposition(JSONObject receipt)throws IOException{
        if(!receipt.optBoolean("accepted",false))throw new IOException("Event acknowledgement rejected");
        String declared=receipt.optString("receiptState","");
        String disposition;
        if(receipt.optBoolean("suppressed",false)){
            if(receipt.optLong("serverSequence",0)!=0)throw new IOException("Conflicting event acknowledgement");
            disposition="suppressed";
        }else{
            if(receipt.optLong("serverSequence",0)<=0)throw new IOException("Stored event sequence missing");
            disposition=receipt.optBoolean("duplicate",false)?"duplicate":"stored";
        }
        if(!declared.isEmpty()&&!declared.equals(disposition))throw new IOException("Conflicting receipt disposition");
        return disposition;
    }
    public static boolean published(String disposition){
        return "stored".equals(disposition)||"duplicate".equals(disposition);
    }
}
