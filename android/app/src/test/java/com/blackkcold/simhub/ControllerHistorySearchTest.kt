package com.blackkcold.simhub

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ControllerHistorySearchTest {
    private fun node(epoch:Int=0)=JSONObject().put("id","device-a").put("name","Kitchen")
        .put("createdAt",10).put("smsEpoch",epoch).put("smsPurgeAt",0)
        .put("keyId","key-a").put("lifecycleStatus","active")
    private fun event(sequence:Int)=JSONObject().put("seq",sequence).put("eventId","sms-$sequence")
        .put("deviceId","device-a").put("kind","sms.received").put("occurredAt",1000+sequence)
    private fun plaintext(event:JSONObject)=JSONObject().put("sender","12345")
        .put("body","private match ${event.getInt("seq")}").put("channelId","sim-a").put("channelRevision",2)
    private class Source(val rows:List<JSONObject>,val watermark:Int=rows.size){
        val paths=mutableListOf<String>()
        var epoch=0
        var purgeAfterPage=false
        var repeatedCursor=false
        val pages get()=paths.count{it.contains("?since=")}
        suspend fun get(path:String):JSONObject {
            paths+=path
            if(path=="/api/v1/devices")return JSONObject().put("devices",JSONArray().put(
                JSONObject().put("id","device-a").put("name","Kitchen").put("createdAt",10)
                    .put("smsEpoch",epoch).put("smsPurgeAt",0).put("keyId","key-a").put("lifecycleStatus","active")))
            if(path.contains("latest=1"))return JSONObject().put("nextSince",watermark)
            val since=path.substringAfter("since=").substringBefore('&').toLong()
            val limit=path.substringAfter("limit=").toInt()
            val page=if(repeatedCursor)rows.take(1) else rows.filter{it.getLong("seq")>since}.take(limit)
            if(purgeAfterPage)epoch+=1
            return JSONObject().put("events",JSONArray(page))
        }
    }
    @Test fun scansBeyondLoadedInboxWindowAndCountsAllMatchesWithBoundedMemory()=runBlocking {
        val source=Source((1..1305).map(::event))
        val states=mutableListOf<ControllerSearchProgress>()
        val result=ControllerHistoryScanner(source::get,{row,_->plaintext(row)},resultLimit=3)
            .scan(ControllerSearchFilter("private match")){states+=it}
        assertTrue(result.complete)
        assertEquals(1305L,result.scanned)
        assertEquals(1305L,result.matched)
        assertEquals(listOf("device-a:sms-1305","device-a:sms-1304","device-a:sms-1303"),result.results.map{it.id})
        assertEquals(27,source.pages)
        assertTrue(states.dropLast(1).none{it.complete})
        assertTrue(states.all{it.results.size<=3})
        assertTrue(source.paths.none{it.contains("private")||it.contains("match")})
    }
    @Test fun frozenWatermarkExcludesNewConcurrentUploads()=runBlocking {
        val source=Source((1..6).map(::event),watermark=5)
        val result=ControllerHistoryScanner(source::get,{row,_->plaintext(row)},pageSize=3)
            .scan(ControllerSearchFilter("")){}
        assertTrue(result.complete)
        assertEquals(5L,result.scanned)
        assertEquals(5L,result.cursor)
        assertFalse(result.results.any{it.id.endsWith("sms-6")})
    }
    @Test fun cancellationNeverFetchesOrPublishesAnotherPage()=runBlocking {
        val source=Source((1..100).map(::event))
        var observed=ControllerSearchProgress()
        try{
            ControllerHistoryScanner(source::get,{row,_->plaintext(row)})
                .scan(ControllerSearchFilter("private")){state->
                    observed=state
                    if(state.scanned>0)throw CancellationException("synthetic cancellation")
                }
            fail("Cancellation must terminate the scan")
        }catch(_:CancellationException){}
        assertEquals(1,source.pages)
        assertEquals(50L,observed.scanned)
        assertFalse(observed.complete)
    }
    @Test fun purgeFenceRejectsPlaintextBeforePagePublication()=runBlocking {
        val source=Source(listOf(event(1))).apply{purgeAfterPage=true}
        val states=mutableListOf<ControllerSearchProgress>()
        try{
            ControllerHistoryScanner(source::get,{row,_->plaintext(row)})
                .scan(ControllerSearchFilter("")){states+=it}
            fail("Changed purge epoch must invalidate the scan")
        }catch(_:ControllerSearchScopeChanged){}
        assertTrue(states.all{it.results.isEmpty()&&!it.complete})
    }
    @Test fun repeatedSequenceFailsWithoutClaimingFullCoverage()=runBlocking {
        val source=Source(listOf(event(1)),watermark=2).apply{repeatedCursor=true}
        var observed=ControllerSearchProgress()
        try{
            ControllerHistoryScanner(source::get,{row,_->plaintext(row)})
                .scan(ControllerSearchFilter("")){observed=it}
            fail("Repeated cursor must fail")
        }catch(_:IllegalArgumentException){}
        assertEquals(1L,observed.scanned)
        assertFalse(observed.complete)
    }
    @Test fun unreadableRowsRemainExplicitCoverageGaps()=runBlocking {
        val source=Source((1..3).map(::event))
        val result=ControllerHistoryScanner(source::get,{row,_->
            if(row.getInt("seq")==2)throw SecurityException("synthetic missing key")
            plaintext(row)
        }).scan(ControllerSearchFilter("")){}
        assertTrue(result.complete)
        assertEquals(3L,result.scanned)
        assertEquals(2L,result.matched)
        assertEquals(1L,result.unreadable)
    }
    @Test fun completePlaintextIsMatchedBeforeDisplayCapAndSourceIdentityRemainsStable(){
        val plain=plaintext(event(1)).put("body","x".repeat(4100)+"rare phrase")
            .put("otp",JSONObject().put("value","123456"))
        val message=ControllerSearchMessage.decode(event(1),node(),plain)
        assertEquals(4000,message.body.length)
        assertTrue(message.bodyTruncated)
        assertTrue(ControllerSearchMessage.matches(ControllerSearchFilter("rare phrase",true,"device-a|sim-a|2"),message,plain))
        assertFalse(ControllerSearchMessage.matches(ControllerSearchFilter("rare phrase",true,"device-a|sim-a|3"),message,plain))
        assertEquals("device-a",message.deviceId)
        assertEquals("sim-a",message.channelId)
        assertEquals(2L,message.channelRevision)
    }
    @Test fun lifecycleOrEnrollmentChangeInvalidatesScopeButRoutineTelemetryDoesNot(){
        val original=node()
        val first=ControllerHistoryScope.fingerprint(listOf(original))
        original.put("online",false).put("name","Renamed")
        assertEquals(first,ControllerHistoryScope.fingerprint(listOf(original)))
        original.put("revoked",true)
        assertNotEquals(first,ControllerHistoryScope.fingerprint(listOf(original)))
        assertNotEquals(first,ControllerHistoryScope.fingerprint(listOf(node().put("createdAt",20))))
    }
}
