package com.blackkcold.simhub

import android.content.Context

/** UI roles are independent of enrollment and Node Key state. */
object HubModes {
    private const val PREF = "simhub_ui_roles_v1"
    private fun prefs(context:Context) = context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
    fun configured(context:Context):Boolean =
        prefs(context).contains("role") || AgentConfig(context).isEnrolled()
    fun role(context:Context):String =
        prefs(context).getString("role",null) ?: if(AgentConfig(context).isEnrolled()) "node" else ""
    fun surface(context:Context):String =
        prefs(context).getString("surface","node") ?: "node"
    fun select(context:Context,role:String) {
        require(role in setOf("node","controller","both"))
        prefs(context).edit().putString("role",role)
            .putString("surface",if(role=="controller") "controller" else "node").apply()
    }
    fun surface(context:Context,surface:String) {
        require(surface in setOf("node","controller"))
        prefs(context).edit().putString("surface",surface).apply()
    }
}
