package com.spoongecko.browser;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.os.Build;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";
    private static final int KEEPALIVE_JOB_ID = 42;

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            // Reschedule persisted JobScheduler job if available
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                JobScheduler js = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
                if (js != null) {
                    ComponentName comp = new ComponentName(context, KeepAliveJobService.class);
                    JobInfo.Builder builder = new JobInfo.Builder(KEEPALIVE_JOB_ID, comp)
                            .setPersisted(true)
                            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE);
                    // setPeriodic requires minimum interval; we rely on system constraints
                    try {
                        js.schedule(builder.build());
                    } catch (Exception e) {
                        Log.w(TAG, "JobScheduler schedule failed on boot: " + e.getMessage());
                    }
                }
            }

            // Optionally start the service after boot (do not start on ACTION_MY_PACKAGE_REPLACED if not desired)
            Intent svc = new Intent(context, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Exception e) {
            Log.w(TAG, "BootReceiver failed: " + e.getMessage());
        }
    }
}
