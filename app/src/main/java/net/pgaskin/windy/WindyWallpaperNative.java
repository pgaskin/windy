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

    public void setOffset(float offset) {
        nativeSetOffset(handle, offset);
    }

    public void setUserLocation(float lng, float lat) {
        nativeSetUserLocation(handle, lng, lat);
    }

    public void setColors(int slow, int fast, int bg1, int bg2) {
        nativeSetColors(handle, slow, fast, bg1, bg2); // packed 0xAARRGGBB
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

    private static native long nativeCreate(Surface surface, int themeIndex, float dpiScale);
    private static native void nativeResize(long handle, int width, int height);
    private static native void nativeRender(long handle);
    private static native void nativeSkip(long handle, int frames);
    private static native void nativeSetOffset(long handle, float offset);
    private static native void nativeSetUserLocation(long handle, float lng, float lat);
    private static native void nativeSetColors(long handle, int slow, int fast, int bg1, int bg2);
    private static native void nativeSetWindField(long handle, byte[] rgba, int width, int height);
    private static native String nativeGpuModel(long handle);
    private static native void nativeDestroy(long handle);
    private static native int nativeThemeColor(int themeIndex, int component);
}
