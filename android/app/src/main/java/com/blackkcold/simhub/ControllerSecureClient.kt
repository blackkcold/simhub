package com.blackkcold.simhub

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Native controller identity is independent from SMS node enrollment.
 * Enrollment produces a single-use ECDH grant. Raw Vault and Node Keys
 * never travel to the Relay and never enter the legacy WebView bridge.
 */
class ControllerSecureClient(private val app:Context, val origin:String) {
    init { require(ControllerLinks.normalize(origin)==origin) { "Trusted HTTPS origin required" } }
    private val prefs=app.getSharedPreferences("simhub_native_controller_"+hex(origin),Context.MODE_PRIVATE)
    private val identityAlias="simhub-controller-id-"+hex(origin)
    private val wrappingAlias="simhub-controller-vault-"+hex(origin)
    private var exchange:KeyPair?=null
    private var requestedId:String?=null
    private var pairPollToken:String?=null
    private var cookie=""
    private var csrf=""
    var lastError:String=""
        private set

    data class PairStatus(val requestId:String,val code:String)
    data class DecodedMessage(val id:String,val thread:String,val sender:String,val body:String,
        val timestamp:Long,val inbound:Boolean,val device:String,val sim:String,
        val otp:String,val failed:Boolean)

    fun hasGrant():Boolean=prefs.getString("controllerId",null)!=null &&
        prefs.getString("sealedVault",null)!=null
    fun controllerId():String=prefs.getString("controllerId","")?:""
    fun requireLocalUnlock():Boolean=prefs.getBoolean("requireDeviceUnlock",false)
    fun setRequireLocalUnlock(enabled:Boolean){
        prefs.edit().putBoolean("requireDeviceUnlock",enabled).apply()
    }
    /**
     * Browser approval may background or kill this process. Persist only a
     * short-lived, AES-GCM sealed copy of the ephemeral ECDH pairing identity.
     * Never store the raw exchange private key or pairing code in preferences.
     */
    private fun pendingAad()=("simhub-controller-pair-v1|"+origin).toByteArray(StandardCharsets.UTF_8)

