// SPDX-License-Identifier: CC0-1.0
// This file is completely AI-generated (unlike the other code, which I almost entirely wrote).
package net.pgaskin.windy;

import android.app.AlertDialog;
import android.content.Context;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.function.IntConsumer;

/** A {@link ColorPickerView} in a dialog, previewing changes as they are made. */
public final class ColorPickerDialog {
    private ColorPickerDialog() {
    }

    /**
     * Shows the picker, calling preview for every change (including when the
     * original color is put back on cancel), and commit if it is accepted.
     */
    public static void show(Context context, CharSequence title, int initial, boolean alphaEnabled, IntConsumer preview, IntConsumer commit) {
        final ColorPickerView picker = new ColorPickerView(context);
        picker.setAlphaEnabled(alphaEnabled);
        picker.setColor(initial);
        picker.setOnColorChangedListener(preview::accept);

        final int padding = Math.round(20 * context.getResources().getDisplayMetrics().density);
        final FrameLayout container = new FrameLayout(context);
        container.setPadding(padding, padding / 2, padding, 0);
        container.addView(picker, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        final boolean[] accepted = {false};
        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(title)
                .setView(container)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    accepted[0] = true;
                    commit.accept(picker.getColor());
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(d -> {
                    if (!accepted[0]) {
                        preview.accept(initial); // undo the live preview
                    }
                })
                .create();

        final Window window = dialog.getWindow();
        if (window != null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); // don't dim the wallpaper being previewed
        }
        dialog.show();
    }
}
