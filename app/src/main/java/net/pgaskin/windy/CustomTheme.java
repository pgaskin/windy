// SPDX-FileCopyrightText: 2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later
package net.pgaskin.windy;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class CustomTheme {
    private static final String TAG = "CustomTheme";

    // do not change the order or add more values (it must match native and saved prefs)
    public static final int COLOR_SLOW = 0;
    public static final int COLOR_FAST = 1;
    public static final int COLOR_BG1 = 2;
    public static final int COLOR_BG2 = 3;
    public static final int COLOR_TINT = 4;
    public static final int COLOR_COUNT = 5;

    public static boolean hasAlpha(int color) {
        return color == COLOR_SLOW || color == COLOR_FAST; // alpha doesn't affect other colors
    }

    // do not change the order (it must match native)
    public static final int PARAM_LINE_HALF_WIDTH = 0;
    public static final int PARAM_PARTICLE_OPACITY = 1;
    public static final int PARAM_ALPHA_DECAY = 2;
    public static final int PARAM_WIND_SPEED = 3;
    public static final int PARAM_COUNT = 4;

    /**
     * Param names, used for the saved keys.
     */
    private static final String[] PARAM_NAMES = {
            "line_half_width",
            "particle_opacity",
            "alpha_decay",
            "wind_speed",
    };

    private static final int MAX_PRESETS = 32;
    private static final int MAX_NAME_LENGTH = 40;

    private static final AtomicInteger seq = new AtomicInteger();
    private static final Set<Runnable> listeners = ConcurrentHashMap.newKeySet();

    private static volatile int[] colors; // array values immutable once set
    private static volatile float[] params;

    private CustomTheme() {
    }

    public static int currentSeq() {
        return seq.get();
    }

    public static int[] colors(Context context) {
        int[] current = colors;
        if (current == null) {
            synchronized (CustomTheme.class) {
                current = colors;
                if (current == null) {
                    current = parseColors(Prefs.get(context).getString(Prefs.KEY_CUSTOM_COLORS, null));
                    colors = current;
                }
            }
        }
        return current.clone();
    }

    public static int color(Context context, int component) {
        return colors(context)[component];
    }

    public static int[] themeColors(int themeIndex) {
        final int[] result = new int[COLOR_COUNT];
        for (int i = 0; i < COLOR_COUNT; i++) {
            result[i] = WindyWallpaperNative.themeColor(themeIndex, i);
        }
        return result;
    }

    public static float[] params(Context context) {
        float[] current = params;
        if (current == null) {
            synchronized (CustomTheme.class) {
                current = params;
                if (current == null) {
                    current = loadParams(Prefs.get(context));
                    params = current;
                }
            }
        }
        return current.clone();
    }

    public static float param(Context context, int param) {
        return params(context)[param];
    }

    public static float[] themeParams(int themeIndex) {
        final float[] result = new float[PARAM_COUNT];
        for (int i = 0; i < PARAM_COUNT; i++) {
            result[i] = WindyWallpaperNative.themeParam(themeIndex, i);
        }
        return result;
    }

    /**
     * Updates the colors and params, applying them to the active renderers.
     * Either may be null to preserve the current value. When persist is false,
     * the change is only kept in memory (for live updates while a color is
     * being picked), call {@link #persist} to save it.
     */
    public static void set(Context context, int[] nextColors, float[] nextParams, boolean persist) {
        if (nextColors != null) {
            if (nextColors.length != COLOR_COUNT) {
                throw new IllegalArgumentException("expected " + COLOR_COUNT + " colors");
            }
            colors = nextColors.clone();
        }
        if (nextParams != null) {
            if (nextParams.length != PARAM_COUNT) {
                throw new IllegalArgumentException("expected " + PARAM_COUNT + " params");
            }
            params = nextParams.clone();
        }
        if (persist) {
            persist(context);
        }
        notifyChanged();
    }

    public static void setColors(Context context, int[] next, boolean persist) {
        set(context, next, null, persist);
    }

    public static void setColor(Context context, int color, int argb, boolean persist) {
        final int[] next = colors(context);
        if (next[color] == argb) {
            return;
        }
        next[color] = argb;
        setColors(context, next, persist);
    }

    public static void setParams(Context context, float[] next, boolean persist) {
        set(context, null, next, persist);
    }

    public static void setParam(Context context, int param, float value, boolean persist) {
        final float[] next = params(context);
        if (next[param] == value) {
            return;
        }
        next[param] = value;
        setParams(context, next, persist);
    }

    public static void persist(Context context) {
        final SharedPreferences.Editor edit = Prefs.get(context).edit();
        edit.putString(Prefs.KEY_CUSTOM_COLORS, formatColors(colors(context)));
        final float[] current = params(context);
        for (int i = 0; i < PARAM_COUNT; i++) {
            edit.putFloat(paramKey(i), current[i]);
        }
        edit.apply();
    }

    private static String paramKey(int param) {
        return Prefs.KEY_CUSTOM_PARAM_PREFIX + PARAM_NAMES[param];
    }

    private static float[] loadParams(SharedPreferences prefs) {
        final float[] result = themeParams(Themes.CUSTOM);
        for (int i = 0; i < PARAM_COUNT; i++) {
            result[i] = prefs.getFloat(paramKey(i), result[i]);
        }
        return result;
    }

    public static void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public static void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private static void notifyChanged() {
        seq.incrementAndGet();
        WindyWallpaperRenderer.wakeAll();
        if (!listeners.isEmpty()) {
            new Handler(Looper.getMainLooper()).post(() -> {
                for (final Runnable listener : listeners) {
                    listener.run();
                }
            });
        }
    }

    public static final class Preset {
        public final String name;
        public final int[] colors;
        public final float[] params;

        Preset(String name, int[] colors, float[] params) {
            this.name = name;
            this.colors = colors;
            this.params = params;
        }

        /**
         * Whether the current colors and params still match this preset.
         */
        public boolean matches(Context context) {
            return Arrays.equals(colors, colors(context)) && Arrays.equals(params, params(context));
        }
    }

    public static List<Preset> presets(Context context) {
        final List<Preset> presets = new ArrayList<>();
        final String saved = Prefs.get(context).getString(Prefs.KEY_CUSTOM_PRESETS, null);
        if (saved == null || saved.isEmpty()) {
            return presets;
        }
        try {
            final JSONArray arr = new JSONArray(saved);
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject obj = arr.getJSONObject(i);
                final String name = obj.optString("name", "").trim();
                if (!name.isEmpty()) {
                    // presets saved before the params existed use the defaults
                    final float[] params = themeParams(Themes.CUSTOM);
                    for (int p = 0; p < PARAM_COUNT; p++) {
                        params[p] = (float) obj.optDouble(PARAM_NAMES[p], params[p]);
                    }
                    presets.add(new Preset(name, parseColors(obj.optString("colors", null)), params));
                }
            }
        } catch (JSONException ex) {
            Log.w(TAG, "ignoring corrupt presets: " + ex);
        }
        return presets;
    }

    public static Preset preset(Context context, String name) {
        for (final Preset preset : presets(context)) {
            if (preset.name.equals(name)) {
                return preset;
            }
        }
        return null;
    }

    public static void savePreset(Context context, String name) {
        name = normalizeName(name);
        if (name.isEmpty()) {
            return;
        }
        final Preset current = new Preset(name, colors(context), params(context));
        final List<Preset> presets = presets(context);
        boolean replaced = false;
        for (int i = 0; i < presets.size(); i++) {
            if (presets.get(i).name.equals(name)) {
                presets.set(i, current);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            while (presets.size() >= MAX_PRESETS) {
                presets.remove(0);
            }
            presets.add(current);
        }
        writePresets(context, presets, name);
    }

    public static void deletePreset(Context context, String name) {
        final List<Preset> presets = presets(context);
        boolean removed = false;
        for (int i = 0; i < presets.size(); i++) {
            if (presets.get(i).name.equals(name)) {
                presets.remove(i);
                removed = true;
                break;
            }
        }
        if (removed) {
            writePresets(context, presets, name.equals(presetName(context)) ? "" : presetName(context));
        }
    }

    public static void loadPreset(Context context, String name) {
        final Preset preset = preset(context, name);
        if (preset == null) {
            return;
        }
        Prefs.get(context).edit().putString(Prefs.KEY_CUSTOM_PRESET, preset.name).apply();
        set(context, preset.colors, preset.params, true);
    }

    public static String presetName(Context context) {
        return Prefs.get(context).getString(Prefs.KEY_CUSTOM_PRESET, "");
    }

    public static void setPresetName(Context context, String name) {
        Prefs.get(context).edit().putString(Prefs.KEY_CUSTOM_PRESET, normalizeName(name)).apply();
    }

    public static String normalizeName(String name) {
        name = name == null ? "" : name.trim();
        return name.length() > MAX_NAME_LENGTH ? name.substring(0, MAX_NAME_LENGTH).trim() : name;
    }

    private static void writePresets(Context context, List<Preset> presets, String selected) {
        final JSONArray arr = new JSONArray();
        try {
            for (final Preset preset : presets) {
                final JSONObject obj = new JSONObject();
                obj.put("name", preset.name);
                obj.put("colors", formatColors(preset.colors));
                for (int p = 0; p < PARAM_COUNT; p++) {
                    obj.put(PARAM_NAMES[p], (double) preset.params[p]);
                }
                arr.put(obj);
            }
        } catch (JSONException ex) {
            Log.e(TAG, "failed to save presets", ex);
            return;
        }
        final SharedPreferences.Editor edit = Prefs.get(context).edit();
        edit.putString(Prefs.KEY_CUSTOM_PRESETS, arr.toString());
        edit.putString(Prefs.KEY_CUSTOM_PRESET, selected);
        edit.apply();
    }

    private static String formatColors(int[] colors) {
        final StringBuilder sb = new StringBuilder();
        for (final int color : colors) {
            if (sb.length() != 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.ROOT, "%08x", color));
        }
        return sb.toString();
    }

    private static int[] parseColors(String saved) {
        final int[] result = themeColors(Themes.CUSTOM);
        if (saved == null) {
            return result;
        }
        final String[] parts = saved.split(",", -1);
        for (int i = 0; i < COLOR_COUNT && i < parts.length; i++) {
            try {
                result[i] = (int) Long.parseLong(parts[i].trim(), 16);
            } catch (NumberFormatException ex) {
                Log.w(TAG, "ignoring invalid saved color " + parts[i]);
            }
        }
        return result;
    }
}
