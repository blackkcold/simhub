package com.blackkcold.simhub;

/** Platform gating only. Android 16 is SDK 36; developer override never bypasses Android installer security. */
public final class RemoteOtaPolicy {
    private RemoteOtaPolicy() {}
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
}
