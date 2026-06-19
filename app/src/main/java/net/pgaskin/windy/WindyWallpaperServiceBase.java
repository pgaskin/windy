// SPDX-FileCopyrightText: 2023-2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later
package net.pgaskin.windy;

import android.app.WallpaperColors;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.PowerManager;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.SurfaceHolder;

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
        WindFieldUpdateService.scheduleStartup(this);
        WindFieldUpdateService.schedulePeriodic(this);
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

        RenderThread(SurfaceHolder holder) {
            super("WindyRender");
            this.holder = holder;
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
            NativeRenderer renderer = null;
            try {
                renderer = new NativeRenderer(holder.getSurface(), themeIndex(), dpiScale);

                applyWindField(renderer);
                applyLocation(renderer, true, false);

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
                        if (resized) {
                            renderer.resize(pendingWidth, pendingHeight);
                            resized = false;
                        }
                    }

                    final boolean updatedWindField = pollWindField(renderer);
                    applyLocation(renderer, updatedWindField, !updatedWindField);

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
                    sleepFrame(1000L / fps);
                }
            } catch (Throwable t) {
                Log.e(TAG, "render thread failed", t);
            } finally {
                if (renderer != null) {
                    renderer.close();
                }
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

        private void applyLocation(NativeRenderer renderer, boolean requestIfMissing, boolean cachedOnly) {
            if (locationFlowPending && LocationActivity.getLocationFlowCompleteCached()) {
                cachedOnly = false;
                locationFlowPending = false;
            }
            if (!cachedOnly) {
                final float[] loc = LocationActivity.updateLocation(WindyWallpaperServiceBase.this, requestIfMissing);
                if (loc != null) {
                    lastLocation = loc;
                }
            }
            if (lastLocation != null) {
                renderer.setUserLocation(lastLocation[0], lastLocation[1]);
            }
        }

        private void sleepFrame(long millis) {
            try {
                Thread.sleep(Math.max(millis, 1L));
            } catch (InterruptedException ignored) {
            }
        }
    }
}
