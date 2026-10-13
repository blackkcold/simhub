package com.blackkcold.simhub;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Runs in Shizuku's shell/root UserService process, never in the Relay process. */
public final class ShizukuShellService extends IShizukuShell.Stub {
    private static final int MAX_OUTPUT=16384;
    public ShizukuShellService() {}
    private static String readBounded(InputStream input) throws Exception {
        try(input;ByteArrayOutputStream data=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[1024];int n;
            while((n=input.read(buffer))!=-1){
                if(data.size()<MAX_OUTPUT)data.write(buffer,0,Math.min(n,MAX_OUTPUT-data.size()));
            }
            return data.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    @Override public String run(String command) {
        long started=System.nanoTime();
        JSONObject result=new JSONObject();
        Process process=null;
        ExecutorService readers=Executors.newFixedThreadPool(2);
        try {
            if(command==null||command.isBlank()||command.length()>2048)
                throw new IllegalArgumentException("Command must contain 1-2048 characters");
            // Shell access is granted by the local Shizuku manager to this APK.
            process=new ProcessBuilder("/system/bin/sh","-c",command).start();
            final Process active=process;
            Future<String> stdout=readers.submit(()->readBounded(active.getInputStream()));
            Future<String> stderr=readers.submit(()->readBounded(active.getErrorStream()));
            boolean finished=active.waitFor(15,TimeUnit.SECONDS);
            if(!finished)active.destroyForcibly();
            result.put("exitCode",finished?active.exitValue():-1);
            result.put("timedOut",!finished);
            result.put("stdout",stdout.get(3,TimeUnit.SECONDS));
            result.put("stderr",stderr.get(3,TimeUnit.SECONDS));
        } catch(Exception failure) {
            try{result.put("error",failure.getClass().getSimpleName());}catch(Exception ignored){}
            if(process!=null)process.destroyForcibly();
        } finally {
            readers.shutdownNow();
            try{result.put("durationMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));}catch(Exception ignored){}
        }
        return result.toString();
    }
    /** Shizuku UserService process cleanup entrypoint. */
    public void destroy(){System.exit(0);}
}
