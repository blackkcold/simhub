package com.blackkcold.simhub;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public final class SmsDeliveryPolicyTest {
    @Test public void gsmOnlyExplicitReceivedStatusIsDelivered(){
        assertEquals(SmsDeliveryPolicy.Result.DELIVERED,
            SmsDeliveryPolicy.classify("3gpp",0));
        assertEquals(SmsDeliveryPolicy.Result.UNKNOWN,
            SmsDeliveryPolicy.classify("3gpp",1));
        assertEquals(SmsDeliveryPolicy.Result.UNKNOWN,
            SmsDeliveryPolicy.classify("3gpp",2));
        assertEquals(SmsDeliveryPolicy.Result.PENDING,
            SmsDeliveryPolicy.classify("3gpp",0x20));
        assertEquals(SmsDeliveryPolicy.Result.PENDING,
            SmsDeliveryPolicy.classify("3gpp",0x3f));
        assertEquals(SmsDeliveryPolicy.Result.FAILED,
            SmsDeliveryPolicy.classify("3gpp",0x40));
        assertEquals(SmsDeliveryPolicy.Result.FAILED,
            SmsDeliveryPolicy.classify("3gpp",0x7f));
        assertEquals(SmsDeliveryPolicy.Result.UNKNOWN,
            SmsDeliveryPolicy.classify("3gpp",0x80));
    }
    @Test public void cdmaNonzeroAndUnknownFormatsRemainUnconfirmed(){
        assertEquals(SmsDeliveryPolicy.Result.DELIVERED,
            SmsDeliveryPolicy.classify("3gpp2",0));
        assertEquals(SmsDeliveryPolicy.Result.UNKNOWN,
            SmsDeliveryPolicy.classify("3gpp2",0x020000));
        assertEquals(SmsDeliveryPolicy.Result.UNKNOWN,
            SmsDeliveryPolicy.classify("auto",0x20));
    }
}
