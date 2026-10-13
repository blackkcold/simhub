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
    @Test public void versionReconciliation() {
        assertTrue(RemoteOtaPolicy.installedMatchesTarget(42,42,"installing"));
        assertTrue(RemoteOtaPolicy.installedMatchesTarget(43,42,"verification_pending"));
        assertFalse(RemoteOtaPolicy.installedMatchesTarget(41,42,"installing"));
        assertFalse(RemoteOtaPolicy.installedMatchesTarget(42,0,"installing"));
        assertFalse(RemoteOtaPolicy.installedMatchesTarget(42,42,"failed"));
        assertTrue(RemoteOtaPolicy.installationNeedsVerification("installing",100,1000));
        assertFalse(RemoteOtaPolicy.installationNeedsVerification("installing",100,200));
        assertFalse(RemoteOtaPolicy.installationNeedsVerification("awaiting_confirmation",100,1000));
    }
    @Test public void localConsentStillRequired() {
        assertFalse(RemoteOtaPolicy.canOffer(37,false,false));
        assertFalse(RemoteOtaPolicy.canOffer(35,true,false));
        assertTrue(RemoteOtaPolicy.canOffer(35,true,true));
    }
}
