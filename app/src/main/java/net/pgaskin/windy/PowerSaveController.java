package net.pgaskin.windy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public class PowerSaveController {
    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean bSavingPower;
            if (Objects.equals(intent.getAction(), "android.os.action.POWER_SAVE_MODE_CHANGED") && PowerSaveController.this.savingPower.get() != (bSavingPower = PowerSaveController.this.powerManager.isPowerSaveMode())) {
                PowerSaveController.this.savingPower.set(bSavingPower);
            }
        }
    };
    private boolean broadcastRegistered = false;
    private final Context context;
    private final PowerManager powerManager;
    private final AtomicBoolean savingPower;

    public PowerSaveController(Context context) {
        this.context = context;
        this.powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        this.savingPower = new AtomicBoolean(this.powerManager.isPowerSaveMode());
    }

    public synchronized void resume() {
        if (!this.broadcastRegistered) {
            boolean bSavingPower = this.powerManager.isPowerSaveMode();
            if (this.savingPower.get() != bSavingPower) {
                this.savingPower.set(bSavingPower);
            }
            IntentFilter filter = new IntentFilter("android.os.action.POWER_SAVE_MODE_CHANGED");
            this.context.registerReceiver(this.broadcastReceiver, filter);
            this.broadcastRegistered = true;
        }
    }

    public synchronized void pause() {
        if (this.broadcastRegistered) {
            this.context.unregisterReceiver(this.broadcastReceiver);
            this.broadcastRegistered = false;
        }
    }

    public boolean isPowerSaveMode() {
        return this.savingPower.get();
    }
}