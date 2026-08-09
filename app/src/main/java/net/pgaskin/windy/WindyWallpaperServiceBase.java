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
 * The concrete {@code WindyWallpaperService} is generated from
 * {@code core/src/config.rs} by {@code app/build.gradle}.
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
        private WindyWallpaperRenderer renderer;

        WindyEngine() {
            setOffsetNotificationsEnabled(true);
        }

        @Override
        public WallpaperColors onComputeColors() {
            final int rgb = WindyWallpaperNative.themeColor(themeIndex());
            final Color c = Color.valueOf(0xFF000000 | rgb);
            return new WallpaperColors(c, c, c);
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            renderer = new EngineRenderer(holder);
            renderer.start();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            if (renderer != null) {
                renderer.setSize(width, height);
            }
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            if (renderer != null) {
                renderer.shutdown();
                renderer = null;
            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            if (renderer != null) {
                renderer.setActive(visible);
            }
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset, float xOffsetStep, float yOffsetStep, int xPixelOffset, int yPixelOffset) {
            if (renderer != null && xOffsetStep != 0 && xOffsetStep != -1) {
                renderer.setOffset(xOffset, xOffsetStep);
            }
        }
    }

    private final class EngineRenderer extends WindyWallpaperRenderer {
        EngineRenderer(SurfaceHolder holder) {
            super("WindyRender", WindyWallpaperServiceBase.this, holder, themeIndex(), true);
        }

        @Override
        protected int fps(boolean easing) {
            return powerSave() ? FPS_POWERSAVE : easing ? FPS_HIGH : FPS_NORMAL;
        }

        @Override
        protected boolean powerSave() {
            return isPowerSaveMode.get();
        }

        @Override
        protected boolean refreshLocation() {
            return true;
        }
    }
}
