package net.pgaskin.windy;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.util.Log;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.math.MathUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicInteger;

public class WindField {
    private static final String TAG = "WindField";
    private static final AtomicInteger currentSeq = new AtomicInteger();
    private static final Object currentBitmapLock = new Object();
    private static Bitmap currentBitmap;

    public static int currentSeq() {
        return currentSeq.get();
    }

    public static Texture createTexture(Context context) {
        synchronized (currentBitmapLock) {
            if (currentBitmap == null) {
                Log.i(TAG, "loading initial wind field bitmap");
                try (final InputStream is = Files.newInputStream(windCacheFile(context, false).toPath())) {
                    if ((currentBitmap = BitmapFactory.decodeStream(is)) == null) {
                        throw new Exception("Failed to decode cached wind field bitmap");
                    }
                } catch (Exception ex) {
                    // ignored
                }
                if (currentBitmap == null) {
                    try (final InputStream is = context.getAssets().open("windy/wind_cache.png")) {
                        if ((currentBitmap = BitmapFactory.decodeStream(is)) == null) {
                            throw new Exception("Failed to decode embedded wind field bitmap");
                        }
                    } catch (Exception ex1) {
                        throw new RuntimeException(ex1);
                    }
                }
            }
            final Texture tex = new Texture(currentBitmap.getWidth(), currentBitmap.getHeight(), Pixmap.Format.RGBA8888);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex.getTextureObjectHandle());
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, currentBitmap, 0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);
            return tex;
        }
    }

    private static File windCacheFile(Context context, boolean temp) {
        return new File(context.createDeviceProtectedStorageContext().getFilesDir(), "wind_cache.png" + (temp ? ".tmp" : ""));
    }

    public static void updateCache(Context context, InputStream src) throws Exception {
        Log.i(TAG, "updating cached field pixmap");

        final BitmapFactory.Options cfg = new BitmapFactory.Options();
        cfg.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap img = BitmapFactory.decodeStream(src, null, cfg);
        if (img == null) {
            throw new Exception("Failed to decode input bitmap");
        }
        if (img.getConfig() != Bitmap.Config.ARGB_8888) {
            throw new Exception("Input bitmap was not decoded as ARGB8888");
        }

        img = Bitmap.createScaledBitmap(img, img.getWidth() / 4, img.getHeight() / 4, true);
        img = blur(img);

        try (final FileOutputStream tmp = new FileOutputStream(windCacheFile(context, true))) {
            if (!img.compress(Bitmap.CompressFormat.PNG, 100, tmp)) {
                throw new Exception("Failed to encode scaled bitmap");
            }
        }
        Files.move(windCacheFile(context, true).toPath(), windCacheFile(context, false).toPath(), StandardCopyOption.ATOMIC_MOVE);

        synchronized (currentBitmapLock) {
            if (currentBitmap != null) {
                currentBitmap.recycle();
            }
            currentBitmap = img;
            currentSeq.addAndGet(1);
        }
    }

    private static Bitmap blur(Bitmap src) {
        final int w = src.getWidth();
        final int h = src.getHeight();
        final int[] a = new int[w*h];
        final int[] b = new int[w*h];
        src.getPixels(a, 0, w, 0, 0, w, h); // ARGB8888
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                blurKernel(a, b, x, y, w, h, false);
            }
        }
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                blurKernel(a, b, x, y, w, h, true);
            }
        }
        if (!src.isMutable()) {
            src = Bitmap.createBitmap(w, h, src.getConfig());
        }
        src.setPixels(b, 0, w, 0, 0, w, h);
        return src;
    }

    private static void blurKernel(int[] in, int[] out, int x, int y, int w, int h, boolean vertical) {
        final int[] KERNEL = {
                // gaussian blur kernel (radius 2)
                // note: 65536 = 2^16
                // note: sum is 65534/65536, so it's close enough (it must not be more, though, or pixels will overflow)
                (int)(65536f * 0.06136f),
                (int)(65536f * 0.24477f),
                (int)(65536f * 0.38774f),
                (int)(65536f * 0.24477f),
                (int)(65536f * 0.06136f),
        };
        int r = 0, g = 0, b = 0;
        for (int ki = 0, i = (vertical?y:x) - (KERNEL.length-1)/2; ki < KERNEL.length; i++, ki++) {
            int px = vertical ? x : MathUtils.clamp(i, 0, w-1);
            int py = vertical ? MathUtils.clamp(i, 0, h-1) : y;
            int pc = in[py*w + px];
            int kv = KERNEL[ki];
            r += kv * (0xFF & (pc >>> 16));
            g += kv * (0xFF & (pc >>> 8));
            b += kv * (0xFF & (pc));
        }
        int pc = 0;
        pc |= 0xFF << 24;
        pc |= ((r >>> 16) << 16);
        pc |= ((g >>> 16) << 8);
        pc |= ((b >>> 16));
        out[y*w + x] = pc;
    }
}
