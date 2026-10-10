package com.blackkcold.simhub;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.SystemClock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;

/** Persisted recovery jobs; triggers are coalesced instead of restarting a running Job. */
public final class SyncJobService extends JobService {
    private static final int PERIODIC=7101,ONCE=7102,DELAYED_A=7103,DELAYED_B=7104;
    private static final ThreadLocal<Integer> ACTIVE_JOB=new ThreadLocal<>();
    private static final ConcurrentHashMap<Integer,FutureTask<Void>> RUNNING=new ConcurrentHashMap<>();
    private static final AtomicBoolean DIRTY=new AtomicBoolean(false);
    private static final long[] DELAYED_AT=new long[2];

    public static void schedule(Context c){
        try{
            JobInfo j=new JobInfo.Builder(PERIODIC,new ComponentName(c,SyncJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                    .setPeriodic(EnergyPolicy.maintenanceIntervalMs()).build();
            c.getSystemService(JobScheduler.class).schedule(j);
        }catch(Exception e){AppLogger.e(c,"SyncJob","Periodic scheduling failed",e);}
    }
    public static synchronized void scheduleNow(Context c){
        try{
            JobScheduler scheduler=c.getSystemService(JobScheduler.class);
            if(scheduler==null)return;
            if(!RUNNING.isEmpty()){DIRTY.set(true);return;}
            if(scheduler.getPendingJob(ONCE)!=null)return;
            JobInfo j=new JobInfo.Builder(ONCE,new ComponentName(c,SyncJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build();
            scheduler.schedule(j);
        }catch(Exception e){AppLogger.e(c,"SyncJob","Immediate scheduling failed",e);}
    }
    public static synchronized void scheduleAfter(Context c,long delayMillis){
        try{
            long delay=Math.max(1000,Math.min(delayMillis,15L*60*1000));
            JobScheduler scheduler=c.getSystemService(JobScheduler.class);
            if(scheduler==null)return;
            int running=ACTIVE_JOB.get()==null?-1:ACTIVE_JOB.get();
            int next=(running==DELAYED_A||RUNNING.containsKey(DELAYED_A))?DELAYED_B:DELAYED_A;
            if(next==running||RUNNING.containsKey(next)){DIRTY.set(true);return;}
            int slot=next==DELAYED_A?0:1;
            long due=SystemClock.elapsedRealtime()+delay;
            if(scheduler.getPendingJob(next)!=null&&DELAYED_AT[slot]>0&&DELAYED_AT[slot]<=due)return;
            DELAYED_AT[slot]=due;
            JobInfo job=new JobInfo.Builder(next,new ComponentName(c,SyncJobService.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(delay).build();
            scheduler.schedule(job);
        }catch(Exception e){AppLogger.e(c,"SyncJob","Delayed scheduling failed",e);}
    }
    @Override public boolean onStartJob(JobParameters p){
        final int id=p.getJobId();
        final Context app=getApplicationContext();
        AppLogger.i(this,"SyncJob","Job started id="+id);
        FutureTask<Void> task=new FutureTask<>(()->{
            try{
                ACTIVE_JOB.set(id);
                new ApiClient(app).syncCycle();
                return null;
            }finally{
                try{AppUpdater.checkInBackground(app);}
                catch(Exception error){AppLogger.e(app,"SyncJob","Update check failed",error);}
                ACTIVE_JOB.remove();
                RUNNING.remove(id);
                jobFinished(p,false);
                if(DIRTY.getAndSet(false))scheduleNow(app);
            }
        });
        RUNNING.put(id,task);
        AgentExecutors.sync().execute(task);
        return true;
    }
    @Override public boolean onStopJob(JobParameters p){
        FutureTask<Void> task=RUNNING.remove(p.getJobId());
        if(task!=null)task.cancel(true);
        AppLogger.w(this,"SyncJob","Job interrupted id="+p.getJobId());
        return true;
    }
}
