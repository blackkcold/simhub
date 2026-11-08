package com.blackkcold.simhub

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class ControllerSendDraft(
    val deviceId:String="",val channelId:String="",val channelRevision:Long=0,
    val recipient:String="",val message:String=""
){
    fun json()=JSONObject().put("deviceId",deviceId).put("channelId",channelId)
        .put("channelRevision",channelRevision).put("recipient",recipient).put("message",message)
    companion object {
        fun from(row:JSONObject?)=if(row==null)ControllerSendDraft() else ControllerSendDraft(
            row.optString("deviceId"),row.optString("channelId"),row.optLong("channelRevision"),
            row.optString("recipient").take(40),row.optString("message").take(4000))
    }
}

/** A changing display order must never change an already selected sending route. */
internal object ControllerSendTarget {
    fun channelId(row:JSONObject)=row.optString("id").ifBlank{row.optString("channelId")}
    fun revision(row:JSONObject)=row.optLong("revision",row.optLong("channelRevision",0))
    fun resolve(devices:List<JSONObject>,draft:ControllerSendDraft):Pair<JSONObject,JSONObject>? {
        val device=devices.firstOrNull{it.optString("id")==draft.deviceId&&ControllerNodePolicy.canSend(it)}?:return null
        val channels=ControllerNodePolicy.channels(device)
        for(i in 0 until channels.length()){
            val channel=channels.optJSONObject(i)?:continue
            if(channelId(channel)==draft.channelId&&revision(channel)==draft.channelRevision&&draft.channelRevision>0)
                return device to channel
        }
        return null
    }
}

internal interface ControllerSendStorage {
    val lock:Any
    fun read():JSONObject
    fun write(value:JSONObject)
}
internal interface ControllerSendTransport {
    fun encrypt(device:JSONObject,id:String,created:Long,expires:Long,payload:JSONObject):JSONObject
    fun status(deviceId:String,id:String):JSONObject?
    fun elevate(credential:String,totp:Boolean)
    fun post(deviceId:String,request:JSONObject):JSONObject
    fun freeze(device:JSONObject,id:String,created:Long,expires:Long,payload:JSONObject):JSONObject =
        JSONObject().put("commandId",id).put("idempotencyKey",id).put("type","sms.send")
            .put("createdAt",created).put("expiresAt",expires)
            .put("ciphertext",encrypt(device,id,created,expires,payload))
    fun statusIntent(intent:JSONObject):JSONObject?=status(intent.getString("deviceId"),intent.getString("id"))
    fun postIntent(intent:JSONObject):JSONObject=post(intent.getString("deviceId"),JSONObject(intent.getJSONObject("request").toString()))
    fun requiresElevation(intent:JSONObject):Boolean=true
}

