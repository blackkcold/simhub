package com.blackkcold.simhub

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Backward-compatible entry point: every surface now lives in HubActivity's shared Compose shell. */
class ControllerActivity:Activity(){
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        if(!HubModes.configured(this))HubModes.select(this,"controller")
        else HubModes.surface(this,"controller")
        startActivity(Intent(this,HubActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
