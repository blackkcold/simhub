package com.blackkcold.simhub;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.content.pm.PackageManager;
import java.util.concurrent.atomic.AtomicBoolean;
import rikka.shizuku.Shizuku;

/** Explicit local UI invocation only; does not expose privileged shell through HTTP. */
public final class ShizukuShellClient {
    public interface Callback { void onFinished(String result,String error); }
    private ShizukuShellClient(){}
    public static void execute(Context context,String command,Callback callback){
        if(!Shizuku.pingBinder()||Shizuku.checkSelfPermission()!=PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("Authorize SIM Hub in Shizuku first");
        if(command==null||command.isBlank()||command.length()>2048)
            throw new IllegalArgumentException("Command must contain 1-2048 characters");
        Shizuku.UserServiceArgs args=new Shizuku.UserServiceArgs(new ComponentName(context,ShizukuShellService.class))
            .processNameSuffix("compat_shell").tag("simhub-compat-shell-v1").version(1).daemon(false);
        Handler main=new Handler(Looper.getMainLooper());
        AtomicBoolean finished=new AtomicBoolean(false);
        ServiceConnection connection=new ServiceConnection(){
            private void deliver(String result,String error){
                if(finished.compareAndSet(false,true))main.post(()->callback.onFinished(result,error));
                try{Shizuku.unbindUserService(args,this,false);}catch(Exception ignored){}
            }
            @Override public void onServiceConnected(ComponentName name,IBinder binder){
                Thread worker=new Thread(()->{
                    try{deliver(IShizukuShell.Stub.asInterface(binder).run(command),null);}
                    catch(Exception error){deliver(null,error.getClass().getSimpleName());}
                },"simhub-shizuku-command");
                worker.setDaemon(true);worker.start();
            }
            @Override public void onServiceDisconnected(ComponentName name){deliver(null,"Shizuku service disconnected");}
        };
        Shizuku.bindUserService(args,connection);
    }
}
