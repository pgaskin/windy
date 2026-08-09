// SPDX-FileCopyrightText: 2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later
package net.pgaskin.windy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

public class WindFieldConsentActivity extends Activity {
    private static final String TAG = "WindFieldConsent";

    public static void request(Context context) {
        if (!Prefs.dataConsentPending(context)) {
            return;
        }
        Log.i(TAG, "asking about wind data updates");
        final Intent intent = new Intent(context, WindFieldConsentActivity.class);
        if (!(context instanceof Activity)) {
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        }
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!Prefs.dataConsentPending(this)) {
            finish();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(R.string.data_consent_message)
                .setCancelable(false)
                .setPositiveButton(R.string.accept, (dialog, which) -> {
                    Log.i(TAG, "wind data updates accepted");
                    this.apply(Prefs.DEFAULT_DATA_INTERVAL, true, false);
                })
                .setNegativeButton(R.string.decline, (dialog, which) -> this.showDeclinedDialog())
                .show();
    }

    private void showDeclinedDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(R.string.data_consent_declined_message)
                .setCancelable(false)
                .setPositiveButton(R.string.data_consent_automatic, (dialog, which) -> {
                    Log.i(TAG, "wind data updates accepted after all");
                    this.apply(Prefs.DEFAULT_DATA_INTERVAL, true, false);
                })
                .setNegativeButton(R.string.data_consent_once, (dialog, which) -> {
                    Log.i(TAG, "wind data will only be updated manually");
                    this.apply(Prefs.INTERVAL_MANUAL, true, true);
                })
                .setNeutralButton(R.string.data_consent_never, (dialog, which) -> {
                    Log.w(TAG, "wind data updates declined, using the built-in field");
                    this.apply(Prefs.INTERVAL_NEVER, false, false);
                })
                .show();
    }

    /**
     * Saves the answer, reschedules the update jobs, and finishes.
     */
    private void apply(long interval, boolean consent, boolean updateNow) {
        Prefs.setDataInterval(this, interval);
        Prefs.setDataConsent(this, consent);
        WindFieldUpdateService.schedulePeriodic(this);
        WindFieldUpdateService.scheduleStartup(this);
        WindField.invalidate(); // the built-in texture may have been swapped for the cached one
        if (updateNow) {
            this.updateNow();
        }
        this.finish();
    }

    private void updateNow() {
        final Context context = this.getApplicationContext();
        new Thread(() -> {
            try {
                WindFieldUpdateService.update(context, null, "consent");
            } catch (Exception ex) {
                Log.e(TAG, "wind field update failed: " + ex);
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(context, R.string.update_failed, Toast.LENGTH_SHORT).show());
            }
        }).start();
    }
}
