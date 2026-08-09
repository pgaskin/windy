// SPDX-FileCopyrightText: 2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later
package net.pgaskin.windy;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

public class WindyWallpaperView extends SurfaceView implements SurfaceHolder.Callback {
    private static final String TAG = "WindyWallpaperView";

    private static final int FPS = 30;

    private RenderThread thread;
    private int themeIndex;
    private boolean paused;

    public WindyWallpaperView(Context context, AttributeSet attrs) {
        super(context, attrs);
        getHolder().addCallback(this);
    }

    public void setThemeIndex(int themeIndex) {
        this.themeIndex = themeIndex;
        if (thread != null) {
            thread.onThemeChanged(themeIndex);
        }
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        if (thread != null) {
            thread.onPausedChanged(paused);
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        thread = new RenderThread(holder, themeIndex, paused);
        thread.start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (thread != null) {
            thread.onResized(width, height);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (thread != null) {
            thread.shutdown();
            thread = null;
        }
    }

    private final class RenderThread extends Thread {
        private final SurfaceHolder holder;

        private volatile boolean running = true;
        private boolean paused;
        private int themeIndex;
        private int pendingWidth, pendingHeight;
        private boolean resized;

        RenderThread(SurfaceHolder holder, int themeIndex, boolean paused) {
            super("WindyPreview");
            this.holder = holder;
            this.themeIndex = themeIndex;
            this.paused = paused;
        }

        synchronized void onThemeChanged(int themeIndex) {
            this.themeIndex = themeIndex;
            notifyAll();
        }

        synchronized void onPausedChanged(boolean paused) {
            this.paused = paused;
            notifyAll();
        }

        synchronized void onResized(int width, int height) {
            pendingWidth = width;
            pendingHeight = height;
            resized = true;
            notifyAll();
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
            final Context context = getContext();
            final float dpiScale = getResources().getDisplayMetrics().density;
            final SharedPreferences prefs = Prefs.get(context);
            NativeRenderer renderer = null;
            int rendererTheme = -1;
            int windFieldSeq = -1;
            int locationSeq = -1;
            try {
                while (running) {
                    final int theme;
                    synchronized (this) {
                        while (running && paused) {
                            try {
                                wait();
                            } catch (InterruptedException ignored) {
                            }
                        }
                        if (!running) {
                            break;
                        }
                        theme = themeIndex;
                        if (resized && renderer != null && rendererTheme == theme) {
                            renderer.resize(pendingWidth, pendingHeight);
                        }
                        resized = false;
                    }

                    final long frameStart = System.nanoTime();

                    if (renderer == null || rendererTheme != theme) {
                        if (renderer != null) {
                            renderer.close();
                            renderer = null;
                        }
                        renderer = new NativeRenderer(holder.getSurface(), theme, dpiScale);
                        rendererTheme = theme;
                        windFieldSeq = -1;
                        locationSeq = -1;
                    }

                    if (windFieldSeq != WindField.currentSeq()) {
                        final WindField.Snapshot snap = WindField.snapshot(context);
                        renderer.setWindField(snap.rgba, snap.width, snap.height);
                        windFieldSeq = snap.seq;
                    }
                    if (locationSeq != LocationActivity.currentSeq()) {
                        locationSeq = LocationActivity.currentSeq();
                        final float[] loc = LocationActivity.savedLocation(context);
                        if (loc != null) {
                            renderer.setUserLocation(loc[0], loc[1]);
                        }
                    }

                    renderer.render();

                    awaitFrame(frameStart, Prefs.limitFps(prefs, FPS));
                }
            } catch (Throwable t) {
                Log.e(TAG, "preview render thread failed", t);
            } finally {
                if (renderer != null) {
                    renderer.close();
                }
            }
        }
    }
}
