// SPDX-FileCopyrightText: 2023-2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later
package net.pgaskin.windy;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.util.Log;

import java.net.URL;
import java.net.UnknownHostException;

import javax.net.ssl.HttpsURLConnection;

public class WindFieldUpdateService extends JobService {
    private static final String TAG = "WindFieldUpdateService";

    private static final int JOB_ID_STARTUP = 72351003;
    private static final int JOB_ID_PERIODIC = 72351004;

    @Override
    public boolean onStartJob(JobParameters params) {
        final String why = describeJob(params.getJobId());
        if (why == null) {
            Log.i(TAG, "unknown job id (it might be old), canceling job");
            this.getSystemService(JobScheduler.class).cancel(params.getJobId());
            return false;
        }

        Log.i(TAG, "doing wind field update (" + why + ")");
        new Thread(() -> {
            try {
                final Network net = params.getNetwork();
                if (net == null) {
                    throw new Exception("no network for job");
                }
                update(this, net, "job:" + why);
                this.jobFinished(params, false);
            } catch (Exception ex) {
                Log.e(TAG, "failed to check for wind field updates, requesting job reschedule: " + ex);
                this.jobFinished(params, true);
            }
        }).start();
        return true;
    }

    /** Fetches the wind field, updating the cache if it changed, blocking. */
    public static void update(Context context, Network net, String why) throws Exception {
        final URL url = new URL(Prefs.dataUrl(context));
        if (!"https".equals(url.getProtocol())) {
            throw new Exception("wind field url must be https");
        }

        if (net != null) {
            final NetworkCapabilities cap = context.getSystemService(ConnectivityManager.class).getNetworkCapabilities(net);
            Log.i(TAG, "updating wind field from " + url + " using network " + net + " with capabilities " + cap);
            if (cap != null && !cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                Log.i(TAG, "network for job is a VPN, seeing if we need to work around connectivity bugs");
                try {
                    net.getAllByName(url.getHost());
                    Log.d(TAG, "nope, everything works fine");
                } catch (UnknownHostException ex) {
                    net = null;
                    Log.w(TAG, "WORKAROUND: no connectivity on VPN (" + ex + "), not explicitly using network (will let the system decide)...");
                }
            }
        } else {
            Log.i(TAG, "updating wind field from " + url);
        }

        final HttpsURLConnection conn = (HttpsURLConnection) (net != null ? net.openConnection(url) : url.openConnection());
        conn.setRequestProperty("User-Agent", "WindyLiveWallpaper/" + BuildConfig.VERSION_NAME + " (" + BuildConfig.APPLICATION_ID + " " + BuildConfig.VERSION_CODE + "; " + BuildConfig.BUILD_TYPE + "; " + why + ") " + System.getProperty("http.agent"));

        String etag = getPreferences(context).getString("etag", null);
        if (etag != null) {
            conn.setRequestProperty("If-None-Match", etag);
        }

        conn.connect();

        final int status = conn.getResponseCode();
        if (status != 200 && status != 304) {
            throw new Exception("response status " + status + " (" + conn.getResponseMessage() + ")");
        }
        if (status == 200) {
            etag = conn.getHeaderField("ETag");
            Log.i(TAG, "processing updated wind field etag=" + (etag != null ? etag : "(null)"));
            WindField.updateCache(context, conn.getInputStream());
        }
        if (etag != null) {
            getPreferences(context).edit().putString("etag", etag).apply();
        } else {
            Log.w(TAG, "no etag in wind field response, next update may re-download unnecessarily");
            getPreferences(context).edit().remove("etag").apply();
        }
        final String source = conn.getHeaderField("X-GFS-Source");
        Log.i(TAG, "wind field source is " + (source != null ? source : "(null)"));

        getPreferences(context).edit()
                .putLong("last_updated", System.currentTimeMillis())
                .putString("source", source) // removes it if null
                .apply();

        Log.i(TAG, "successfully checked for wind field updates");
    }

    public static long lastUpdated(Context context) {
        return getPreferences(context).getLong("last_updated", 0);
    }

    /** Whether the data has ever been fetched, i.e. this isn't a fresh installation. */
    static boolean hasHistory(Context context) {
        return !getPreferences(context).getAll().isEmpty();
    }

    public static String lastSource(Context context) {
        return getPreferences(context).getString("source", null);
    }

    public static void clearEtag(Context context) {
        getPreferences(context).edit().remove("etag").remove("last_updated").remove("source").apply();
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    private static SharedPreferences getPreferences(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences("wind", Context.MODE_PRIVATE);
    }

    public static String describeJob(int jobID) {
        switch (jobID) {
            case JOB_ID_PERIODIC:
                return "periodic";
            case JOB_ID_STARTUP:
                return "startup";
            default:
                return null;
        }
    }

    public static boolean scheduleStartup(Context context) {
        if (Prefs.dataConsentPending(context)) {
            Log.i(TAG, "not scheduling initial update without consent");
            return false;
        }
        if (Prefs.dataInterval(context) <= 0) {
            Log.i(TAG, "automatic wind field updates are disabled, not scheduling initial update");
            return false;
        }
        final long last = getPreferences(context).getLong("last_expedited_update", 0);
        if (Math.abs(System.currentTimeMillis() - last) < BuildConfig.WIND_FIELD_UPDATE_INTERVAL_MINIMUM * 60 * 1000) {
            Log.w(TAG, "not scheduling requested expedited wind field update since last one was scheduled very recently");
            return false;
        }
        getPreferences(context).edit().putLong("last_expedited_update", System.currentTimeMillis()).apply();
        return schedule(context, JOB_ID_STARTUP);
    }

    public static boolean schedulePeriodic(Context context) {
        if (Prefs.dataConsentPending(context) || Prefs.dataInterval(context) <= 0) {
            Log.i(TAG, "automatic wind field updates are disabled, canceling periodic update job");
            context.getSystemService(JobScheduler.class).cancel(JOB_ID_PERIODIC);
            return false;
        }
        return schedule(context, JOB_ID_PERIODIC);
    }

    private static boolean schedule(Context context, int jobID) {
        Log.i(TAG, "scheduling wind field update job (type: " + describeJob(jobID) + ")");
        try {
            final JobInfo.Builder builder = new JobInfo.Builder(jobID, new ComponentName(context, WindFieldUpdateService.class));
            switch (jobID) {
                case JOB_ID_PERIODIC:
                    final long interval = Math.max(Prefs.dataInterval(context) * 1000, JobInfo.getMinPeriodMillis());
                    builder.setPeriodic(interval, interval / 4);
                    builder.setRequiresBatteryNotLow(true);
                    break;
                case JOB_ID_STARTUP:
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        builder.setExpedited(true);
                    }
                    break;
                default:
                    throw new IllegalArgumentException("Unknown jobID");
            }
            builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED);
            builder.setEstimatedNetworkBytes(256 * 1000, 0);
            builder.setBackoffCriteria(BuildConfig.WIND_FIELD_UPDATE_INTERVAL_MINIMUM * 60 * 1000, JobInfo.BACKOFF_POLICY_EXPONENTIAL);

            final JobScheduler scheduler = context.getSystemService(JobScheduler.class);
            if (scheduler.schedule(builder.build()) != JobScheduler.RESULT_SUCCESS) {
                throw new RuntimeException("Job scheduler rejected job");
            }
        } catch (Exception ex) {
            Log.e(TAG, "failed to schedule wind field update job (type: " + describeJob(jobID) + ")");
            return false;
        }
        return true;
    }
}
