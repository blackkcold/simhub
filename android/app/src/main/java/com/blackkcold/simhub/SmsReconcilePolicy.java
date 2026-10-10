package com.blackkcold.simhub;

/** Pure reconciliation policy, testable without a phone or Android framework. */
final class SmsReconcilePolicy {
    private static final long LOOKBACK_MS=6L*60*60*1000;
    private SmsReconcilePolicy(){}
    static long cutoff(long nowMillis){return Math.max(0L,nowMillis-LOOKBACK_MS);}
    static boolean restart(long cursorDate,long cutoffMillis){return cursorDate<cutoffMillis;}
    static boolean passComplete(int scanned,int pageSize){return scanned<pageSize;}
}
