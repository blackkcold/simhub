package com.blackkcold.simhub

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telephony.SubscriptionManager
import org.json.JSONObject

data class HubSms(
    val id: Long, val from: String, val text: String, val date: Long, val type: Int,
    val subscription: Int,
    val sourceDeviceId:String="",val sourceDeviceName:String="",
    val channelId:String="",val simTag:String="",val simTail:String="",
    val shared:Boolean=false,val historicalUnverified:Boolean=false
)
data class HubThread(
    val key: String, val address: String, val subscription: Int,
    val latest: HubSms, val count: Int
)
data class HubSnapshot(
    val enrolled: Boolean,
    val device: String,
    val server: String,
    val realtime: Boolean,
    val pending: Int,
    val lastSync: Long,
    val transportError: String,
    val providerError: String,
    val smsRole: Boolean,
    val smsRead: Boolean,
    val smsSend: Boolean,
    val state: JSONObject,
    val sms: List<HubSms>,
    val localDeviceId:String=""
) {
    val threads: List<HubThread> get() = sms.groupBy { keyFor(it.from,it.subscription,it.sourceDeviceId,it.channelId) }
        .map { (key, items) -> HubThread(key,items.first().from,items.first().subscription,items.first(),items.size) }
        .sortedByDescending { it.latest.date }
    companion object {
        fun keyFor(address:String,subscription:Int,deviceId:String="",channelId:String="") =
            deviceId+"|"+(if(channelId.isNotBlank())channelId else subscription.toString())+"|"+address.lowercase()
    }
}
object HubRepository {
    fun snapshot(context: Context, limit: Int=400): HubSnapshot {
        val cfg=AgentConfig(context)
        val role=context.getSystemService(RoleManager::class.java)
        val smsRole=role?.isRoleHeld(RoleManager.ROLE_SMS)==true
        val read=context.checkSelfPermission(Manifest.permission.READ_SMS)==PackageManager.PERMISSION_GRANTED
        val send=context.checkSelfPermission(Manifest.permission.SEND_SMS)==PackageManager.PERMISSION_GRANTED
        val state=StateCollector.collect(context)
        state.optJSONArray("subscriptions")?.let{arr->
            for(i in 0 until arr.length()){
                val item=arr.optJSONObject(i)?:continue
                val tag=SimTagStore.get(context,item.optString("channelId",""),item.optLong("channelRevision",1))
                item.put("localSimTag",tag.optString("tag",""))
                item.put("localSimTail",tag.optString("tail",""))
            }
        }
        // SMS permission or OEM provider failure must not take down the SIM,
        // connection-health and enrollment screens.
        val messages=if(!read) emptyList() else try {
            readMessages(context,limit)
        } catch (error:Exception) {
            cfg.recordSmsProviderError(if(error is SecurityException)
                "SMS_PROVIDER_SECURITY_EXCEPTION" else "SMS_PROVIDER_QUERY_FAILED")
            emptyList()
        }
        val local=messages.map{ sms->
            val channel=ChannelIdentity.forHistoricalSubscription(context,sms.subscription,sms.date)
            val tag=channel?.let{SimTagStore.get(context,it.channelId,it.revision)}?:JSONObject()
            sms.copy(sourceDeviceId=cfg.deviceId(),sourceDeviceName=cfg.deviceName(),
                channelId=channel?.channelId.orEmpty(),simTag=tag.optString("tag",""),
                simTail=tag.optString("tail",""),historicalUnverified=channel==null)
        }
        val remote=try{
            val poolRows=SharedPoolClient(context).cached(limit)
            val profiles=poolRows.filter{row->
                row.optJSONObject("payload")?.has("channelRevision")==true &&
                row.optJSONObject("payload")?.has("tag")==true
            }.groupBy{row->row.optString("deviceId","")+"|"+row.optString("channelId","")}
                .mapValues{(_,items)->items.maxByOrNull{it.optLong("occurredAt",0)}?.optJSONObject("payload")}
            poolRows.mapNotNull{row->
                if(row.optString("deviceId")==cfg.deviceId())return@mapNotNull null
                val body=row.optJSONObject("payload")?:return@mapNotNull null
                val direction=body.optString("direction","")
                if(direction!="in"&&direction!="out")return@mapNotNull null
                val address=if(direction=="out")body.optString("recipient","") else body.optString("sender","")
                if(address.isBlank())return@mapNotNull null
                val device=row.optString("deviceId","")
                val channel=row.optString("channelId","")
                val profile=profiles[device+"|"+channel]
                HubSms(-row.optLong("seq"),address,body.optString("body",""),row.optLong("occurredAt")*1000,
                    if(direction=="out")Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_INBOX,-1,
                    device,body.optString("sourceDeviceName","Shared SIM"),
                    channel,profile?.optString("tag","")?.ifBlank{body.optString("simTag","")}
                        ?:body.optString("simTag",""),
                    profile?.optString("tail","")?.ifBlank{body.optString("simTail","")}
                        ?:body.optString("simTail",""),
                    shared=true,historicalUnverified=channel.isBlank())
            }
        }catch(error:Exception){AppLogger.e(context,"SharedPool","Cannot read shared cache",error);emptyList()}
        val unified=(local+remote).sortedWith(compareByDescending<HubSms>{it.date}.thenByDescending{it.id}).take(limit)
        return HubSnapshot(
            cfg.isEnrolled(), cfg.deviceName(),cfg.server(),cfg.alwaysOn(),
            LocalStore.get(context).pendingEventCount(),cfg.lastSyncSuccessAt(),
            cfg.lastSyncError(),cfg.smsProviderError(),smsRole,read,send,state,
            unified,cfg.deviceId()
        )
    }
    fun readMessages(context: Context,limit:Int): List<HubSms> {
        if(context.checkSelfPermission(Manifest.permission.READ_SMS)!=PackageManager.PERMISSION_GRANTED)return emptyList()
        val entries=ArrayList<HubSms>(limit)
        val fields=arrayOf("_id","address","body","date","type","sub_id")
        try {
            context.contentResolver.query(Telephony.Sms.CONTENT_URI,fields,null,null,"date DESC")?.use { cursor ->
                val id=cursor.getColumnIndexOrThrow("_id")
                val addr=cursor.getColumnIndexOrThrow("address")
                val body=cursor.getColumnIndexOrThrow("body")
                val date=cursor.getColumnIndexOrThrow("date")
                val type=cursor.getColumnIndexOrThrow("type")
                val sub=cursor.getColumnIndex("sub_id")
                while(cursor.moveToNext() && entries.size<limit) {
                    val address=cursor.getString(addr) ?: continue
                    if(address.isBlank())continue
                    entries.add(HubSms(cursor.getLong(id),address,cursor.getString(body) ?: "",
                        cursor.getLong(date),cursor.getInt(type),
                        if(sub>=0 && !cursor.isNull(sub))cursor.getInt(sub) else -1))
                }
            }
        } catch(error:SecurityException) {
            AppLogger.e(context,"HubSms","SMS Provider permission unavailable",error)
            throw error
        } catch(error:Exception) {
            AppLogger.e(context,"HubSms","Unable to read local SMS messages",error)
            throw error
        }
        return entries
    }
    fun activeSubscriptions(context: Context): List<Pair<Int,String>> {
        if(context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)!=PackageManager.PERMISSION_GRANTED)return emptyList()
        return try {
            context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList
                ?.map { Pair(it.subscriptionId, "${it.displayName} · SIM ${it.simSlotIndex+1}") } ?: emptyList()
        } catch(e:SecurityException) {
            AppLogger.e(context,"HubSim","SIM permission unavailable",e)
            emptyList()
        }
    }
}
