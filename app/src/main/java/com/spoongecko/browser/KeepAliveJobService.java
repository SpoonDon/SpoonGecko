package com.spoongecko.browser;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public class KeepAliveJobService extends JobService {
    private static final String TAG = "KeepAliveJobService";

    @Override
    public boolean onStartJob(JobParameters params) {
        try {
            Intent svc = new Intent(this, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Exception e) {
            Log.w(TAG, "Job start failed: " + e.getMessage());
        }
        // No long-running work here; job finished
        jobFinished(params, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
