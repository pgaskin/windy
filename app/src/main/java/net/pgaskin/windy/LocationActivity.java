package net.pgaskin.windy;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;

import com.badlogic.gdx.math.Vector2;

import java.util.concurrent.atomic.AtomicBoolean;

public class LocationActivity extends Activity {
    private static final String TAG = "LocationActivity";

    boolean requestedInitial = false;
    boolean doneForeground = false;
    boolean doneBackground = false;

    @Override
    protected void onSaveInstanceState(Bundle s) {
        s.putBoolean("requestedInitial", this.requestedInitial);
        s.putBoolean("doneForeground", this.doneForeground);
        s.putBoolean("doneBackground", this.doneBackground);
        super.onSaveInstanceState(s);
    }

    @Override
    protected void onRestoreInstanceState(Bundle s) {
        this.requestedInitial = s.getBoolean("requestedInitial");
        this.doneForeground = s.getBoolean("doneForeground");
        this.doneBackground = s.getBoolean("doneBackground");
        super.onRestoreInstanceState(s);
    }

    @Override
    protected void onResume() {
        super.onResume();
        this.doNextPermissionRequest(true);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        this.requestedInitial = true;
        for (int i = 0; i < permissions.length; i++) {
            if (permissions[i].equals(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                if (grantResults[i] == PackageManager.PERMISSION_DENIED) {
                    Log.w(TAG, "foreground location denied");
                } else {
                    Log.i(TAG, "foreground location granted");
                }
                this.doneForeground = true;
            }
            if (permissions[i].equals(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                if (grantResults[i] == PackageManager.PERMISSION_DENIED) {
                    Log.w(TAG, "background location denied");
                } else {
                    Log.i(TAG, "background location granted");
                }
                this.doneBackground = true;
            }
        }
        this.doNextPermissionRequest(false);
    }

    public void doNextPermissionRequest(boolean initial) {
        if (initial == this.requestedInitial) {
            return;
        }
        if (!this.requestedInitial) {
            Log.i(TAG, "doing initial permission request");
        } else {
            Log.i(TAG, "handling permission request result");
        }
        if (this.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "saving location since we have access");
            LocationActivity.updateLocation(this, false, true);
        }
        if (!this.doneForeground && this.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (!this.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                Log.i(TAG, "requesting foreground location");
                this.requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 0);
            } else {
                Log.i(TAG, "showing dialog about foreground location");
                new AlertDialog.Builder(this)
                    .setTitle("Windy Live Wallpaper")
                    .setMessage("Location is required to update wind map position.")
                    .setPositiveButton("Accept", (dialog, which) -> {
                        Log.i(TAG, "requesting foreground location");
                        this.requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 0);
                    })
                    .setNegativeButton("Decline", (dialog, which) -> {
                        Log.w(TAG, "foreground location declined");
                        this.doneForeground = true;
                        LocationActivity.markLocationFlowComplete(this);
                        this.finish();
                    })
                    .create().show();
            }
            return;
        }
        if (!this.doneBackground && this.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (!this.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                Log.i(TAG, "requesting background location");
                this.requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 0);
            } else {
                Log.i(TAG, "showing dialog about background location");
                new AlertDialog.Builder(this)
                    .setTitle("Windy Live Wallpaper")
                    .setMessage("Background location is required for future wind map position updates.")
                    .setPositiveButton("Accept", (dialog, which) -> {
                        Log.i(TAG, "requesting background location");
                        this.requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 0);
                    })
                    .setNegativeButton("Decline", (dialog, which) -> {
                        Log.w(TAG, "background location declined");
                        this.doneBackground = true;
                        LocationActivity.markLocationFlowComplete(this);
                        this.finish();
                    })
                    .create().show();
            }
            return;
        }
        LocationActivity.markLocationFlowComplete(this);
        this.finish();
    }

    public static Vector2 updateLocation(Context context, boolean requestIfMissing) {
        return LocationActivity.updateLocation(context, requestIfMissing, false);
    }

    private static Vector2 updateLocation(Context context, boolean requestIfMissing, boolean isForeground) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                || (!isForeground && context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED)) {
            if (requestIfMissing && !LocationActivity.getLocationFlowComplete(context)) {
                Log.i(TAG, "permissions missing, starting location flow");
                Intent intent = new Intent(context, LocationActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                context.startActivity(intent);
            }
        } else {
            final LocationManager mgr = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            final Location loc = mgr.getLastKnownLocation("passive");
            if (loc != null) {
                final SharedPreferences prefs = LocationActivity.getPreferences(context);
                final float lat = (float) Math.round(loc.getLatitude() * 10.0f) / 10.0f;
                final float lng = (float) Math.round(loc.getLongitude() * 10.0f) / 10.0f;
                Log.i(TAG, "updated user location lng=" + lng + " lat=" + lat);
                prefs.edit().putFloat("last_lng", lng).putFloat("last_lat", lat).apply();
                return new Vector2(lng, lat);
            } else {
                Log.w(TAG, "failed to update user location");
            }
        }
        final SharedPreferences prefs = LocationActivity.getPreferences(context);
        final float lng = prefs.getFloat("last_lng", 0.0f);
        final float lat = prefs.getFloat("last_lat", 0.0f);
        if (lng != 0.0f || lat != 0.0f) {
            Log.i(TAG, "using last known location lng=" + lng + " lat=" + lat);
            return new Vector2(lng, lat);
        }
        Log.w(TAG, "no location known");
        return null;
    }

    private static final AtomicBoolean locationFlowCompleteCached = new AtomicBoolean();

    private static boolean getLocationFlowComplete(Context context) {
        if (LocationActivity.locationFlowCompleteCached.get()) {
            return true;
        }
        if (LocationActivity.getPreferences(context).getBoolean("permission_requested", false)) {
            LocationActivity.locationFlowCompleteCached.set(true);
            return true;
        }
        return false;
    }

    private static void markLocationFlowComplete(Context context) {
        Log.i(TAG, "marking location flow as complete; will not ask again");
        LocationActivity.getPreferences(context).edit().putBoolean("permission_requested", true).apply();
        LocationActivity.locationFlowCompleteCached.set(true);
    }

    private static SharedPreferences getPreferences(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences("location", Context.MODE_PRIVATE);
    }
}