package com.blackkcold.simhub;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
public final class LogSanitizer {
    private static final Pattern SECRET=Pattern.compile("(?i)(authorization|admin[ _-]?token|device[ _-]?token|bootstrap(?: secret)?|vault[ _-]?key|node[ _-]?key|recovery[ _-]?key|passphrase|secret)\\s*[:=]\\s*([^\\s,;]+)");
    private static final Pattern SMS=Pattern.compile("(?i)(sms(?: body)?|message|body)\\s*[:=]\\s*([^\\n;]+)");
    private static final Pattern OTP=Pattern.compile("(?i)(otp|verification[ _-]?code|验证码|校验码)\\s*[:=]?\\s*([0-9]{4,8})");
    private static final Pattern PHONE=Pattern.compile("(?<![0-9])(\\+?[0-9]{3})([0-9]{4,8})([0-9]{4})(?![0-9])");
    private static final Pattern BEARER=Pattern.compile("(?i)(Bearer|Device)\\s+[A-Za-z0-9._~+/-]{16,}");
    public static String sanitize(String input){
        if(input==null)return "";
        String s=SECRET.matcher(input).replaceAll("$1=[REDACTED]");
        s=SMS.matcher(s).replaceAll("$1=[SMS BODY REDACTED]");
        s=OTP.matcher(s).replaceAll("$1=[OTP REDACTED]");
        s=BEARER.matcher(s).replaceAll("$1 [REDACTED]");
        Matcher m=PHONE.matcher(s);StringBuffer out=new StringBuffer();
        while(m.find())m.appendReplacement(out,Matcher.quoteReplacement(m.group(1)+"****"+m.group(3)));
        m.appendTail(out);return out.toString();
    }
    private LogSanitizer(){}
}