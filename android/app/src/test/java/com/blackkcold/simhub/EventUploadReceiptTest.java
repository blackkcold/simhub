package com.blackkcold.simhub;

import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class EventUploadReceiptTest {
    @Test public void suppressionIsConsumedWithoutClaimingPublication()throws Exception{
        JSONObject ack=new JSONObject().put("accepted",true).put("suppressed",true).put("serverSequence",0);
        assertEquals("suppressed",EventUploadReceipt.disposition(ack));
        assertFalse(EventUploadReceipt.published(EventUploadReceipt.disposition(ack)));
    }
    @Test public void storedAndDuplicateNeedDurableSequence()throws Exception{
        JSONObject ack=new JSONObject().put("accepted",true).put("serverSequence",19);
        assertEquals("stored",EventUploadReceipt.disposition(ack));
        ack.put("duplicate",true);
        assertEquals("duplicate",EventUploadReceipt.disposition(ack));
        ack.put("serverSequence",0);
        try{EventUploadReceipt.disposition(ack);fail("Missing storage acknowledgement accepted");}
        catch(IOException expected){}
    }
}
