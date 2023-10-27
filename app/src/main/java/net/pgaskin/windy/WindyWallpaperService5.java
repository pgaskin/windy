package net.pgaskin.windy;

import android.app.WallpaperColors;

import com.badlogic.gdx.graphics.Color;

public class WindyWallpaperService5 extends WindyWallpaperService {
    public Config onConfigure() {
        final Config config = new Config();
        config.slowWindColor = new Color(0.49803922f, 0.81960785f, 0.58431375f, 0.6f);
        config.fastWindColor = new Color(1.0f, 1.0f, 1.0f, 0.5f);
        config.bgColor = new Color(9681375);
        config.bgColor2 = new Color(-270695969);
        config.wallpaperColors = new WallpaperColors(android.graphics.Color.valueOf(-16148809), null, null, 0);
        return config;
    }
}
