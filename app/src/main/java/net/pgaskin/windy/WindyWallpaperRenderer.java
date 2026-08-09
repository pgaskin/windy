// SPDX-FileCopyrightText: 2023-2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later
package net.pgaskin.windy;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.SurfaceHolder;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public abstract class WindyWallpaperRenderer extends Thread {
    private static final String TAG = "WindyWallpaperRenderer";

    private static final int MIN_PAGES_TO_SWIPE = 4; // matches the original
    private static final int STATIC_FRAMES = 300;

    private static final Set<WindyWallpaperRenderer> renderers = ConcurrentHashMap.newKeySet();

    /** Wakes all renders to check for texture/location updates. */
    static void wakeAll() {
        for (final WindyWallpaperRenderer renderer : renderers) {
            renderer.wake();
        }
    }

    private final Context context;
    private final SurfaceHolder holder;

    private volatile boolean running = true;
    private boolean active;
    private int themeIndex;
    private int pendingWidth, pendingHeight;
    private boolean resized;

    private float targetOffset; // [-1, 1]
    private float easedOffset;
    private boolean offsetDirty;

    private boolean locationFlowPending = !LocationConsentActivity.getLocationFlowCompleteCached();
    private float[] lastLocation;
    private int rendererTheme = -1;
    private int windFieldSeq = -1;
    private int locationSeq = -1;

    private volatile boolean staticMode;
    private volatile boolean settingsDirty;
    private boolean settled;

    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener = (prefs, key) -> {
        settingsDirty = true;
        wake();
    };

    protected WindyWallpaperRenderer(String name, Context context, SurfaceHolder holder, int themeIndex, boolean active) {
        super(name);
        this.context = context;
        this.holder = holder;
        this.themeIndex = themeIndex;
        this.active = active;
    }

    /** Target framerate. Easing is true during parallax animation. */
    protected abstract int fps(boolean easing);

    /** Reduce animation for battery saver mode. */
    protected boolean powerSave() {
        return false;
    }

    /** Whether the location may be refreshed (which may trigger the permission dialog). */
    protected boolean refreshLocation() {
        return false;
    }

    /** Whether to destroy the renderer while a static frame is showing. */
    protected boolean releaseWhenSettled() {
        return true;
    }

    public synchronized void setActive(boolean active) {
        this.active = active;
        notifyAll();
    }

    public synchronized void setThemeIndex(int themeIndex) {
        this.themeIndex = themeIndex;
        notifyAll();
    }

    public synchronized void setSize(int width, int height) {
        pendingWidth = width;
        pendingHeight = height;
        resized = true;
        notifyAll();
    }

    public synchronized void setOffset(float xOffset, float xOffsetStep) {
        if (staticMode) {
            return; // no parallax for static mode
        }
        // like the original
        final int steps = (int) (1.0f / xOffsetStep);
        final float stretch = Math.min(steps / (float) MIN_PAGES_TO_SWIPE, 1.0f);
        targetOffset = Math.max(-1.0f, Math.min(1.0f, (xOffset - 0.5f) * 2.0f * stretch));
        offsetDirty = true;
        notifyAll();
    }

    public synchronized void wake() {
        notifyAll();
    }

    public void shutdown() {
        synchronized (this) {
            running = false;
            notifyAll();
        }
        try {
            join();
        } catch (InterruptedException ignored) {
        }
    }

    @Override
    public void run() {
        final float dpiScale = context.getResources().getDisplayMetrics().density;
        final SharedPreferences prefs = Prefs.get(context);
        WindyWallpaperNative renderer = null;
        renderers.add(this);
        prefs.registerOnSharedPreferenceChangeListener(settingsListener);
        try {
            while (running) {
                final int theme;
                synchronized (this) {
                    while (running && !active) {
                        try {
                            wait();
                        } catch (InterruptedException ignored) {
                        }
                    }
                    if (!running) {
                        break;
                    }
                    theme = themeIndex;
                    settingsDirty = false; // clear before reading to avoid losing changes
                }

                if (staticMode != Prefs.staticMode(prefs)) {
                    staticMode = !staticMode;
                    settled = false;
                }

                if (staticMode && !stale()) {
                    awaitChange();
                    continue;
                }

                final long frameStart = System.nanoTime();

                final boolean fresh = renderer == null || rendererTheme != theme;
                if (fresh) {
                    if (renderer != null) {
                        renderer.close();
                        renderer = null;
                    }
                    renderer = new WindyWallpaperNative(holder.getSurface(), theme, dpiScale);
                    renderer.setOffset(staticMode ? 0.0f : easedOffset);
                    rendererTheme = theme;
                    Prefs.setGpuModel(prefs, renderer.gpuModel()); // only writes if it changed
                    settled = false;
                }

                synchronized (this) {
                    if (resized) {
                        renderer.resize(pendingWidth, pendingHeight);
                        resized = false;
                        settled = false;
                    }
                }

                // Only refresh the location when the wind texture changes,
                // since refreshing saves it, which bumps seq and would make a
                // static frame stale immediately.
                final boolean windFieldUpdated = windFieldSeq != WindField.currentSeq();
                if (windFieldUpdated || fresh) {
                    applyWindField(renderer);
                }
                applyLocation(renderer, windFieldUpdated || (fresh && lastLocation == null), fresh);

                if (staticMode) {
                    renderer.render(); // render the initial blank frame so it feels more responsive
                    renderer.skip(STATIC_FRAMES);
                    renderer.render();
                    settled = true;

                    final long shown = System.nanoTime();
                    if (releaseWhenSettled()) {
                        renderer.close();
                        renderer = null;
                    }
                    Log.i(TAG, getName() + " rendered static frame in " + (shown - frameStart) / 1000000L + "ms"
                            + " (+" + (System.nanoTime() - shown) / 1000000L + "ms cleanup)");

                    awaitChange();
                    continue;
                }

                boolean easing = false;
                synchronized (this) {
                    if (offsetDirty || Math.abs(targetOffset - easedOffset) > 0.001f) {
                        if (powerSave()) {
                            easedOffset = targetOffset;
                        } else {
                            easedOffset += (targetOffset - easedOffset) * 0.18f;
                            easing = Math.abs(targetOffset - easedOffset) > 0.001f;
                        }
                        renderer.setOffset(easedOffset);
                        offsetDirty = easing;
                    }
                }

                renderer.render();

                awaitFrame(frameStart, Prefs.limitFps(prefs, fps(easing)));
            }
        } catch (Throwable t) {
            Log.e(TAG, getName() + " render loop failed", t);
        } finally {
            prefs.unregisterOnSharedPreferenceChangeListener(settingsListener);
            renderers.remove(this);
            if (renderer != null) {
                renderer.close();
            }
        }
    }

    /** Whether the static-mode frame needs to be updated. */
    private synchronized boolean stale() {
        return !settled || resized
                || themeIndex != rendererTheme
                || windFieldSeq != WindField.currentSeq()
                || locationSeq != LocationConsentActivity.currentSeq();
    }

    /** Sleeps until the static-mode frame needs to be updated. */
    private synchronized void awaitChange() {
        if (!running || !active || settingsDirty || stale()) {
            return;
        }
        try {
            wait();
        } catch (InterruptedException ignored) {
        }
    }

    private void applyWindField(WindyWallpaperNative renderer) {
        final WindField.Snapshot snap = WindField.snapshot(context);
        renderer.setWindField(snap.rgba, snap.width, snap.height);
        windFieldSeq = snap.seq;
    }

    private void applyLocation(WindyWallpaperNative renderer, boolean refresh, boolean fresh) {
        if (!refreshLocation()) {
            refresh = false;
        } else if (locationFlowPending && LocationConsentActivity.getLocationFlowCompleteCached()) {
            locationFlowPending = false;
            refresh = true;
        }
        if (!refresh && !fresh && LocationConsentActivity.currentSeq() == locationSeq) {
            return;
        }
        final float[] loc = refresh
                ? LocationConsentActivity.updateLocation(context, true)
                : LocationConsentActivity.savedLocation(context);
        locationSeq = LocationConsentActivity.currentSeq();
        if (loc != null) {
            lastLocation = loc;
        }
        if (lastLocation != null) {
            renderer.setUserLocation(lastLocation[0], lastLocation[1]);
        }
    }

    private synchronized void awaitFrame(long frameStart, int fps) {
        final long remaining = 1000000000L / fps - (System.nanoTime() - frameStart);
        if (running && remaining > 0) {
            try {
                wait(remaining / 1000000L, (int) (remaining % 1000000L));
            } catch (InterruptedException ignored) {
            }
        }
    }
}
