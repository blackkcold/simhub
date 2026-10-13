package com.blackkcold.simhub;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class EnrollmentTelemetryKeysTest {
    @Test public void resetClearsStaleRelayErrorAndSynchronizationTimestamp(){
        Set<String> keys=new HashSet<>(Arrays.asList(EnrollmentTelemetryKeys.forReset()));
        for(String key:new String[]{
                "last_sync_error","last_sync_success_at","last_command_fetch_error",
                "last_command_fetch_at","last_upload_error","last_upload_at",
                "last_upload_count","last_upload_attempt_at","last_queue_error",
                "last_reconcile_at","last_reconcile_count","sms_scanned_generation"}){
            assertTrue("Enrollment reset must remove "+key,keys.contains(key));
        }
        assertEquals("Reset keys must be unique",keys.size(),EnrollmentTelemetryKeys.forReset().length);
    }

    @Test public void resetDoesNotErasePhoneHardwareStateOrUserPreferences(){
        Set<String> keys=new HashSet<>(Arrays.asList(EnrollmentTelemetryKeys.forReset()));
        for(String localOnly:new String[]{"last_sms_received_at","sms_change_generation",
                "energy_mode","language","auto_check_updates","remote_ota_allowed"}){
            assertFalse("Must retain device-wide preference "+localOnly,keys.contains(localOnly));
        }
    }

    @Test public void returnedKeyArrayCannotMutatePolicy(){
        String[] first=EnrollmentTelemetryKeys.forReset();
        first[0]="different";
        assertEquals("last_sync_error",EnrollmentTelemetryKeys.forReset()[0]);
    }
}