/** Immutable, encrypted send intents; only explicit user submission can replay a missing intent. */
internal class ControllerSendCoordinator(
    private val storage:ControllerSendStorage,
    private val transport:ControllerSendTransport,
    private val now:()->Long={System.currentTimeMillis()/1000}
){
    private fun entries(document:JSONObject):List<JSONObject>{
        val items=document.optJSONArray("intents")?:JSONArray()
        require(items.length()<=100){"Send journal exceeds its bound"}
        return (0 until items.length()).map{items.getJSONObject(it)}
    }
    fun draft():ControllerSendDraft=synchronized(storage.lock){ControllerSendDraft.from(storage.read().optJSONObject("draft"))}
    fun saveDraft(draft:ControllerSendDraft)=synchronized(storage.lock){
        val document=storage.read()
        if(document.optString("activeIntent").isEmpty()){
            document.put("v",1).put("draft",draft.json());storage.write(document)
        }
    }
    fun activeId():String=synchronized(storage.lock){storage.read().optString("activeIntent")}
    fun startDraft(draft:ControllerSendDraft=ControllerSendDraft())=synchronized(storage.lock){
        val document=storage.read();document.put("v",1).put("draft",draft.json()).remove("activeIntent");storage.write(document)
    }
    fun open(id:String)=synchronized(storage.lock){
        val document=storage.read();val intent=entries(document).firstOrNull{it.optString("id")==id}
            ?:throw IllegalArgumentException("Send intent unavailable")
        document.put("activeIntent",id).put("draft",intent.getJSONObject("draft"));storage.write(document)
    }
    fun list():List<JSONObject> = synchronized(storage.lock){entries(storage.read()).asReversed()}
    fun find(id:String):JSONObject?=synchronized(storage.lock){entries(storage.read()).firstOrNull{it.optString("id")==id}}
    private fun save(intent:JSONObject,activate:Boolean=false){
        val document=storage.read();val all=entries(document).toMutableList()
        val index=all.indexOfFirst{it.optString("id")==intent.getString("id")}
        if(index>=0)all[index]=intent else{
            while(all.size>=100){
                val removable=all.indexOfFirst{it.optString("phase")=="observed"&&it.optString("state") in setOf("delivered","succeeded","failed","rejected","expired")}
                require(removable>=0){"发送中心已达到 100 条未完成任务上限，请先核实任务状态"}
                all.removeAt(removable)
            }
            all.add(intent)
        }
        document.put("v",1).put("intents",JSONArray(all))
        if(activate)document.put("activeIntent",intent.getString("id")).put("draft",intent.getJSONObject("draft"))
        storage.write(document)
    }
    fun prepare(devices:List<JSONObject>,draft:ControllerSendDraft):JSONObject=synchronized(storage.lock){
        require(draft.recipient.trim().matches(Regex("^\\+?[0-9 ()-]{3,40}$"))){"Invalid recipient"}
        require(draft.message.isNotBlank()&&draft.message.length<=4000){"Invalid SMS body"}
        val (device,_)=ControllerSendTarget.resolve(devices,draft)?:throw IllegalStateException("所选节点或 SIM 已不可用，请重新核对；未改用其他 SIM")
        val id=UUID.randomUUID().toString();val created=now();val expiryLimit=created+1200
        val payload=JSONObject().put("v",2).put("action","sms.send").put("commandId",id)
            .put("issuedAt",created).put("expiresAt",expiryLimit).put("channelId",draft.channelId)
            .put("channelRevision",draft.channelRevision).put("to",draft.recipient.trim()).put("body",draft.message)
        val request=transport.freeze(device,id,created,expiryLimit,payload)
        val expires=request.getLong("expiresAt")
        require(expires>created&&expires<=expiryLimit){"Invalid frozen transport expiry"}
        require(request.getString("commandId")==id){"Frozen command identity mismatch"}
        val intent=JSONObject().put("id",id).put("deviceId",draft.deviceId).put("deviceName",device.optString("name"))
            .put("draft",draft.json()).put("request",request).put("createdAt",created).put("expiresAt",expires)
            .put("phase","prepared").put("state","prepared").put("observed",false).put("updatedAt",created)
        require(storage.read().optString("activeIntent").isEmpty()){"请先查看已有发送任务；新任务需单独创建"}
        save(intent,true) // Must finish durably before any authentication / POST side effect.
        intent
    }
    fun submit(id:String,credential:String="",totp:Boolean=true):JSONObject=synchronized(storage.lock){
        reconcile(id,true,credential,totp)
    }
    fun refresh(id:String):JSONObject=synchronized(storage.lock){reconcile(id,false,"",true)}
    fun observe(commands:List<JSONObject>)=synchronized(storage.lock){
        val document=storage.read();val all=entries(document);var changed=false
        for(intent in all){
            if(intent.optJSONObject("request")?.optString("transport")=="peer")continue
            val command=commands.firstOrNull{it.optString("commandId")==intent.optString("id")&&
                it.optString("deviceId")==intent.optString("deviceId")&&it.optString("type")=="sms.send"}?:continue
            intent.put("phase","observed").put("observed",true).put("state",command.optString("state"))
                .put("result",command.optJSONObject("result")?:JSONObject()).put("updatedAt",now()).remove("error")
            changed=true
        }
        if(changed){document.put("intents",JSONArray(all));storage.write(document)}
    }
    private fun reconcile(id:String,allowPost:Boolean,credential:String,totp:Boolean):JSONObject{
        val intent=find(id)?:throw IllegalArgumentException("Send intent unavailable")
        val deviceId=intent.getString("deviceId")
        val phase=intent.optString("phase")
        if(phase!="prepared"||!allowPost){
            // Generic 404/network errors must throw; only exact command_not_found
            // is a missing record. A previously observed acceptance stays final.
            val command=transport.statusIntent(intent)
            if(command!=null){
                if(command.optString("commandId")!=id||command.optString("deviceId")!=deviceId||command.optString("type")!="sms.send")
                    throw SecurityException("Command receipt identity mismatch")
                intent.put("phase","observed").put("observed",true).put("state",command.optString("state"))
                    .put("result",command.optJSONObject("result")?:JSONObject()).remove("error")
                intent.put("updatedAt",now());save(intent);return intent
            }
            if(intent.optBoolean("observed")||!allowPost){
                intent.put("error",if(intent.optBoolean("observed"))"receipt_unavailable" else "command_not_found")
                save(intent);return intent
            }
        }
        if(now()>=intent.getLong("expiresAt")){
            intent.put("error","original_request_expired");save(intent);return intent
        }
        if(credential.isNotBlank()&&transport.requiresElevation(intent))transport.elevate(credential,totp)
        intent.put("phase","submitting").put("state","awaiting_receipt").put("updatedAt",now()).remove("error")
        save(intent) // Process death after this point is an uncertain result, never a new intent.
        try{
            val response=transport.postIntent(intent)
            if(response.optString("commandId")!=id)throw SecurityException("Command acknowledgement identity mismatch")
            intent.put("phase","observed").put("observed",true).put("state",response.optString("state","queued"))
                .put("result",response.optJSONObject("result")?:JSONObject()).put("updatedAt",now());save(intent)
        }catch(error:Exception){
            intent.put("phase","uncertain").put("state","awaiting_receipt").put("updatedAt",now())
                .put("error",if(error is ControllerSecureClient.ApiFailure)error.code else "submission_response_unknown")
            save(intent)
        }
        return intent
    }
    companion object {
        fun forClient(api:ControllerSecureClient)=ControllerSendCoordinator(
            object:ControllerSendStorage{
                override val lock=ControllerSecureClient.localJournalLock
                override fun read()=api.readLocalJournal("send-workspace")
                override fun write(value:JSONObject)=api.writeLocalJournal("send-workspace",value)
            },ControllerPeerSendTransport(api))
    }
}
