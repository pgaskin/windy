// SPDX-FileCopyrightText: 2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later
package net.pgaskin.windy;

import android.app.Activity;
import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
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

    private WindyWallpaperView preview;
    private HorizontalScrollView themeScroll;
    private LinearLayout themeList;
    private int themeIndex;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity);

        preview = findViewById(R.id.preview);
        themeScroll = findViewById(R.id.theme_scroll);
        themeList = findViewById(R.id.theme_list);

        final Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.inflateMenu(R.menu.main);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_settings) {
                startActivity(new Intent(this, SettingsActivity.class));
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
    protected void onResume() {
        super.onResume();
        preview.setPaused(false);
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
        return Themes.get(Prefs.themeIndex(this)).index;
    }

    private void buildThemeCards() {
        final LayoutInflater inflater = getLayoutInflater();
        for (Themes.Entry theme : Themes.ALL) {
            final View card = inflater.inflate(R.layout.theme_card, themeList, false);
            final ImageView image = card.findViewById(R.id.theme_image);
            image.setImageResource(theme.thumbnail);
            image.setClipToOutline(true);
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

    private void setAsWallpaper() {
        final Themes.Entry theme = Themes.get(themeIndex);
        final Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, new ComponentName(this, theme.service));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException ex) {
            Log.w(TAG, "no live wallpaper preview activity, opening the chooser: " + ex);
            try {
                startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER));
            } catch (ActivityNotFoundException ex1) {
                Log.e(TAG, "no live wallpaper chooser either: " + ex1);
                Toast.makeText(this, R.string.set_wallpaper_failed, Toast.LENGTH_LONG).show();
            }
        }
    }
}
