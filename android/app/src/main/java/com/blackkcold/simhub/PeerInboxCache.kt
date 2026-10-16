package com.blackkcold.simhub

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Small Keystore-encrypted offline snapshot, independent of Controller Vault profiles. */
object PeerInboxCache {
    /** Capture Compose state on the UI thread, before any asynchronous file write. */
    fun snapshot(inbox:ControllerInboxState):String {
        val rows=JSONArray()
        var bytes=0
        for(m in inbox.messages.take(100)){
            val row=JSONObject().put("id",m.id).put("thread",m.thread).put("sender",m.sender).put("address",m.address).put("body",m.body).put("timestamp",m.timestamp).put("inbound",m.inbound).put("device",m.device).put("sim",m.sim).put("sourceId",m.sourceId).put("otp",m.otp).put("failed",m.failed)
            val size=row.toString().toByteArray(Charsets.UTF_8).size+1
            if(bytes+size>290000)break
            rows.put(row)
            bytes+=size
        }
        val more=inbox.hasMore||inbox.messages.size>rows.length()
        return JSONObject().put("type","snapshot").put("v",1).put("messages",rows).put("hasMore",more).put("cachedAt",System.currentTimeMillis()/1000).toString()
    }
    fun persist(context:Context,peer:String,serialized:String){
        SecretStore(context).putString("p2p.inbox.$peer",serialized)
    }
    fun save(context:Context,peer:String,inbox:ControllerInboxState){
        persist(context,peer,snapshot(inbox))
    }
    fun restore(context:Context,peer:String,inbox:ControllerInboxState):Boolean=try{
        SecretStore(context).getString("p2p.inbox.$peer")?.let{inbox.accept(it)}?:false
    }catch(_:Exception){false}
    fun clear(context:Context,peer:String){SecretStore(context).remove("p2p.inbox.$peer")}
}
