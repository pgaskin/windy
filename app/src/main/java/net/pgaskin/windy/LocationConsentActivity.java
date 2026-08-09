// SPDX-FileCopyrightText: 2023-2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later
package net.pgaskin.windy;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class LocationConsentActivity extends Activity {
    private static final String TAG = "LocationConsentActivity";

    private static final AtomicInteger currentSeq = new AtomicInteger();

    boolean requestedInitial = false;
    boolean doneForeground = false;
    boolean doneBackground = false;

    @Override
    protected void onSaveInstanceState(Bundle s) {
        s.putBoolean("requestedInitial", this.requestedInitial);
        s.putBoolean("doneForeground", this.doneForeground);
        s.putBoolean("doneBackground", this.doneBackground);
        super.onSaveInstanceState(s);
    }

    @Override
    protected void onCreate(Bundle s) {
        if (s != null) {
            this.requestedInitial = s.getBoolean("requestedInitial");
            this.doneForeground = s.getBoolean("doneForeground");
            this.doneBackground = s.getBoolean("doneBackground");
        }
        super.onCreate(s);
    }

    @Override
    protected void onResume() {
        super.onResume();
        this.doNextPermissionRequest(true);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        this.requestedInitial = true;
        for (int i = 0; i < permissions.length; i++) {
            if (permissions[i].equals(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                if (grantResults[i] == PackageManager.PERMISSION_DENIED) {
                    Log.w(TAG, "foreground location denied");
                } else {
                    Log.i(TAG, "foreground location granted");
                }
                this.doneForeground = true;
            }
            if (permissions[i].equals(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                if (grantResults[i] == PackageManager.PERMISSION_DENIED) {
                    Log.w(TAG, "background location denied");
                } else {
                    Log.i(TAG, "background location granted");
                }
                this.doneBackground = true;
            }
        }
        this.doNextPermissionRequest(false);
    }

    public void doNextPermissionRequest(boolean initial) {
        if (initial == this.requestedInitial) {
            return;
        }
        if (!this.requestedInitial) {
            Log.i(TAG, "doing initial permission request");
        } else {
            Log.i(TAG, "handling permission request result");
        }
        if (this.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "saving location since we have access");
            LocationConsentActivity.updateLocation(this, false, true);
        }
        // the dialogs are always shown first so the permission is never
        // requested without explaining what it's for
        if (!this.doneForeground && this.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "showing dialog about foreground location");
            new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(R.string.location_consent_message)
                .setCancelable(false)
                .setPositiveButton(R.string.accept, (dialog, which) -> {
                    Log.i(TAG, "requesting foreground location");
                    this.requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 0);
                })
                .setNegativeButton(R.string.decline, (dialog, which) -> {
                    Log.w(TAG, "foreground location declined");
                    this.doneForeground = true;
                    LocationConsentActivity.markLocationFlowComplete(this);
                    this.finish();
                })
                .create().show();
            return;
        }
        if (!this.doneBackground && this.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "showing dialog about background location");
            new AlertDialog.Builder(this)
                .setTitle(R.string.app_name)
                .setMessage(R.string.location_consent_background_message)
                .setCancelable(false)
                .setPositiveButton(R.string.accept, (dialog, which) -> {
                    Log.i(TAG, "requesting background location");
                    this.requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 0);
                })
                .setNegativeButton(R.string.decline, (dialog, which) -> {
                    Log.w(TAG, "background location declined");
                    this.doneBackground = true;
                    LocationConsentActivity.markLocationFlowComplete(this);
                    this.finish();
                })
                .create().show();
            return;
        }
        LocationConsentActivity.markLocationFlowComplete(this);
        this.finish();
    }

    /** Returns the current location as {@code {lng, lat}}, or null if unknown. */
    public static float[] updateLocation(Context context, boolean requestIfMissing) {
        return LocationConsentActivity.updateLocation(context, requestIfMissing, false);
    }

    private static float[] updateLocation(Context context, boolean requestIfMissing, boolean isForeground) {
        if (Prefs.locationInterval(context) == 0) {
            return LocationConsentActivity.savedLocation(context); // manual
        }
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                || (!isForeground && context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED)) {
            if (requestIfMissing && !LocationConsentActivity.getLocationFlowComplete(context)) {
                Log.i(TAG, "permissions missing, starting location flow");
                Intent intent = new Intent(context, LocationConsentActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                context.startActivity(intent);
            }
        } else {
            final LocationManager mgr = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            final Location loc = mgr.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
            if (loc != null) {
                final float lng = LocationConsentActivity.round(loc.getLongitude());
                final float lat = LocationConsentActivity.round(loc.getLatitude());
                Log.i(TAG, "updated user location lng=" + lng + " lat=" + lat);
                LocationConsentActivity.saveLocation(context, lng, lat);
                return new float[]{lng, lat};
            } else {
                Log.w(TAG, "failed to update user location");
            }
        }
        final float[] stored = LocationConsentActivity.savedLocation(context);
        if (stored != null) {
            Log.i(TAG, "using last known location lng=" + stored[0] + " lat=" + stored[1]);
            return stored;
        }
        Log.w(TAG, "no location known");
        return null;
    }

    /** Returns the saved location as {@code {lng, lat}}, or null if unknown. */
    public static float[] savedLocation(Context context) {
        final SharedPreferences prefs = LocationConsentActivity.getPreferences(context);
        final float lng = prefs.getFloat("last_lng", 0.0f);
        final float lat = prefs.getFloat("last_lat", 0.0f);
        return lng != 0.0f || lat != 0.0f ? new float[]{lng, lat} : null;
    }

    /** Returns the time the stored location was last updated, or 0 if never. */
    public static long lastUpdated(Context context) {
        return LocationConsentActivity.getPreferences(context).getLong("last_updated", 0);
    }

    /** Whether the location has ever been used, i.e. this isn't a fresh installation. */
    static boolean hasHistory(Context context) {
        return !LocationConsentActivity.getPreferences(context).getAll().isEmpty();
    }

    /** Updates the saved location. */
    public static void saveLocation(Context context, float lng, float lat) {
        LocationConsentActivity.getPreferences(context).edit()
                .putFloat("last_lng", lng)
                .putFloat("last_lat", lat)
                .putLong("last_updated", System.currentTimeMillis())
                .apply();
        LocationConsentActivity.currentSeq.incrementAndGet();
        WindyWallpaperRenderer.wakeAll();
    }

    /** Updates and saves the current location, blocking. */
    public static void requestCurrentLocation(Context context, Consumer<float[]> callback) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "not requesting current location without permission");
            callback.accept(null);
            return;
        }
        final LocationManager mgr = context.getSystemService(LocationManager.class);
        final String provider = mgr.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                ? LocationManager.NETWORK_PROVIDER
                : LocationManager.PASSIVE_PROVIDER;
        Log.i(TAG, "requesting current location from provider " + provider);
        mgr.getCurrentLocation(provider, null, context.getMainExecutor(), loc -> {
            if (loc == null) {
                Log.w(TAG, "failed to get current location");
                callback.accept(null);
                return;
            }
            final float lng = LocationConsentActivity.round(loc.getLongitude());
            final float lat = LocationConsentActivity.round(loc.getLatitude());
            Log.i(TAG, "got current location lng=" + lng + " lat=" + lat);
            LocationConsentActivity.saveLocation(context, lng, lat);
            callback.accept(new float[]{lng, lat});
        });
    }

    public static int currentSeq() {
        return LocationConsentActivity.currentSeq.get();
    }

    private static float round(double deg) {
        return (float) Math.round(deg * 10.0) / 10.0f;
    }

    private static final AtomicBoolean locationFlowCompleteCached = new AtomicBoolean();

    private static boolean getLocationFlowComplete(Context context) {
        if (LocationConsentActivity.locationFlowCompleteCached.get()) {
            return true;
        }
        if (LocationConsentActivity.getPreferences(context).getBoolean("permission_requested", false)) {
            LocationConsentActivity.locationFlowCompleteCached.set(true);
            return true;
        }
        return false;
    }

    private static void markLocationFlowComplete(Context context) {
        Log.i(TAG, "marking location flow as complete; will not ask again");
        LocationConsentActivity.getPreferences(context).edit().putBoolean("permission_requested", true).apply();
        LocationConsentActivity.locationFlowCompleteCached.set(true);
    }

    public static boolean getLocationFlowCompleteCached() {
        return LocationConsentActivity.locationFlowCompleteCached.get();
    }

    private static SharedPreferences getPreferences(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences("location", Context.MODE_PRIVATE);
    }
}