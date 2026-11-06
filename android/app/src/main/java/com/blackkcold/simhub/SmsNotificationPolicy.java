package com.blackkcold.simhub;

/** Companion mode leaves incoming-message notifications to the system's SMS handler. */
final class SmsNotificationPolicy {
    private SmsNotificationPolicy(){}
    static boolean shouldNotify(boolean defaultHandler,boolean notificationPermission){
        return defaultHandler&&notificationPermission;
    }
}
