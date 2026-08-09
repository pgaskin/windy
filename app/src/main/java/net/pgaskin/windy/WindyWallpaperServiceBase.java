// SPDX-FileCopyrightText: 2023-2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later
package net.pgaskin.windy;

import android.app.WallpaperColors;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.PowerManager;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.SurfaceHolder;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Engine and lifecycle for the Windy wallpaper services.
 *
 * The concrete per-theme services ({@code WindyWallpaperService} and its nested
 * subclasses) are generated from {@code core/src/config.rs} by the app's Gradle
 * theme codegen; see {@code app/build.gradle}.
 */
public abstract class WindyWallpaperServiceBase extends WallpaperService {
    private static final String TAG = "WindyWallpaperService";

    private static final int FPS_HIGH = 60; // parallax
    private static final int FPS_NORMAL = 13;
    private static final int FPS_POWERSAVE = 3;

    private static final int STATIC_FRAMES = 300;

    private static final Set<RenderThread> renderThreads = ConcurrentHashMap.newKeySet();

    static void wakeRenderThreads() {
        for (final RenderThread thread : renderThreads) {
            thread.wake();
        }
    }

    protected abstract int themeIndex();

    private final AtomicBoolean isPowerSaveMode = new AtomicBoolean();
    private final BroadcastReceiver powerSaveReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            final boolean enabled = powerManager.isPowerSaveMode();
            Log.d(TAG, "got power saving mode update (enabled: " + enabled + ")");
            isPowerSaveMode.set(enabled);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        WindFieldConsentActivity.request(this);
        WindFieldUpdateService.scheduleStartup(this);
        WindFieldUpdateService.schedulePeriodic(this);
        LocationUpdateService.schedule(this);
        registerReceiver(powerSaveReceiver, new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(powerSaveReceiver);
    }

    @Override
    public Engine onCreateEngine() {
        return new WindyEngine();
    }

    private final class WindyEngine extends Engine {
        private RenderThread thread;

        WindyEngine() {
            setOffsetNotificationsEnabled(true);
        }

        @Override
        public WallpaperColors onComputeColors() {
            final int rgb = NativeRenderer.themeColor(themeIndex());
            final Color c = Color.valueOf(0xFF000000 | rgb);
            return new WallpaperColors(c, c, c);
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            thread = new RenderThread(holder);
            thread.start();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            if (thread != null) {
                thread.onResized(width, height);
            }
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            if (thread != null) {
                thread.shutdown();
                thread = null;
            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            if (thread != null) {
                thread.onVisibilityChanged(visible);
            }
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset, float xOffsetStep, float yOffsetStep, int xPixelOffset, int yPixelOffset) {
            if (thread != null && xOffsetStep != 0 && xOffsetStep != -1) {
                thread.onOffsetChanged(xOffset, xOffsetStep);
            }
        }
    }

    private final class RenderThread extends Thread {
        private static final int MIN_PAGES_TO_SWIPE = 4; // matches the original

        private final SurfaceHolder holder;

        private volatile boolean running = true;
        private volatile boolean visible = true;
        private int pendingWidth, pendingHeight;
        private boolean resized;

        private float targetOffset; // [-1, 1]
        private float easedOffset;
        private boolean offsetDirty;

        private boolean locationFlowPending = !LocationActivity.getLocationFlowCompleteCached();
        private float[] lastLocation;
        private int windFieldSeq = -1;
        private int locationSeq = -1;

        private volatile boolean staticMode;
        private volatile boolean settingsDirty;
        private boolean stillDrawn;

        private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener = (prefs, key) -> {
            settingsDirty = true;
            wake();
        };

        RenderThread(SurfaceHolder holder) {
            super("WindyRender");
            this.holder = holder;
        }

        synchronized void wake() {
            notifyAll();
        }

        synchronized void onResized(int width, int height) {
            pendingWidth = width;
            pendingHeight = height;
            resized = true;
            notifyAll();
        }

        synchronized void onVisibilityChanged(boolean visible) {
            this.visible = visible;
            notifyAll();
        }

        synchronized void onOffsetChanged(float xOffset, float xOffsetStep) {
            if (staticMode) {
                return;
            }
            // like the original
            final int steps = (int) (1.0f / xOffsetStep);
            final float stretch = Math.min(steps / (float) MIN_PAGES_TO_SWIPE, 1.0f);
            targetOffset = Math.max(-1.0f, Math.min(1.0f, (xOffset - 0.5f) * 2.0f * stretch));
            offsetDirty = true;
            notifyAll();
        }

