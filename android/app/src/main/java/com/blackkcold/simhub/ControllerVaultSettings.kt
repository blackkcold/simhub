package com.blackkcold.simhub

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class ControllerVaultSettingsSnapshot(val vaultId:String,val keyId:String,val revision:Long,val settings:JSONObject)

/** The same opaque AEAD document used by Web. Aliases never change route identity. */
internal object ControllerVaultSettingsCrypto {
    fun key(device:String,channel:String,revision:Long)="$device|$channel|$revision"
    fun keyId(raw:ByteArray)=CryptoBox.b64(MessageDigest.getInstance("SHA-256").digest(raw).copyOf(16))
    private fun validLabel(value:String,max:Int)=value.length<=max&&value.none{it.code<32||it.code==127}
    fun normalized(source:JSONObject):JSONObject {
        require(source.optInt("v")==1){"Unsupported encrypted settings version"}
        val result=JSONObject(source.toString())
        for((section,max) in listOf("channelAliases" to 80,"phoneOverrides" to 32)){
            val original=source.optJSONObject(section)?:JSONObject();require(original.length()<=1000){"Too many SIM labels"}
            val safe=JSONObject()
            original.keys().forEach{key->
                val value=original.opt(key)
                if(key.length<=400&&key.contains('|')&&value is String&&validLabel(value,max))safe.put(key,value)
            }
            result.put(section,safe)
        }
        return result
    }
    fun patch(source:JSONObject,key:String,alias:String,phone:String):JSONObject {
        require(key.length<=400&&key.contains('|')){"Invalid SIM identity"}
        require(validLabel(alias,80)&&validLabel(phone,32)){"Invalid SIM label"}
        val result=normalized(source)
        for((section,value) in listOf("channelAliases" to alias,"phoneOverrides" to phone)){
            val map=result.getJSONObject(section)
            if(value.isEmpty())map.remove(key) else map.put(key,value)
        }
        return result
    }
    fun encrypt(raw:ByteArray,vaultId:String,settings:JSONObject):JSONObject {
        val clear=normalized(settings).toString().toByteArray(StandardCharsets.UTF_8)
        try{
            require(clear.size<=44984){"Encrypted settings are full"}
            val iv=ByteArray(12).also{SecureRandom().nextBytes(it)}
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(raw,"AES"),GCMParameterSpec(128,iv))
            cipher.updateAAD(("simhub-vault-settings-v1|$vaultId").toByteArray(StandardCharsets.UTF_8))
            return JSONObject().put("v",2).put("alg","A256GCM").put("kid",keyId(raw))
                .put("iv",CryptoBox.b64(iv)).put("ct",CryptoBox.b64(cipher.doFinal(clear)))
        }finally{clear.fill(0)}
    }
    fun decode(raw:ByteArray,vaultId:String,response:JSONObject):ControllerVaultSettingsSnapshot {
        require(response.optString("vaultId")==vaultId&&response.optString("keyId")==keyId(raw)){"Settings belong to another Vault"}
        val revision=response.optLong("revision",-1);require(revision>=0){"Invalid settings revision"}
        val envelope=response.optJSONObject("ciphertext")
        if(envelope==null){
            require(revision==0L){"Settings ciphertext missing"}
            return ControllerVaultSettingsSnapshot(vaultId,keyId(raw),revision,
                JSONObject().put("v",1).put("channelAliases",JSONObject()).put("phoneOverrides",JSONObject()))
        }
        require(envelope.optInt("v")==2&&envelope.optString("alg")=="A256GCM"&&envelope.optString("kid")==keyId(raw)){"Invalid settings envelope"}
        val iv=CryptoBox.ub64(envelope.getString("iv"));require(iv.size==12){"Invalid settings nonce"}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(raw,"AES"),GCMParameterSpec(128,iv))
        cipher.updateAAD(("simhub-vault-settings-v1|$vaultId").toByteArray(StandardCharsets.UTF_8))
        val clear=cipher.doFinal(CryptoBox.ub64(envelope.getString("ct")))
        try{return ControllerVaultSettingsSnapshot(vaultId,keyId(raw),revision,normalized(JSONObject(String(clear,StandardCharsets.UTF_8))))}
        finally{clear.fill(0)}
    }
    fun decorate(device:JSONObject,settings:JSONObject):JSONObject {
        val copy=JSONObject(device.toString());val channels=ControllerNodePolicy.channels(copy)
        for(i in 0 until channels.length()){
            val channel=channels.optJSONObject(i)?:continue
            val identity=key(copy.optString("id"),ControllerSendTarget.channelId(channel),ControllerSendTarget.revision(channel))
            val alias=settings.optJSONObject("channelAliases")?.optString(identity).orEmpty()
            val phone=settings.optJSONObject("phoneOverrides")?.optString(identity).orEmpty()
            // Preserve the node label when clearing a private override in this process.
            if(!channel.has("nodeAlias"))channel.put("nodeAlias",channel.optString("alias"))
            channel.put("alias",alias.ifBlank{channel.optString("nodeAlias")})
                .put("privateAlias",alias).put("privatePhone",phone)
        }
        return copy
    }
}

internal class ControllerVaultSettings(private val api:ControllerSecureClient){
    fun load():ControllerVaultSettingsSnapshot {
        val response=api.api("/api/v1/vault/settings")
        val raw=api.vault()
        try{return ControllerVaultSettingsCrypto.decode(raw,api.vaultId(),response)}finally{raw.fill(0)}
    }
    fun update(deviceId:String,channelId:String,revision:Long,alias:String,phone:String):ControllerVaultSettingsSnapshot {
        val identity=ControllerVaultSettingsCrypto.key(deviceId,channelId,revision)
        for(attempt in 0..2){
            val current=load()
            val next=ControllerVaultSettingsCrypto.patch(current.settings,identity,alias,phone)
            val raw=api.vault()
            try{
                val body=JSONObject().put("vaultId",current.vaultId).put("keyId",current.keyId)
                    .put("expectedRevision",current.revision).put("ciphertext",ControllerVaultSettingsCrypto.encrypt(raw,current.vaultId,next))
                try{return ControllerVaultSettingsCrypto.decode(raw,current.vaultId,api.api("/api/v1/vault/settings","POST",body))}
                catch(error:ControllerSecureClient.ApiFailure){
                    if(error.status!=409||error.code!="settings_conflict"||attempt==2)throw error
                }
            }finally{raw.fill(0)}
        }
        throw IllegalStateException("Settings changed concurrently; retry after refresh")
    }
}
