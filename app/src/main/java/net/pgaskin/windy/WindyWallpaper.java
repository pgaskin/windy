package net.pgaskin.windy;

import android.app.WallpaperColors;
import android.os.Build;

import com.badlogic.gdx.graphics.Color;

public class WindyWallpaper {
    public static class Blue extends WindyWallpaperService {
        public Config onConfigure() {
            final Config config = new Config();
            config.slowWindColor = new Color(0.49803922f, 0.81960785f, 0.58431375f, 0.3f);
            config.fastWindColor = new Color(0.98039216f, 0.9411765f, 0.8235294f, 0.25f);
            config.bgColor = new Color(71853823);
            config.bgColor2 = new Color(8760063);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                config.wallpaperColors = new WallpaperColors(android.graphics.Color.valueOf(-16232077), null, null, 0);
            }
            return config;
        }
    }

    public static class Blush extends WindyWallpaperService {
        public Config onConfigure() {
            final Config config = new Config();
            config.slowWindColor = new Color(0.8509804f, 0.6901961f, 0.91764706f, 0.3f);
            config.fastWindColor = new Color(0.8627451f, 0.9647059f, 1.0f, 0.5f);
            config.bgColor = new Color(1081657599);
            config.bgColor2 = new Color(-642716929);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                config.wallpaperColors = new WallpaperColors(android.graphics.Color.valueOf(-11895607), null, null, 0);
            }
            return config;
        }
    }

    public static class Midnight extends WindyWallpaperService {
        public Config onConfigure() {
            final Config config = new Config();
            config.slowWindColor = new Color(0.21568628f, 0.21960784f, 0.21568628f, 0.25f);
            config.fastWindColor = new Color(0.7294118f, 0.7411765f, 0.7372549f, 0.3f);
            config.bgColor = new Color(175);
            config.bgColor2 = new Color(1179077119);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                config.wallpaperColors = new WallpaperColors(android.graphics.Color.valueOf(-15724272), null, null, WallpaperColors.HINT_SUPPORTS_DARK_THEME);
            }
            return config;
        }
    }

    public static class Maroon extends WindyWallpaperService {
        public Config onConfigure() {
            final Config config = new Config();
            config.slowWindColor = new Color(0.576f, 0.192f, 0.192f, 0.25f);
            config.fastWindColor = new Color(0.792f, 0.376f, 0.376f, 0.30f);
            config.bgColor = new Color(0x1A0909FF);
            config.bgColor2 = new Color(0x451717FF);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                config.wallpaperColors = new WallpaperColors(android.graphics.Color.valueOf(0xFF4F1A1A), null, null, WallpaperColors.HINT_SUPPORTS_DARK_THEME);
            }
            return config;
        }
    }

    public static class SunsetWhirled extends WindyWallpaperService {
        public Config onConfigure() {
            final Config config = new Config();
            config.slowWindColor = new Color(0.9764706f, 0.8627451f, 0.64705884f, 0.6f);
            config.fastWindColor = new Color(1.0f, 1.0f, 1.0f, 0.7f);
            config.bgColor = new Color(-444496161);
            config.bgColor2 = new Color(-139227681);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                config.wallpaperColors = new WallpaperColors(android.graphics.Color.valueOf(-1537647), null, null, 0);
            }
            return config;
        }
    }

    public static class TurquoiseWhirled extends WindyWallpaperService {
        public Config onConfigure() {
            final Config config = new Config();
            config.slowWindColor = new Color(0.49803922f, 0.81960785f, 0.58431375f, 0.6f);
            config.fastWindColor = new Color(1.0f, 1.0f, 1.0f, 0.5f);
            config.bgColor = new Color(9681375);
            config.bgColor2 = new Color(-270695969);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                config.wallpaperColors = new WallpaperColors(android.graphics.Color.valueOf(-16148809), null, null, 0);
            }
            return config;
        }
    }

    public static class SkyBlueWhirled extends WindyWallpaperService {
        public Config onConfigure() {
            final Config config = new Config();
            config.slowWindColor = new Color(1.0f, 1.0f, 1.0f, 0.5f);
            config.fastWindColor = new Color(0.95686275f, 1.0f, 0.5294118f, 0.25f);
            config.bgColor = new Color(1974139647);
            config.bgColor2 = new Color(-184580097);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                config.wallpaperColors = new WallpaperColors(android.graphics.Color.valueOf(-8671499), null, null, 0);
            }
            return config;
        }
    }
}
