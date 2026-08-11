// SPDX-FileCopyrightText: 2023-2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later
package net.pgaskin.windy;

import android.view.Surface;

/**
 * Native renderer bindings.
 *
 * Not thread-safe. All methods (including {@link #close()}) must be called from
 * the same thread.
 */
public final class WindyWallpaperNative implements AutoCloseable {
    static {
        System.loadLibrary("windy_jni");
    }

    private long handle;

    public WindyWallpaperNative(Surface surface, int themeIndex, float dpiScale) {
        this.handle = nativeCreate(surface, themeIndex, dpiScale);
        if (this.handle == 0) {
            throw new RuntimeException("failed to create native renderer");
        }
    }

    public void resize(int width, int height) {
        nativeResize(handle, width, height);
    }

    public void render() {
        nativeRender(handle);
    }

    public void skip(int frames) {
        nativeSkip(handle, frames);
    }

    /** Clears the trails and respawns the particles. */
    public void restart() {
        nativeRestart(handle);
    }

    public void setOffset(float offset) {
        nativeSetOffset(handle, offset);
    }

    public void setUserLocation(float lng, float lat) {
        nativeSetUserLocation(handle, lng, lat);
    }

    /**
     * Replaces the colors (packed 0xAARRGGBB) and params (the width is in dp),
     * indexed by {@link CustomTheme} {@code COLOR_*} and {@code PARAM_*}.
     */
    public void setCustom(int[] colors, float[] params) {
        nativeSetCustom(handle, colors, params);
    }

    /** The name of the GPU the renderer is using, or null if unknown. */
    public String gpuModel() {
        return nativeGpuModel(handle);
    }

    // row-major rgba8888
    public void setWindField(byte[] rgba, int width, int height) {
        nativeSetWindField(handle, rgba, width, height);
    }

    @Override
    public void close() {
        if (handle != 0) {
            nativeDestroy(handle);
            handle = 0;
        }
    }

    public static int themeColor(int themeIndex, int component) {
        return nativeThemeColor(themeIndex, component); // packed 0xAARRGGBB
    }

    /** The color for the system theme, derived from a theme's colors. */
    public static int themeTint(int themeIndex) {
        return nativeThemeTint(themeIndex); // packed 0xAARRGGBB
    }

    /**
     * The color for the system theme, derived from the colors (packed
     * 0xAARRGGBB) indexed by {@link CustomTheme} {@code COLOR_*}.
     */
    public static int customTint(int[] colors) {
        return nativeCustomTint(colors); // packed 0xAARRGGBB
    }

    public static float themeParam(int themeIndex, int param) {
        return nativeThemeParam(themeIndex, param);
    }

    /**
     * Renders the colors (packed 0xAARRGGBB) and params, indexed by
     * {@link CustomTheme} {@code COLOR_*} and {@code PARAM_*}, as the source
     * for a {@code Theme} in core/src/config.rs.
     */
    public static String themeSource(String name, int[] colors, float[] params) {
        return nativeThemeSource(name, colors, params);
    }

    private static native long nativeCreate(Surface surface, int themeIndex, float dpiScale);
    private static native void nativeResize(long handle, int width, int height);
    private static native void nativeRender(long handle);
    private static native void nativeSkip(long handle, int frames);
    private static native void nativeRestart(long handle);
    private static native void nativeSetOffset(long handle, float offset);
    private static native void nativeSetUserLocation(long handle, float lng, float lat);
    private static native void nativeSetCustom(long handle, int[] colors, float[] params);
    private static native void nativeSetWindField(long handle, byte[] rgba, int width, int height);
    private static native String nativeGpuModel(long handle);
    private static native void nativeDestroy(long handle);
    private static native int nativeThemeColor(int themeIndex, int component);
    private static native int nativeThemeTint(int themeIndex);
    private static native int nativeCustomTint(int[] colors);
    private static native float nativeThemeParam(int themeIndex, int param);
    private static native String nativeThemeSource(String name, int[] colors, float[] params);
}
