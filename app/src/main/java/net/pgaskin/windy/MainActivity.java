// SPDX-FileCopyrightText: 2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later
package net.pgaskin.windy;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toolbar;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";

    private static final int REQUEST_SET_WALLPAPER = 1;

    private WindyWallpaperView preview;
    private HorizontalScrollView themeScroll;
    private LinearLayout themeList;
    private int themeIndex;

    private MenuItem customizeItem;
    private CustomThemeView customTheme;
    private ImageView customCardImage;
    private final Runnable customThemeListener = this::refreshCustomCard;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity);

        preview = findViewById(R.id.preview);
        themeScroll = findViewById(R.id.theme_scroll);
        themeList = findViewById(R.id.theme_list);
        customTheme = findViewById(R.id.custom_theme);

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

        themeIndex = initialThemeIndex();
        buildThemeCards();
        selectTheme(themeIndex, false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        CustomTheme.addListener(customThemeListener);
        refreshCustomCard();
    }

    @Override
    protected void onStop() {
        super.onStop();
        CustomTheme.removeListener(customThemeListener);
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
            LocationConsentActivity.request(this);
            Location.update(this);
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
            customTheme.setPadding(bars.left, customTheme.getPaddingTop(), bars.right, 0);
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
        customTheme.setActive(custom, animate);
        customizeItem.setVisible(!custom); // it copies the shown theme into the custom one
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

    private void refreshCustomCard() {
        if (customCardImage == null) {
            return;
        }
        final int[] colors = CustomTheme.colors(this);
        customCardImage.setImageDrawable(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{
                0xFF000000 | colors[CustomTheme.COLOR_BG1],
                0xFF000000 | colors[CustomTheme.COLOR_BG2],
        }));
    }

    private void showCustomizeDialog() {
        final Themes.Entry theme = Themes.get(themeIndex);
        new AlertDialog.Builder(this)
                .setTitle(R.string.customize)
                .setMessage(getString(R.string.customize_message, theme.name))
                .setPositiveButton(R.string.customize_continue, (dialog, which) -> {
                    CustomTheme.setPresetName(this, "");
                    CustomTheme.set(this, CustomTheme.themeColors(theme.index), CustomTheme.themeParams(theme.index), true);
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
