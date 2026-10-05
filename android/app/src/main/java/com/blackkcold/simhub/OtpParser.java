package com.blackkcold.simhub;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OtpParser {
    private static final Pattern CAND = Pattern.compile("(?<![A-Za-z0-9])([A-Za-z0-9]{4,8})(?![A-Za-z0-9])");
    private static final String[] KEYWORDS = {"验证码","驗證碼","校验码","動態碼","动态码","认证码","認證碼","verification code","verify code","security code","one-time code","one time code","otp","pin","code is","passcode"};
    public static final class Result { public final boolean detected; public final String value; public final double confidence; Result(boolean d,String v,double c){detected=d;value=v;confidence=c;} }
    public static Result parse(String body){
        if(body==null||body.isBlank()) return new Result(false,null,0);
        String lower=body.toLowerCase(Locale.ROOT); Matcher m=CAND.matcher(body); String best=null; double bestScore=0;
        while(m.find()){
            String c=m.group(1); if(!c.chars().anyMatch(Character::isDigit)) continue;
            if(c.length()==4 && (c.startsWith("19")||c.startsWith("20"))) continue;
            double s=0.22; if(c.length()==6)s+=0.18; else if(c.length()==4)s+=0.12; else if(c.length()==8)s+=0.08;
            int start=Math.max(0,m.start()-48), end=Math.min(lower.length(),m.end()+48); String near=lower.substring(start,end);
            for(String k:KEYWORDS) if(near.contains(k)){s+=0.55;break;}
            if(near.matches(".*(?:¥|￥|\\$|usd|rmb)\\s*"+Pattern.quote(c.toLowerCase(Locale.ROOT))+".*")) s-=0.35;
            if(near.matches(".*\\d{1,2}[:：]"+Pattern.quote(c)+".*")) s-=0.25;
            if(allSame(c)) s-=0.25;
            if(s>bestScore){bestScore=s;best=c;}
        }
        return best!=null&&bestScore>=0.62?new Result(true,best,Math.min(0.99,bestScore)):new Result(false,null,Math.max(0,bestScore));
    }
    private static boolean allSame(String s){for(int i=1;i<s.length();i++)if(s.charAt(i)!=s.charAt(0))return false;return true;}
}