        void shutdown() {
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
            final float dpiScale = getResources().getDisplayMetrics().density;
            final SharedPreferences prefs = Prefs.get(WindyWallpaperServiceBase.this);
            NativeRenderer renderer = null;
            renderThreads.add(this);
            prefs.registerOnSharedPreferenceChangeListener(settingsListener);
            try {
                while (running) {
                    synchronized (this) {
                        while (running && !visible) {
                            try {
                                wait();
                            } catch (InterruptedException ignored) {
                            }
                        }
                        if (!running) {
                            break;
                        }
                        settingsDirty = false; // cleared before reading, so a concurrent change isn't lost
                    }

                    if (staticMode != Prefs.staticMode(prefs)) {
                        staticMode = !staticMode;
                        stillDrawn = false;
                    }

                    if (staticMode) {
                        if (stillStale()) {
                            final long start = System.nanoTime();
                            if (renderer == null) {
                                renderer = new NativeRenderer(holder.getSurface(), themeIndex(), dpiScale);
                            }
                            drawStill(renderer);
                            final long shown = System.nanoTime();
                            renderer.close();
                            renderer = null;
                            Log.i(TAG, "rendered still in " + (shown - start) / 1000000L + "ms"
                                    + " (+" + (System.nanoTime() - shown) / 1000000L + "ms cleanup)");
                        }
                        awaitChange();
                        continue;
                    }

                    if (renderer == null) {
                        renderer = new NativeRenderer(holder.getSurface(), themeIndex(), dpiScale);
                        renderer.setOffset(easedOffset);
                        applyWindField(renderer);
                        applyLocation(renderer, true);
                    }
                    drawFrame(renderer, prefs);
                }
            } catch (Throwable t) {
                Log.e(TAG, "render thread failed", t);
            } finally {
                prefs.unregisterOnSharedPreferenceChangeListener(settingsListener);
                renderThreads.remove(this);
                if (renderer != null) {
                    renderer.close();
                }
            }
        }

        private void drawFrame(NativeRenderer renderer, SharedPreferences prefs) {
            synchronized (this) {
                if (resized) {
                    renderer.resize(pendingWidth, pendingHeight);
                    resized = false;
                }
            }

            final long frameStart = System.nanoTime();

            applyLocation(renderer, pollWindField(renderer));

            boolean easing = false;
            synchronized (this) {
                if (offsetDirty || Math.abs(targetOffset - easedOffset) > 0.001f) {
                    if (isPowerSaveMode.get()) {
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

            final int fps = isPowerSaveMode.get() ? FPS_POWERSAVE : easing ? FPS_HIGH : FPS_NORMAL;
            awaitFrame(frameStart, Prefs.limitFps(prefs, fps));
        }

        /** Renders a single settled frame for static mode. */
        private void drawStill(NativeRenderer renderer) {
            final int width, height;
            synchronized (this) {
                width = pendingWidth;
                height = pendingHeight;
                resized = false;
            }
            if (width > 0 && height > 0) {
                renderer.resize(width, height);
            }
            renderer.setOffset(0.0f); // a still can't parallax, so keep it centered

            // Only look for a new location when the wind data changed (or none
            // is known yet) since refreshing it saves the location, which bumps
            // the seq and would make it stale immediately.
            final boolean windFieldUpdated = windFieldSeq != WindField.currentSeq();
            applyWindField(renderer);
            applyLocation(renderer, windFieldUpdated || lastLocation == null);
            if (lastLocation != null) {
                renderer.setUserLocation(lastLocation[0], lastLocation[1]); // the renderer is new
            }

            renderer.skip(STATIC_FRAMES);
            renderer.render();
            stillDrawn = true;
        }

        /** Whether the static-mode frame needs to be updated. */
        private synchronized boolean stillStale() {
            return !stillDrawn || resized
                    || windFieldSeq != WindField.currentSeq()
                    || locationSeq != LocationActivity.currentSeq();
        }

        /** Sleeps until the static-mode frame needs to be updated. */
        private synchronized void awaitChange() {
            if (!running || !visible || settingsDirty || stillStale()) {
                return;
            }
            try {
                wait();
            } catch (InterruptedException ignored) {
            }
        }

        private boolean pollWindField(NativeRenderer renderer) {
            if (WindField.currentSeq() == windFieldSeq) {
                return false;
            }
            applyWindField(renderer);
            return true;
        }

        private void applyWindField(NativeRenderer renderer) {
            final WindField.Snapshot snap = WindField.snapshot(WindyWallpaperServiceBase.this);
            renderer.setWindField(snap.rgba, snap.width, snap.height);
            windFieldSeq = snap.seq;
        }

        private void applyLocation(NativeRenderer renderer, boolean refresh) {
            if (locationFlowPending && LocationActivity.getLocationFlowCompleteCached()) {
                locationFlowPending = false;
                refresh = true;
            }
            if (!refresh && LocationActivity.currentSeq() == locationSeq) {
                return;
            }
            final float[] loc = refresh
                    ? LocationActivity.updateLocation(WindyWallpaperServiceBase.this, true)
                    : LocationActivity.savedLocation(WindyWallpaperServiceBase.this);
            locationSeq = LocationActivity.currentSeq();
            if (loc != null) {
                lastLocation = loc;
                renderer.setUserLocation(loc[0], loc[1]);
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
}
