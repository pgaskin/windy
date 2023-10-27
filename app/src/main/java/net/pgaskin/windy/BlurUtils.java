package net.pgaskin.windy;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.math.MathUtils;
import java.io.ByteArrayOutputStream;

public class BlurUtils {
    private static final int RADIUS = 2;
    private static final float[] KERNEL = {0.06136f, 0.24477f, 0.38774f, 0.24477f, 0.06136f}; // RADIUS*2 + 1

    public static Pixmap blurAndDownscale(FileHandle fh) {
        Bitmap bm = BitmapFactory.decodeFile(fh.file().getAbsolutePath());
        Bitmap bm1 = Bitmap.createScaledBitmap(bm, bm.getWidth() / 4, bm.getHeight() / 4, true);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bm1.compress(Bitmap.CompressFormat.PNG, 100, stream);
        byte[] byteArray = stream.toByteArray();
        return blurPixmap(new Pixmap(byteArray, 0, byteArray.length));
    }

    public static Pixmap blurPixmap(Pixmap pixmap) {
        int width = pixmap.getWidth();
        int height = pixmap.getHeight();
        if (pixmap.getFormat() != Pixmap.Format.RGBA8888) {
            Pixmap tmp = new Pixmap(width, height, Pixmap.Format.RGBA8888);
            tmp.drawPixmap(pixmap, 0, 0);
            pixmap.dispose();
            pixmap = tmp;
        }

        Pixmap out = new Pixmap(width, height, Pixmap.Format.RGBA8888);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                blurPixel(pixmap, out, x, y, width, height, false);
            }
        }
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                blurPixel(pixmap, out, x, y, width, height, true);
            }
        }
        pixmap.dispose();
        return out;
    }

    private static void blurPixel(Pixmap in, Pixmap out, int x, int y, int width, int height, boolean vertical) {
        float r = 0, g = 0, b = 0, a = 0;
        int center = vertical ? y : x;
        for (int ki = 0, i = center - RADIUS; i <= center + RADIUS; i++, ki++) {
            int px = vertical ? x : MathUtils.clamp(i, 0, width);
            int py = vertical ? MathUtils.clamp(i, 0, height) : y;
            int pc = in.getPixel(px, py);
            float kv = KERNEL[ki];
            r += (((pc & 0xff000000) >>> 24) / 255f) * kv;
            g += (((pc & 0x00ff0000) >>> 16) / 255f) * kv;
            b += (((pc & 0x0000ff00) >>> 8) / 255f) * kv;
            a += (((pc & 0x000000ff)) / 255f) * kv;
        }
        out.drawPixel(x, y, ((int)(r * 255) << 24) | ((int)(g * 255) << 16) | ((int)(b * 255) << 8) | (int)(a * 255));
    }
}