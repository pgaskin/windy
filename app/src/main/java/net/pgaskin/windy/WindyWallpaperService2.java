package net.pgaskin.windy;

import android.app.WallpaperColors;

import com.badlogic.gdx.graphics.Color;

public class WindyWallpaperService2 extends WindyWallpaperService {
    public Config onConfigure() {
        final Config config = new Config();
        config.slowWindColor = new Color(0.8509804f, 0.6901961f, 0.91764706f, 0.3f);
        config.fastWindColor = new Color(0.8627451f, 0.9647059f, 1.0f, 0.5f);
        config.bgColor = new Color(1081657599);
        config.bgColor2 = new Color(-642716929);
        config.wallpaperColors = new WallpaperColors(android.graphics.Color.valueOf(-11895607), null, null, 0);
        return config;
    }
}
