package com.blackkcold.simhub;
import static org.junit.Assert.*;
import org.junit.Test;
public class RemoteOtaPolicyTest {
    @Test public void platformGate() {
        assertFalse(RemoteOtaPolicy.supported(35,false));
        assertFalse(RemoteOtaPolicy.supported(29,false));
        assertTrue(RemoteOtaPolicy.supported(36,false));
        assertTrue(RemoteOtaPolicy.supported(37,false));
        assertTrue(RemoteOtaPolicy.supported(38,false));
        assertTrue(RemoteOtaPolicy.supported(35,true));
    }
    @Test public void localConsentStillRequired() {
        assertFalse(RemoteOtaPolicy.canOffer(37,false,false));
        assertFalse(RemoteOtaPolicy.canOffer(35,true,false));
        assertTrue(RemoteOtaPolicy.canOffer(35,true,true));
    }
}
