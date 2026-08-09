// SPDX-License-Identifier: CC0-1.0
// This file is completely AI-generated (unlike the other code, which I almost entirely wrote).
package net.pgaskin.windy;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.util.Locale;

/**
 * A saturation/value square with hue and (optionally) alpha sliders, plus a hex
 * field. Changes are reported live so the wallpaper can follow along.
 */
public class ColorPickerView extends LinearLayout {
    public interface OnColorChangedListener {
        void onColorChanged(int color);
    }

    private static final int SQUARE_HEIGHT_DP = 160;
    private static final int SLIDER_HEIGHT_DP = 28;
    private static final int GAP_DP = 12;

    private final float density;
    private final SatValView satVal;
    private final SliderView hue;
    private final SliderView alphaSlider;
    private final ColorSwatchView preview;
    private final EditText hex;

    private final float[] hsv = {0.0f, 0.0f, 1.0f};
    private int alpha = 0xFF;
    private boolean alphaEnabled = true;
    private boolean updating; // avoid feeding our own updates back through the hex field

    private OnColorChangedListener listener;

    public ColorPickerView(Context context) {
        this(context, null);
    }

    public ColorPickerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);

        satVal = new SatValView(context);
        addView(satVal, new LayoutParams(LayoutParams.MATCH_PARENT, dp(SQUARE_HEIGHT_DP)));

        hue = new SliderView(context, false);
        addView(hue, sliderParams());

        alphaSlider = new SliderView(context, true);
        addView(alphaSlider, sliderParams());

        final LinearLayout bottom = new LinearLayout(context);
        bottom.setOrientation(HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);

        preview = new ColorSwatchView(context);
        final LayoutParams previewParams = new LayoutParams(dp(44), dp(28));
        previewParams.rightMargin = dp(GAP_DP);
        bottom.addView(preview, previewParams);

        hex = new EditText(context);
        hex.setSingleLine();
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hex.setFilters(new InputFilter[]{new InputFilter.LengthFilter(9), new InputFilter.AllCaps()});
        hex.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!updating) {
                    parseHex(s.toString());
                }
            }
        });
        bottom.addView(hex, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        final LayoutParams bottomParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        bottomParams.topMargin = dp(GAP_DP / 2);
        addView(bottom, bottomParams);
    }

    private LayoutParams sliderParams() {
        final LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, dp(SLIDER_HEIGHT_DP));
        params.topMargin = dp(GAP_DP);
        return params;
    }

    public void setOnColorChangedListener(OnColorChangedListener listener) {
        this.listener = listener;
    }

    /** Whether the color has a meaningful alpha channel. */
    public void setAlphaEnabled(boolean enabled) {
        alphaEnabled = enabled;
        alphaSlider.setVisibility(enabled ? VISIBLE : GONE);
        if (!enabled) {
            alpha = 0xFF;
        }
        updateViews(false);
    }

    public void setColor(int color) {
        alpha = alphaEnabled ? Color.alpha(color) : 0xFF;
        Color.colorToHSV(color, hsv);
        updateViews(false);
    }

    public int getColor() {
        return Color.HSVToColor(alpha, hsv);
    }

    private void parseHex(String text) {
        String value = text.trim();
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        if (value.length() != 6 && value.length() != 8) {
            return;
        }
        final long parsed;
        try {
            parsed = Long.parseLong(value, 16);
        } catch (NumberFormatException ex) {
            return;
        }
        final int color = value.length() == 6 ? (int) (parsed | 0xFF000000L) : (int) parsed;
        alpha = alphaEnabled ? Color.alpha(color) : 0xFF;
        Color.colorToHSV(color, hsv);
        updateViews(true);
    }

    /** Refreshes everything from the current color, optionally keeping the hex field as typed. */
    private void updateViews(boolean fromHex) {
        final int color = getColor();
        updating = true;
        if (!fromHex) {
            hex.setText(alphaEnabled
                    ? String.format(Locale.ROOT, "#%08X", color)
                    : String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF));
        }
        updating = false;
        preview.setColor(color);
        satVal.invalidate();
        hue.invalidate();
        alphaSlider.invalidate();
        if (listener != null) {
            listener.onColorChanged(color);
        }
    }

    private int dp(int value) {
        return Math.round(value * density);
    }

    /** Saturation (x) and value (y) for the current hue. */
    private final class SatValView extends View {
        private static final int BITMAP_SIZE = 256;

        private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();
        private final Path clip = new Path();

        private Bitmap bitmap;
        private float bitmapHue = -1.0f;

        SatValView(Context context) {
            super(context);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(2 * density);
            ringPaint.setColor(Color.WHITE);
            ringShadowPaint.setStyle(Paint.Style.STROKE);
            ringShadowPaint.setStrokeWidth(density);
            ringShadowPaint.setColor(0x80000000);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (bitmap == null || bitmapHue != hsv[0]) {
                render();
            }
            final float radius = 6 * density;
            bounds.set(0, 0, getWidth(), getHeight());
            clip.reset();
            clip.addRoundRect(bounds, radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);
            canvas.drawBitmap(bitmap, null, bounds, bitmapPaint);
            canvas.restore();

            final float x = hsv[1] * getWidth();
            final float y = (1.0f - hsv[2]) * getHeight();
            final float ring = 7 * density;
            canvas.drawCircle(x, y, ring, ringPaint);
            canvas.drawCircle(x, y, ring + 1.5f * density, ringShadowPaint);
        }

        /** Renders the square for the current hue (into a software bitmap). */
        private void render() {
            if (bitmap == null) {
                bitmap = Bitmap.createBitmap(BITMAP_SIZE, BITMAP_SIZE, Bitmap.Config.ARGB_8888);
            }
            final Paint paint = new Paint();
            paint.setShader(new ComposeShader(
                    new LinearGradient(0, 0, BITMAP_SIZE, 0, Color.WHITE, Color.HSVToColor(new float[]{hsv[0], 1.0f, 1.0f}), Shader.TileMode.CLAMP),
                    new LinearGradient(0, 0, 0, BITMAP_SIZE, Color.WHITE, Color.BLACK, Shader.TileMode.CLAMP),
                    PorterDuff.Mode.MULTIPLY));
            new Canvas(bitmap).drawRect(0, 0, BITMAP_SIZE, BITMAP_SIZE, paint);
            bitmapHue = hsv[0];
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    hsv[1] = clamp(event.getX() / Math.max(getWidth(), 1));
                    hsv[2] = 1.0f - clamp(event.getY() / Math.max(getHeight(), 1));
                    updateViews(false);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
            }
            return super.onTouchEvent(event);
        }
    }

    /** A hue or alpha bar. */
    private final class SliderView extends View {
        private final boolean isAlpha;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint checkerPaint = new Paint();
        private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();

        private Shader gradient;
        private int gradientWidth;
        private int gradientColor;

        SliderView(Context context, boolean isAlpha) {
            super(context);
            this.isAlpha = isAlpha;
            checkerPaint.setShader(new BitmapShader(ColorSwatchView.checkerBitmap((int) (5 * density)), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
            thumbPaint.setStyle(Paint.Style.STROKE);
            thumbPaint.setStrokeWidth(2 * density);
            thumbPaint.setColor(Color.WHITE);
            thumbBorderPaint.setStyle(Paint.Style.STROKE);
            thumbBorderPaint.setStrokeWidth(density);
            thumbBorderPaint.setColor(0x80000000);
        }

        /** The bar itself, inset so the thumb stays inside the view. */
        private void updateBounds() {
            final float inset = 8 * density;
            bounds.set(inset, 2 * density, Math.max(getWidth() - inset, inset + 1), getHeight() - 2 * density);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float radius = getHeight() / 2.0f - 2 * density;
            updateBounds();

            final int opaque = Color.HSVToColor(hsv);
            if (gradient == null || gradientWidth != getWidth() || (isAlpha && gradientColor != opaque)) {
                gradient = isAlpha
                        ? new LinearGradient(bounds.left, 0, bounds.right, 0, opaque & 0xFFFFFF, opaque, Shader.TileMode.CLAMP)
                        : new LinearGradient(bounds.left, 0, bounds.right, 0, hueColors(), null, Shader.TileMode.CLAMP);
                gradientWidth = getWidth();
                gradientColor = opaque;
            }
            if (isAlpha) {
                canvas.drawRoundRect(bounds, radius, radius, checkerPaint);
            }
            paint.setShader(gradient);
            canvas.drawRoundRect(bounds, radius, radius, paint);
            paint.setShader(null);

            final float fraction = isAlpha ? alpha / 255.0f : hsv[0] / 360.0f;
            final float x = bounds.left + fraction * bounds.width();
            final float y = getHeight() / 2.0f;
            final float thumb = getHeight() / 2.0f - 3 * density;
            canvas.drawCircle(x, y, thumb, thumbPaint);
            canvas.drawCircle(x, y, thumb + 1.5f * density, thumbBorderPaint);
        }

        private int[] hueColors() {
            final int[] colors = new int[7];
            for (int i = 0; i < colors.length; i++) {
                colors[i] = Color.HSVToColor(new float[]{i * 360.0f / (colors.length - 1), 1.0f, 1.0f});
            }
            return colors;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    updateBounds();
                    final float fraction = clamp((event.getX() - bounds.left) / Math.max(bounds.width(), 1));
                    if (isAlpha) {
                        alpha = Math.round(fraction * 255);
                    } else {
                        hsv[0] = Math.min(fraction * 360.0f, 359.99f);
                    }
                    updateViews(false);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
            }
            return super.onTouchEvent(event);
        }
    }

    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(value, 1.0f));
    }
}
