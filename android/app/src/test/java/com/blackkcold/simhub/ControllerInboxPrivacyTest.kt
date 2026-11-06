package com.blackkcold.simhub

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ControllerInboxPrivacyTest {
    private fun node()=JSONObject().put("id","node").put("createdAt",1).put("smsEpoch",0)
        .put("smsPurgeAt",0).put("revoked",false).put("resetRequestedAt",0).put("lifecycleStatus","active")
    private fun fill(inbox:ControllerInboxState){
        assertTrue(inbox.accept(JSONObject().put("v",1).put("type","snapshot")
            .put("hasMore",true).put("hasNewer",true).put("messages",JSONArray().put(
                JSONObject().put("id","old-sms").put("body","synthetic retained plaintext"))).toString()))
    }
    @Test fun everyPrivacyRevisionClearsLoadedOlderRowsAndCursors(){
        for(change in listOf<(JSONObject)->Unit>(
            {it.put("smsEpoch",1)},{it.put("smsPurgeAt",100)},{it.put("createdAt",2)},
            {it.put("revoked",true)},{it.put("resetRequestedAt",100)},{it.put("lifecycleStatus","reset_pending")}
        )){
            val inbox=ControllerInboxState();val privacy=ControllerInboxPrivacy(inbox)
            assertFalse(privacy.observe(listOf(node())));fill(inbox)
            val changed=node().also(change)
            assertTrue(privacy.observe(listOf(changed)))
            assertTrue(inbox.messages.isEmpty());assertFalse(inbox.unlocked)
            assertFalse(inbox.hasMore);assertFalse(inbox.hasNewer)
        }
    }
    @Test fun telemetryAndDisplayChangesPreserveTheLoadedWindow(){
        val inbox=ControllerInboxState();val privacy=ControllerInboxPrivacy(inbox)
        privacy.observe(listOf(node()));fill(inbox)
        val changed=node().put("online",false).put("name","New display name").put("lastSeenAt",100)
            .put("state",JSONObject().put("batteryPct",31))
        assertFalse(privacy.observe(listOf(changed)))
        assertEquals(1,inbox.messages.size);assertTrue(inbox.hasMore);assertTrue(inbox.hasNewer)
    }
    @Test fun removingAnEnrollmentClearsItsPlaintext(){
        val inbox=ControllerInboxState();val privacy=ControllerInboxPrivacy(inbox)
        privacy.observe(listOf(node()));fill(inbox)
        assertTrue(privacy.observe(emptyList()));assertTrue(inbox.messages.isEmpty())
        privacy.clear();assertFalse(privacy.observe(listOf(node())))
    }
}
