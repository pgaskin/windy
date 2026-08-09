// SPDX-FileCopyrightText: 2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later
package net.pgaskin.windy;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

public class LocationUpdateService extends JobService {
    private static final String TAG = "LocationUpdateService";

    private static final int JOB_ID_PERIODIC = 72351005;

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.i(TAG, "doing periodic location update");
        LocationConsentActivity.updateLocation(this, false);
        return false; // don't block
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }

    public static void schedule(Context context) {
        final JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        final long interval = Prefs.locationInterval(context);
        if (interval <= 0) {
            Log.i(TAG, "location updates are manual, canceling location update job");
            scheduler.cancel(JOB_ID_PERIODIC);
            return;
        }

        final long intervalMillis = Math.max(interval * 1000, JobInfo.getMinPeriodMillis());
        Log.i(TAG, "scheduling location update job every " + intervalMillis / 1000 + "s");
        try {
            final JobInfo job = new JobInfo.Builder(JOB_ID_PERIODIC, new ComponentName(context, LocationUpdateService.class))
                    .setPeriodic(intervalMillis, intervalMillis / 4)
                    .setRequiresBatteryNotLow(true)
                    .build();
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
                throw new RuntimeException("Job scheduler rejected job");
            }
        } catch (Exception ex) {
            Log.e(TAG, "failed to schedule location update job", ex);
        }
    }
}
