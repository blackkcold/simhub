package com.blackkcold.simhub;

import org.junit.Test;
import static org.junit.Assert.*;

public class CommandStatePolicyTest {
    @Test public void completedReceiptsCannotBeOverwrittenByLateSubmission(){
        assertTrue(CommandStatePolicy.accepts("claimed","submitted"));
        assertTrue(CommandStatePolicy.accepts("claimed","sent"));
        assertTrue(CommandStatePolicy.accepts("claimed","delivered"));
        assertTrue(CommandStatePolicy.accepts("submitted","failed"));
        assertTrue(CommandStatePolicy.accepts("sent","delivered"));
        assertTrue(CommandStatePolicy.accepts("submitted","outcome_unknown"));
        assertTrue(CommandStatePolicy.accepts("outcome_unknown","sent"));
        assertFalse(CommandStatePolicy.accepts("outcome_unknown","submitted"));
        assertFalse(CommandStatePolicy.accepts("sent","submitted"));
        assertFalse(CommandStatePolicy.accepts("failed","submitted"));
        assertFalse(CommandStatePolicy.accepts("delivered","sent"));
        assertFalse(CommandStatePolicy.accepts(null,"sent"));
    }
}
