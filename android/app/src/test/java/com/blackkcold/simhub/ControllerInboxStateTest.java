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
    @Test public void contactLabelsCannotOverrideReplyAddress() {
        ControllerInboxState state=new ControllerInboxState();
        assertTrue(state.accept("{\"type\":\"snapshot\",\"v\":1,\"messages\":[{\"id\":\"m1\","
            +"\"sender\":\"Alice Chen\",\"address\":\"+8613800138000\",\"timestamp\":123}]}"));
        assertEquals("Alice Chen",state.getMessages().get(0).getSender());
        assertEquals("+8613800138000",state.getMessages().get(0).getAddress());
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
    @Test public void batchAckRejectedWhenOversizedAndNeverMutatesExistingMessages() throws Exception {
        ControllerInboxState state=new ControllerInboxState();
        assertTrue(state.accept("{\"type\":\"snapshot\",\"v\":1,\"messages\":[{\"id\":\"one\",\"timestamp\":100}]}"));
        assertFalse(state.accept("x".repeat(450001)));
        assertEquals(1,state.getMessages().size());
        org.json.JSONArray rows=new org.json.JSONArray();
        for(int i=0;i<261;i++)rows.put(new org.json.JSONObject().put("id","id"+i));
        String oversized=new org.json.JSONObject().put("type","snapshot").put("v",1)
            .put("messages",rows).toString();
        assertFalse(state.accept(oversized));
        assertEquals("one",state.getMessages().get(0).getId());
    }
    @Test public void deepHistoryWindowRetainsOlderMessagesAndCanNavigateToLatest() throws Exception {
        ControllerInboxState state=new ControllerInboxState();
        for(int batch=0;batch<14;batch++) {
            org.json.JSONArray rows=new org.json.JSONArray();
            for(int i=0;i<100;i++){
                int t=1400-(batch*100+i);
                rows.put(new org.json.JSONObject().put("id","sms"+t).put("timestamp",t));
            }
            boolean older=batch>0;
            assertTrue(state.accept(new org.json.JSONObject().put("type","snapshot").put("v",1)
                .put("messages",rows).put("hasMore",batch<13).put("hasNewer",older)
                .toString(),older,older));
        }
        assertEquals(1200,state.getMessages().size());
        assertEquals("sms1",state.getMessages().get(1199).getId());
        assertTrue(state.getHasNewer());
        org.json.JSONArray latest=new org.json.JSONArray().put(
            new org.json.JSONObject().put("id","fresh").put("timestamp",1500));
        assertTrue(state.accept(new org.json.JSONObject().put("type","snapshot").put("v",1)
            .put("messages",latest).put("hasNewer",false).toString()));
        assertEquals(1,state.getMessages().size());
        assertEquals("fresh",state.getMessages().get(0).getId());
    }
    @Test public void conversationsNeverMergeAcrossDevicesOrSimRevision() {
        String p=ControllerConversation.INSTANCE.key("node1","simA",1,"+86 138-0013-8000");
        assertEquals(p,ControllerConversation.INSTANCE.key("node1","simA",1,"13800138000"));
        assertNotEquals(p,ControllerConversation.INSTANCE.key("node2","simA",1,"13800138000"));
        assertNotEquals(p,ControllerConversation.INSTANCE.key("node1","simA",2,"13800138000"));
    }

    @Test public void firstDeltaLoadMustInitializeHistoricalCursor() {
        assertTrue(ControllerPagingPolicy.INSTANCE.isInitialPage("delta",0));
        assertTrue(ControllerPagingPolicy.INSTANCE.isInitialPage("latest",100));
        assertFalse(ControllerPagingPolicy.INSTANCE.isInitialPage("delta",50));
        assertFalse(ControllerPagingPolicy.INSTANCE.isInitialPage("older",0));
        assertFalse(ControllerPagingPolicy.INSTANCE.isInitialPage("older",50));
    }
    @Test public void sourceLabelsNeverExposeOpaqueChannelIdentifiers() throws Exception {
        org.json.JSONObject channel=new org.json.JSONObject()
            .put("id","c1bb1442-37fa-49e6-9fd8-b54f71298301")
            .put("revision",4).put("alias","工作卡");
        org.json.JSONObject node=new org.json.JSONObject().put("state",
            new org.json.JSONObject().put("channels",new org.json.JSONArray().put(channel)));
        assertEquals("工作卡",ControllerSourceLabel.INSTANCE.name(node,
            "c1bb1442-37fa-49e6-9fd8-b54f71298301",4,""));
        assertEquals("历史 SIM",ControllerSourceLabel.INSTANCE.name(node,
            "d312c45b-20aa-4bc8-bd2e-2e8ea7ab9c02",5,
            "d312c45b-20aa-4bc8-bd2e-2e8ea7ab9c02"));
        assertEquals("历史 SIM · 来源待确认",ControllerSourceLabel.INSTANCE.name(node,"",0,""));
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
