package com.blackkcold.simhub

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ControllerPeerIntentTest {
    private class Journal:ControllerSendStorage{
        override val lock=Any()
        var value="{}"
        override fun read()=JSONObject(value)
        override fun write(value:JSONObject){this.value=value.toString()}
    }
    private class Peer:ControllerSendTransport{
        var selected="peer-original"
        var loseResponse=true
        var known:JSONObject?=null
        val posts=mutableListOf<String>()
        var lookups=0
        override fun encrypt(device:JSONObject,id:String,created:Long,expires:Long,payload:JSONObject):JSONObject=
            throw AssertionError("Peer route must not require a Relay Node Key")
        override fun status(deviceId:String,id:String):JSONObject?=throw AssertionError("Peer status must use its frozen route")
        override fun elevate(credential:String,totp:Boolean){throw AssertionError("Independent peer grant must not use Relay elevation")}
        override fun post(deviceId:String,request:JSONObject):JSONObject=throw AssertionError("Never switch a frozen peer intent to Relay SMS")
        override fun freeze(device:JSONObject,id:String,created:Long,expires:Long,payload:JSONObject)=
            JSONObject().put("transport","peer").put("peerId",selected).put("commandId",id)
                .put("expiresAt",created+120).put("peerPayload",JSONObject(payload.toString()).put("commandExpires",created+120))
        override fun requiresElevation(intent:JSONObject)=false
        override fun statusIntent(intent:JSONObject):JSONObject?{
            lookups++
            assertEquals("peer-original",intent.getJSONObject("request").getString("peerId"))
            return known
        }
        override fun postIntent(intent:JSONObject):JSONObject{
            posts+=intent.getJSONObject("request").toString()
            if(loseResponse)throw java.io.IOException("synthetic lost response")
            return JSONObject().put("commandId",intent.getString("id")).put("state","queued")
                .put("result",JSONObject().put("route","RELAY_LITE").put("relayAccepted",true))
        }
    }
    private fun node()=JSONObject().put("id","node").put("name","Node").put("online",true).put("nodeType","modem")
        .put("state",JSONObject().put("smsOperational",true).put("channels",JSONArray().put(JSONObject().put("id","sim").put("revision",1))))
    private fun draft()=ControllerSendDraft("node","sim",1,"12345","synthetic peer body")
    @Test fun staleSmsAndAmbiguousPeerReceiptsRemainOutcomeUnknown(){
        for(state in listOf("outcome_unknown","claimed","indeterminate","unknown"))
            assertEquals("outcome_unknown",ControllerPeerReceiptState.normalize(state))
        for(state in listOf("queued","submitted","sent","delivered","failed"))
            assertEquals(state,ControllerPeerReceiptState.normalize(state))
        assertNull(ControllerPeerReceiptState.normalize("unrecognized"))
    }
    @Test fun lostResponseAndProcessRecreationRetainPeerIdDeadlineAndPayload(){
        val store=Journal();val peer=Peer();var now=1000L
        val first=ControllerSendCoordinator(store,peer){now}
        val prepared=first.prepare(listOf(node()),draft());val id=prepared.getString("id")
        assertEquals(1120L,prepared.getLong("expiresAt"))
        first.submit(id,"unused Relay credential")
        peer.selected="newly-linked-peer";now=1020L;peer.loseResponse=false
        val resumed=ControllerSendCoordinator(store,peer){now}
        resumed.submit(id)
        assertEquals(1,peer.lookups)
        assertEquals(2,peer.posts.size)
        assertEquals(peer.posts[0],peer.posts[1])
        assertEquals("peer-original",resumed.find(id)!!.getJSONObject("request").getString("peerId"))
    }
    @Test fun observedOfflineCarrierAcceptanceNeverTriggersAnotherSend(){
        val peer=Peer().apply{loseResponse=false}
        val coordinator=ControllerSendCoordinator(Journal(),peer){1000L}
        val id=coordinator.prepare(listOf(node()),draft()).getString("id")
        coordinator.submit(id)
        val result=coordinator.submit(id)
        assertEquals(1,peer.posts.size)
        assertTrue(result.getBoolean("observed"))
        assertEquals("receipt_unavailable",result.getString("error"))
        assertTrue(result.getJSONObject("result").getBoolean("relayAccepted"))
    }
    @Test fun expiredPeerIntentIsOnlyReconciledAndUnrelatedRelayReceiptCannotReplaceIt(){
        val peer=Peer();var now=1000L;val coordinator=ControllerSendCoordinator(Journal(),peer){now}
        val id=coordinator.prepare(listOf(node()),draft()).getString("id")
        coordinator.submit(id)
        coordinator.observe(listOf(JSONObject().put("commandId",id).put("deviceId","node").put("type","sms.send").put("state","delivered")))
        assertFalse(coordinator.find(id)!!.optBoolean("observed"))
        now=1120L
        assertEquals("original_request_expired",coordinator.submit(id).getString("error"))
        assertEquals(1,peer.posts.size)
    }
}
