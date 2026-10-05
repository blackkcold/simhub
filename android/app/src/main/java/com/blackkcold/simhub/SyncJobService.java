package com.blackkcold.simhub;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import java.util.concurrent.Executors;

public final class SyncJobService extends JobService {
    private static final int PERIODIC=7101,ONCE=7102;
    public static void schedule(Context c){try{JobInfo j=new JobInfo.Builder(PERIODIC,new ComponentName(c,SyncJobService.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(15*60*1000L).build();c.getSystemService(JobScheduler.class).schedule(j);}catch(Exception ignored){}}
    public static void scheduleNow(Context c){try{JobInfo j=new JobInfo.Builder(ONCE,new ComponentName(c,SyncJobService.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(0).setOverrideDeadline(5000).build();c.getSystemService(JobScheduler.class).schedule(j);}catch(Exception ignored){}}
    @Override public boolean onStartJob(JobParameters p){Executors.newSingleThreadExecutor().execute(()->{new ApiClient(this).syncCycle();jobFinished(p,false);});return true;}
    @Override public boolean onStopJob(JobParameters p){return true;}
}
