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
public final class NativeRenderer implements AutoCloseable {
    static {
        System.loadLibrary("windy_wallpaper_android");
    }

    private long handle;

    public NativeRenderer(Surface surface, int themeIndex, float dpiScale) {
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

    public void setOffset(float offset) {
        nativeSetOffset(handle, offset);
    }

    public void setUserLocation(float lng, float lat) {
        nativeSetUserLocation(handle, lng, lat);
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

    public static int themeColor(int themeIndex) {
        return nativeThemeColor(themeIndex);
    }

    private static native long nativeCreate(Surface surface, int themeIndex, float dpiScale);
    private static native void nativeResize(long handle, int width, int height);
    private static native void nativeRender(long handle);
    private static native void nativeSetOffset(long handle, float offset);
    private static native void nativeSetUserLocation(long handle, float lng, float lat);
    private static native void nativeSetWindField(long handle, byte[] rgba, int width, int height);
    private static native void nativeDestroy(long handle);
    private static native int nativeThemeColor(int themeIndex);
}
