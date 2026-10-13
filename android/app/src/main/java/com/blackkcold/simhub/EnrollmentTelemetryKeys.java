package com.blackkcold.simhub;

/** Relay enrollment-scoped metrics; never remove system SMS or device-wide settings. */
public final class EnrollmentTelemetryKeys {
    private static final String[] KEYS = {
        "last_sync_error","last_sync_success_at",
        "last_command_fetch_error","last_command_fetch_at","last_command_fetch_count",
        "last_upload_error","last_upload_at","last_upload_count",
        "last_upload_attempt_at","uploaded_total","last_queue_error",
        "last_reconcile_at","last_reconcile_count",
        "queue_failures","last_queue_failure_at","sms_provider_error",
        "sms_scanned_generation"
    };

    public static String[] forReset() { return KEYS.clone(); }

    private EnrollmentTelemetryKeys() {}
}
