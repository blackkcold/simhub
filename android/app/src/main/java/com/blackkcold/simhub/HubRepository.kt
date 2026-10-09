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
    val subscription: Int
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
    val sms: List<HubSms>
) {
    val threads: List<HubThread> get() = sms.groupBy { keyFor(it.from,it.subscription) }
        .map { (key, items) -> HubThread(key,items.first().from,items.first().subscription,items.first(),items.size) }
        .sortedByDescending { it.latest.date }
    companion object {
        fun keyFor(address: String, subscription: Int) = subscription.toString() + "|" + address.lowercase()
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
        return HubSnapshot(
            cfg.isEnrolled(), cfg.deviceName(),cfg.server(),cfg.alwaysOn(),
            LocalStore.get(context).pendingEventCount(),cfg.lastSyncSuccessAt(),
            cfg.lastSyncError(),cfg.smsProviderError(),smsRole,read,send,state,
            if(read) readMessages(context,limit) else emptyList()
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
