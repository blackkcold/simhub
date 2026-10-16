package com.blackkcold.simhub

/** In-memory local-auth state; never inferred from a stored Relay grant. */
object ControllerAccessGate {
    private val unlocked=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun grant(origin:String){unlocked.add(origin)}
    fun permits(origin:String)=unlocked.contains(origin)
    fun clear(origin:String){unlocked.remove(origin)}
    fun clearAll(){unlocked.clear()}
}
