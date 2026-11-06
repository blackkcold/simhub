package com.blackkcold.simhub;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.telephony.SubscriptionManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.UUID;
import static org.junit.Assert.*;

/** Runs on min-SDK emulators with synthetic data, never sends a radio SMS. */
@RunWith(AndroidJUnit4.class)
public class NodeRecoveryInstrumentedTest {
    private Context isolatedContext(){
        Context real=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String prefix="node-recovery-"+UUID.randomUUID()+"-";
        return new ContextWrapper(real){
            @Override public Context getApplicationContext(){return this;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){
                return super.getSharedPreferences(prefix+name,mode);
            }
        };
    }
    @Test public void smsManagerFactorySupportsMinimumSdk(){
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertNotNull(SmsSender.managerForSubscription(c,SubscriptionManager.INVALID_SUBSCRIPTION_ID));
    }
    @Test public void localSmsEnvelopeSurvivesUnpairedAndResetState()throws Exception{
        Context c=isolatedContext();
        AgentConfig config=new AgentConfig(c);
        assertFalse(config.isEnrolled());
        JSONObject envelope=PendingSmsCrypto.encrypt(c,new JSONObject().put("body","synthetic pending SMS"));
        config.clearEnrollment();
        assertEquals("synthetic pending SMS",PendingSmsCrypto.decrypt(c,envelope).getString("body"));
    }
}
