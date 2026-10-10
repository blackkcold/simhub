package com.blackkcold.simhub;

/** Small pure-JVM policy shared by system compatibility UI and metadata audit. */
public final class CompatibilityPolicy {
    private CompatibilityPolicy(){}
    public static boolean validAuditToken(String value){
        return value!=null && value.length()>=1 && value.length()<=48 &&
                value.matches("[a-z0-9_]+");
    }
    public static String otpProtectionNotice(int sdk,int targetSdk){
        if(sdk<37)return "pre_android_17";
        return targetSdk>=37?"android_17_generic_otp_protection":
                "android_17_target_compatibility_not_full_exemption";
    }
    public static boolean canOfferSelfManagedAssociation(int sdk,boolean enrolled){
        return sdk>=33 && enrolled;
    }
}
