package net.pgaskin.windy;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Network;
import android.util.Log;

import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

public class WindFieldUpdateService extends JobService {
    private static final String TAG = "WindFieldUpdateService";
    private static final int JOB_ID_STARTUP = TAG.hashCode() + 1;
    private static final int JOB_ID_PERIODIC = TAG.hashCode() + 2;

    private static final String WIND_FIELD_URL = "https://windy.api.pgaskin.net/wind_field.jpg";
    private static final long WIND_FIELD_ESTIMATED_SIZE_BYTES = 1500*1000;
    private static final long MIN_FORCED_UPDATE_MILLIS = 15*60*1000;
    private static final long UPDATE_INTERVAL_MILLIS = 6*60*60*1000;
    private static final long UPDATE_SLACK_MILLIS = 60*60*1000;
    private static final long INITIAL_UPDATE_BACKOFF_MILLIS = 5*60*1000;
    private static final int UPDATE_BACKOFF_POLICY = JobInfo.BACKOFF_POLICY_EXPONENTIAL;

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.i(TAG, "doing wind field update");
        new Thread(() -> {
            try {
                Network net = params.getNetwork();
                if (net == null) {
                    throw new Exception("no network for job");
                }

                HttpsURLConnection conn = (HttpsURLConnection) net.openConnection(new URL(WIND_FIELD_URL));
                conn.setRequestProperty("User-Agent", "WindyLiveWallpaper/" + BuildConfig.VERSION_NAME + " " + System.getProperty("http.agent"));

                String etag = getPreferences(this).getString("etag", null);
                if (etag != null) {
                    conn.setRequestProperty("If-None-Match", etag);
                }

                conn.connect();

                int status = conn.getResponseCode();
                if (status != 200 && status != 304) {
                    throw new Exception("response status " + status + " (" + conn.getResponseMessage() + ")");
                }
                if (status == 200) {
                    etag = conn.getHeaderField("ETag");
                    Log.i(TAG, "processing updated wind field etag=" + (etag != null ? etag : "(null)"));
                    WindField.updateCache(this, conn.getInputStream());
                }
                if (etag != null) {
                    getPreferences(this).edit().putString("etag", etag).apply();
                } else {
                    Log.w(TAG, "no etag in wind field response, next update may re-download unnecessarily");
                    getPreferences(this).edit().remove("etag").apply();
                }

                Log.i(TAG, "successfully checked for wind field updates");
                this.jobFinished(params, false);
            } catch (Exception ex) {
                Log.e(TAG, "failed to check for wind field updates, requesting job reschedule: " + ex);
                this.jobFinished(params, true);
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    private static SharedPreferences getPreferences(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences("wind", Context.MODE_PRIVATE);
    }

    public static void scheduleNow(Context context) {
        long last = getPreferences(context).getLong("last_expedited_update", 0);
        if (Math.abs(System.currentTimeMillis() - last) < MIN_FORCED_UPDATE_MILLIS) {
            Log.w(TAG, "not scheduling requested expedited wind field update since last one was scheduled very recently");
            return;
        }
        Log.i(TAG, "scheduling expedited wind field update");
        try {
            JobInfo.Builder builder = new JobInfo.Builder(JOB_ID_STARTUP, new ComponentName(context, WindFieldUpdateService.class));
            builder.setExpedited(true);
            builder.setEstimatedNetworkBytes(WIND_FIELD_ESTIMATED_SIZE_BYTES, 0);
            builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED);
            builder.setBackoffCriteria(INITIAL_UPDATE_BACKOFF_MILLIS, UPDATE_BACKOFF_POLICY); // note: capped at 5h; may be longer during doze
            if (context.getSystemService(JobScheduler.class).schedule(builder.build()) != JobScheduler.RESULT_SUCCESS) {
                throw new Exception("job scheduler rejected job");
            }
        } catch (Exception ex) {
            Log.e(TAG, "failed to schedule expedited wind field update");
        }
        getPreferences(context).edit().putLong("last_expedited_update", System.currentTimeMillis()).apply();
    }

    public static void schedulePeriodic(Context context) {
        Log.i(TAG, "scheduling periodic wind field update");
        try {
            JobInfo.Builder builder = new JobInfo.Builder(JOB_ID_PERIODIC, new ComponentName(context, WindFieldUpdateService.class));
            builder.setPeriodic(UPDATE_INTERVAL_MILLIS, UPDATE_SLACK_MILLIS);
            builder.setRequiresBatteryNotLow(true);
            builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED);
            builder.setEstimatedNetworkBytes(WIND_FIELD_ESTIMATED_SIZE_BYTES, 0);
            builder.setBackoffCriteria(INITIAL_UPDATE_BACKOFF_MILLIS, UPDATE_BACKOFF_POLICY); // note: capped at 5h; may be longer during doze
            if (context.getSystemService(JobScheduler.class).schedule(builder.build()) != JobScheduler.RESULT_SUCCESS) {
                throw new Exception("job scheduler rejected job");
            }
        } catch (Exception ex) {
            Log.e(TAG, "failed to schedule periodic wind field update");
        }
    }
}
