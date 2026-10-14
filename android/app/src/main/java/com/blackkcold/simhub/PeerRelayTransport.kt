package com.blackkcold.simhub

import android.content.Context
import com.blackkcold.simhub.p2p.*
import org.json.JSONObject
import java.io.IOException

/** Existing Relay authentication transports a separately encrypted P2P RPC. */
class PeerRelayTransport(private val context:Context,private val origin:String,private val deviceId:String):HubTransport {
    private val api=ControllerSecureClient(context,origin)
    override fun route()="LEGACY_RELAY"
    override fun request(peer:JSONObject,id:String,request:JSONObject):JSONObject {
        val store=PeerStore.get(context)
        val carrier="pw-"+PeerCrypto.digest(PeerCrypto.utf8(store.myId()+"|"+peer.getString("id")+"|"+id))
        val path="/api/v1/devices/$deviceId/commands/$carrier/peer-response"
        val existing=api.api("/api/v1/commands/recent?limit=50").optJSONArray("commands")
        val queued=existing!=null&&(0 until existing.length()).any{existing.optJSONObject(it)?.optString("commandId")==carrier}
        if(!queued){
            val all=api.api("/api/v1/devices").getJSONArray("devices")
            val node=(0 until all.length()).map{all.getJSONObject(it)}.firstOrNull{it.optString("id")==deviceId}?:throw IOException("relay_node_unavailable")
            val now=System.currentTimeMillis()/1000
            val expires=minOf(now+120,request.optLong("commandExpires",now+120))
            if(expires<=now)throw IOException("peer_command_expired")
            val key=store.key(peer.getString("id"))
            val frame=try{PeerProtocol.seal(key,store.myId(),peer.getString("id"),id,expires,request)}finally{key.fill(0)}
            val payload=JSONObject().put("v",2).put("action","peer.rpc").put("commandId",carrier).put("expiresAt",expires).put("peerFrame",frame)
            val cipher=api.encryptCommand(node,carrier,"peer.rpc",now,expires,payload)
            api.api("/api/v1/devices/$deviceId/commands","POST",JSONObject().put("commandId",carrier).put("idempotencyKey",carrier).put("type","peer.rpc").put("createdAt",now).put("expiresAt",expires).put("ciphertext",cipher))
        }
        val until=System.currentTimeMillis()+6000
        do {
            val response=try{api.api(path).optJSONObject("frame")}catch(e:Exception){
                if(e !is ControllerSecureClient.ApiError||e.code!="peer_response_pending")throw e
                null
            }
            if(response!=null){
                if(response.optString("id")!=id)throw SecurityException("peer_response_id_mismatch")
                val key=store.key(peer.getString("id"))
                val opened=try{PeerProtocol.open(key,peer.getString("id"),store.myId(),response,System.currentTimeMillis()/1000)}finally{key.fill(0)}
                if(opened.optString("requestDigest")!=PeerCrypto.digest(PeerCrypto.utf8(request.toString())))throw SecurityException("peer_response_request_mismatch")
                return opened.getJSONObject("result")
            }
            Thread.sleep(750)
        }while(System.currentTimeMillis()<until)
        throw IOException("relay_peer_response_pending")
    }
    companion object {
        fun configured(c:Context,p:JSONObject):PeerRelayTransport? {
            val origin=ControllerLinks.normalize(p.optString("relayOrigin"))?:return null
            val device=p.optString("relayDeviceId")
            if(!p.optBoolean("relayConfigured")||origin !in ControllerProfiles.list(c)||!device.matches(Regex("[A-Za-z0-9_-]{1,160}")))return null
            if(!ControllerSecureClient(c,origin).hasGrant())return null
            return PeerRelayTransport(c.applicationContext,origin,device)
        }
        fun associate(c:Context,p:JSONObject):Boolean {
            val hint=p.optJSONObject("relayHint")?:return false
            val device=hint.optString("deviceId")
            if(!device.matches(Regex("[A-Za-z0-9_-]{1,160}")))return false
            val origin=ControllerLinks.normalize(hint.optString("origin"))?.takeIf{it in ControllerProfiles.list(c)}?:ControllerProfiles.selected(c)?:return false
            val client=ControllerSecureClient(c,origin);if(!client.hasGrant())return false
            val state=client.api("/api/v1/devices/$device/state").optJSONObject("state")?:return false
            if(state.optString("p2pIdentityId")!=p.getString("id")||state.optInt("peerRpcVersion")!=1)return false
            val store=PeerStore.get(c);val fresh=store.peer(p.getString("id"))
            fresh.put("relayOrigin",origin).put("relayDeviceId",device).put("relayConfigured",true)
            store.update(fresh);return true
        }
    }
}
