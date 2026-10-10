package com.blackkcold.simhub;

import org.junit.Test;
import static org.junit.Assert.*;

public class CompatibilityPolicyTest {
    @Test public void separatesDeviceSdkAndAppTarget(){
        assertEquals("pre_android_17",CompatibilityPolicy.otpProtectionNotice(36,37));
        assertEquals("android_17_generic_otp_protection",CompatibilityPolicy.otpProtectionNotice(37,37));
        assertEquals("android_17_target_compatibility_not_full_exemption",CompatibilityPolicy.otpProtectionNotice(37,36));
    }
    @Test public void blocksUnpairedAndUnsupportedAssociation(){
        assertFalse(CompatibilityPolicy.canOfferSelfManagedAssociation(32,true));
        assertFalse(CompatibilityPolicy.canOfferSelfManagedAssociation(37,false));
        assertTrue(CompatibilityPolicy.canOfferSelfManagedAssociation(33,true));
    }
    @Test public void auditCategoriesRejectSmsSecrets(){
        assertTrue(CompatibilityPolicy.validAuditToken("shizuku_granted"));
        assertFalse(CompatibilityPolicy.validAuditToken("OTP 123456"));
        assertFalse(CompatibilityPolicy.validAuditToken("read/sms"));
        assertFalse(CompatibilityPolicy.validAuditToken(""));
    }
}
