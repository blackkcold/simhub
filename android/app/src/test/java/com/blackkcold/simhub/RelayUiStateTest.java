package com.blackkcold.simhub;

import org.junit.Test;
import static org.junit.Assert.*;

public class RelayUiStateTest {
    @Test public void unpairedNeverDisplaysStaleTransportHealth(){
        RelayUiState.Phase p=RelayUiState.phase(false,false,false);
        assertEquals(RelayUiState.Phase.UNPAIRED,p);
        assertFalse(RelayUiState.showTransportDiagnostics(p));
        assertFalse(RelayUiState.canRefreshRelay(p));
    }

    @Test public void onlyActiveEnrollmentDisplaysTransportHealth(){
        RelayUiState.Phase p=RelayUiState.phase(true,false,false);
        assertEquals(RelayUiState.Phase.ACTIVE,p);
        assertTrue(RelayUiState.showTransportDiagnostics(p));
        assertTrue(RelayUiState.canRefreshRelay(p));
    }

    @Test public void resetPendingOverridesAnyApparentEnrollment(){
        for(boolean enrolled:new boolean[]{false,true}){
            RelayUiState.Phase p=RelayUiState.phase(enrolled,true,false);
            assertEquals(RelayUiState.Phase.RESET_PENDING,p);
            assertFalse(RelayUiState.showTransportDiagnostics(p));
            assertFalse(RelayUiState.canRefreshRelay(p));
        }
    }

    @Test public void recoveryCannotPretendToBeOnline(){
        RelayUiState.Phase p=RelayUiState.phase(true,true,true);
        assertEquals(RelayUiState.Phase.RECOVERY,p);
        assertFalse(RelayUiState.showTransportDiagnostics(p));
        assertFalse(RelayUiState.canRefreshRelay(p));
    }
}
