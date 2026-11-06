package com.blackkcold.simhub

import org.json.JSONObject

/** Clears plaintext before accepting a changed enrollment/lifecycle/SMS purge scope. */
internal class ControllerInboxPrivacy(private val inbox:ControllerInboxState){
    private var scope:String?=null
    fun observe(devices:List<JSONObject>):Boolean {
        val next=ControllerHistoryScope.fingerprint(devices)
        val changed=scope!=null&&scope!=next
        if(changed)inbox.clear()
        scope=next
        return changed
    }
    fun clear(){scope=null;inbox.clear()}
}
