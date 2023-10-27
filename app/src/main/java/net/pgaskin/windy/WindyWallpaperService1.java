package net.pgaskin.windy;

import android.app.WallpaperColors;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.Vector2;

public class WindyWallpaperService1 extends WindyWallpaperService {
    public Config onConfigure() {
        final Config config = new Config();
        config.slowWindColor = new Color(0.49803922f, 0.81960785f, 0.58431375f, 0.3f);
        config.fastWindColor = new Color(0.98039216f, 0.9411765f, 0.8235294f, 0.25f);
        config.bgColor = new Color(71853823);
        config.bgColor2 = new Color(8760063);
        config.wallpaperColors = new WallpaperColors(android.graphics.Color.valueOf(-16232077), null, null, 0);
        config.fakeLocation = new Vector2(-97.0f, 38.0f);
        return config;
    }
}
