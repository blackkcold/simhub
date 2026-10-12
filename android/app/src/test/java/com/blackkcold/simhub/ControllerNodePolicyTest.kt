package com.blackkcold.simhub

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ControllerNodePolicyTest {
    private fun node(mode:String, permission:Boolean=true, override:Boolean=false,
                     reported:Boolean=false, online:Boolean=true):JSONObject =
        JSONObject().put("id","node-1").put("nodeType","android").put("online",online)
            .put("state",JSONObject().put("smsMode",mode)
                .put("smsSendPermission",permission)
                .put("smsSendAllowed",reported)
                .put("smsDeveloperOverride",override)
                .put("channels",JSONArray().put(JSONObject().put("id","sim-1").put("revision",1))))

    @Test fun companionIsReadOnlyByDefault() {
        assertFalse(ControllerNodePolicy.canSend(node("companion",reported=false)))
        assertFalse(ControllerNodePolicy.canSend(node("companion",reported=true,override=false)))
        assertFalse(ControllerNodePolicy.canSend(node("companion",permission=false,override=true,reported=true)))
    }
    @Test fun developerOverrideNeedsLiveCapabilityAndPermission() {
        val test=node("companion",permission=true,override=true,reported=true)
        assertTrue(ControllerNodePolicy.canSend(test))
        assertTrue(ControllerNodePolicy.isDeveloperSend(test))
        assertFalse(ControllerNodePolicy.canSend(node("companion",override=true,reported=false)))
    }
    @Test fun fullSmsRoleStillRequiresSendPermission() {
        assertTrue(ControllerNodePolicy.canSend(node("default",permission=true,reported=true)))
        assertFalse(ControllerNodePolicy.canSend(node("default",permission=false,reported=true)))
        assertFalse(ControllerNodePolicy.canSend(node("default",permission=true,reported=false)))
    }
    @Test fun offlineRevokedAndUnknownNodesCannotSend() {
        assertFalse(ControllerNodePolicy.canSend(node("default",reported=true,online=false)))
        assertFalse(ControllerNodePolicy.canSend(node("default",reported=true).put("revoked",true)))
        assertFalse(ControllerNodePolicy.canSend(node("unknown",reported=true)))
    }
    @Test fun modemUsesIndependentCapability() {
        val modem=node("companion",reported=false).put("nodeType","modem")
        assertTrue(ControllerNodePolicy.canSend(modem))
        modem.getJSONObject("state").put("smsOperational",false)
        assertFalse(ControllerNodePolicy.canSend(modem))
    }
}
