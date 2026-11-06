package com.blackkcold.simhub

import android.net.Uri
import com.blackkcold.simhub.p2p.PeerGrantPolicy
import com.blackkcold.simhub.p2p.PeerStore
import com.blackkcold.simhub.p2p.RelayLiteClient
import org.json.JSONObject
import java.util.UUID

/** Dev-only adapter: one encrypted controller intent keeps one immutable peer route. */
internal class ControllerPeerSendTransport(private val api:ControllerSecureClient):ControllerSendTransport {
    private val context=api.applicationContext()
    override fun encrypt(device:JSONObject,id:String,created:Long,expires:Long,payload:JSONObject)=
        api.encryptCommand(device,id,"sms.send",created,expires,payload)
    override fun status(deviceId:String,id:String):JSONObject?=try{
        api.api("/api/v1/devices/"+Uri.encode(deviceId)+"/commands/"+Uri.encode(id)).getJSONObject("command")
    }catch(error:ControllerSecureClient.ApiFailure){
        if(error.status==404&&error.code=="command_not_found")null else throw error
    }
    override fun elevate(credential:String,totp:Boolean){
        api.api("/api/v1/auth/elevate","POST",JSONObject().put(if(totp)"totp" else "adminToken",credential))
    }
    override fun post(deviceId:String,request:JSONObject)=
        api.api("/api/v1/devices/"+Uri.encode(deviceId)+"/commands","POST",request)

    override fun freeze(device:JSONObject,id:String,created:Long,expires:Long,payload:JSONObject):JSONObject {
        val peer=linkedSender(api,device.getString("id"))?:return super.freeze(device,id,created,expires,payload)
        val deadline=minOf(expires,created+120)
        val peerPayload=JSONObject().put("action","sms.send").put("to",payload.getString("to"))
            .put("body",payload.getString("body")).put("channelId",payload.getString("channelId"))
            .put("channelRevision",payload.getLong("channelRevision")).put("commandExpires",deadline)
        // The enclosing send journal is Keystore-encrypted. Gateway additionally
        // persists its own sealed outbox before either Relay/P2P carrier is tried.
        return JSONObject().put("transport","peer").put("peerId",peer.getString("id"))
            .put("commandId",id).put("type","sms.send").put("createdAt",created)
            .put("expiresAt",deadline).put("peerPayload",peerPayload)
    }
    override fun requiresElevation(intent:JSONObject)=intent.getJSONObject("request").optString("transport")!="peer"
    private fun peer(intent:JSONObject):String {
        val peerId=intent.getJSONObject("request").getString("peerId")
        val profile=PeerStore.get(context).peer(peerId)
        if(!profile.optBoolean("relayConfigured")||profile.optString("relayOrigin")!=api.origin||
            profile.optString("relayDeviceId")!=intent.getString("deviceId")||
            profile.optString("pairState")=="REVOKED"||!PeerGrantPolicy.allows(profile,"sms.send",false))
            throw SecurityException("frozen_peer_route_unavailable")
        return peerId
    }
    override fun statusIntent(intent:JSONObject):JSONObject? {
        if(requiresElevation(intent))return super.statusIntent(intent)
        val peerId=peer(intent);val id=intent.getString("id")
        val cached=RelayLiteClient.cachedResult(context,peerId,id)
        val result=try{
            PeerHubGateway.request(context,peerId,UUID.randomUUID().toString(),
                JSONObject().put("action","command.status").put("commandId",id),allowOffline=false)
        }catch(error:Exception){
            if(error is SecurityException||cached==null)throw error
            cached
        }
        // An offline carrier accepting a status query has not answered it. The
        // original command's authenticated cached receipt may still be useful.
        val actual=if(result.optString("route")=="RELAY_LITE"&&result.optBoolean("relayAccepted"))
            cached?:throw java.io.IOException("peer_status_pending") else result
        if(actual.optString("state")=="unknown")return cached?.let{normalize(intent,it)}
        return normalize(intent,actual)
    }
    override fun postIntent(intent:JSONObject):JSONObject {
        if(requiresElevation(intent))return super.postIntent(intent)
        val peerId=peer(intent)
        val payload=JSONObject(intent.getJSONObject("request").getJSONObject("peerPayload").toString())
        return normalize(intent,PeerHubGateway.send(context,peerId,intent.getString("id"),payload))
    }
    private fun normalize(intent:JSONObject,result:JSONObject):JSONObject {
        val id=intent.getString("id")
        if(result.has("commandId")&&result.optString("commandId")!=id)throw SecurityException("peer_receipt_identity_mismatch")
        val state=when(result.optString("state")){
            "claimed","indeterminate","unknown"->"outcome_unknown"
            "queued","submitted","sent","delivered","succeeded","failed","rejected","expired"->result.getString("state")
            else->throw java.io.IOException("peer_receipt_unavailable")
        }
        return JSONObject().put("commandId",id).put("deviceId",intent.getString("deviceId"))
            .put("type","sms.send").put("state",state).put("result",result)
    }
    companion object {
        fun linkedSender(api:ControllerSecureClient,deviceId:String):JSONObject? =
            PeerHubGateway.linked(api.applicationContext(),api.origin).firstOrNull{
                it.optString("relayDeviceId")==deviceId&&it.optString("pairState")=="ACTIVE"&&
                    PeerGrantPolicy.allows(it,"sms.send",false)
            }
    }
}
