package com.blackkcold.simhub;

/** Pure TP-Status classification; Android PDU parsing stays in the receiver. */
public final class SmsDeliveryPolicy {
    public enum Result { DELIVERED, PENDING, FAILED, UNKNOWN }
    private SmsDeliveryPolicy(){}

    public static Result classify(String format,int status){
        if(status==0)return Result.DELIVERED;
        // GSM 03.40 / 3GPP TS 23.040 TP-Status distinguishes retryable
        // service-centre errors from permanent delivery errors.
        if("3gpp".equals(format)){
            if(status>=0x20&&status<=0x3f)return Result.PENDING;
            if(status>=0x40&&status<=0x7f)return Result.FAILED;
        }
        // Nonzero CDMA status is encoded in shifted class/cause fields; a
        // nonzero or ambiguous value cannot be assumed to mean delivered.
        return Result.UNKNOWN;
    }
}
