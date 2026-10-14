package com.blackkcold.simhub

import android.content.Context
import com.blackkcold.simhub.p2p.*
import org.json.JSONObject

/** Shared entry for either workspace. Routing never creates an authorization. */
object PeerHubGateway {
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
