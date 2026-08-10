// SPDX-FileCopyrightText: 2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later
package net.pgaskin.windy;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class CustomThemeView extends LinearLayout {
    private final ColorSwatchView[] swatches = new ColorSwatchView[CustomTheme.COLOR_COUNT];
    private final Runnable customThemeListener = this::refreshColors;

    private Spinner presetSpinner;
    private View presetDelete;

    public CustomThemeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        inflate(context, R.layout.custom_theme, this); // so the children are always ours

        presetSpinner = findViewById(R.id.preset_spinner);
        presetDelete = findViewById(R.id.preset_delete);

        final LinearLayout swatchList = findViewById(R.id.swatch_list);
        final LayoutInflater inflater = LayoutInflater.from(context);
        for (int i = 0; i < CustomTheme.COLOR_COUNT; i++) {
            final int color = i;
            final View item = inflater.inflate(R.layout.custom_swatch, swatchList, false);
            final String label = getContext().getString(CustomTheme.COLORS[color].label);
            swatches[color] = item.findViewById(R.id.swatch_color);
            ((TextView) item.findViewById(R.id.swatch_label)).setText(label);
            item.setContentDescription(label);
            item.setOnClickListener(v -> pickColor(color, label));
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
                if (!name.isEmpty() && !name.equals(CustomTheme.presetName(getContext()))
                        && CustomTheme.preset(getContext(), name) != null) {
                    CustomTheme.loadPreset(getContext(), name);
                    refresh();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        findViewById(R.id.preset_save).setOnClickListener(v -> showSavePresetDialog());
        presetDelete.setOnClickListener(v -> showDeletePresetDialog());
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        CustomTheme.addListener(customThemeListener); // the colors also change from the dialogs
        refreshColors();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        CustomTheme.removeListener(customThemeListener);
    }

    public void setActive(boolean active) {
        setVisibility(active ? VISIBLE : GONE);
        if (active) {
            refresh();
        }
    }

    public void refresh() {
        refreshColors();
        refreshPresets();
    }

    private void refreshColors() {
        final int[] colors = CustomTheme.colors(getContext());
        for (int i = 0; i < swatches.length; i++) {
            swatches[i].setColor(colors[i]);
        }
    }

    private void refreshPresets() {
        final List<CustomTheme.Preset> presets = CustomTheme.presets(getContext());
        final String selected = CustomTheme.presetName(getContext());
        final List<String> items = new ArrayList<>();
        if (selected.isEmpty()) {
            items.add(getContext().getString(presets.isEmpty() ? R.string.preset_none : R.string.preset_unsaved));
        }
        for (final CustomTheme.Preset preset : presets) {
            items.add(preset.name);
        }

        final ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        presetSpinner.setAdapter(adapter);
        presetSpinner.setSelection(Math.max(items.indexOf(selected), 0));
        presetDelete.setEnabled(!selected.isEmpty());
    }

    private void updatePresetSelection() {
        final String name = CustomTheme.presetName(getContext());
        if (!name.isEmpty()) {
            final CustomTheme.Preset preset = CustomTheme.preset(getContext(), name);
            if (preset == null || !preset.matches(getContext())) {
                CustomTheme.setPresetName(getContext(), "");
            }
        }
        refreshPresets();
    }

    private void pickColor(int color, String label) {
        final int initial = CustomTheme.color(getContext(), color);
        ColorPickerDialog.show(getContext(), label, initial, CustomTheme.COLORS[color].hasAlpha,
                picked -> CustomTheme.setColor(getContext(), color, picked, false), // live preview
                picked -> {
                    CustomTheme.setColor(getContext(), color, picked, true);
                    updatePresetSelection();
                });
    }

    private void showAdvancedDialog() {
        final float[] initial = CustomTheme.params(getContext());
        final SeekBar[] sliders = new SeekBar[CustomTheme.PARAM_COUNT];
        final TextView[] values = new TextView[CustomTheme.PARAM_COUNT];

        final LayoutInflater inflater = LayoutInflater.from(getContext());
        final LinearLayout container = (LinearLayout) inflater.inflate(R.layout.dialog_advanced, null);

        final View title = inflater.inflate(R.layout.dialog_advanced_title, null);
        title.findViewById(R.id.param_restart).setOnClickListener(v -> WindyWallpaperRenderer.restartAll());

        for (int i = 0; i < CustomTheme.PARAM_COUNT; i++) {
            final int param = i;
            final View row = inflater.inflate(R.layout.custom_param, container, false);
            final CustomTheme.ParamSpec spec = CustomTheme.PARAMS[param];
            ((TextView) row.findViewById(R.id.param_label)).setText(spec.label);
            values[param] = row.findViewById(R.id.param_value);
            sliders[param] = row.findViewById(R.id.param_slider);
            sliders[param].setMax(spec.steps);
            sliders[param].setProgress(spec.progress(initial[param]));
            values[param].setText(spec.text(initial[param]));
            sliders[param].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    final float value = spec.value(progress);
                    values[param].setText(spec.text(value));
                    if (fromUser) {
                        CustomTheme.setParam(getContext(), param, value, false); // live preview
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
        final AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setCustomTitle(title)
                .setView(container)
                .setPositiveButton(R.string.save, (d, which) -> {
                    accepted[0] = true;
                    CustomTheme.persist(getContext());
                    updatePresetSelection();
                })
                .setNeutralButton(R.string.reset, null) // set below so it doesn't dismiss it
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(d -> {
                    if (!accepted[0]) {
                        CustomTheme.setParams(getContext(), initial, false); // undo the live preview
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
            CustomTheme.setParams(getContext(), defaults, false);
            for (int i = 0; i < CustomTheme.PARAM_COUNT; i++) {
                sliders[i].setProgress(CustomTheme.PARAMS[i].progress(defaults[i]));
            }
        });
    }

    private void showSavePresetDialog() {
        final EditText input = new EditText(getContext());
        input.setSingleLine();
        input.setHint(R.string.preset_name);
        final String current = CustomTheme.presetName(getContext());
        input.setText(current.isEmpty()
                ? getContext().getString(R.string.preset_default_name, CustomTheme.presets(getContext()).size() + 1)
                : current);
        input.selectAll();

        final int padding = Math.round(20 * getResources().getDisplayMetrics().density);
        final LinearLayout container = new LinearLayout(getContext());
        container.setPadding(padding, padding / 2, padding, 0);
        container.addView(input, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(getContext())
                .setTitle(R.string.save_preset_title)
                .setView(container)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    final String name = CustomTheme.normalizeName(input.getText().toString());
                    if (!name.isEmpty()) {
                        CustomTheme.savePreset(getContext(), name);
                        refreshPresets();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showDeletePresetDialog() {
        final String name = CustomTheme.presetName(getContext());
        if (name.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(getContext())
                .setTitle(R.string.delete_preset)
                .setMessage(getContext().getString(R.string.delete_preset_message, name))
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    CustomTheme.deletePreset(getContext(), name);
                    refreshPresets();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
