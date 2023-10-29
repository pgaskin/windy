package net.pgaskin.windy;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.badlogic.gdx.backends.android.AndroidLiveWallpaperService;
import com.badlogic.gdx.graphics.Color;

public abstract class WindyWallpaperService extends AndroidLiveWallpaperService {
    private WindyWallpaper windy;
    protected final WindyWallpaper.Config config = new WindyWallpaper.Config();

    @Override // AndroidLiveWallpaperService
    public void onCreateApplication() {
        WindFieldUpdateService.schedulePeriodic(this);

        final AndroidApplicationConfiguration cfg = new AndroidApplicationConfiguration();
        cfg.useAccelerometer = false;
        cfg.useCompass = false;
        cfg.useGyroscope = false;
        cfg.numSamples = 2;
        cfg.r = cfg.g = cfg.b = cfg.a = 8;
        cfg.depth = 16;

        super.onCreateApplication();
        this.app.setLogLevel(Application.LOG_INFO);

        this.windy = new WindyWallpaper(this, this.config);
        this.initialize(windy, cfg);
    }

    @Override
    public void onDestroy() {
        if (this.windy != null) {
            // this doesn't seem to get called properly by GDX unless we do this...
            this.windy.pause();
        }
        super.onDestroy();
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

    public static class SunsetWhirled extends WindyWallpaperService {
        public SunsetWhirled() {
            config.slowWindColor = new Color(0.9764706f, 0.8627451f, 0.64705884f, 0.60f);
            config.fastWindColor = new Color(1.0000000f, 1.0000000f, 1.00000000f, 0.70f);
            config.bgColor = new Color(0xE58186Df);
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
}