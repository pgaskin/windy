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
    public static final String KEY_DATA_METERED = "data_metered";
    public static final String KEY_DATA_CONSENT = "data_consent";
    public static final String KEY_MAX_FPS = "max_fps";
    public static final String KEY_STATIC_MODE = "static_mode";
    public static final String KEY_THEME = "theme_service"; // class name
    public static final String KEY_GPU_MODEL = "gpu_model";
    public static final String KEY_CUSTOM_COLORS = "custom_colors"; // current
    public static final String KEY_CUSTOM_PRESET = "custom_preset"; // if unmodified
    public static final String KEY_CUSTOM_PRESETS = "custom_presets"; // saved
    public static final String KEY_CUSTOM_PARAM_PREFIX = "custom_param_"; // + the param name

    private static final String KEY_THEME_LEGACY = "theme"; // index into Themes.ALL

    public static final long INTERVAL_NEVER = 0;
    public static final long INTERVAL_MANUAL = -1;

    public static final int MAX_FPS_AUTOMATIC = 0;

    public static final long DEFAULT_LOCATION_INTERVAL = 3 * 60 * 60; // seconds
    public static final long DEFAULT_DATA_INTERVAL = BuildConfig.WIND_FIELD_UPDATE_INTERVAL * 60; // seconds

    private static volatile SharedPreferences prefs;

    private Prefs() {
    }

    /** Get the shared prefs instance after doing migrations. */
    public static SharedPreferences get(Context context) {
        SharedPreferences current = prefs;
        if (current == null) {
            synchronized (Prefs.class) {
                current = prefs;
                if (current == null) {
                    current = context.getApplicationContext()
                            .createDeviceProtectedStorageContext()
                            .getSharedPreferences(STORE, Context.MODE_PRIVATE);
                    migrate(context, current); // before anything uses them
                    prefs = current;
                }
            }
        }
        return current;
    }

    private static void migrate(Context context, SharedPreferences prefs) {
        migrateDataConsent(context, prefs);
        migrateTheme(prefs);
    }

    /**
     * Location refresh interval in seconds, or 0 if manual.
     */
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
     * Whether automatic updates may use metered connections.
     */
    public static boolean dataMetered(Context context) {
        return get(context).getBoolean(KEY_DATA_METERED, false);
    }

    /**
     * Whether {@link WindFieldConsentActivity} still needs to ask about wind
     * data updates. Nothing is updated automatically until it has.
     */
    public static boolean dataConsentPending(Context context) {
        return !get(context).contains(KEY_DATA_CONSENT);
    }

    public static void setDataConsent(Context context, boolean consent) {
        get(context).edit().putBoolean(KEY_DATA_CONSENT, consent).apply();
    }

    /**
     * Installations from before the consent dialog existed imply consent.
     */
    private static void migrateDataConsent(Context context, SharedPreferences prefs) {
        if (prefs.contains(KEY_DATA_CONSENT)) {
            return;
        }
        if (WindFieldUpdateService.hasHistory(context) || LocationConsentActivity.hasHistory(context)) {
            Log.i(TAG, "keeping wind data updates enabled for an existing installation");
            prefs.edit().putBoolean(KEY_DATA_CONSENT, true).apply();
        }
    }

    public static int limitFps(Context context, int fps) {
        final long max = number(context, KEY_MAX_FPS, MAX_FPS_AUTOMATIC);
        return max > MAX_FPS_AUTOMATIC ? (int) Math.min(fps, max) : fps;
    }

    public static boolean staticMode(Context context) {
        return get(context).getBoolean(KEY_STATIC_MODE, false);
    }

    /**
     * Index of the last selected theme, or 0.
     */
    public static int themeIndex(Context context) {
        final String service = get(context).getString(KEY_THEME, null);
        if (service != null) {
            for (final Themes.Entry theme : Themes.ALL) {
                if (theme.service.equals(service)) {
                    return theme.index;
                }
            }
            Log.w(TAG, "ignoring unknown saved theme " + service);
        }
        return 0;
    }

    public static void setThemeIndex(Context context, int index) {
        get(context).edit().putString(KEY_THEME, Themes.get(index).service).apply();
    }

    private static void migrateTheme(SharedPreferences prefs) {
        if (prefs.contains(KEY_THEME) || !prefs.contains(KEY_THEME_LEGACY)) {
            return;
        }
        prefs.edit()
                .putString(KEY_THEME, Themes.get(prefs.getInt(KEY_THEME_LEGACY, 0)).service)
                .remove(KEY_THEME_LEGACY)
                .apply();
    }

    /**
     * The GPU last used by the renderer, or null if it hasn't run yet.
     */
    public static String gpuModel(Context context) {
        final String model = get(context).getString(KEY_GPU_MODEL, null);
        return model == null || model.isEmpty() ? null : model;
    }

    public static void setGpuModel(Context context, String model) {
        if (model == null || (model = model.trim()).isEmpty()) {
            return; // keep whatever we knew before
        }
        final SharedPreferences prefs = get(context);
        if (!model.equals(prefs.getString(KEY_GPU_MODEL, null))) {
            prefs.edit().putString(KEY_GPU_MODEL, model).apply();
        }
    }

    private static long interval(Context context, String key, long def) {
        return Math.max(number(context, key, def), INTERVAL_MANUAL);
    }

    private static long number(Context context, String key, long def) {
        final String value = get(context).getString(key, null);
        if (value != null) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ex) {
                Log.w(TAG, "ignoring invalid " + key + " " + value);
            }
        }
        return def;
    }
}
