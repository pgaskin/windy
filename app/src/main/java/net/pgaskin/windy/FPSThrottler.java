package net.pgaskin.windy;

public class FPSThrottler {
    public static final int HIGH_FPS = 60;
    public static final int LOWER_FPS = 14;
    public static final int LOWEST_FPS = 4;
    public static final int LOW_FPS = 18;
    public static final int MED_FPS = 30;
    public static final int POWERSAVE_FPS = 5;

    public int fps;
    private final PowerSaveController powerSave;
    private long startTime;

    public FPSThrottler(PowerSaveController powerSave) {
        this.fps = HIGH_FPS;
        this.powerSave = powerSave;
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

    public void end(int fps) {
        if (this.powerSave.isPowerSaveMode()) {
            this.fps /= 2;
        }
        long frameTime = (System.nanoTime() - this.startTime) / 1000000;
        synchronized (Thread.currentThread()) {
            try {
                Thread.currentThread().wait(Math.max((1000 / fps) - frameTime, 1L));
            } catch (InterruptedException e) {
                // ignored
            }
        }
    }
}