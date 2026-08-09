// SPDX-License-Identifier: CC0-1.0
// This file is completely AI-generated (unlike the other code, which I almost entirely wrote).
package net.pgaskin.windy;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/** A rounded color chip, showing a checkerboard through translucent colors. */
public class ColorSwatchView extends View {
    private static final int CHECKER_DP = 5;
    private static final int CHECKER_LIGHT = 0xFFCFCFCF;
    private static final int CHECKER_DARK = 0xFF9A9A9A;
    private static final int BORDER = 0x66808080;

    private final Paint checkerPaint = new Paint();
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final float radius;

    private int color = Color.TRANSPARENT;

    public ColorSwatchView(Context context) {
        this(context, null);
    }

    public ColorSwatchView(Context context, AttributeSet attrs) {
        super(context, attrs);
        final float density = getResources().getDisplayMetrics().density;
        radius = 6 * density;
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(density);
        borderPaint.setColor(BORDER);
        checkerPaint.setShader(new BitmapShader(checkerBitmap((int) (CHECKER_DP * density)), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
    }

    public void setColor(int color) {
        if (this.color != color) {
            this.color = color;
            invalidate();
        }
    }

    public int getColor() {
        return color;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float inset = borderPaint.getStrokeWidth() / 2;
        bounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
        if (Color.alpha(color) != 0xFF) {
            canvas.drawRoundRect(bounds, radius, radius, checkerPaint);
        }
        fillPaint.setColor(color);
        canvas.drawRoundRect(bounds, radius, radius, fillPaint);
        canvas.drawRoundRect(bounds, radius, radius, borderPaint);
    }

    static Bitmap checkerBitmap(int cell) {
        cell = Math.max(cell, 2);
        final Bitmap bitmap = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        final Paint paint = new Paint();
        canvas.drawColor(CHECKER_LIGHT);
        paint.setColor(CHECKER_DARK);
        canvas.drawRect(0, 0, cell, cell, paint);
        canvas.drawRect(cell, cell, cell * 2, cell * 2, paint);
        return bitmap;
    }
}
