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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class CustomTheme {
    private static final String TAG = "CustomTheme";

    // do not change the order or add more values (it must match native and saved prefs)
    public static final int SLOW = 0;
    public static final int FAST = 1;
    public static final int BG1 = 2;
    public static final int BG2 = 3;
    public static final int TINT = 4;
    public static final int COUNT = 5;

    public static boolean hasAlpha(int component) {
        return component == SLOW || component == FAST; // alpha doesn't affect other colors
    }

    private static final int MAX_PRESETS = 32;
    private static final int MAX_NAME_LENGTH = 40;

    private static final AtomicInteger seq = new AtomicInteger();
    private static final Set<Runnable> listeners = ConcurrentHashMap.newKeySet();

    private static volatile int[] colors; // array values immutable once set

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
        final int[] result = new int[COUNT];
        for (int i = 0; i < COUNT; i++) {
            result[i] = WindyWallpaperNative.themeColor(themeIndex, i);
        }
        return result;
    }

    /**
     * Updates the colors, applying them to the active renderers.
     *
     * When persist is false, the change is only kept in memory (for live
     * updates while a color is being picked), call {@link #persist} to save it.
     */
    public static void setColors(Context context, int[] next, boolean persist) {
        if (next.length != COUNT) {
            throw new IllegalArgumentException("expected " + COUNT + " colors");
        }
        colors = next.clone();
        if (persist) {
            persist(context);
        }
        notifyChanged();
    }

    public static void setColor(Context context, int component, int argb, boolean persist) {
        final int[] next = colors(context);
        if (next[component] == argb) {
            return;
        }
        next[component] = argb;
        setColors(context, next, persist);
    }

    /** Saves the current colors. */
    public static void persist(Context context) {
        final int[] current = colors(context);
        Prefs.get(context).edit().putString(Prefs.KEY_CUSTOM_COLORS, formatColors(current)).apply();
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

        Preset(String name, int[] colors) {
            this.name = name;
            this.colors = colors;
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
                    presets.add(new Preset(name, parseColors(obj.optString("colors", null))));
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
        final int[] current = colors(context);
        final List<Preset> presets = presets(context);
        boolean replaced = false;
        for (int i = 0; i < presets.size(); i++) {
            if (presets.get(i).name.equals(name)) {
                presets.set(i, new Preset(name, current));
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            while (presets.size() >= MAX_PRESETS) {
                presets.remove(0);
            }
            presets.add(new Preset(name, current));
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
        setColors(context, preset.colors, true);
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
        for (int i = 0; i < COUNT && i < parts.length; i++) {
            try {
                result[i] = (int) Long.parseLong(parts[i].trim(), 16);
            } catch (NumberFormatException ex) {
                Log.w(TAG, "ignoring invalid saved color " + parts[i]);
            }
        }
        return result;
    }
}
