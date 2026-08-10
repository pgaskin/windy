// SPDX-FileCopyrightText: 2023-2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later
package net.pgaskin.windy;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

public class LocationConsentActivity extends Activity {
    private static final String TAG = "LocationConsentActivity";

    private static final String STATE_REQUESTED_INITIAL = "requestedInitial";
    private static final String STATE_DONE_FOREGROUND = "doneForeground";
    private static final String STATE_DONE_BACKGROUND = "doneBackground";

    private boolean requestedInitial;
    private boolean doneForeground;
    private boolean doneBackground;

    public static void request(Context context) {
        if (Location.hasPermission(context, false) || Location.consentDone(context)) {
            return;
        }
        Log.i(TAG, "permissions missing, starting location flow");
        final Intent intent = new Intent(context, LocationConsentActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle s) {
        if (s != null) {
            requestedInitial = s.getBoolean(STATE_REQUESTED_INITIAL);
            doneForeground = s.getBoolean(STATE_DONE_FOREGROUND);
            doneBackground = s.getBoolean(STATE_DONE_BACKGROUND);
        }
        super.onCreate(s);
    }

    @Override
    protected void onSaveInstanceState(Bundle s) {
        s.putBoolean(STATE_REQUESTED_INITIAL, requestedInitial);
        s.putBoolean(STATE_DONE_FOREGROUND, doneForeground);
        s.putBoolean(STATE_DONE_BACKGROUND, doneBackground);
        super.onSaveInstanceState(s);
    }

    @Override
    protected void onResume() {
        super.onResume();
        doNextPermissionRequest(true);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        requestedInitial = true;
        for (int i = 0; i < permissions.length; i++) {
            final boolean granted = grantResults[i] != PackageManager.PERMISSION_DENIED;
            if (permissions[i].equals(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                logResult(false, granted);
                doneForeground = true;
            }
            if (permissions[i].equals(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                logResult(true, granted);
                doneBackground = true;
            }
        }
        doNextPermissionRequest(false);
    }

    private void doNextPermissionRequest(boolean initial) {
        if (initial == requestedInitial) {
            return;
        }
        Log.i(TAG, requestedInitial ? "handling permission request result" : "doing initial permission request");

        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "saving location since we have access");
            Location.updateInForeground(this);
        }

        // the dialogs are always shown first so the permission is never
        // requested without explaining what it's for
        if (!doneForeground && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            showConsentDialog(false);
            return;
        }
        if (!doneBackground && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            showConsentDialog(true);
            return;
        }
        finishFlow();
    }

    private void showConsentDialog(boolean background) {
        final String what = background ? "background" : "foreground";
        final String permission = background
                ? Manifest.permission.ACCESS_BACKGROUND_LOCATION
                : Manifest.permission.ACCESS_COARSE_LOCATION;
        Log.i(TAG, "showing dialog about " + what + " location");
        new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(background ? R.string.location_consent_background_message : R.string.location_consent_message)
                .setCancelable(false)
                .setPositiveButton(R.string.accept, (dialog, which) -> {
                    Log.i(TAG, "requesting " + what + " location");
                    requestPermissions(new String[]{permission}, 0);
                })
                .setNegativeButton(R.string.decline, (dialog, which) -> {
                    Log.w(TAG, what + " location declined");
                    if (background) {
                        doneBackground = true;
                    } else {
                        doneForeground = true;
                    }
                    finishFlow();
                })
                .create().show();
    }

    private static void logResult(boolean background, boolean granted) {
        final String what = background ? "background" : "foreground";
        if (granted) {
            Log.i(TAG, what + " location granted");
        } else {
            Log.w(TAG, what + " location denied");
        }
    }

    private void finishFlow() {
        Location.markConsentDone(this);
        finish();
    }
}
