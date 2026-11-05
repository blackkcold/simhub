package com.blackkcold.simhub;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CommandStatusInstrumentedTest {
    @Test public void concurrentCallbacksKeepTerminalStateAndReceipt()throws Exception{
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        LocalStore store=LocalStore.get(c);
        String id="receipt-test-"+UUID.randomUUID();
        ExecutorService threads=Executors.newFixedThreadPool(3);
        try{
            assertTrue(store.claimCommand(id,"sms.send"));
            CountDownLatch start=new CountDownLatch(1);
            Future<?> a=threads.submit(()->{try{start.await();store.finishCommandAndQueueAck(id,"submitted",new JSONObject());}catch(Exception e){throw new RuntimeException(e);}});
            Future<?> b=threads.submit(()->{try{start.await();store.finishCommandAndQueueAck(id,"sent",new JSONObject());}catch(Exception e){throw new RuntimeException(e);}});
            Future<?> d=threads.submit(()->{try{start.await();store.finishCommandAndQueueAck(id,"delivered",new JSONObject());}catch(Exception e){throw new RuntimeException(e);}});
            start.countDown();a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);d.get(10,TimeUnit.SECONDS);
            assertEquals("delivered",store.commandState(id));
            JSONArray found=new JSONArray();
            for(JSONObject ack:store.pendingCommandAcks(100))if(id.equals(ack.optString("commandId")))found.put(ack);
            assertEquals(1,found.length());assertEquals("delivered",found.getJSONObject(0).getString("state"));
            store.finishCommandAndQueueAck(id,"submitted",new JSONObject());
            assertEquals("delivered",store.commandState(id));
        }finally{
            threads.shutdownNow();
            store.getWritableDatabase().delete("command_acks","command_id=?",new String[]{id});
            store.getWritableDatabase().delete("processed_commands","id=?",new String[]{id});
        }
    }
}
