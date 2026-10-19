package com.blackkcold.simhub.p2p;

import java.util.Base64;
import java.util.regex.Pattern;

/** Pure, independently testable wire validator for short-lived TURN REST ICE. */
public final class TurnIceCredentialPolicy {
    private static final Pattern TURN_URI = Pattern.compile(
            "^turns?:[A-Za-z0-9][A-Za-z0-9.-]{0,251}(?::[0-9]{1,5})?(?:\\?transport=(?:udp|tcp))?$");
    private static final Pattern USERNAME = Pattern.compile("^[0-9]{10,12}:[0-9a-f]{32}$");

    public static boolean allowsUrl(String url) {
        return url != null && url.length() <= 280 && TURN_URI.matcher(url).matches();
    }

    public static boolean allowsCredentials(String username, String credential, long now) {
        if (username == null || credential == null || !USERNAME.matcher(username).matches()
                || credential.length() > 128) return false;
        try {
            long expires = Long.parseLong(username.substring(0, username.indexOf(':')));
            if (expires <= now || expires > now + 900) return false;
            // The legacy coturn REST MAC is 20 bytes. No plaintext passwords or long-lived tokens.
            return Base64.getDecoder().decode(credential).length == 20;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private TurnIceCredentialPolicy() {}
}
