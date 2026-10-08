package com.blackkcold.simhub;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

public final class SyncJobService extends JobService {
    private static final int PERIODIC=7101,ONCE=7102;
    private static final ConcurrentHashMap<Integer, Future<?>> RUNNING=new ConcurrentHashMap<>();
    public static void schedule(Context c){try{JobInfo j=new JobInfo.Builder(PERIODIC,new ComponentName(c,SyncJobService.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(15*60*1000L).build();c.getSystemService(JobScheduler.class).schedule(j);}catch(Exception e){AppLogger.e(c,"SyncJob","Periodic scheduling failed",e);}}
    public static void scheduleNow(Context c){try{JobInfo j=new JobInfo.Builder(ONCE,new ComponentName(c,SyncJobService.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(0).setOverrideDeadline(5000).build();c.getSystemService(JobScheduler.class).schedule(j);}catch(Exception e){AppLogger.e(c,"SyncJob","Immediate scheduling failed",e);}}
    @Override public boolean onStartJob(JobParameters p){AppLogger.i(this,"SyncJob","Job started id="+p.getJobId());Future<?> future=AgentExecutors.sync().submit(()->{try{new ApiClient(getApplicationContext()).syncCycle();}finally{RUNNING.remove(p.getJobId());jobFinished(p,false);}});Future<?> previous=RUNNING.put(p.getJobId(),future);if(previous!=null&&!previous.isDone())previous.cancel(true);return true;}
    @Override public boolean onStopJob(JobParameters p){Future<?> future=RUNNING.remove(p.getJobId());if(future!=null)future.cancel(true);AppLogger.w(this,"SyncJob","Job interrupted id="+p.getJobId());return true;}
}