package com.blackkcold.simhub;

import org.junit.Test;
import static org.junit.Assert.*;

public class SmsNotificationPolicyTest {
    @Test public void companionAndDeniedPermissionNeverPostDuplicateNotifications(){
        assertFalse(SmsNotificationPolicy.shouldNotify(false,false));
        assertFalse(SmsNotificationPolicy.shouldNotify(false,true));
        assertFalse(SmsNotificationPolicy.shouldNotify(true,false));
        assertTrue(SmsNotificationPolicy.shouldNotify(true,true));
    }
}
