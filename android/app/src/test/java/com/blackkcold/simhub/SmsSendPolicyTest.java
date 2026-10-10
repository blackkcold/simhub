package com.blackkcold.simhub;

import org.junit.Test;
import static org.junit.Assert.*;

public class SmsSendPolicyTest {
    @Test public void fullHandlerCanSendWithPermission() {
        assertTrue(SmsSendPolicy.allowed(true, true, false));
    }
    @Test public void companionCannotSendByPermissionAlone() {
        assertFalse(SmsSendPolicy.allowed(false, true, false));
    }
    @Test public void developerOverridePermitsTesting() {
        assertTrue(SmsSendPolicy.allowed(false, true, true));
    }
    @Test public void deniedPermissionAlwaysBlocksSending() {
        assertFalse(SmsSendPolicy.allowed(true, false, true));
        assertFalse(SmsSendPolicy.allowed(false, false, true));
    }
}
