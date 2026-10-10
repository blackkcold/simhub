package com.blackkcold.simhub;

import org.junit.Test;
import static org.junit.Assert.*;

public class SmsReconcilePolicyTest {
    @Test public void sixHoursIncludesThreeHourProtectedOtps(){
        long now=24L*3600*1000;
        assertEquals(18L*3600*1000,SmsReconcilePolicy.cutoff(now));
        assertTrue(21L*3600*1000 >= SmsReconcilePolicy.cutoff(now));
    }
    @Test public void expiredCursorRestartsFromWindowBeginning(){
        assertTrue(SmsReconcilePolicy.restart(10,11));
        assertFalse(SmsReconcilePolicy.restart(11,11));
        assertFalse(SmsReconcilePolicy.restart(12,11));
    }
    @Test public void shortPageReplaysEntireWindowOnNextCycle(){
        assertTrue(SmsReconcilePolicy.passComplete(0,100));
        assertTrue(SmsReconcilePolicy.passComplete(99,100));
        assertFalse(SmsReconcilePolicy.passComplete(100,100));
    }
    @Test public void cutoffNeverNegative(){
        assertEquals(0,SmsReconcilePolicy.cutoff(1000));
    }
}
