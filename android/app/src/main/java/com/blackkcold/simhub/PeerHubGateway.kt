package com.blackkcold.simhub

import android.content.Context
import com.blackkcold.simhub.p2p.*
import org.json.JSONObject

/** Shared entry for either workspace. Routing never creates an authorization. */
object PeerHubGateway {
    /** Persist one payload/expiry before either carrier; uncertain replies retain that payload. */
    fun send(c:Context,peerId:String,id:String,draft:JSONObject):JSONObject {
        val store=PeerStore.get(c)
        val prepared=synchronized(store){
            var row=store.outbound(peerId,id)
            if(row==null){store.queueOutbound(peerId,id,draft);row=store.outbound(peerId,id)}
            row?:throw IllegalStateException("peer_outbox_missing")
        }
        if(prepared.optString("state")=="expired"){
            val status=request(c,peerId,java.util.UUID.randomUUID().toString(),JSONObject().put("action","command.status").put("commandId",id))
            if(status.optString("state") in listOf("unknown","claimed","indeterminate"))return JSONObject().put("state","indeterminate").put("reason","expired_dispatch_unconfirmed")
            store.clearOutbound(peerId,id);return status
        }
        val payload=prepared.getJSONObject("request")
        for(field in listOf("action","to","body","channelId","channelRevision"))if(payload.optString(field)!=draft.optString(field))throw SecurityException("peer_request_id_reused")
        val result=request(c,peerId,id,payload)
        if(result.optString("state") in listOf("submitted","succeeded","failed","expired","delivered"))store.clearOutbound(peerId,id)
        return result
    }
    fun request(c:Context,peerId:String,id:String,request:JSONObject,preferRelay:Boolean=true):JSONObject {
        val store=PeerStore.get(c);val p=store.peer(peerId);val coordinator=ConnectionCoordinator(store)
        if(preferRelay){
            if(!p.optBoolean("relayConfigured")&&!p.optBoolean("relayAssociationDisabled"))runCatching{PeerRelayTransport.associate(c,p)}
            PeerRelayTransport.configured(c,store.peer(peerId))?.let{coordinator.relay(it)}
        }
        if(p.optBoolean("publicDiscoveryEnabled")){PeerCloudConnector.ensure(c.applicationContext,peerId);PeerCloudConnector.active(peerId)?.let{coordinator.internet(it)}}
        return coordinator.request(peerId,id,request)
    }
    fun linked(c:Context,origin:String):List<JSONObject>{
        val rows=PeerStore.get(c).list()
        return (0 until rows.length()).map{rows.getJSONObject(it)}.filter{
            it.optString("localRole")=="CONTROLLER"&&it.optString("pairState")!="REVOKED"&&it.optBoolean("relayConfigured")&&it.optString("relayOrigin")==origin
        }
    }
}
