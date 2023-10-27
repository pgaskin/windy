package net.pgaskin.windy;

import com.badlogic.gdx.Graphics;

public class FPSThrottler {
    public static final int HIGH_FPS = 60;
    public static final int LOWER_FPS = 14;
    public static final int LOWEST_FPS = 4;
    public static final int LOW_FPS = 18;
    public static final int MED_FPS = 30;
    public static final int POWERSAVE_FPS = 5;

    public int fps;
    private boolean letGdxHandleFrames;
    private boolean newFrameHasBenDrawn;
    private final PowerSaveController powerSave;
    private long startTime;

    public FPSThrottler(PowerSaveController powerSave) {
        this(powerSave, true);
    }

    public FPSThrottler(PowerSaveController powerSave, boolean letGdxHandleFrames) {
        this.fps = HIGH_FPS;
        this.newFrameHasBenDrawn = true;
        this.powerSave = powerSave;
        this.letGdxHandleFrames = letGdxHandleFrames;
    }

    public void begin(boolean forceFrame) {
        if (forceFrame) {
            synchronized (Thread.currentThread()) {
                Thread.currentThread().notify();
            }
        }
        begin();
    }

    public void begin() {
        this.startTime = System.nanoTime();
    }

    public void end() {
        end(this.fps);
    }

    public void endAndRequestFrame(Graphics graphics, int fps) {
        if (fps > 0) {
            end(fps);
            this.newFrameHasBenDrawn = true;
            requestRendering(graphics);
            return;
        }
        this.newFrameHasBenDrawn = true;
    }

    public void requestRendering(Graphics graphics) {
        if (graphics == null) {
            return;
        }
        if (this.newFrameHasBenDrawn) {
            graphics.requestRendering();
        }
        this.newFrameHasBenDrawn = false;
    }

    public void end(int fps) {
        int fps2 = this.powerSave.isPowerSaveMode() ? fps / 2 : fps;
        this.newFrameHasBenDrawn = true;
        if (fps2 == HIGH_FPS && this.letGdxHandleFrames) {
            return;
        }
        long frameTime = (System.nanoTime() - this.startTime) / 1000000;
        synchronized (Thread.currentThread()) {
            try {
                Thread.currentThread().wait(Math.max((1000 / fps2) - frameTime, 1L));
            } catch (InterruptedException e) {
                // ignored
            }
        }
    }

    public void reset() {
        this.newFrameHasBenDrawn = true;
    }

    public void setLetGdxHandleFrames(boolean letGdxHandleFrames) {
        this.letGdxHandleFrames = letGdxHandleFrames;
    }
}