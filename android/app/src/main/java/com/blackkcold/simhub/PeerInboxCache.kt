package com.blackkcold.simhub

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Small Keystore-encrypted offline snapshot, independent of Controller Vault profiles. */
object PeerInboxCache {
    fun save(context:Context,peer:String,inbox:ControllerInboxState){
        val rows=JSONArray()
        for(m in inbox.messages.take(100)){
            val row=JSONObject().put("id",m.id).put("thread",m.thread).put("sender",m.sender).put("body",m.body).put("timestamp",m.timestamp).put("inbound",m.inbound).put("device",m.device).put("sim",m.sim).put("sourceId",m.sourceId).put("otp",m.otp).put("failed",m.failed)
            if(rows.toString().toByteArray().size+row.toString().toByteArray().size>300000)break
            rows.put(row)
        }
        SecretStore(context).putString("p2p.inbox.$peer",JSONObject().put("type","snapshot").put("v",1).put("messages",rows).put("hasMore",true).toString())
    }
    fun restore(context:Context,peer:String,inbox:ControllerInboxState):Boolean=try{
        SecretStore(context).getString("p2p.inbox.$peer")?.let{inbox.accept(it)}?:false
    }catch(_:Exception){false}
    fun clear(context:Context,peer:String){SecretStore(context).remove("p2p.inbox.$peer")}
}