    private fun rememberPending(id:String,code:String,key:KeyPair,token:String){
        val expires=System.currentTimeMillis()+600_000L
        val secret=JSONObject().put("requestId",id).put("code",code)
            .put("expires",expires).put("pollToken",token).put("private",enc(key.private.encoded))
            .put("public",enc(key.public.encoded)).toString()
                .toByteArray(StandardCharsets.UTF_8)
        try {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE,localWrappingKey())
            cipher.updateAAD(pendingAad())
            val saved=JSONObject().put("iv",enc(cipher.iv)).put("ct",enc(cipher.doFinal(secret)))
            prefs.edit().putString("pendingPairing",saved.toString()).apply()
        }finally{secret.fill(0)}
    }
    fun pendingPairing():PairStatus? {
        val sealed=prefs.getString("pendingPairing",null)?:return null
        return try{
            val data=JSONObject(sealed)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,localWrappingKey(),
                GCMParameterSpec(128,dec(data.getString("iv"))))
            cipher.updateAAD(pendingAad())
            val clear=cipher.doFinal(dec(data.getString("ct")))
            try {
                val json=JSONObject(String(clear,StandardCharsets.UTF_8))
                if(json.getLong("expires")<=System.currentTimeMillis())
                    throw SecurityException("Pairing expired")
                val factory=KeyFactory.getInstance("EC")
                exchange=KeyPair(factory.generatePublic(X509EncodedKeySpec(dec(json.getString("public")))),
                    factory.generatePrivate(PKCS8EncodedKeySpec(dec(json.getString("private")))))
                requestedId=json.getString("requestId")
                pairPollToken=json.getString("pollToken")
                PairStatus(requestedId!!,json.getString("code"))
            }finally{clear.fill(0)}
        }catch(_:Exception){
            clearPending()
            null
        }
    }
    private fun clearPending(){
        exchange=null;requestedId=null;pairPollToken=null
        prefs.edit().remove("pendingPairing").apply()
    }

    fun identityFingerprint():String=MessageDigest.getInstance("SHA-256")
        .digest(identity().public.encoded).take(8).joinToString(""){"%02x".format(it.toInt() and 255)}

    private fun identity():KeyPair {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        val existing=store.getKey(identityAlias,null)
        if(existing!=null) {
            val certificate=store.getCertificate(identityAlias)
            return KeyPair(certificate.publicKey,existing as java.security.PrivateKey)
        }
        val gen=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC,"AndroidKeyStore")
        gen.initialize(KeyGenParameterSpec.Builder(identityAlias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false).build())
        return gen.generateKeyPair()
    }
    private fun localWrappingKey():java.security.Key {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        store.getKey(wrappingAlias,null)?.let{return it}
        val generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(wrappingAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(false).build())
        return generator.generateKey()
    }
    private fun sealVault(vault:ByteArray):String {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,localWrappingKey())
        cipher.updateAAD(origin.toByteArray(StandardCharsets.UTF_8))
        return JSONObject().put("iv",enc(cipher.iv)).put("ct",enc(cipher.doFinal(vault))).toString()
    }
    fun vault():ByteArray {
        val saved=JSONObject(prefs.getString("sealedVault",null)?:throw SecurityException("No authorized Vault"))
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,localWrappingKey(),GCMParameterSpec(128,dec(saved.getString("iv"))))
        cipher.updateAAD(origin.toByteArray(StandardCharsets.UTF_8))
        return cipher.doFinal(dec(saved.getString("ct"))).also{check(it.size==32)}
    }
    /** Blocking network; only call from Dispatchers.IO. Never follow external redirects. */
    fun request(path:String,method:String="GET",body:JSONObject?=null,authenticated:Boolean=false,pollToken:String?=null):JSONObject {
        require(path.startsWith("/api/v1/") && !path.contains("://"))
        val connection=(URL(origin+path).openConnection() as HttpURLConnection)
        connection.connectTimeout=10000;connection.readTimeout=15000
        connection.instanceFollowRedirects=false
        connection.requestMethod=method
        connection.setRequestProperty("Accept","application/json")
        if(!pollToken.isNullOrEmpty())connection.setRequestProperty("X-SimHub-Pair-Token",pollToken)
        // Background polling is not user activity; idle sessions must still expire.
        if(authenticated && cookie.isNotEmpty())connection.setRequestProperty("Cookie",cookie)
        if(authenticated && method!="GET" && csrf.isNotEmpty())connection.setRequestProperty("X-SimHub-CSRF",csrf)
        if(body!=null){
            connection.doOutput=true
            connection.setRequestProperty("Content-Type","application/json")
            connection.outputStream.use{it.write(body.toString().toByteArray(StandardCharsets.UTF_8))}
        }
        try {
            val status=connection.responseCode
            if(status in 300..399)throw SecurityException("Refused redirect from controller origin")
            val response=(if(status in 200..299)connection.inputStream else connection.errorStream)
                ?.use{BoundedHttpReader.readUtf8(it,BoundedHttpReader.MAX_JSON_BYTES)} ?: "{}"
            val json=JSONObject(response)
            if(status !in 200..299) {
                if(status==401 && authenticated){cookie="";csrf=""}
                if(status==401 && path.startsWith("/api/v1/controllers/login/"))
                    throw SecurityException("管理终端授权已失效，请在网页重新批准")
                throw IllegalStateException(json.optString("message","HTTP $status").take(180))
            }
            if(path=="/api/v1/controllers/login/verify"){
                val setCookie=connection.getHeaderField("Set-Cookie")?:""
                cookie=setCookie.substringBefore(";")
                if(!cookie.startsWith("simhub_session="))throw SecurityException("No secure admin session")
                csrf=json.getString("csrfToken")
            }
            return json
        }finally{connection.disconnect()}
    }
    fun beginPairing(label:String):PairStatus {
        if(hasGrant())throw IllegalStateException("Already paired")
        val generator=KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"),SecureRandom())
        val key=generator.generateKeyPair()
        val data=request("/api/v1/controllers/pair","POST",JSONObject()
            .put("label",label.take(64))
            .put("identityPublic",enc(identity().public.encoded))
            .put("pairingProtocolVersion",2)
            .put("exchangePublic",enc(key.public.encoded)))
        exchange=key
        requestedId=data.getString("requestId")
        val code=data.getString("pairingCode")
        pairPollToken=data.getString("pollToken")
        rememberPending(requestedId!!,code,key,pairPollToken!!)
        return PairStatus(requestedId!!,code)
    }
    /** Returns true only after the admin approved and key authentication succeeded. */
    fun pollPairing():Boolean {
        val id=requestedId?:pendingPairing()?.requestId?:return false
        val result=request("/api/v1/controllers/pair/"+id,pollToken=pairPollToken ?: throw SecurityException("配对凭据已失效，请重新申请"))
        if(result.optString("state")=="expired"){
            clearPending()
            throw IllegalStateException("Pairing request expired")
        }
        if(result.optString("state")!="approved")return false
        val keypair=exchange?:throw IllegalStateException("Pairing expired after app restart")
        val envelope=result.getJSONObject("vaultEnvelope")
        val peer=KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(dec(envelope.getString("epk"))))
        val ka=KeyAgreement.getInstance("ECDH")
        ka.init(keypair.private);ka.doPhase(peer,true)
        val shared=ka.generateSecret()
        val info="simhub-controller-grant-v1|$id|$origin".toByteArray(StandardCharsets.UTF_8)
        val derived=hkdf(shared,dec(envelope.getString("salt")),info)
        shared.fill(0)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(derived,"AES"),
            GCMParameterSpec(128,dec(envelope.getString("iv"))))
        derived.fill(0)
        cipher.updateAAD("simhub-controller-vault-v1|$id|$origin".toByteArray(StandardCharsets.UTF_8))
        val raw=cipher.doFinal(dec(envelope.getString("ct")))
        try {
            require(raw.size==32)
            val expected=result.optString("grantVaultKeyId")
            val actual=enc(MessageDigest.getInstance("SHA-256").digest(raw).copyOf(16))
            if(expected.isNotEmpty() && actual!=expected)
                throw SecurityException("管理端收到的 Vault 密钥与云端身份不一致")
            val saved=sealVault(raw)
            prefs.edit().putString("controllerId",result.getString("controllerId"))
                .putString("sealedVault",saved)
                .putString("vaultId",result.optString("grantVaultId")).apply()
        }finally{raw.fill(0);clearPending()}
        return true
    }
    fun login() {
        if(!hasGrant())throw SecurityException("Controller authorization required")
        val id=controllerId()
        val challenge=request("/api/v1/controllers/login/challenge","POST",
            JSONObject().put("controllerId",id)).getString("challenge")
        val signed="simhub-controller-login-v1|$id|$challenge"
        val signature=Signature.getInstance("SHA256withECDSA")
        signature.initSign(identity().private)
        signature.update(signed.toByteArray(StandardCharsets.UTF_8))
        request("/api/v1/controllers/login/verify","POST",JSONObject()
            .put("controllerId",id).put("challenge",challenge)
            .put("signature",enc(signature.sign())))
        // Signed authentication does not by itself validate the E2EE Vault.
        // Reject a grant bound to another root, including older v0.15.1 grants.
        try {
            val status=request("/api/v1/vault/status",authenticated=true)
            if(!status.optBoolean("initialized"))throw SecurityException("请先在网页初始化共享 Vault")
            val storedId=prefs.getString("vaultId","").orEmpty()
            if(storedId.isNotEmpty() && storedId!=status.getString("vaultId"))
                throw SecurityException("共享 Vault 身份已改变，请重新授权管理终端")
            val raw=vault()
            val kid=try{enc(MessageDigest.getInstance("SHA-256").digest(raw).copyOf(16))}
                finally{raw.fill(0)}
            if(kid!=status.getString("keyId"))
                throw SecurityException("当前管理终端的 Vault 与云端不一致，请清除旧授权并重新配对")
            if(storedId.isEmpty())prefs.edit().putString("vaultId",status.getString("vaultId")).apply()
        }catch(failure:Exception) {
            cookie="";csrf=""
            throw failure
        }
    }
    fun api(path:String,method:String="GET",body:JSONObject?=null):JSONObject {
        if(cookie.isEmpty())login()
        return try{request(path,method,body,true)}
        catch(failure:Exception){
            // Retry reads after an expired cookie using signed reauthentication;
            // never replay a non-idempotent SMS or enrollment mutation.
            if(method=="GET" && cookie.isEmpty()){
                login()
                request(path,method,body,true)
            }else throw failure
        }
    }
    fun localReset(){
        cookie="";csrf="";clearPending()
        prefs.edit().clear().apply()
        val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        ks.deleteEntry(identityAlias);ks.deleteEntry(wrappingAlias)
    }
    fun decryptEvent(event:JSONObject,device:JSONObject,vault:ByteArray):JSONObject {
        val crypto=event.getJSONObject("ciphertext")
        val key:ByteArray
        val aad:String
        if(crypto.optInt("v")==1){
            key=vault.copyOf();aad="simhub-event-v1"
        }else{
            val kid=crypto.getString("kid")
            var wrapped:JSONObject?=null
            if(device.optString("keyId")==kid)wrapped=device.optJSONObject("wrappedKey")
            if(device.optString("pendingKeyId")==kid)wrapped=device.optJSONObject("pendingWrappedKey")
            val history=device.optJSONArray("historicalWrappedKeys")
            if(history!=null)for(i in 0 until history.length()){
                val row=history.optJSONObject(i)?:continue
                if(row.optString("keyId")==kid)wrapped=row.optJSONObject("wrappedKey")
            }
            key=if(wrapped!=null){
                val raw=decryptGcm(vault,wrapped,"simhub-node-key-wrap-v1|$kid")
                if(enc(MessageDigest.getInstance("SHA-256").digest(raw).copyOf(12))!=kid){
                    raw.fill(0);throw SecurityException("Node key mismatch")
                }
                raw
            }else{
                val derived=hkdf(vault,"simhub-device-v1".toByteArray(),
                    event.getString("deviceId").toByteArray())
                if(enc(MessageDigest.getInstance("SHA-256").digest(derived).copyOf(12))!=kid){
                    derived.fill(0);throw SecurityException("Historical Node Key unavailable")
                }
                derived
            }
            fun field(value:String)=enc(value.toByteArray(StandardCharsets.UTF_8))
            aad=listOf("simhub-event-v2",field(event.optString("deviceId")),
                field(event.optString("eventId")),field(event.optString("kind")),
                event.optLong("occurredAt").toString(),field(event.optString("subscriptionId")),
                if(event.optBoolean("hasOtp"))"1" else "0").joinToString("|")
        }
        try{return JSONObject(String(decryptGcm(key,crypto,aad),StandardCharsets.UTF_8))}
        finally{if(key!==vault)key.fill(0)}
    }
    fun encryptCommand(device:JSONObject,commandId:String,type:String,created:Long,
                       expires:Long,payload:JSONObject):JSONObject {
        val vault=vault()
        try {
            val kid=device.optString("keyId")
            val wrapped=device.optJSONObject("wrappedKey")
            val key=if(kid.isNotEmpty()&&wrapped!=null)
                decryptGcm(vault,wrapped,"simhub-node-key-wrap-v1|$kid")
            else hkdf(vault,"simhub-device-v1".toByteArray(),device.getString("id").toByteArray())
            try {
                fun field(value:String)=enc(value.toByteArray(StandardCharsets.UTF_8))
                val aad=listOf("simhub-command-v2",field(device.getString("id")),field(commandId),
                    field(type),created.toString(),expires.toString(),field(commandId)).joinToString("|")
                val iv=ByteArray(12).also{SecureRandom().nextBytes(it)}
                val cipher=Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,iv))
                cipher.updateAAD(aad.toByteArray(StandardCharsets.UTF_8))
                return JSONObject().put("v",2).put("alg","A256GCM")
                    .put("kid",enc(MessageDigest.getInstance("SHA-256").digest(key).copyOf(12)))
                    .put("iv",enc(iv)).put("ct",enc(cipher.doFinal(payload.toString().toByteArray())))
            }finally{key.fill(0)}
        }finally{vault.fill(0)}
    }
    companion object {
        fun enc(data:ByteArray):String=Base64.encodeToString(data,Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        fun dec(data:String):ByteArray=Base64.decode(data,Base64.URL_SAFE or Base64.NO_WRAP)
        private fun hex(value:String):String=MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).take(12).joinToString(""){"%02x".format(it)}
        fun hkdf(ikm:ByteArray,salt:ByteArray,info:ByteArray):ByteArray {
            val mac=Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(salt,"HmacSHA256"))
            val prk=mac.doFinal(ikm)
            mac.init(SecretKeySpec(prk,"HmacSHA256"))
            mac.update(info);mac.update(1.toByte())
            return mac.doFinal().also{prk.fill(0)}
        }
        fun decryptGcm(key:ByteArray,env:JSONObject,aad:String):ByteArray {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),
                GCMParameterSpec(128,dec(env.getString("iv"))))
            cipher.updateAAD(aad.toByteArray(StandardCharsets.UTF_8))
            return cipher.doFinal(dec(env.getString("ct")))
        }
    }
}
