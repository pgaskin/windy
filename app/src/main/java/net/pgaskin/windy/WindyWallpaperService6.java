package net.pgaskin.windy;

import android.app.WallpaperColors;

import com.badlogic.gdx.graphics.Color;

public class WindyWallpaperService6 extends WindyWallpaperService {
    public Config onConfigure() {
        final Config config = new Config();
        config.slowWindColor = new Color(1.0f, 1.0f, 1.0f, 0.5f);
        config.fastWindColor = new Color(0.95686275f, 1.0f, 0.5294118f, 0.25f);
        config.bgColor = new Color(1974139647);
        config.bgColor2 = new Color(-184580097);
        config.wallpaperColors = new WallpaperColors(android.graphics.Color.valueOf(-8671499), null, null, 0);
        return config;
    }
}
