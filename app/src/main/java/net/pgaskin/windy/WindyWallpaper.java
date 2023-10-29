package net.pgaskin.windy;

import android.app.WallpaperColors;
import android.os.Build;

import com.badlogic.gdx.graphics.Color;

public class WindyWallpaper {
    public static class Blue extends WindyWallpaperService {
        public Blue() {
            config.slowWindColor = new Color(0.49803922f, 0.81960785f, 0.58431375f, 0.30f);
            config.fastWindColor = new Color(0.98039216f, 0.94117650f, 0.82352940f, 0.25f);
            config.bgColor = new Color(0x044866FF);
            config.bgColor2 = new Color(0x0085AAFF);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                config.wallpaperColors = new WallpaperColors(android.graphics.Color.valueOf(0xFF085173), null, null, 0);
            }
        }
    }

    public static class Blush extends WindyWallpaperService {
        public Blush() {
            config.slowWindColor = new Color(0.8509804f, 0.6901961f, 0.91764706f, 0.30f);
            config.fastWindColor = new Color(0.8627451f, 0.9647059f, 1.00000000f, 0.50f);
            config.bgColor = new Color(0x4078C8FF);
            config.bgColor2 = new Color(0xD9B0EAFF);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                config.wallpaperColors = new WallpaperColors(android.graphics.Color.valueOf(0xFF4A7CC9), null, null, 0);
            }
        }
    }

    public static class Midnight extends WindyWallpaperService {
        public Midnight() {
            config.slowWindColor = new Color(0.21568628f, 0.21960784f, 0.21568628f, 0.25f);
            config.fastWindColor = new Color(0.72941180f, 0.74117650f, 0.73725490f, 0.30f);
            config.bgColor = new Color(0x000000AF);
            config.bgColor2 = new Color(0x464749FF);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                config.wallpaperColors = new WallpaperColors(android.graphics.Color.valueOf(0xFF101110), null, null, WallpaperColors.HINT_SUPPORTS_DARK_THEME);
            }
        }
    }

    public static class Maroon extends WindyWallpaperService {
        public Maroon() {
            config.slowWindColor = new Color(0.576f, 0.192f, 0.192f, 0.25f);
            config.fastWindColor = new Color(0.792f, 0.376f, 0.376f, 0.30f);
            config.bgColor = new Color(0x1A0909FF);
            config.bgColor2 = new Color(0x451717FF);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                config.wallpaperColors = new WallpaperColors(android.graphics.Color.valueOf(0xFF4F1A1A), null, null, WallpaperColors.HINT_SUPPORTS_DARK_THEME);
            }
        }
    }

    public static class SunsetWhirled extends WindyWallpaperService {
        public SunsetWhirled() {
            config.slowWindColor = new Color(0.9764706f, 0.8627451f, 0.64705884f, 0.60f);
            config.fastWindColor = new Color(1.0000000f, 1.0000000f, 1.00000000f, 0.70f);
            config.bgColor = new Color(0xE58186Df);
            config.bgColor2 = new Color(0xF7B38DDF);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                config.wallpaperColors = new WallpaperColors(android.graphics.Color.valueOf(0xFFE88991), null, null, 0);
            }
        }
    }

    public static class TurquoiseWhirled extends WindyWallpaperService {
        public TurquoiseWhirled() {
            config.slowWindColor = new Color(0.49803922f, 0.81960785f, 0.58431375f, 0.60f);
            config.fastWindColor = new Color(1.00000000f, 1.00000000f, 1.00000000f, 0.50f);
            config.bgColor = new Color(0x0093B9DF);
            config.bgColor2 = new Color(0xEFDD81DF);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                config.wallpaperColors = new WallpaperColors(android.graphics.Color.valueOf(0xFF0996B7), null, null, 0);
            }
        }
    }

    public static class SkyBlueWhirled extends WindyWallpaperService {
        public SkyBlueWhirled() {
            config.slowWindColor = new Color(1.00000000f, 1.0000000f, 1.0000000f, 0.50f);
            config.fastWindColor = new Color(0.95686275f, 1.0000000f, 0.5294118f, 0.25f);
            config.bgColor = new Color(0x75AAFAFF);
            config.bgColor2 = new Color(0xF4FF87FF);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                config.wallpaperColors = new WallpaperColors(android.graphics.Color.valueOf(0xFF7BAEF5), null, null, 0);
            }
        }
    }
}
