// SPDX-FileCopyrightText: 2023-2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later
package net.pgaskin.windy;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class Location {
    private static final String TAG = "Location";

    private static final String STORE = "location";

    private static final String KEY_LNG = "last_lng";
    private static final String KEY_LAT = "last_lat";
    private static final String KEY_UPDATED = "last_updated";
    private static final String KEY_CONSENT_DONE = "permission_requested";
    private static final String KEY_CONSENT_INSTALL = "permission_requested_install";

    private static final AtomicInteger currentSeq = new AtomicInteger();
    private static final AtomicBoolean consentDone = new AtomicBoolean();

    private Location() {
    }

    public static int currentSeq() {
        return currentSeq.get();
    }

    /** Returns the saved location as {@code {lng, lat}}, or null if unknown. */
    public static float[] saved(Context context) {
        final SharedPreferences prefs = prefs(context);
        final float lng = prefs.getFloat(KEY_LNG, 0.0f);
        final float lat = prefs.getFloat(KEY_LAT, 0.0f);
        return lng != 0.0f || lat != 0.0f ? new float[]{lng, lat} : null;
    }

    /** Updates the saved location. */
    public static void save(Context context, float lng, float lat) {
        prefs(context).edit()
                .putFloat(KEY_LNG, lng)
                .putFloat(KEY_LAT, lat)
                .putLong(KEY_UPDATED, System.currentTimeMillis())
                .apply();
        currentSeq.incrementAndGet();
        WindyWallpaperRenderer.wakeAll();
    }

    /** Returns the time the stored location was last updated, or 0 if never. */
    public static long lastUpdated(Context context) {
        return prefs(context).getLong(KEY_UPDATED, 0);
    }

    /**
     * Whether the location has ever been used, i.e. this isn't a fresh
     * installation.
     */
    static boolean hasHistory(Context context) {
        return !prefs(context).getAll().isEmpty();
    }

    /**
     * Refreshes the saved location from the passive provider if it can, and
     * returns it as {@code {lng, lat}}, falling back to the last known one, or
     * null if there is none.
     */
    public static float[] update(Context context) {
        return update(context, false);
    }

    /**
     * Like {@link #update}, but only needs the foreground permission, for use
     * while the consent activity is showing.
     */
    static float[] updateInForeground(Context context) {
        return update(context, true);
    }

    private static float[] update(Context context, boolean foregroundOnly) {
        if (Prefs.locationInterval(context) == 0) {
            return saved(context); // manual
        }
        if (hasPermission(context, foregroundOnly)) {
            final LocationManager mgr = context.getSystemService(LocationManager.class);
            final android.location.Location loc = mgr.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
            if (loc != null) {
                final float lng = round(loc.getLongitude());
                final float lat = round(loc.getLatitude());
                Log.i(TAG, "updated user location lng=" + lng + " lat=" + lat);
                save(context, lng, lat);
                return new float[]{lng, lat};
            }
            Log.w(TAG, "failed to update user location");
        }
        final float[] stored = saved(context);
        if (stored != null) {
            Log.i(TAG, "using last known location lng=" + stored[0] + " lat=" + stored[1]);
            return stored;
        }
        Log.w(TAG, "no location known");
        return null;
    }

    /** Asks the location provider for a fresh location, saving it, asynchronously. */
    public static void requestCurrent(Context context, Consumer<float[]> callback) {
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
            final float lng = round(loc.getLongitude());
            final float lat = round(loc.getLatitude());
            Log.i(TAG, "got current location lng=" + lng + " lat=" + lat);
            save(context, lng, lat);
            callback.accept(new float[]{lng, lat});
        });
    }

    /**
     * Whether the permissions are granted. The background one is also needed
     * unless the app is in the foreground.
     */
    static boolean hasPermission(Context context, boolean foregroundOnly) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        return foregroundOnly
                || context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Whether {@link LocationConsentActivity} has already been shown.
     */
    public static boolean consentDone(Context context) {
        if (consentDone.get()) {
            return true;
        }
        final SharedPreferences prefs = prefs(context);
        if (!prefs.getBoolean(KEY_CONSENT_DONE, false)) {
            return false;
        }
        // the prefs may be persisted across re-installations, but the
        // permission grants aren't and need to be re-requested
        final long installed = installTime(context);
        if (installed != 0) {
            if (askedBeforeInstall(prefs, installed)) {
                Log.i(TAG, "the location flow ran before the app was reinstalled, asking again");
                prefs.edit().remove(KEY_CONSENT_DONE).remove(KEY_CONSENT_INSTALL).apply();
                return false;
            }
            if (prefs.getLong(KEY_CONSENT_INSTALL, 0) == 0) {
                prefs.edit().putLong(KEY_CONSENT_INSTALL, installed).apply();
            }
        }
        consentDone.set(true);
        return true;
    }

    /** Whether the consent was recorded before this installation existed. */
    private static boolean askedBeforeInstall(SharedPreferences prefs, long installed) {
        final long asked = prefs.getLong(KEY_CONSENT_INSTALL, 0);
        if (asked != 0) {
            return asked != installed;
        }
        // fall back to the saved location
        final long updated = prefs.getLong(KEY_UPDATED, 0);
        return updated != 0 && updated < installed;
    }

    static void markConsentDone(Context context) {
        Log.i(TAG, "marking location flow as complete; will not ask again");
        prefs(context).edit()
                .putBoolean(KEY_CONSENT_DONE, true)
                .putLong(KEY_CONSENT_INSTALL, installTime(context))
                .apply();
        consentDone.set(true);
    }

    /** When this installation was first installed, or 0 if unknown. */
    private static long installTime(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).firstInstallTime;
        } catch (PackageManager.NameNotFoundException ex) {
            Log.w(TAG, "failed to get the install time: " + ex);
            return 0;
        }
    }

    private static float round(double deg) {
        return (float) Math.round(deg * 10.0) / 10.0f;
    }

    private static SharedPreferences prefs(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }
}
