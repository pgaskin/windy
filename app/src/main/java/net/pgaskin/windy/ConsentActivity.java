// SPDX-FileCopyrightText: 2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later
package net.pgaskin.windy;

import android.app.Activity;
import android.os.Bundle;

import java.util.concurrent.atomic.AtomicBoolean;

abstract class ConsentActivity extends Activity {
    private static final AtomicBoolean showing = new AtomicBoolean();

    /**
     * Whether a consent activity currently exists (so we don't accidentally
     * stack two over each other).
     *
     * This intentionally only covers activities which actually got created so a
     * failed start (e.g., a blocked background start) doesn't suppress all
     * future dialogs (it's better to just risk showing both in this cases).
     */
    static boolean showing() {
        return showing.get();
    }

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        showing.set(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        showing.set(false);
    }
}
