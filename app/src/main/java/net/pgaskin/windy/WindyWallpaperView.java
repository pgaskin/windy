// SPDX-FileCopyrightText: 2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later
package net.pgaskin.windy;

import android.content.Context;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

public class WindyWallpaperView extends SurfaceView implements SurfaceHolder.Callback {
    private static final int FPS = 30;

    private WindyWallpaperRenderer renderer;
    private int themeIndex;
    private boolean paused;

    public WindyWallpaperView(Context context, AttributeSet attrs) {
        super(context, attrs);
        getHolder().addCallback(this);
    }

    public void setThemeIndex(int themeIndex) {
        this.themeIndex = themeIndex;
        if (renderer != null) {
            renderer.setThemeIndex(themeIndex);
        }
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        if (renderer != null) {
            renderer.setActive(!paused);
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        renderer = new PreviewRenderer(holder);
        renderer.start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (renderer != null) {
            renderer.setSize(width, height);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (renderer != null) {
            renderer.shutdown();
            renderer = null;
        }
    }

    private final class PreviewRenderer extends WindyWallpaperRenderer {
        PreviewRenderer(SurfaceHolder holder) {
            super("WindyPreview", getContext(), holder, themeIndex, !paused);
        }

        @Override
        protected int fps(boolean easing) {
            return FPS;
        }

        @Override
        protected boolean releaseWhenSettled() {
            return false; // keep theme switching instant while the app is open
        }
    }
}
