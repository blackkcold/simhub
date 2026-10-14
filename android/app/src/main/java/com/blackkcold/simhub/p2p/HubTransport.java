package com.blackkcold.simhub.p2p;

import org.json.JSONObject;

/** Transport sees Peer keys only; business dispatch is route independent. */
public interface HubTransport {
    JSONObject request(JSONObject peer,String requestId,JSONObject request)throws Exception;
    String route();
}
