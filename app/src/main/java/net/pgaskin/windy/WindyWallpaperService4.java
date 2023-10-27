package net.pgaskin.windy;

import android.app.WallpaperColors;

import com.badlogic.gdx.graphics.Color;

public class WindyWallpaperService4 extends WindyWallpaperService {
    public Config onConfigure() {
        final Config config = new Config();
        config.slowWindColor = new Color(0.9764706f, 0.8627451f, 0.64705884f, 0.6f);
        config.fastWindColor = new Color(1.0f, 1.0f, 1.0f, 0.7f);
        config.bgColor = new Color(-444496161);
        config.bgColor2 = new Color(-139227681);
        config.wallpaperColors = new WallpaperColors(android.graphics.Color.valueOf(-1537647), null, null, 0);
        return config;
    }
}
