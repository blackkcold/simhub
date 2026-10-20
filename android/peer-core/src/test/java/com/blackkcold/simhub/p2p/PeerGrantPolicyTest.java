package com.blackkcold.simhub.p2p;

import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;

public final class PeerGrantPolicyTest {
    private static JSONObject peer(String role,int version,String to,String from)throws Exception{
        return new JSONObject().put("pairState","ACTIVE").put("localRole",role)
            .put("protocolVersion",version).put("permissions",PeerGrantPolicy.READ)
            .put("grantsToRemote",to).put("grantsFromRemote",from);
    }
    @Test public void v1KeepsStrictNodeControllerRoles()throws Exception {
        JSONObject node=peer("NODE",1,"","");
        JSONObject controller=peer("CONTROLLER",1,"","");
        assertTrue(PeerGrantPolicy.allows(node,"sms.page",true));
        assertFalse(PeerGrantPolicy.allows(node,"sms.page",false));
        assertTrue(PeerGrantPolicy.allows(controller,"sms.page",false));
        assertFalse(PeerGrantPolicy.allows(controller,"sms.page",true));
        assertFalse(PeerGrantPolicy.allows(node,"sms.send",true));
    }
    @Test public void v2IsDirectionalAndCannotDerivePermissionsFromTransport()throws Exception {
        JSONObject p=peer("CONTROLLER",2,PeerGrantPolicy.READ,"");
        assertTrue(PeerGrantPolicy.allows(p,"sms.page",true));
        assertFalse(PeerGrantPolicy.allows(p,"sms.page",false));
        assertFalse(PeerGrantPolicy.allows(p,"sms.send",true));
        p.put("grantsFromRemote",PeerGrantPolicy.READ_SEND);
        assertTrue(PeerGrantPolicy.allows(p,"sms.send",false));
        assertFalse(PeerGrantPolicy.allows(p,"sms.send",true));
        p.put("grantsToRemote",PeerGrantPolicy.READ_SEND);
        assertTrue(PeerGrantPolicy.allows(p,"sms.send",true));
        p.put("pairState","REVOKED");
        assertFalse(PeerGrantPolicy.allows(p,"sms.send",true));
    }
    @Test public void malformedOrUnknownGrantsAlwaysFailClosed()throws Exception {
        assertTrue(PeerGrantPolicy.valid(""));
        assertTrue(PeerGrantPolicy.valid(PeerGrantPolicy.READ));
        assertFalse(PeerGrantPolicy.valid("send"));
        assertFalse(PeerGrantPolicy.valid("read,history,state,send,admin"));
        JSONObject p=peer("NODE",2,"read,history,state,admin","");
        assertFalse(PeerGrantPolicy.allows(p,"sms.page",true));
        assertFalse(PeerGrantPolicy.allows(p,"peer.revoke",true));
    }
}
