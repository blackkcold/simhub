package com.blackkcold.simhub;

import org.junit.Test;
import static org.junit.Assert.*;

public final class LogSanitizerTest {
    @Test public void redactsSecrets(){
        String out=LogSanitizer.sanitize("admin_token=abc123456789 Device abcdefghijklmnopqrstuvwxyz bootstrap secret=xyz");
        assertFalse(out.contains("abc123456789"));assertFalse(out.contains("abcdefghijklmnopqrstuvwxyz"));assertFalse(out.contains("xyz"));
        assertTrue(out.contains("[REDACTED]"));
    }
    @Test public void redactsSmsAndOtp(){
        String out=LogSanitizer.sanitize("sms body: hello private world; otp=123456");
        assertFalse(out.contains("hello private world"));assertFalse(out.contains("123456"));
        assertTrue(out.contains("[SMS BODY REDACTED]"));assertTrue(out.contains("[OTP REDACTED]"));
    }
    @Test public void masksPhoneNumbers(){
        String out=LogSanitizer.sanitize("recipient 13812345678");
        assertFalse(out.contains("13812345678"));assertTrue(out.contains("138****5678"));
    }
}