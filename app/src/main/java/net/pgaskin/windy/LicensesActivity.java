// SPDX-FileCopyrightText: 2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later
package net.pgaskin.windy;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toolbar;

public class LicensesActivity extends Activity {
    private static final String ASSET_URL = "file:///android_asset/windy/licenses.html";

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.licenses_activity);

        final Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        webView = findViewById(R.id.licenses);
        findViewById(R.id.root).setOnApplyWindowInsetsListener((v, insets) -> {
            final Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            toolbar.setPadding(bars.left, bars.top, bars.right, 0);
            webView.setPadding(bars.left, 0, bars.right, bars.bottom);
            return insets;
        });

        final WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            settings.setAlgorithmicDarkeningAllowed(true);
        } else if (isNightMode()) {
            settings.setForceDark(WebSettings.FORCE_DARK_ON);
        }
        webView.setBackgroundColor(getColor(android.R.color.transparent));
        webView.loadUrl(ASSET_URL);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        webView.destroy();
    }

    private boolean isNightMode() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }
}
