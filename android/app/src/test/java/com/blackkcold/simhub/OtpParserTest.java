package com.blackkcold.simhub;

import org.junit.Test;
import static org.junit.Assert.*;

public class OtpParserTest {
    @Test public void detectsChineseOtp(){
        OtpParser.Result r=OtpParser.parse("您的验证码为 839241，10分钟内有效");
        assertTrue(r.detected);assertEquals("839241",r.value);
    }

    @Test public void detectsEnglishOtp(){
        OtpParser.Result r=OtpParser.parse("Your verification code is A7B92C. Do not share it.");
        assertTrue(r.detected);assertEquals("A7B92C",r.value);
    }

    @Test public void rejectsBareYearLikeNumber(){
        OtpParser.Result r=OtpParser.parse("Statement year 2026");
        assertFalse(r.detected);
    }

    @Test public void rejectsRepeatedDigitsWithoutSemanticContext(){
        OtpParser.Result r=OtpParser.parse("Reference 111111");
        assertFalse(r.detected);
    }
}
