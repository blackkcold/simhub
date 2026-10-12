package com.blackkcold.simhub;

import org.junit.Test;
import static org.junit.Assert.*;

/** No SMS/Vault material may survive a native lock. */
public class ControllerInboxStateTest {
    @Test public void validSnapshotIsVisibleAndLockWipesNativeMemory() {
        ControllerInboxState state=new ControllerInboxState();
        state.accept("{\"type\":\"snapshot\",\"v\":1,\"messages\":[{\"id\":\"dev:one\","
            +"\"thread\":\"13800138000\",\"sender\":\"13800138000\","
            +"\"body\":\"verification 123456\",\"timestamp\":1789000000,"
            +"\"inbound\":true,\"device\":\"Node One\",\"sim\":\"SIM A\","
            +"\"otp\":\"123456\",\"failed\":false}],\"hasMore\":true}");
        assertTrue(state.getUnlocked());
        assertEquals(1,state.getMessages().size());
        assertEquals("123456",state.getMessages().get(0).getOtp());
        assertTrue(state.getHasMore());
        state.accept("{\"type\":\"locked\"}");
        assertFalse(state.getUnlocked());
        assertEquals(0,state.getMessages().size());
        assertFalse(state.getHasMore());
    }
    @Test public void olderPagesMergeWithoutRemovingRecentMessages() {
        ControllerInboxState state=new ControllerInboxState();
        state.accept("{\"type\":\"snapshot\",\"v\":1,\"messages\":[{\"id\":\"new\",\"timestamp\":200,\"body\":\"recent\"}],\"hasMore\":true}");
        state.accept("{\"type\":\"snapshot\",\"v\":1,\"messages\":[{\"id\":\"old\",\"timestamp\":100,\"body\":\"older\"}],\"hasMore\":false}",true);
        assertEquals(2,state.getMessages().size());
        assertEquals("new",state.getMessages().get(0).getId());
        assertFalse(state.getHasMore());
        state.accept("{\"type\":\"snapshot\",\"v\":1,\"messages\":[{\"id\":\"old\",\"timestamp\":100,\"body\":\"older\"}],\"hasMore\":false}",true);
        assertEquals(2,state.getMessages().size());
    }
    @Test public void unrecognizedOrOversizedMessagesDoNotUnlock() {
        ControllerInboxState state=new ControllerInboxState();
        state.accept("{\"type\":\"snapshot\",\"v\":2,\"messages\":[]}");
        assertFalse(state.getUnlocked());
        state.accept("{\"type\":\"snapshot\",\"v\":1,\"messages\":{}}");
        assertFalse(state.getUnlocked());
        state.accept("x".repeat(450001));
        assertFalse(state.getUnlocked());
    }
}
