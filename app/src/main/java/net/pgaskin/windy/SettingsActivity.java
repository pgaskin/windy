// SPDX-FileCopyrightText: 2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later
package net.pgaskin.windy;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.provider.Settings;
import android.text.InputType;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;
import android.widget.Toolbar;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Locale;

public class SettingsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);

        final Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        final View container = findViewById(R.id.settings_container);
        findViewById(R.id.root).setOnApplyWindowInsetsListener((v, insets) -> {
            final Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            toolbar.setPadding(bars.left, bars.top, bars.right, 0);
            container.setPadding(bars.left, 0, bars.right, bars.bottom);
            return insets;
        });

        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction().replace(R.id.settings_container, new SettingsFragment()).commit();
        }
    }

    public static class SettingsFragment extends PreferenceFragment implements SharedPreferences.OnSharedPreferenceChangeListener {
        private static final String TAG = "SettingsActivity";

        private static final int REQUEST_LOCATION = 1;
        private static final int REQUEST_BACKGROUND_LOCATION = 2;

        private final Handler handler = new Handler(Looper.getMainLooper());

        private PreferenceCategory locationCategory;
        private Preference locationPref;
        private Preference backgroundLocationPref;
        private Preference updateNowPref;
        private SwitchPreference meteredPref;
        private EditTextPreference urlPref;
        private Preference devicePref;
        private PreferenceCategory aboutCategory;
        private Preference dataSourcePref;
        private boolean updating;

        private AlertDialog locationDialog;
        private EditText locationInput;

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            getPreferenceManager().setStorageDeviceProtected();
            getPreferenceManager().setSharedPreferencesName(Prefs.STORE);

            final Context context = getActivity();
            final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);

            locationCategory = new PreferenceCategory(context);
            locationCategory.setTitle(R.string.location);
            screen.addPreference(locationCategory);

            locationPref = new Preference(context);
            locationPref.setOrder(0);
            locationPref.setOnPreferenceClickListener(p -> {
                showLocationDialog();
                return true;
            });
            locationCategory.addPreference(locationPref);

            final ListPreference locationInterval = new ListPreference(context);
            locationInterval.setOrder(1);
            locationInterval.setKey(Prefs.KEY_LOCATION_INTERVAL);
            locationInterval.setTitle(R.string.location_interval);
            locationInterval.setDialogTitle(R.string.location_interval);
            locationInterval.setSummary(getString(R.string.location_interval_summary));
            locationInterval.setEntries(R.array.location_interval_entries);
            locationInterval.setEntryValues(R.array.location_interval_values);
            locationInterval.setDefaultValue(String.valueOf(Prefs.DEFAULT_LOCATION_INTERVAL));
            locationCategory.addPreference(locationInterval);

            backgroundLocationPref = new Preference(context);
            backgroundLocationPref.setOrder(2); // added and removed as needed
            backgroundLocationPref.setTitle(R.string.background_location);
            backgroundLocationPref.setSummary(R.string.background_location_summary);
            backgroundLocationPref.setOnPreferenceClickListener(p -> {
                requestBackgroundLocation();
                return true;
            });

            final Preference privacy = new Preference(context);
            privacy.setOrder(3);
            privacy.setLayoutResource(R.layout.preference_note); // the title is the note text
            privacy.setTitle(R.string.location_privacy_note);
            privacy.setSelectable(false);
            locationCategory.addPreference(privacy);

            final PreferenceCategory dataCategory = new PreferenceCategory(context);
            dataCategory.setTitle(R.string.data);
            screen.addPreference(dataCategory);

            updateNowPref = new Preference(context);
            updateNowPref.setTitle(R.string.update_now);
            updateNowPref.setOnPreferenceClickListener(p -> {
                updateNow();
                return true;
            });
            dataCategory.addPreference(updateNowPref);

            final Preference showTexture = new Preference(context);
            showTexture.setTitle(R.string.show_texture);
            showTexture.setSummary(R.string.show_texture_summary);
            showTexture.setOnPreferenceClickListener(p -> {
                showTextureDialog();
                return true;
            });
            dataCategory.addPreference(showTexture);

            final ListPreference dataInterval = new ListPreference(context);
            dataInterval.setKey(Prefs.KEY_DATA_INTERVAL);
            dataInterval.setTitle(R.string.data_interval);
            dataInterval.setDialogTitle(R.string.data_interval);
            dataInterval.setSummary(getString(R.string.data_interval_summary));
            dataInterval.setEntries(R.array.data_interval_entries);
            dataInterval.setEntryValues(R.array.data_interval_values);
            dataInterval.setDefaultValue(String.valueOf(Prefs.DEFAULT_DATA_INTERVAL));
            dataCategory.addPreference(dataInterval);

            meteredPref = new SwitchPreference(context);
            meteredPref.setKey(Prefs.KEY_DATA_METERED);
            meteredPref.setTitle(R.string.data_metered);
            meteredPref.setSummary(R.string.data_metered_summary);
            meteredPref.setDefaultValue(false);
            dataCategory.addPreference(meteredPref);

            urlPref = new EditTextPreference(context) {
                @Override
                protected void onPrepareDialogBuilder(AlertDialog.Builder builder) {
                    super.onPrepareDialogBuilder(builder);
                    builder.setNeutralButton(R.string.reset, (dialog, which) -> setText(BuildConfig.WIND_FIELD_API_URL));
                }
            };
            urlPref.setKey(Prefs.KEY_DATA_URL);
            urlPref.setTitle(R.string.data_url);
            urlPref.setDialogTitle(R.string.data_url);
            urlPref.setDefaultValue(BuildConfig.WIND_FIELD_API_URL);
            urlPref.getEditText().setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            urlPref.getEditText().setSingleLine();
            urlPref.setOnPreferenceChangeListener((p, value) -> {
                final String url = normalizeUrl(String.valueOf(value));
                if (url == null) {
                    Toast.makeText(getActivity(), R.string.data_url_invalid, Toast.LENGTH_SHORT).show();
                    return false;
                }
                if (!url.equals(String.valueOf(value))) {
                    urlPref.setText(url);
                    return false;
                }
                return true;
            });
            dataCategory.addPreference(urlPref);

            final PreferenceCategory renderingCategory = new PreferenceCategory(context);
            renderingCategory.setTitle(R.string.rendering);
            screen.addPreference(renderingCategory);

            final ListPreference maxFps = new ListPreference(context);
            maxFps.setKey(Prefs.KEY_MAX_FPS);
            maxFps.setTitle(R.string.max_fps);
            maxFps.setDialogTitle(R.string.max_fps);
            maxFps.setSummary(getString(R.string.max_fps_summary));
            maxFps.setEntries(R.array.max_fps_entries);
            maxFps.setEntryValues(R.array.max_fps_values);
            maxFps.setDefaultValue(String.valueOf(Prefs.MAX_FPS_AUTOMATIC));
            renderingCategory.addPreference(maxFps);

            final SwitchPreference staticMode = new SwitchPreference(context);
            staticMode.setKey(Prefs.KEY_STATIC_MODE);
            staticMode.setTitle(R.string.static_mode);
            staticMode.setSummary(R.string.static_mode_summary);
            staticMode.setDefaultValue(false);
            renderingCategory.addPreference(staticMode);

            devicePref = new Preference(context);
            devicePref.setLayoutResource(R.layout.preference_note);
            devicePref.setSelectable(false);
            renderingCategory.addPreference(devicePref);

            aboutCategory = new PreferenceCategory(context);
            aboutCategory.setTitle(R.string.about);
            screen.addPreference(aboutCategory);

            final Preference source = new Preference(context);
            source.setOrder(0);
            source.setTitle(R.string.source_code);
            source.setSummary(R.string.source_code_url);
            source.setOnPreferenceClickListener(p -> {
                openUrl(getString(R.string.source_code_url));
                return true;
            });
            aboutCategory.addPreference(source);

            final Preference version = new Preference(context);
            version.setOrder(1);
            version.setTitle(R.string.version);
            version.setSummary(BuildConfig.VERSION_NAME);
            version.setSelectable(false);
            aboutCategory.addPreference(version);

            final Preference licenses = new Preference(context);
            licenses.setOrder(2);
            licenses.setTitle(R.string.licenses);
            licenses.setOnPreferenceClickListener(p -> {
                startActivity(new Intent(getActivity(), LicensesActivity.class));
                return true;
            });
            aboutCategory.addPreference(licenses);

            dataSourcePref = new Preference(context);
            dataSourcePref.setOrder(3); // added and removed as needed
            dataSourcePref.setTitle(R.string.data_source);
            dataSourcePref.setSummary(R.string.data_source_name);
            dataSourcePref.setOnPreferenceClickListener(p -> {
                openUrl(getString(R.string.data_source_url));
                return true;
            });

            setPreferenceScreen(screen);
        }

        @Override
        public void onResume() {
            super.onResume();
            getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
            refresh();
        }

        @Override
        public void onPause() {
            super.onPause();
            getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
            final Context context = getActivity();
            if (context == null) {
                return;
            }
            switch (key) {
                case Prefs.KEY_LOCATION_INTERVAL:
                    LocationUpdateService.schedule(context);
                    break;
                case Prefs.KEY_DATA_INTERVAL:
                    // picking an interval here answers the consent dialog too
                    Prefs.setDataConsent(context, Prefs.dataInterval(context) != Prefs.INTERVAL_NEVER);
                    WindFieldUpdateService.schedulePeriodic(context);
                    WindFieldUpdateService.scheduleStartup(context);
                    WindField.invalidate();
                    break;
                case Prefs.KEY_DATA_METERED:
                    WindFieldUpdateService.schedulePeriodic(context); // re-schedule with the new network constraint
                    WindFieldUpdateService.scheduleStartup(context);
                    break;
                case Prefs.KEY_DATA_URL:
                    WindFieldUpdateService.clearEtag(context);
                    WindField.invalidate();
                    break;
                case Prefs.KEY_GPU_MODEL:
                    break; // just refresh
                default:
                    return;
            }
            refresh();
        }

        private void refresh() {
            final Context context = getActivity();
            if (context == null) {
                return;
            }

            final float[] location = Location.saved(context);
            final long locationInterval = Prefs.locationInterval(context);
            locationPref.setTitle(location != null ? formatLocation(location) : getString(R.string.location_unset));
            locationPref.setSummary(locationInterval == 0
                    ? getString(R.string.location_manual)
                    : formatUpdated(context, Location.lastUpdated(context)));

            if (context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationCategory.removePreference(backgroundLocationPref);
            } else {
                locationCategory.addPreference(backgroundLocationPref);
                backgroundLocationPref.setOrder(2); // after the interval, before the note
            }

            final long dataInterval = Prefs.dataInterval(context);
            updateNowPref.setEnabled(!updating && dataInterval != Prefs.INTERVAL_NEVER);
            updateNowPref.setSummary(updating
                    ? getString(R.string.updating)
                    : dataInterval == Prefs.INTERVAL_NEVER
                      ? getString(R.string.data_builtin)
                      : formatUpdatedSource(context));

            meteredPref.setEnabled(dataInterval > 0); // only limits automatic updates

            urlPref.setSummary(Prefs.dataUrl(context));

            final String gpu = Prefs.gpuModel(context); // only known once the wallpaper has rendered
            final String device = gpu != null ? getString(R.string.device_note, Build.MODEL, gpu) : Build.MODEL;
            devicePref.setTitle(getString(R.string.device_note_os, device, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, Build.ID));

            // the data source is only known for the default API
            if (isDefaultDataHost(context)) {
                aboutCategory.addPreference(dataSourcePref);
            } else {
                aboutCategory.removePreference(dataSourcePref);
            }
        }

        private static boolean isDefaultDataHost(Context context) {
            try {
                final String host = new URL(Prefs.dataUrl(context)).getHost();
                return host.equalsIgnoreCase(new URL(BuildConfig.WIND_FIELD_API_URL).getHost());
            } catch (MalformedURLException ex) {
                return false;
            }
        }

        private void openUrl(String url) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (ActivityNotFoundException ex) {
                Log.w(TAG, "no activity to open " + url + ": " + ex);
            }
        }

        private static String normalizeUrl(String value) {
            String url = value.trim();
            if (url.isEmpty()) {
                return BuildConfig.WIND_FIELD_API_URL;
            }
            if (!url.contains("://")) {
                url = "https://" + url;
            }
            try {
                final URL parsed = new URL(url);
                if (!"https".equals(parsed.getProtocol()) || parsed.getHost().isEmpty()) {
                    return null;
                }
            } catch (MalformedURLException ex) {
                return null;
            }
            return url;
        }

        private String formatUpdatedSource(Context context) {
            final String updated = formatUpdated(context, WindFieldUpdateService.lastUpdated(context));
            final String source = WindFieldUpdateService.lastSource(context);
            return source != null ? getString(R.string.updated_source, updated, source) : updated;
        }

        private static String formatLocation(float[] lngLat) {
            return String.format(Locale.US, "%.1f, %.1f", lngLat[0], lngLat[1]);
        }

        private String formatUpdated(Context context, long time) {
            if (time <= 0) {
                return getString(R.string.updated_never);
            }
            return getString(R.string.updated_at, DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
        }

        private static float[] parseLocation(String text) {
            final String[] parts = text.trim().split("[,;\\s]+");
            if (parts.length != 2) {
                return null;
            }
            try {
                final float lng = Float.parseFloat(parts[0]);
                final float lat = Float.parseFloat(parts[1]);
                if (lng < -180 || lng > 180 || lat < -90 || lat > 90) {
                    return null;
                }
                return new float[]{lng, lat};
            } catch (NumberFormatException ex) {
                return null;
            }
        }

        private void showTextureDialog() {
            final Context context = getActivity().getApplicationContext();
            new Thread(() -> {
                Bitmap texture = null;
                try {
                    final WindField.Snapshot snap = WindField.snapshot(context);
                    texture = Bitmap.createBitmap(snap.width, snap.height, Bitmap.Config.ARGB_8888);
                    texture.copyPixelsFromBuffer(ByteBuffer.wrap(snap.rgba));
                } catch (Throwable t) {
                    Log.e(TAG, "failed to load the wind texture: " + t);
                }
                final Bitmap bitmap = texture;
                handler.post(() -> {
                    if (getActivity() == null) {
                        return;
                    }
                    if (bitmap == null) {
                        Toast.makeText(getActivity(), R.string.show_texture_failed, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    final ImageView view = (ImageView) getActivity().getLayoutInflater().inflate(R.layout.dialog_texture, null);
                    view.setImageBitmap(bitmap);
                    new AlertDialog.Builder(getActivity())
                            .setView(view)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
            }).start();
        }

        private void showLocationDialog() {
            final Context context = getActivity();
            final View view = getActivity().getLayoutInflater().inflate(R.layout.dialog_location, null);
            final EditText input = view.findViewById(R.id.location_input);
            final float[] current = Location.saved(context);
            if (current != null) {
                input.setText(formatLocation(current));
                input.setSelection(input.getText().length());
            }

            final AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle(R.string.location)
                    .setView(view)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setNeutralButton(R.string.use_current_location, null)
                    .create();
            dialog.setOnShowListener(d -> {
                // set the listeners directly so the dialog isn't dismissed on
                // invalid input or when getting the current location
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
                    final float[] location = parseLocation(input.getText().toString());
                    if (location == null) {
                        input.setError(getString(R.string.location_invalid));
                        return;
                    }
                    Location.save(context, location[0], location[1]);
                    refresh();
                    dialog.dismiss();
                });
                dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(v -> useCurrentLocation());
            });
            dialog.setOnDismissListener(d -> {
                locationDialog = null;
                locationInput = null;
            });

            locationDialog = dialog;
            locationInput = input;
            dialog.show();
        }

        private void useCurrentLocation() {
            final Context context = getActivity();
            if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
                return;
            }
            if (locationDialog != null) {
                locationDialog.getButton(DialogInterface.BUTTON_NEUTRAL).setEnabled(false);
            }
            Location.requestCurrent(context, location -> {
                if (getActivity() == null) {
                    return;
                }
                if (location == null) {
                    Toast.makeText(getActivity(), R.string.location_unavailable, Toast.LENGTH_SHORT).show();
                    if (locationDialog != null) {
                        locationDialog.getButton(DialogInterface.BUTTON_NEUTRAL).setEnabled(true);
                    }
                    return;
                }
                if (locationInput != null) {
                    locationInput.setText(formatLocation(location));
                }
                refresh();
                if (locationDialog != null) {
                    locationDialog.dismiss();
                }
            });
        }

        private void requestBackgroundLocation() {
            final Context context = getActivity();
            if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_BACKGROUND_LOCATION);
            } else {
                requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQUEST_BACKGROUND_LOCATION);
            }
        }

        private void openAppSettings() {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getActivity().getPackageName(), null)));
        }

        @Override
        public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
            for (int i = 0; i < permissions.length; i++) {
                final boolean granted = grantResults[i] == PackageManager.PERMISSION_GRANTED;
                if (Manifest.permission.ACCESS_COARSE_LOCATION.equals(permissions[i])) {
                    if (!granted) {
                        Toast.makeText(getActivity(), R.string.location_permission_denied, Toast.LENGTH_SHORT).show();
                    } else if (requestCode == REQUEST_LOCATION) {
                        useCurrentLocation();
                    } else if (requestCode == REQUEST_BACKGROUND_LOCATION) {
                        requestBackgroundLocation();
                    }
                }
                if (Manifest.permission.ACCESS_BACKGROUND_LOCATION.equals(permissions[i]) && !granted
                        && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                    // it can't be requested again, so it must be granted manually
                    openAppSettings();
                }
            }
            refresh();
        }

        private void updateNow() {
            final Context context = getActivity().getApplicationContext();
            updating = true;
            refresh();
            new Thread(() -> {
                boolean ok = true;
                try {
                    WindFieldUpdateService.update(context, null, "manual");
                } catch (Exception ex) {
                    Log.e(TAG, "manual wind field update failed: " + ex);
                    ok = false;
                }
                final boolean succeeded = ok;
                handler.post(() -> {
                    updating = false;
                    if (getActivity() == null) {
                        return;
                    }
                    Toast.makeText(getActivity(), succeeded ? R.string.update_succeeded : R.string.update_failed, Toast.LENGTH_SHORT).show();
                    refresh();
                });
            }).start();
        }
    }
}
