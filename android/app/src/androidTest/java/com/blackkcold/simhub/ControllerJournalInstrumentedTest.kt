package com.blackkcold.simhub

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ControllerJournalInstrumentedTest {
    @Test fun localJournalIsEncryptedAndBoundToItsControllerGrant(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val origin="https://journal-${UUID.randomUUID()}.example.invalid"
        val hash=MessageDigest.getInstance("SHA-256").digest(origin.toByteArray())
            .take(12).joinToString(""){"%02x".format(it)}
        val prefs=context.getSharedPreferences("simhub_native_controller_$hash",Context.MODE_PRIVATE)
        // Synthetic grant flags exercise local Keystore storage only; no HTTP or Vault use.
        prefs.edit().putString("controllerId","synthetic-controller").putString("sealedVault","synthetic").commit()
        val first=ControllerSecureClient(context,origin)
        try{
            first.writeLocalJournal("send-workspace",JSONObject().put("draft",JSONObject().put("message","private synthetic draft")))
            assertFalse(prefs.getString("sealedLocal:send-workspace","")!!.contains("private synthetic draft"))
            val recreated=ControllerSecureClient(context,origin)
            assertEquals("private synthetic draft",recreated.readLocalJournal("send-workspace").getJSONObject("draft").getString("message"))
            prefs.edit().putString("controllerId","different-controller").commit()
            try{recreated.readLocalJournal("send-workspace");fail("Rebound journal accepted")}
            catch(_:javax.crypto.AEADBadTagException){}
        }finally{first.localReset()}
    }
}
