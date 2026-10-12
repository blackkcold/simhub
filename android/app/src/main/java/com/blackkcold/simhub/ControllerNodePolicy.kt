package com.blackkcold.simhub

import org.json.JSONArray
import org.json.JSONObject

/**
 * Relay-reported send capability is always checked again by the node before transmission.
 * Companion mode is read-only by default; a temporary developer override is never
 * presented as ordinary full-SMS capability.
 */
internal object ControllerNodePolicy {
    fun channels(device: JSONObject): JSONArray {
        val state = device.optJSONObject("state") ?: JSONObject()
        return state.optJSONArray("channels") ?: state.optJSONArray("subscriptions") ?: JSONArray()
    }

    fun isDeveloperSend(device: JSONObject): Boolean {
        val state = device.optJSONObject("state") ?: JSONObject()
        return state.optString("smsMode") == "companion" &&
            state.optBoolean("smsDeveloperOverride") &&
            state.optBoolean("smsSendAllowed") &&
            state.optBoolean("smsSendPermission")
    }

    fun canSend(device: JSONObject): Boolean {
        if (!device.optBoolean("online") || device.optBoolean("revoked") ||
            device.optLong("resetRequestedAt", 0L) > 0L || channels(device).length() == 0) return false
        val state = device.optJSONObject("state") ?: JSONObject()
        if (device.optString("nodeType") == "modem" || state.optString("nodeType") == "modem")
            return state.opt("smsOperational") != false
        if (state.optString("smsMode") == "companion") return isDeveloperSend(device)
        return state.optString("smsMode") == "default" && state.optBoolean("smsSendPermission") &&
            (state.optBoolean("smsSendAllowed") || !state.has("smsSendAllowed"))
    }

    fun sendModeLabel(device: JSONObject): String {
        val state = device.optJSONObject("state") ?: JSONObject()
        return when {
            device.optString("nodeType") == "modem" -> "Modem 通道"
            state.optString("smsMode") == "companion" && isDeveloperSend(device) -> "开发者临时测试发送"
            state.optString("smsMode") == "companion" -> "不接管短信 · 只读"
            state.optString("smsMode") == "default" -> "完全接管 · 可发送需授权"
            else -> "短信接管模式未确认"
        }
    }
}
