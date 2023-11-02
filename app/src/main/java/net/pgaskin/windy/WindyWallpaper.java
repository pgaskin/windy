package net.pgaskin.windy;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.android.AndroidLiveWallpaper;
import com.badlogic.gdx.backends.android.AndroidWallpaperListener;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;

import java.nio.ByteBuffer;

public class WindyWallpaper implements ApplicationListener, AndroidWallpaperListener {
    public interface Provider {
        Windy.PowerSaveModeProvider createPowerSaveModeProvider();
        Windy.UserLocationProvider createUserLocationProvider();
        Windy.WindFieldProvider createWindFieldProvider();
    }

    public static class Config extends Windy.Config {
        public Color wallpaperColorPrimary;
        public Color wallpaperColorSecondary;
        public Color wallpaperColorTertiary;
    }

    private final Config config;
    private final Provider provider;
    private Windy windy;

    public WindyWallpaper(Config config, Provider provider) {
        this.config = config;
        this.provider = provider;
    }

    @Override
    public void create() {
        if (config.wallpaperColorPrimary != null) {
            final Color c1 = config.wallpaperColorPrimary;
            final Color c2 = config.wallpaperColorSecondary != null ? config.wallpaperColorSecondary : c1;
            final Color c3 = config.wallpaperColorTertiary != null ? config.wallpaperColorTertiary : c2;
            ((AndroidLiveWallpaper) Gdx.app).notifyColorsChanged(c1, c2, c3);
        }
        windy = new Windy(config, provider.createPowerSaveModeProvider(), provider.createUserLocationProvider(), provider.createWindFieldProvider());

        // this breaks encapsulation, but it's for testing only, so whatever
        // note: generate previews with something like: for x in *.png; do base=${x%%.png}; convert $x -crop 960x960+0+600 -quality 80 ../app/src/main/res/drawable/windy_${base,,}.jpg; done
        if (BuildConfig.SAVE_SCREENSHOTS) {
            for (int x = 0; x < 100; x++) windy.render();
            final Pixmap pixmap = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.getBackBufferWidth(), Gdx.graphics.getBackBufferHeight());
            final ByteBuffer pixels = pixmap.getPixels();
            final int size = Gdx.graphics.getBackBufferWidth() * Gdx.graphics.getBackBufferHeight() * 4;
            for (int i = 3; i < size; i += 4) pixels.put(i, (byte) 255);
            PixmapIO.writePNG(Gdx.files.external(provider.getClass().getSimpleName() + ".png"), pixmap);
            pixmap.dispose();
        }
    }

    @Override
    public void resize(int width, int height) {
        if (windy != null) {
            windy.resize(width, height);
        }
    }

    @Override
    public void render() {
        if (windy != null) {
            windy.render();
        }
    }

    @Override
    public void pause() {}

    @Override
    public void resume() {}

    @Override
    public void dispose() {
        if (windy != null) {
            windy.dispose();
            windy = null;
        }
    }

    @Override
    public void offsetChange(float xOffset, float yOffset, float xOffsetStep, float yOffsetStep, int xPixelOffset, int yPixelOffset) {
        if (windy != null) {
            if (xOffsetStep != 0 && xOffsetStep != -1) {
                windy.setOffsetX(xOffset, xOffsetStep);
            }
        }
    }

    @Override
    public void previewStateChange(boolean isPreview) {}

    @Override
    public void iconDropped(int x, int y) {}
}