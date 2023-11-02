package net.pgaskin.windy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;
import android.util.Log;
import com.badlogic.gdx.Application;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.badlogic.gdx.backends.android.AndroidLiveWallpaperService;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;

import java.util.concurrent.atomic.AtomicBoolean;

public abstract class WindyWallpaperService extends AndroidLiveWallpaperService implements WindyWallpaper.Provider {
    private static final String TAG = "WindyWallpaperService";
    protected final WindyWallpaper.Config config = new WindyWallpaper.Config();

    private final AtomicBoolean isPowerSaveMode = new AtomicBoolean();
    private final BroadcastReceiver powerSaveReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final PowerManager powerManager = (PowerManager) WindyWallpaperService.this.getSystemService(Context.POWER_SERVICE);
            final boolean enabled = powerManager.isPowerSaveMode();
            Log.d(TAG, "got power saving mode update (enabled: " + enabled + ")");
            isPowerSaveMode.set(enabled);
        }
    };

    @Override
    public void onCreateApplication() {
        super.onCreateApplication();
        app.setLogLevel(Application.LOG_INFO);

        final AndroidApplicationConfiguration cfg = new AndroidApplicationConfiguration();
        cfg.useAccelerometer = false;
        cfg.useCompass = false;
        cfg.useGyroscope = false;
        cfg.numSamples = 2;
        cfg.r = cfg.g = cfg.b = cfg.a = 8;
        cfg.depth = 16;

        WindFieldUpdateService.scheduleStartup(this);
        WindFieldUpdateService.schedulePeriodic(this);

        registerReceiver(powerSaveReceiver, new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));

        initialize(new WindyWallpaper(config, this), cfg);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(powerSaveReceiver);
    }

    @Override
    public Windy.PowerSaveModeProvider createPowerSaveModeProvider() {
        return isPowerSaveMode::get;
    }

    @Override
    public Windy.UserLocationProvider createUserLocationProvider() {
        return requestIfMissing -> LocationActivity.updateLocation(this, requestIfMissing);
    }

    @Override
    public Windy.WindFieldProvider createWindFieldProvider() {
        return new Windy.WindFieldProvider() {
            private Texture windField;
            private int windFieldSeq;

            @Override
            public Texture swapTexture(Texture old) {
                if (old != null && old != windField) throw new IllegalStateException("WindFieldProvider was given wrong texture");
                if (old != null && windFieldSeq == WindField.currentSeq()) return null;
                if (old != null) old.dispose();
                Log.d(TAG, "applying wind field texture");
                windFieldSeq = WindField.currentSeq();
                windField = WindField.createTexture(WindyWallpaperService.this);
                return windField;
            }
        };
    }

    public static class Blue extends WindyWallpaperService {
        public Blue() {
            config.slowWindColor = new Color(0.49803922f, 0.81960785f, 0.58431375f, 0.30f);
            config.fastWindColor = new Color(0.98039216f, 0.94117650f, 0.82352940f, 0.25f);
            config.bgColor = new Color(0x044866FF);
            config.bgColor2 = new Color(0x0085AAFF);
            config.wallpaperColorPrimary = config.wallpaperColorSecondary = config.wallpaperColorTertiary = new Color(0x085173FF);
        }
    }

    public static class Green extends WindyWallpaperService {
        public Green() {
            config.slowWindColor = new Color(0.50f, 0.82f, 0.18f, 0.30f);
            config.fastWindColor = new Color(0.98f, 0.94f, 0.12f, 0.25f);
            config.bgColor = new Color(0x044822FF);
            config.bgColor2 = new Color(0x008533FF);
            config.wallpaperColorPrimary = config.wallpaperColorSecondary = config.wallpaperColorTertiary = new Color(0x085112FF);
        }
    }

    public static class Blush extends WindyWallpaperService {
        public Blush() {
            config.slowWindColor = new Color(0.8509804f, 0.6901961f, 0.91764706f, 0.30f);
            config.fastWindColor = new Color(0.8627451f, 0.9647059f, 1.00000000f, 0.50f);
            config.bgColor = new Color(0x4078C8FF);
            config.bgColor2 = new Color(0xD9B0EAFF);
            config.wallpaperColorPrimary = config.wallpaperColorSecondary = config.wallpaperColorTertiary = new Color(0x4A7CC9FF);
        }
    }

    public static class Midnight extends WindyWallpaperService {
        public Midnight() {
            config.slowWindColor = new Color(0.21568628f, 0.21960784f, 0.21568628f, 0.25f);
            config.fastWindColor = new Color(0.72941180f, 0.74117650f, 0.73725490f, 0.30f);
            config.bgColor = new Color(0x000000AF);
            config.bgColor2 = new Color(0x464749FF);
            config.wallpaperColorPrimary = config.wallpaperColorSecondary = config.wallpaperColorTertiary = new Color(0x101110FF);
        }
    }

    public static class Maroon extends WindyWallpaperService {
        public Maroon() {
            config.slowWindColor = new Color(0.576f, 0.192f, 0.192f, 0.25f);
            config.fastWindColor = new Color(0.792f, 0.376f, 0.376f, 0.30f);
            config.bgColor = new Color(0x1A0909FF);
            config.bgColor2 = new Color(0x451717FF);
            config.wallpaperColorPrimary = config.wallpaperColorSecondary = config.wallpaperColorTertiary = new Color(0x4F1A1AFF);
        }
    }

    public static class Sepia extends WindyWallpaperService {
        public Sepia() {
            config.slowWindColor = new Color(0.26f, 0.16f, 0.05f, 0.25f);
            config.fastWindColor = new Color(0.44f, 0.28f, 0.11f, 0.30f);
            config.bgColor = new Color(0xBDA682FF);
            config.bgColor2 = new Color(0xC49F64FF);
            config.wallpaperColorPrimary = config.wallpaperColorSecondary = config.wallpaperColorTertiary = new Color(0xD87900FF);
        }
    }

    public static class SunsetWhirled extends WindyWallpaperService {
        public SunsetWhirled() {
            config.slowWindColor = new Color(0.9764706f, 0.8627451f, 0.64705884f, 0.60f);
            config.fastWindColor = new Color(1.0000000f, 1.0000000f, 1.00000000f, 0.70f);
            config.bgColor = new Color(0xE58186DF);
            config.bgColor2 = new Color(0xF7B38DDF);
            config.wallpaperColorPrimary = config.wallpaperColorSecondary = config.wallpaperColorTertiary = new Color(0xE88991FF);
        }
    }

    public static class TurquoiseWhirled extends WindyWallpaperService {
        public TurquoiseWhirled() {
            config.slowWindColor = new Color(0.49803922f, 0.81960785f, 0.58431375f, 0.60f);
            config.fastWindColor = new Color(1.00000000f, 1.00000000f, 1.00000000f, 0.50f);
            config.bgColor = new Color(0x0093B9DF);
            config.bgColor2 = new Color(0xEFDD81DF);
            config.wallpaperColorPrimary = config.wallpaperColorSecondary = config.wallpaperColorTertiary = new Color(0x0996B7FF);
        }
    }

    public static class SkyBlueWhirled extends WindyWallpaperService {
        public SkyBlueWhirled() {
            config.slowWindColor = new Color(1.00000000f, 1.0000000f, 1.0000000f, 0.50f);
            config.fastWindColor = new Color(0.95686275f, 1.0000000f, 0.5294118f, 0.25f);
            config.bgColor = new Color(0x75AAFAFF);
            config.bgColor2 = new Color(0xF4FF87FF);
            config.wallpaperColorPrimary = config.wallpaperColorSecondary = config.wallpaperColorTertiary = new Color(0x7BAEF5FF);
        }
    }

    public static class SparkWhirled extends WindyWallpaperService {
        public SparkWhirled() {
            config.slowWindColor = new Color(0.25f, 0.00f, 0.50f, 0.85f);
            config.fastWindColor = new Color(1.00f, 0.50f, 0.00f, 0.65f);
            config.bgColor = new Color(0x270D03FF);
            config.bgColor2 = new Color(0x031A27FF);
            config.wallpaperColorPrimary = config.wallpaperColorSecondary = config.wallpaperColorTertiary = new Color(0x96241AFF);
        }
    }
}