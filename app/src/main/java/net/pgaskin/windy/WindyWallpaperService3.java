package net.pgaskin.windy;

import android.app.WallpaperColors;

import com.badlogic.gdx.graphics.Color;

public class WindyWallpaperService3 extends WindyWallpaperService {
    public Config onConfigure() {
        final Config config = new Config();
        config.slowWindColor = new Color(0.21568628f, 0.21960784f, 0.21568628f, 0.25f);
        config.fastWindColor = new Color(0.7294118f, 0.7411765f, 0.7372549f, 0.3f);
        config.bgColor = new Color(175);
        config.bgColor2 = new Color(1179077119);
        config.wallpaperColors = new WallpaperColors(android.graphics.Color.valueOf(-15724272), null, null, WallpaperColors.HINT_SUPPORTS_DARK_THEME);
        return config;
    }
}
