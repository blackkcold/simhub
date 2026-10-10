package com.blackkcold.simhub

import android.content.Context
import org.json.JSONArray
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Controller QR packages carry only a public management HTTPS origin.
 * No enrollment token, OTP, cookie, password or Vault key is accepted.
 */
object ControllerLinks {
    fun normalize(raw:String):String? {
        if(raw.length>2048 || raw.isBlank())return null
        return try {
            val uri=URI(raw.trim())
            if(uri.scheme!="https" || uri.host.isNullOrBlank() ||
                uri.rawUserInfo!=null || uri.rawQuery!=null || uri.rawFragment!=null ||
                (uri.rawPath!=null && uri.rawPath!="" && uri.rawPath!="/") ||
                uri.port>65535 || uri.port==0) null
            else {
                val host=uri.host!!.lowercase(java.util.Locale.ROOT)
                if(host=="localhost" || host.endsWith(".localhost") || host.endsWith(".local")) null
                else URI("https",null,host,uri.port,null,null,null).toASCIIString().removeSuffix("/")
            }
        }catch(_:Exception){null}
    }
    fun decode(raw:String):String? {
        if(raw.length>4096)return null
        val value=raw.trim()
        normalize(value)?.let{return it}
        return try {
            val uri=URI(value)
            if(uri.scheme!="simhub" || uri.host!="controller" || !uri.rawPath.isNullOrEmpty() ||
                uri.rawFragment!=null || uri.rawUserInfo!=null) return null
            val parts=uri.rawQuery?.split("&") ?:return null
            if(parts.size!=1 || !parts[0].startsWith("url="))return null
            normalize(URLDecoder.decode(parts[0].substringAfter("="),StandardCharsets.UTF_8.name()))
        }catch(_:Exception){null}
    }
}
object ControllerProfiles {
    private const val PREF="simhub_controller_profiles_v1" // Migrates existing v0.14.0 profiles in place
    private fun prefs(c:Context)=c.getSharedPreferences(PREF,Context.MODE_PRIVATE)
    fun list(c:Context):List<String>{
        val a=runCatching { JSONArray(prefs(c).getString("origins","[]")) }.getOrDefault(JSONArray())
        return (0 until a.length()).mapNotNull { ControllerLinks.normalize(a.optString(it)) }.distinct()
    }
    fun selected(c:Context):String? {
        val origin=ControllerLinks.normalize(prefs(c).getString("selected","") ?: "")
        return origin?.takeIf { it in list(c) } ?:list(c).firstOrNull()
    }
    fun add(c:Context,origin:String):String? {
        val checked=ControllerLinks.decode(origin) ?:return null
        val all=(list(c)+checked).distinct()
        if(all.size>20)return null
        prefs(c).edit().putString("origins",JSONArray(all).toString()).putString("selected",checked).apply()
        return checked
    }
    fun select(c:Context,origin:String):Boolean {
        val checked=ControllerLinks.normalize(origin) ?:return false
        if(checked !in list(c))return false
        prefs(c).edit().putString("selected",checked).apply()
        return true
    }
    fun remove(c:Context,origin:String):Boolean {
        val old=list(c)
        if(origin !in old)return false
        val remaining=old.filterNot{it==origin}
        val next=if(selected(c)==origin)remaining.firstOrNull() else selected(c)
        prefs(c).edit().putString("origins",JSONArray(remaining).toString())
            .putString("selected",next).apply()
        // This removes a profile shortcut, not the browser's stored cookies/Vault.
        return true
    }
}
