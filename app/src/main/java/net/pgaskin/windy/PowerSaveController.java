package net.pgaskin.windy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;

import java.util.concurrent.atomic.AtomicBoolean;

public class PowerSaveController {
    private final Context context;
    private final PowerManager powerManager;
    private final AtomicBoolean powerSaveMode = new AtomicBoolean();
    private final IntentFilter filter = new IntentFilter("android.os.action.POWER_SAVE_MODE_CHANGED");
    private boolean registered = false;

    public PowerSaveController(Context context) {
        this.context = context;
        this.powerManager = (PowerManager) this.context.getSystemService(Context.POWER_SERVICE);
        this.resume();
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            PowerSaveController.this.updatePowerSaveMode();
        }
    };

    public synchronized void resume() {
        if (!this.registered) {
            this.updatePowerSaveMode();
            this.context.registerReceiver(this.receiver, this.filter);
            this.registered = true;
        }
    }

    public synchronized void pause() {
        if (this.registered) {
            this.context.unregisterReceiver(this.receiver);
            this.registered = false;
        }
    }

    public boolean isPowerSaveMode() {
        return this.powerSaveMode.get();
    }

    private void updatePowerSaveMode() {
        this.powerSaveMode.set(this.powerManager.isPowerSaveMode());
    }
}