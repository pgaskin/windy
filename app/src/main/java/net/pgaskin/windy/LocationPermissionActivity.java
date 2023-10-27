package net.pgaskin.windy;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;

public class LocationPermissionActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
    }

    @Override
    protected void onResume() {
        super.onResume();
        requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        int i = 0;
        while (true) {
            if (i >= permissions.length) {
                break;
            } else if (grantResults[i] != -1) {
                i++;
            } else {
                UserLocationController.suppressPermissionRequests();
                break;
            }
        }
        finish();
    }
}