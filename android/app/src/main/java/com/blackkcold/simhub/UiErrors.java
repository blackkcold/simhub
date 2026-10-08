package com.blackkcold.simhub;

import android.content.Context;

public final class UiErrors {
    public static String message(Context c,Throwable error){
        if(error==null)return c.getString(R.string.generic_error);
        String m=String.valueOf(error.getMessage());
        return switch(m){
            case "This node is already enrolled. Reset enrollment before pairing it to another vault or relay." -> c.getString(R.string.err_already_enrolled);
            case "Invalid enrollment link" -> c.getString(R.string.err_invalid_enrollment);
            case "Enrollment link missing fields" -> c.getString(R.string.err_enrollment_missing);
            case "Relay must use HTTPS", "SIM Hub server must use HTTPS" -> c.getString(R.string.err_https_required);
            case "Enrollment bootstrap secret missing" -> c.getString(R.string.err_bootstrap_missing);
            case "Enrollment bootstrap secret length invalid", "Relay bootstrap envelope missing", "Legacy enrollment key missing", "Enrollment key length invalid" -> c.getString(R.string.err_bootstrap_invalid);
            case "Node key id mismatch" -> c.getString(R.string.err_key_mismatch);
            case "Invalid destination" -> c.getString(R.string.err_invalid_destination);
            case "Invalid SMS body" -> c.getString(R.string.err_invalid_body);
            case "Unable to write SMS provider" -> c.getString(R.string.err_sms_provider);
            case "Not enrolled" -> c.getString(R.string.err_not_enrolled);
            default -> (m==null||m.isBlank()||"null".equals(m))?c.getString(R.string.generic_error):m;
        };
    }
    private UiErrors(){}
}