// SPDX-FileCopyrightText: 2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later
package net.pgaskin.windy;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toolbar;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";

    private static final int REQUEST_SET_WALLPAPER = 1;

    private static final int[] CUSTOM_COLOR_LABELS = {
            R.string.custom_color_slow,
            R.string.custom_color_fast,
            R.string.custom_color_bg1,
            R.string.custom_color_bg2,
            R.string.custom_color_tint,
    };

    private static final int[] CUSTOM_PARAM_LABELS = {
            R.string.param_line_width,
            R.string.param_opacity,
            R.string.param_trail_decay,
            R.string.param_wind_speed,
    };
    private static final float[] CUSTOM_PARAM_MIN = {0.25f, 0.0f, 0.95f, 0.0f};
    private static final float[] CUSTOM_PARAM_MAX = {4.0f, 2.0f, 0.9999f, 0.5f};
    private static final int[] CUSTOM_PARAM_STEPS = {75, 100, 500, 100};
    private static final int[] CUSTOM_PARAM_DECIMALS = {2, 2, 4, 3};

    private WindyWallpaperView preview;
    private HorizontalScrollView themeScroll;
    private LinearLayout themeList;
    private int themeIndex;

    private MenuItem customizeItem;
    private View customPanel;
    private Spinner presetSpinner;
    private Button presetDelete;
    private final ColorSwatchView[] swatches = new ColorSwatchView[CustomTheme.COUNT];
    private ImageView customCardImage;
    private final Runnable customColorListener = this::refreshCustomColors;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity);

        preview = findViewById(R.id.preview);
        themeScroll = findViewById(R.id.theme_scroll);
        themeList = findViewById(R.id.theme_list);

        final Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.inflateMenu(R.menu.main);
        customizeItem = toolbar.getMenu().findItem(R.id.action_customize);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_settings) {
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            }
            if (item.getItemId() == R.id.action_customize) {
                showCustomizeDialog();
                return true;
            }
            return false;
        });

        findViewById(R.id.set_wallpaper).setOnClickListener(v -> setAsWallpaper());

        applyInsets(toolbar);
        applySystemBarAppearance();

        buildCustomPanel();

        themeIndex = initialThemeIndex();
        buildThemeCards();
        selectTheme(themeIndex, false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        CustomTheme.addListener(customColorListener);
        refreshCustomColors();
    }

    @Override
    protected void onStop() {
        super.onStop();
        CustomTheme.removeListener(customColorListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        preview.setPaused(false);
        // ask about the wind data first, then about the location (like the
        // wallpaper does when it's started from the picker)
        if (Prefs.dataConsentPending(this)) {
            WindFieldConsentActivity.request(this);
        } else {
            LocationConsentActivity.updateLocation(this, true);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        preview.setPaused(true);
    }

    private void applyInsets(Toolbar toolbar) {
        // preview is edge-to-edge, toolbars are inset
        findViewById(R.id.root).setOnApplyWindowInsetsListener((v, insets) -> {
            final Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            toolbar.setPadding(bars.left, bars.top, bars.right, 0);
            themeScroll.setPadding(bars.left, themeScroll.getPaddingTop(), bars.right, bars.bottom);
            customPanel.setPadding(bars.left, customPanel.getPaddingTop(), bars.right, 0);
            return insets;
        });
    }

    private void applySystemBarAppearance() {
        final WindowInsetsController controller = getWindow().getInsetsController();
        if (controller == null) {
            return;
        }
        // the status bar is over the preview, the navigation bar is over the theme selector
        final boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        controller.setSystemBarsAppearance(
                night ? 0 : WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
    }

    private int initialThemeIndex() {
        final WallpaperInfo info = WallpaperManager.getInstance(this).getWallpaperInfo();
        if (info != null && getPackageName().equals(info.getPackageName())) {
            for (Themes.Entry theme : Themes.ALL) {
                if (theme.service.equals(info.getServiceName())) {
                    return theme.index;
                }
            }
        }
        return Prefs.themeIndex(this);
    }

    private void buildThemeCards() {
        final LayoutInflater inflater = getLayoutInflater();
        for (Themes.Entry theme : Themes.ALL) {
            final View card = inflater.inflate(R.layout.theme_card, themeList, false);
            final ImageView image = card.findViewById(R.id.theme_image);
            image.setImageResource(theme.thumbnail);
            image.setClipToOutline(true);
            if (theme.index == Themes.CUSTOM) {
                customCardImage = image; // shows the picked colors instead
            }
            ((TextView) card.findViewById(R.id.theme_name)).setText(theme.name);
            card.setContentDescription(theme.label);
            card.setOnClickListener(v -> selectTheme(theme.index, true));
            themeList.addView(card);
        }
    }

    private void selectTheme(int index, boolean animate) {
        themeIndex = index;
        Prefs.setThemeIndex(this, index);
        preview.setThemeIndex(index);
        for (int i = 0; i < themeList.getChildCount(); i++) {
            themeList.getChildAt(i).setSelected(i == index);
        }
        final boolean custom = index == Themes.CUSTOM;
        customPanel.setVisibility(custom ? View.VISIBLE : View.GONE);
        customizeItem.setVisible(!custom); // it copies the shown theme into the custom one
        if (custom) {
            refreshPresets();
        }
        final View card = themeList.getChildAt(index);
        themeScroll.post(() -> {
            final int x = card.getLeft() - (themeScroll.getWidth() - card.getWidth()) / 2;
            if (animate) {
                themeScroll.smoothScrollTo(x, 0);
            } else {
                themeScroll.scrollTo(x, 0);
            }
        });
    }

    private void buildCustomPanel() {
        customPanel = findViewById(R.id.custom_panel);
        presetSpinner = findViewById(R.id.preset_spinner);
        presetDelete = findViewById(R.id.preset_delete);

        final LinearLayout swatchList = findViewById(R.id.swatch_list);
        final LayoutInflater inflater = getLayoutInflater();
        for (int i = 0; i < CustomTheme.COUNT; i++) {
            final int component = i;
            final View item = inflater.inflate(R.layout.custom_swatch, swatchList, false);
            final String label = getString(CUSTOM_COLOR_LABELS[component]);
            swatches[component] = item.findViewById(R.id.swatch_color);
            ((TextView) item.findViewById(R.id.swatch_label)).setText(label);
            item.setContentDescription(label);
            item.setOnClickListener(v -> pickCustomColor(component, label));
            swatchList.addView(item);
        }

        final View advanced = inflater.inflate(R.layout.custom_advanced, swatchList, false);
        advanced.setOnClickListener(v -> showAdvancedDialog());
        swatchList.addView(advanced);

        presetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                final Object item = parent.getItemAtPosition(position);
                final String name = item == null ? "" : item.toString();
                // ignore the placeholder and the selection we just applied
                // (setSelection notifies again when the spinner is laid out)
                if (!name.isEmpty() && !name.equals(CustomTheme.presetName(MainActivity.this))
                        && CustomTheme.preset(MainActivity.this, name) != null) {
                    CustomTheme.loadPreset(MainActivity.this, name);
                    refreshPresets();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        findViewById(R.id.preset_save).setOnClickListener(v -> showSavePresetDialog());
        presetDelete.setOnClickListener(v -> showDeletePresetDialog());
    }

    private void showAdvancedDialog() {
        final float[] initial = CustomTheme.params(this);
        final SeekBar[] sliders = new SeekBar[CustomTheme.PARAM_COUNT];
        final TextView[] values = new TextView[CustomTheme.PARAM_COUNT];

        final LayoutInflater inflater = getLayoutInflater();
        final LinearLayout container = (LinearLayout) inflater.inflate(R.layout.dialog_advanced, null);

        final View title = inflater.inflate(R.layout.dialog_advanced_title, null);
        title.findViewById(R.id.param_restart).setOnClickListener(v -> WindyWallpaperRenderer.restartAll());

        for (int i = 0; i < CustomTheme.PARAM_COUNT; i++) {
            final int param = i;
            final View row = inflater.inflate(R.layout.custom_param, container, false);
            ((TextView) row.findViewById(R.id.param_label)).setText(CUSTOM_PARAM_LABELS[param]);
            values[param] = row.findViewById(R.id.param_value);
            sliders[param] = row.findViewById(R.id.param_slider);
            sliders[param].setMax(CUSTOM_PARAM_STEPS[param]);
            sliders[param].setProgress(paramProgress(param, initial[param]));
            values[param].setText(paramText(param, initial[param]));
            sliders[param].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    final float value = paramValue(param, progress);
                    values[param].setText(paramText(param, value));
                    if (fromUser) {
                        CustomTheme.setParam(MainActivity.this, param, value, false); // live preview
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
            container.addView(row);
        }

        final boolean[] accepted = {false};
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setCustomTitle(title)
                .setView(container)
                .setPositiveButton(R.string.save, (d, which) -> {
                    accepted[0] = true;
                    CustomTheme.persist(this);
                    updatePresetSelection();
                })
                .setNeutralButton(R.string.reset, null) // set below so it doesn't dismiss it
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(d -> {
                    if (!accepted[0]) {
                        CustomTheme.setParams(this, initial, false); // undo the live preview
                    }
                })
                .create();

        final Window window = dialog.getWindow();
        if (window != null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); // don't dim the wallpaper being previewed
        }
        dialog.show();
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(v -> {
            final float[] defaults = CustomTheme.themeParams(Themes.CUSTOM);
            CustomTheme.setParams(this, defaults, false);
            for (int i = 0; i < CustomTheme.PARAM_COUNT; i++) {
                sliders[i].setProgress(paramProgress(i, defaults[i]));
            }
        });
    }

    private static float paramValue(int param, int progress) {
        final float fraction = progress / (float) CUSTOM_PARAM_STEPS[param];
        if (param == CustomTheme.ALPHA_DECAY) {
            // it only really matters close to 1.0, so scale the slider accordingly
            final double min = Math.log1p(-CUSTOM_PARAM_MIN[param]);
            final double max = Math.log1p(-CUSTOM_PARAM_MAX[param]);
            return (float) (1.0 - Math.exp(min + (max - min) * fraction));
        }
        return CUSTOM_PARAM_MIN[param] + (CUSTOM_PARAM_MAX[param] - CUSTOM_PARAM_MIN[param]) * fraction;
    }

    private static int paramProgress(int param, float value) {
        final double fraction;
        if (param == CustomTheme.ALPHA_DECAY) {
            final double min = Math.log1p(-CUSTOM_PARAM_MIN[param]);
            final double max = Math.log1p(-CUSTOM_PARAM_MAX[param]);
            fraction = (Math.log1p(-Math.min(value, 0.999999)) - min) / (max - min);
        } else {
            fraction = (value - CUSTOM_PARAM_MIN[param]) / (CUSTOM_PARAM_MAX[param] - CUSTOM_PARAM_MIN[param]);
        }
        return Math.round((float) (Math.max(0.0, Math.min(fraction, 1.0)) * CUSTOM_PARAM_STEPS[param]));
    }

    private static String paramText(int param, float value) {
        return String.format(Locale.getDefault(), "%." + CUSTOM_PARAM_DECIMALS[param] + "f", value);
    }

    private void pickCustomColor(int component, String label) {
        final int initial = CustomTheme.color(this, component);
        ColorPickerDialog.show(this, label, initial, CustomTheme.hasAlpha(component),
                color -> CustomTheme.setColor(this, component, color, false), // live preview
                color -> {
                    CustomTheme.setColor(this, component, color, true);
                    updatePresetSelection();
                });
    }

    private void updatePresetSelection() {
        final String name = CustomTheme.presetName(this);
        if (!name.isEmpty()) {
            final CustomTheme.Preset preset = CustomTheme.preset(this, name);
            final int[] colors = CustomTheme.colors(this);
            final float[] params = CustomTheme.params(this);
            boolean same = preset != null;
            for (int i = 0; same && i < CustomTheme.COUNT; i++) {
                same = preset.colors[i] == colors[i];
            }
            for (int i = 0; same && i < CustomTheme.PARAM_COUNT; i++) {
                same = preset.params[i] == params[i];
            }
            if (!same) {
                CustomTheme.setPresetName(this, "");
            }
        }
        refreshPresets();
    }

    private void refreshCustomColors() {
        final int[] colors = CustomTheme.colors(this);
        for (int i = 0; i < swatches.length; i++) {
            swatches[i].setColor(colors[i]);
        }
        if (customCardImage != null) {
            customCardImage.setImageDrawable(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{
                    0xFF000000 | colors[CustomTheme.BG1],
                    0xFF000000 | colors[CustomTheme.BG2],
            }));
        }
    }

    private void refreshPresets() {
        refreshCustomColors();

        final List<CustomTheme.Preset> presets = CustomTheme.presets(this);
        final String selected = CustomTheme.presetName(this);
        final List<String> items = new ArrayList<>();
        if (selected.isEmpty()) {
            items.add(getString(presets.isEmpty() ? R.string.preset_none : R.string.preset_unsaved));
        }
        for (final CustomTheme.Preset preset : presets) {
            items.add(preset.name);
        }

        final ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        presetSpinner.setAdapter(adapter);
        presetSpinner.setSelection(Math.max(items.indexOf(selected), 0));
        presetDelete.setEnabled(!selected.isEmpty());
    }

    private void showSavePresetDialog() {
        final EditText input = new EditText(this);
        input.setSingleLine();
        input.setHint(R.string.preset_name);
        final String current = CustomTheme.presetName(this);
        input.setText(current.isEmpty()
                ? getString(R.string.preset_default_name, CustomTheme.presets(this).size() + 1)
                : current);
        input.selectAll();

        final int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        final LinearLayout container = new LinearLayout(this);
        container.setPadding(padding, padding / 2, padding, 0);
        container.addView(input, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle(R.string.save_preset_title)
                .setView(container)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    final String name = CustomTheme.normalizeName(input.getText().toString());
                    if (!name.isEmpty()) {
                        CustomTheme.savePreset(this, name);
                        refreshPresets();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showDeletePresetDialog() {
        final String name = CustomTheme.presetName(this);
        if (name.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_preset)
                .setMessage(getString(R.string.delete_preset_message, name))
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    CustomTheme.deletePreset(this, name);
                    refreshPresets();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showCustomizeDialog() {
        final Themes.Entry theme = Themes.get(themeIndex);
        new AlertDialog.Builder(this)
                .setTitle(R.string.customize)
                .setMessage(getString(R.string.customize_message, theme.name))
                .setPositiveButton(R.string.customize_continue, (dialog, which) -> {
                    CustomTheme.setPresetName(this, "");
                    CustomTheme.setParams(this, CustomTheme.themeParams(theme.index), false);
                    CustomTheme.setColors(this, CustomTheme.themeColors(theme.index), true);
                    selectTheme(Themes.CUSTOM, true);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void setAsWallpaper() {
        final Themes.Entry theme = Themes.get(themeIndex);
        final Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, new ComponentName(this, theme.service));
        try {
            startActivityForResult(intent, REQUEST_SET_WALLPAPER);
        } catch (ActivityNotFoundException ex) {
            Log.w(TAG, "no live wallpaper preview activity, opening the chooser: " + ex);
            try {
                startActivityForResult(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER), REQUEST_SET_WALLPAPER);
            } catch (ActivityNotFoundException ex1) {
                Log.e(TAG, "no live wallpaper chooser either: " + ex1);
                Toast.makeText(this, R.string.set_wallpaper_failed, Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SET_WALLPAPER && resultCode == RESULT_OK) {
            Log.i(TAG, "wallpaper was set, closing");
            finish();
        }
    }
}
