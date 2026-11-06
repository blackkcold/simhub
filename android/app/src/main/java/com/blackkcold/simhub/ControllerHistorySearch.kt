package com.blackkcold.simhub

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

data class ControllerSearchFilter(val query:String,val otpOnly:Boolean=false,val sourceId:String="")
internal data class ControllerSearchProgress(
    val scanned:Long=0,val matched:Long=0,val unreadable:Long=0,
    val results:List<ControllerSms> = emptyList(),val oldestAt:Long=0,val newestAt:Long=0,
    val watermark:Long=0,val cursor:Long=0,val complete:Boolean=false,val scopeFingerprint:String=""
)
internal class ControllerSearchScopeChanged:SecurityException("History scope changed; start a fresh search")

/** Identity/purge fences exclude mutable names, online state and routine telemetry. */
internal object ControllerHistoryScope {
    fun fingerprint(devices:List<JSONObject>):String = devices.sortedBy{it.getString("id")}.joinToString("\n"){node->
        require(node.optString("id").isNotBlank()&&node.has("smsEpoch")&&node.has("smsPurgeAt")&&node.has("createdAt")){
            "Relay does not report history privacy revisions"
        }
        JSONArray().put(node.getString("id")).put(node.getLong("createdAt"))
            .put(node.getLong("smsEpoch")).put(node.getLong("smsPurgeAt"))
            .put(node.optBoolean("revoked")).put(node.optLong("resetRequestedAt",0))
            .put(node.optString("lifecycleStatus")).put(node.optString("keyId")).toString()
    }
}

/** Same stable source/thread identity as the normal inbox; display strings never route SMS. */
internal object ControllerSearchMessage {
    fun decode(event:JSONObject,node:JSONObject,plain:JSONObject):ControllerSms {
        val kind=event.getString("kind")
        val direction=plain.optString("direction")
        val inbound=kind=="sms.received"||direction=="in"||(kind=="sms.history"&&direction!="out")
        val sender=if(inbound)plain.optString("sender") else plain.optString("recipient")
        val address=sender.ifBlank{plain.optString("sender",plain.optString("recipient",""))}
        val channel=plain.optString("channelId")
        val revision=plain.optInt("channelRevision",0)
        val deviceId=event.getString("deviceId")
        return ControllerSms(
            "$deviceId:${event.getString("eventId")}",ControllerConversation.key(deviceId,channel,revision,address),
            plain.optString("contactName").ifBlank{address}.take(100),plain.optString("body").take(4000),
            event.getLong("occurredAt"),inbound,node.optString("name").take(100),
            ControllerSourceLabel.name(node,channel,revision,plain.optString("simTag")).take(90),
            "$deviceId|$channel|$revision".take(160),plain.optJSONObject("otp")?.optString("value").orEmpty().take(20),
            false,address.take(120),deviceId,channel,revision.toLong(),plain.optString("body").length>4000
        )
    }
    fun matches(filter:ControllerSearchFilter,message:ControllerSms,plain:JSONObject):Boolean {
        if(filter.otpOnly&&message.otp.isBlank())return false
        if(filter.sourceId.isNotBlank()&&filter.sourceId!=message.sourceId)return false
        val query=filter.query.trim()
        // Match the whole decrypted body before applying the bounded display preview.
        return query.isBlank()||listOf(plain.optString("body"),plain.optString("sender"),
            plain.optString("recipient"),plain.optString("contactName"),message.device,message.sim)
            .any{it.contains(query,ignoreCase=true)}
    }
}

/**
 * Streams every Relay-retained SMS up to a captured sequence watermark. Search
 * terms never enter an HTTP request. Only a bounded set of plaintext matches is
 * kept in memory; no plaintext index, journal, query or body is persisted.
 */
internal class ControllerHistoryScanner(
    private val get:suspend (String)->JSONObject,
    private val decrypt:(JSONObject,JSONObject)->JSONObject,
    private val resultLimit:Int=1000,
    private val pageSize:Int=50,
    private val decorate:(JSONObject)->JSONObject={it}
) {
    init{require(resultLimit in 1..1000&&pageSize in 1..100)}
    private suspend fun devices():List<JSONObject>{
        currentCoroutineContext().ensureActive()
        val rows=get("/api/v1/devices").getJSONArray("devices")
        return (0 until rows.length()).map{rows.getJSONObject(it)}
    }
    suspend fun scan(filter:ControllerSearchFilter,onProgress:suspend (ControllerSearchProgress)->Unit):ControllerSearchProgress {
        require(filter.query.length<=200)
        val nodes=devices()
        val identity=ControllerHistoryScope.fingerprint(nodes)
        val byId=nodes.map(decorate).associateBy{it.getString("id")}
        val head=get("/api/v1/events?latest=1&scope=sms&limit=1")
        val watermark=head.getLong("nextSince")
        require(watermark>=0)
        var state=ControllerSearchProgress(watermark=watermark,scopeFingerprint=identity)
        onProgress(state)
        suspend fun verifyScope(){
            if(ControllerHistoryScope.fingerprint(devices())!=identity)throw ControllerSearchScopeChanged()
        }
        while(state.cursor<watermark){
            currentCoroutineContext().ensureActive()
            val page=get("/api/v1/events?since=${state.cursor}&scope=sms&limit=$pageSize")
            val rows=page.getJSONArray("events")
            require(rows.length()<=pageSize){"Relay exceeded history page limit"}
            var cursor=state.cursor
            var scanned=state.scanned
            var matched=state.matched
            var unreadable=state.unreadable
            var oldest=state.oldestAt
            var newest=state.newestAt
            val matches=state.results.toMutableList()
            for(i in 0 until rows.length()){
                currentCoroutineContext().ensureActive()
                val event=rows.getJSONObject(i)
                val sequence=event.getLong("seq")
                require(sequence>cursor){"Non-monotonic history sequence"}
                if(sequence>watermark){cursor=watermark;break}
                cursor=sequence
                require(event.getString("kind").startsWith("sms.")){"Non-SMS event in history scope"}
                val node=byId[event.getString("deviceId")]?:throw ControllerSearchScopeChanged()
                scanned+=1
                val timestamp=event.getLong("occurredAt")
                require(timestamp>=0)
                if(timestamp>0){oldest=if(oldest==0L)timestamp else minOf(oldest,timestamp);newest=maxOf(newest,timestamp)}
                val plain=try{decrypt(event,node)}catch(_:Exception){unreadable+=1;continue}
                val message=ControllerSearchMessage.decode(event,node,plain)
                if(ControllerSearchMessage.matches(filter,message,plain)){
                    matched+=1
                    matches+=message
                }
            }
            if(rows.length()>0)require(cursor>state.cursor){"History cursor did not advance"}
            // A fresh device fence is checked before any plaintext from this page
            // becomes visible. A reset/purge invalidates all previously held rows.
            verifyScope()
            val complete=rows.length()==0||cursor>=watermark
            state=ControllerSearchProgress(scanned,matched,unreadable,
                matches.sortedWith(compareByDescending<ControllerSms>{it.timestamp}.thenByDescending{it.id}).take(resultLimit),
                oldest,newest,watermark,cursor,complete,identity)
            onProgress(state)
            if(complete)return state
        }
        verifyScope()
        return state.copy(complete=true).also{onProgress(it)}
    }
}
