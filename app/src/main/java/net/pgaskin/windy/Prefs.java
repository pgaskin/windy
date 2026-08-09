// SPDX-FileCopyrightText: 2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later
package net.pgaskin.windy;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

public final class Prefs {
    private static final String TAG = "Prefs";

    public static final String STORE = "settings";

    public static final String KEY_LOCATION_INTERVAL = "location_interval";
    public static final String KEY_DATA_INTERVAL = "data_interval";
    public static final String KEY_DATA_URL = "data_url";
    public static final String KEY_DATA_CONSENT = "data_consent";
    public static final String KEY_THEME = "theme";

    public static final long INTERVAL_NEVER = 0;
    public static final long INTERVAL_MANUAL = -1;

    public static final long DEFAULT_LOCATION_INTERVAL = 3 * 60 * 60; // seconds
    public static final long DEFAULT_DATA_INTERVAL = BuildConfig.WIND_FIELD_UPDATE_INTERVAL * 60; // seconds

    private Prefs() {
    }

    public static SharedPreferences get(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    /** Location refresh interval in seconds, or 0 if manual. */
    public static long locationInterval(Context context) {
        return interval(context, KEY_LOCATION_INTERVAL, DEFAULT_LOCATION_INTERVAL);
    }

    /**
     * Wind data refresh interval in seconds, {@link #INTERVAL_NEVER} to always
     * use the built-in field, or {@link #INTERVAL_MANUAL} to use the downloaded
     * one, but not update it automatically.
     */
    public static long dataInterval(Context context) {
        return interval(context, KEY_DATA_INTERVAL, DEFAULT_DATA_INTERVAL);
    }

    public static void setDataInterval(Context context, long seconds) {
        get(context).edit().putString(KEY_DATA_INTERVAL, String.valueOf(seconds)).apply();
    }

    public static String dataUrl(Context context) {
        final String url = get(context).getString(KEY_DATA_URL, null);
        return url == null || url.trim().isEmpty() ? BuildConfig.WIND_FIELD_API_URL : url.trim();
    }

    /**
     * Whether {@link WindFieldConsentActivity} still needs to ask about wind
     * data updates. Nothing is updated automatically until it has.
     */
    public static boolean dataConsentPending(Context context) {
        migrateDataConsent(context);
        return !get(context).contains(KEY_DATA_CONSENT);
    }

    public static void setDataConsent(Context context, boolean consent) {
        get(context).edit().putBoolean(KEY_DATA_CONSENT, consent).apply();
    }

    /** Installations from before the consent dialog existed imply consent. */
    private static void migrateDataConsent(Context context) {
        final SharedPreferences prefs = get(context);
        if (prefs.contains(KEY_DATA_CONSENT)) {
            return;
        }
        if (WindFieldUpdateService.hasHistory(context) || LocationActivity.hasHistory(context)) {
            Log.i(TAG, "keeping wind data updates enabled for an existing installation");
            prefs.edit().putBoolean(KEY_DATA_CONSENT, true).apply();
        }
    }

    public static int themeIndex(Context context) {
        return get(context).getInt(KEY_THEME, 0);
    }

    public static void setThemeIndex(Context context, int index) {
        get(context).edit().putInt(KEY_THEME, index).apply();
    }

    private static long interval(Context context, String key, long def) {
        final String value = get(context).getString(key, null); // string for ListPreference
        if (value != null) {
            try {
                return Math.max(Long.parseLong(value), INTERVAL_MANUAL);
            } catch (NumberFormatException ex) {
                // use the default
            }
        }
        return def;
    }
}
