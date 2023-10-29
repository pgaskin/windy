package net.pgaskin.windy;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Network;
import android.os.Build;
import android.util.Log;

import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

public class WindFieldUpdateService extends JobService {
    private static final String TAG = "WindFieldUpdateService";

    private static final int JOB_ID_STARTUP = 72351003;
    private static final int JOB_ID_PERIODIC = 72351004;

    @Override
    public boolean onStartJob(JobParameters params) {
        String why = describeJob(params.getJobId());
        if (why == null) {
            Log.i(TAG, "unknown job id (it might be old), canceling job");
            this.getSystemService(JobScheduler.class).cancel(params.getJobId());
            return false;
        }

        Log.i(TAG, "doing wind field update (" + why + ")");
        new Thread(() -> {
            try {
                Network net = params.getNetwork();
                if (net == null) {
                    throw new Exception("no network for job");
                }

                HttpsURLConnection conn = (HttpsURLConnection) net.openConnection(new URL("https", BuildConfig.WIND_FIELD_API_HOST, "/wind_field.jpg"));
                conn.setRequestProperty("User-Agent", "WindyLiveWallpaper/" + BuildConfig.VERSION_NAME + " (" + BuildConfig.APPLICATION_ID + " " + BuildConfig.VERSION_CODE + "; " + BuildConfig.BUILD_TYPE + "; job:" + why + ") " + System.getProperty("http.agent"));

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
        long last = getPreferences(context).getLong("last_expedited_update", 0);
        if (Math.abs(System.currentTimeMillis() - last) < BuildConfig.WIND_FIELD_UPDATE_INTERVAL_MINIMUM * 60 * 1000) {
            Log.w(TAG, "not scheduling requested expedited wind field update since last one was scheduled very recently");
            return false;
        }
        getPreferences(context).edit().putLong("last_expedited_update", System.currentTimeMillis()).apply();
        return schedule(context, JOB_ID_STARTUP);
    }

    public static boolean schedulePeriodic(Context context) {
        return schedule(context, JOB_ID_PERIODIC);
    }

    private static boolean schedule(Context context, int jobID) {
        Log.i(TAG, "scheduling wind field update job (type: " + describeJob(jobID) + ")");
        try {
            JobInfo.Builder builder = new JobInfo.Builder(jobID, new ComponentName(context, WindFieldUpdateService.class));
            switch (jobID) {
                case JOB_ID_PERIODIC:
                    builder.setPeriodic(BuildConfig.WIND_FIELD_UPDATE_INTERVAL * 60 * 1000, BuildConfig.WIND_FIELD_UPDATE_INTERVAL * 60 * 1000 / 4);
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
            builder.setEstimatedNetworkBytes(BuildConfig.WIND_FIELD_SIZE_ESTIMATED * 1000, 0);
            builder.setBackoffCriteria(BuildConfig.WIND_FIELD_UPDATE_INTERVAL_MINIMUM * 60 * 1000, JobInfo.BACKOFF_POLICY_EXPONENTIAL);

            JobScheduler scheduler = context.getSystemService(JobScheduler.class);
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
