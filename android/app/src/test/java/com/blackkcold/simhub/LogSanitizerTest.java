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
    @Test public void redactsEverySupportedOtpLabelAndSeparator(){
        String[] labels={"otp","OTP","VerificationCode","verification code","verification_code","verification-code","验证码","校验码"};
        String[] separators={""," ","\t\n","="," \t= \t",":"," \t: \t\n"};
        for(String label:labels)for(String separator:separators){
            assertEquals(label+"=[OTP REDACTED];",LogSanitizer.sanitize(label+separator+"24681357;"));
        }
    }
    @Test public void preservesTextWithoutAnOtp(){
        String input="otp \t= \n 123X; verification code: absent";
        assertEquals(input,LogSanitizer.sanitize(input));
    }
    @Test(timeout=5000) public void handlesLongWhitespaceAndStillRedactsLaterSecrets(){
        char[] spaces=new char[100000];
        java.util.Arrays.fill(spaces,' ');
        String whitespace=new String(spaces);
        String nearMiss="otp"+whitespace+"!; VerificationCode"+whitespace+"= "+whitespace+"123X; ";
        assertEquals(nearMiss+"otp=[OTP REDACTED]",LogSanitizer.sanitize(nearMiss+"otp=24681357"));
        assertEquals("otp=[OTP REDACTED]",LogSanitizer.sanitize("otp"+whitespace+"="+whitespace+"24681357"));
    }
}
