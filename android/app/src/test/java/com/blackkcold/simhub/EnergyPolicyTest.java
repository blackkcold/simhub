package com.blackkcold.simhub;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

/** Pure scheduling contracts, independent of OEM process and Doze behavior. */
public class EnergyPolicyTest {
    @Test public void intervalsAreExplicitAndBounded() {
        assertEquals(15_000L,EnergyPolicy.commandIntervalMs(EnergyPolicy.REALTIME));
        assertEquals(120_000L,EnergyPolicy.commandIntervalMs(EnergyPolicy.BALANCED));
        assertEquals(840_000L,EnergyPolicy.commandIntervalMs(EnergyPolicy.ECO));
    }
    @Test public void balancedIsAdaptiveButEcoAndRealtimeRemainExplicit() {
        assertEquals(45_000L,EnergyPolicy.commandIntervalMs(EnergyPolicy.BALANCED,true));
        assertEquals(120_000L,EnergyPolicy.commandIntervalMs(EnergyPolicy.BALANCED,false));
        assertEquals(840_000L,EnergyPolicy.commandIntervalMs(EnergyPolicy.ECO,true));
        assertEquals(15_000L,EnergyPolicy.commandIntervalMs(EnergyPolicy.REALTIME,false));
    }
    @Test public void unknownModeFailsSafeToBalancedTransport() {
        assertEquals(120_000L,EnergyPolicy.commandIntervalMs("unknown"));
        assertEquals(900_000L,EnergyPolicy.maintenanceIntervalMs());
        assertEquals(1_800_000L,EnergyPolicy.reconciliationIntervalMs());
    }
}
