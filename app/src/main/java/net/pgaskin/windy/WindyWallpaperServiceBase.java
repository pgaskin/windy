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
    private static final int FPS_NORMAL_FASTWIND = 20; // avoid jagged motion when the wind speed is higher
    private static final int FPS_POWERSAVE = 3;

    protected abstract int themeIndex();

    private static int normalFps(float windSpeed) {
        // Particles move by wind_speed*dt each frame, so scale the framerate
        // with the wind speed (between FPS_NORMAL and FPS_NORMAL_FASTWIND) to
        // keep the distance covered per frame (i.e., the smoothness), about the
        // same (though it does increase power usage slightly when the fps is
        // increased).
        final int fps = Math.round(FPS_NORMAL * windSpeed / 0.1f); // the default wind speed used for most themes
        return Math.max(FPS_NORMAL, Math.min(FPS_NORMAL_FASTWIND, fps));
    }

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

        // note: this is rate-limited, and also may not take effect until the
        // screen is turned off and on, or the wallpaper is changed
        private final Runnable customThemeListener = this::notifyColorsChanged;

        WindyEngine() {
            setOffsetNotificationsEnabled(true);
        }

        @Override
        public WallpaperColors onComputeColors() {
            final int tint = themeIndex() == Themes.CUSTOM
                    ? WindyWallpaperNative.customTint(CustomTheme.colors(WindyWallpaperServiceBase.this))
                    : WindyWallpaperNative.themeTint(themeIndex());
            final Color c = Color.valueOf(0xFF000000 | (tint & 0xFFFFFF));
            return new WallpaperColors(c, c, c);
        }

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            if (themeIndex() == Themes.CUSTOM) {
                CustomTheme.addListener(customThemeListener);
            }
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            CustomTheme.removeListener(customThemeListener);
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
        // only touched from the render thread (the constructor happens-before it starts)
        private int normalFps;
        private int normalFpsSeq = -1; // custom theme seq

        EngineRenderer(SurfaceHolder holder) {
            super("WindyRender", WindyWallpaperServiceBase.this, holder, themeIndex(), true);
            updateNormalFps();
        }

        private void updateNormalFps() {
            final float windSpeed;
            if (themeIndex() == Themes.CUSTOM) {
                normalFpsSeq = CustomTheme.currentSeq(); // before reading, to not miss a concurrent change
                windSpeed = CustomTheme.param(WindyWallpaperServiceBase.this, CustomTheme.PARAM_WIND_SPEED);
            } else {
                windSpeed = WindyWallpaperNative.themeParam(themeIndex(), CustomTheme.PARAM_WIND_SPEED);
            }
            normalFps = normalFps(windSpeed);
            Log.d(TAG, "using " + normalFps + " fps for wind speed " + windSpeed);
        }

        @Override
        protected int fps(boolean easing) {
            if (powerSave()) {
                return FPS_POWERSAVE;
            }
            if (easing) {
                return FPS_HIGH;
            }
            if (themeIndex() == Themes.CUSTOM && normalFpsSeq != CustomTheme.currentSeq()) {
                updateNormalFps();
            }
            return normalFps;
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
