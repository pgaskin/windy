package net.pgaskin.windy;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;

import com.badlogic.gdx.math.Vector2;

import java.util.concurrent.atomic.AtomicBoolean;

public class UserLocationController {
    private static final AtomicBoolean suppressRequests = new AtomicBoolean();
    private final Context context;
    private final LocationManager locationManager;

    public UserLocationController(Context context) {
        this.context = context;
        this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        suppressRequests.set(false);
    }

    public static void suppressPermissionRequests() {
        suppressRequests.set(true);
    }

    public Vector2 lastKnown(boolean requestPermission) {
        // TODO: figure out a good way to do background location

        int locPermission = this.context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (locPermission != PackageManager.PERMISSION_GRANTED) {
            if (requestPermission && !suppressRequests.get()) {
                Intent intent = new Intent(this.context, LocationPermissionActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                this.context.startActivity(intent);
            }
        } else {
            Context secureContext = this.context.createDeviceProtectedStorageContext();
            SharedPreferences preferences = secureContext.getSharedPreferences("location", 0);
            Location location = this.locationManager.getLastKnownLocation("passive");
            if (location != null) {
                float lat = (float) location.getLatitude();
                float lng = (float) location.getLongitude();
                SharedPreferences.Editor editor = preferences.edit();
                editor.putFloat("last_lng", lng);
                editor.putFloat("last_lat", lat);
                editor.apply();
                return new Vector2(lng, lat);
            }

            float lng2 = preferences.getFloat("last_lng", 0.0f);
            float lat2 = preferences.getFloat("last_lat", 0.0f);
            if (lng2 != 0.0f || lat2 != 0.0f) {
                return new Vector2(lng2, lat2);
            }
        }
        return getFallbackLocation();
    }

    private Vector2 getFallbackLocation() {
        return new Vector2(-97.0f, 38.0f);
    }
}