package com.blackkcold.simhub

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ControllerSendCoordinatorTest {
    private class MemoryStore:ControllerSendStorage{
        override val lock=Any()
        var saved="{}"
        var rejectWrites=false
        override fun read()=JSONObject(saved)
        override fun write(value:JSONObject){if(rejectWrites)throw java.io.IOException("synthetic disk failure");saved=value.toString()}
    }
    private class Relay:ControllerSendTransport{
        val posts=mutableListOf<String>()
        var found:JSONObject?=null
        var loseResponse=false
        var lookups=0
        override fun encrypt(device:JSONObject,id:String,created:Long,expires:Long,payload:JSONObject)=
            JSONObject().put("v",2).put("iv","fixed-encrypted-envelope").put("ct",payload.toString())
        override fun status(deviceId:String,id:String):JSONObject?{lookups++;return found}
        override fun elevate(credential:String,totp:Boolean){}
        override fun post(deviceId:String,request:JSONObject):JSONObject{
            posts.add(request.toString())
            if(loseResponse)throw java.io.IOException("synthetic lost response")
            return JSONObject().put("commandId",request.getString("commandId")).put("state","queued")
        }
    }
    private fun node(id:String="a",revision:Long=1,online:Boolean=true)=JSONObject().put("id",id).put("name","Node $id")
        .put("online",online).put("nodeType","modem").put("state",JSONObject().put("smsOperational",true)
            .put("channels",JSONArray().put(JSONObject().put("id","sim-$id").put("revision",revision))))
    private fun draft()=ControllerSendDraft("a","sim-a",1,"+123456789","synthetic message")
    @Test fun lostResponseRecoversOriginalAcceptedCommandWithoutSecondPost(){
        val storage=MemoryStore();val relay=Relay();var now=1000L
        val first=ControllerSendCoordinator(storage,relay){now}
        val intent=first.prepare(listOf(node()),draft());val id=intent.getString("id")
        relay.loseResponse=true;assertEquals("uncertain",first.submit(id).getString("phase"))
        relay.found=JSONObject().put("commandId",id).put("deviceId","a").put("type","sms.send").put("state","submitted")
        val resumed=ControllerSendCoordinator(storage,relay){now}
        assertEquals(id,resumed.activeId());assertEquals(draft(),resumed.draft())
        assertEquals("submitted",resumed.submit(id).getString("state"));assertEquals(1,relay.posts.size)
    }
    @Test fun retryMissingUnexpiredIntentUsesByteIdenticalEnvelope(){
        val storage=MemoryStore();val relay=Relay();val coordinator=ControllerSendCoordinator(storage,relay){1000L}
        val id=coordinator.prepare(listOf(node()),draft()).getString("id")
        relay.loseResponse=true;coordinator.submit(id)
        relay.loseResponse=false;coordinator.submit(id)
        assertEquals(2,relay.posts.size);assertEquals(relay.posts[0],relay.posts[1]);assertEquals(1,relay.lookups)
    }
    @Test fun observedReceiptNeverReplaysEvenWhenHistoryWasDeleted(){
        val relay=Relay();val coordinator=ControllerSendCoordinator(MemoryStore(),relay){1000L}
        val id=coordinator.prepare(listOf(node()),draft()).getString("id")
        coordinator.submit(id);relay.found=null
        val result=coordinator.submit(id)
        assertEquals(1,relay.posts.size);assertTrue(result.getBoolean("observed"))
        assertEquals("receipt_unavailable",result.getString("error"))
    }
    @Test fun expiredUncertainIntentAndRefreshOnlyNeverPost(){
        val relay=Relay();var now=1000L;val coordinator=ControllerSendCoordinator(MemoryStore(),relay){now}
        val intent=coordinator.prepare(listOf(node()),draft());val id=intent.getString("id")
        coordinator.refresh(id);assertEquals(0,relay.posts.size)
        relay.loseResponse=true;coordinator.submit(id);now=intent.getLong("expiresAt")
        assertEquals("original_request_expired",coordinator.submit(id).getString("error"));assertEquals(1,relay.posts.size)
    }
    @Test fun failedJournalWriteCannotCauseNetworkSideEffect(){
        val storage=MemoryStore();val relay=Relay();val coordinator=ControllerSendCoordinator(storage,relay){1000L}
        val id=coordinator.prepare(listOf(node()),draft()).getString("id")
        storage.rejectWrites=true
        try{coordinator.submit(id);fail("Journal failure was ignored")}catch(_:java.io.IOException){}
        assertTrue(relay.posts.isEmpty())
    }
    @Test fun missingTargetAndChangedRevisionCannotSelectAnotherSim(){
        assertNotNull(ControllerSendTarget.resolve(listOf(node("b"),node("a")),draft()))
        assertNull(ControllerSendTarget.resolve(listOf(node("a",online=false),node("b")),draft()))
        assertNull(ControllerSendTarget.resolve(listOf(node("a",revision=2),node("b")),draft()))
    }
    @Test fun frozenIntentRequiresExplicitNewDraftAndLateDraftSaveCannotChangeIt(){
        val storage=MemoryStore();val relay=Relay();val coordinator=ControllerSendCoordinator(storage,relay){1000L}
        val id=coordinator.prepare(listOf(node()),draft()).getString("id")
        coordinator.saveDraft(draft().copy(message="changed"))
        assertEquals(draft(),coordinator.draft());assertEquals(id,coordinator.activeId())
        try{coordinator.prepare(listOf(node()),draft());fail("A frozen send was duplicated")}catch(_:IllegalArgumentException){}
        coordinator.startDraft();assertEquals("",coordinator.activeId());assertNotNull(coordinator.find(id))
    }
}
