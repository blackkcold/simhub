package com.blackkcold.simhub;

/** Platform gating only. Android 16 is SDK 36; developer override never bypasses Android installer security. */
public final class RemoteOtaPolicy {
    private RemoteOtaPolicy() {}
    public static boolean terminal(String stage){return "failed".equals(stage)||"succeeded".equals(stage);}
    public static boolean requiresWorker(String stage){
        return "checking".equals(stage)||"downloading".equals(stage)||"verified".equals(stage);
    }
    public static boolean installedMatchesTarget(int installed,int expected,String stage){
        return expected>0 && installed>=expected && !"failed".equals(stage) && !"succeeded".equals(stage);
    }
    public static boolean installationNeedsVerification(String stage,long updatedAt,long nowSeconds){
        return "installing".equals(stage) && updatedAt>0 && nowSeconds-updatedAt>=900;
    }
    public static boolean supported(int sdk, boolean developerOverride) {
        return sdk >= 36 || developerOverride;
    }
    public static boolean canOffer(int sdk, boolean developerOverride, boolean localConsent) {
        return supported(sdk, developerOverride) && localConsent;
    }
    public static boolean validTarget(String channel,String target){
        if("latest".equals(target))return "stable".equals(channel)||"dev".equals(channel);
        if(target==null)return false;
        if("stable".equals(channel))return target.matches("[0-9]+\\.[0-9]+\\.[0-9]+");
        if("dev".equals(channel))return target.matches("[0-9]+\\.[0-9]+\\.[0-9]+-dev\\.[1-9][0-9]*");
        return false;
    }
}
