package net.pgaskin.windy;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.utils.GdxRuntimeException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicInteger;

public class WindField {
    private static final String TAG = "WindField";
    private static final AtomicInteger currentSeq = new AtomicInteger();
    private static final Object currentPixmapLock = new Object();
    private static Pixmap currentPixmap;

    public static int currentSeq() {
        return currentSeq.get();
    }

    public static Texture createTexture(Context context) {
        synchronized (currentPixmapLock) {
            if (currentPixmap == null) {
                Log.i(TAG, "loading initial wind field pixmap");
                try {
                    currentPixmap = new Pixmap(Gdx.files.absolute(windCacheFile(context, false).getAbsolutePath()));
                } catch (GdxRuntimeException e) {
                    currentPixmap = new Pixmap(Gdx.files.internal("windy/wind_cache.jpg"));
                }
            }
            return new Texture(currentPixmap);
        }
    }

    private static File windCacheFile(Context context, boolean temp) {
        return new File(context.createDeviceProtectedStorageContext().getFilesDir(), "wind_cache.png" + (temp ? ".tmp" : ""));
    }

    /**
     * Processes and caches a wind image for later use.
     * <ul>
     *     <li>Input image: <ul>
     *         <li>Equirectangular projection (x: -180 to 180, y: 90 to -90).
     *         <li>Any scale works, but note that the original version was designed for 1440x721 (i.e., .25 lng-lat grid).
     *         <li>Any image format works, but the original app took a JPEG image.
     *         <li>The original app's image came from <a href="https://www.gstatic.com/pixel/livewallpaper/windy/gfs_wind_500.jpg">www.gstatic.com/pixel/livewallpaper/windy/gfs_wind_500.jpg</a> (note: gfs_wind_1000 is an identical image), which was last updated 12 Jun 2019 09:47:19 GMT.
     *         <li>R/G color component is wind direction (unit vector northing/easting components -> 0-1 where 0.5 is zero, and below is -1). If you look at the levels, it should normally be mostly flat, with a spike at the beginning and end.
     *         <li>B color component is wind speed magnitude (0-1 where 0 is zero and 1 is some arbitrary maximum, probably around 30-40 m/s). If you look at the levels, it should normally look like a normal distribution on the lower part.
     *     </ul>
     *     <li>Processing: <ul>
     *         <li>Downscale to 1/4 of the size (bilinear).
     *         <li>Gaussian blur horizontal/vertical with kernel size 5 and sigma 1.
     *         <li>This all has the effect of smoothing out sharper turns and removing very fine details from the wallpaper (TODO: it might be nice to have an option to keep this detail, since it looks interesting in itself)
     *     </ul>
     * </ul>
     */
    public static void updateCache(Context context, InputStream src) throws Exception {
        Log.i(TAG, "updating cached field pixmap");

        BitmapFactory.Options cfg = new BitmapFactory.Options();
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

        try (FileOutputStream tmp = new FileOutputStream(windCacheFile(context, true))) {
            if (!img.compress(Bitmap.CompressFormat.PNG, 100, tmp)) {
                throw new Exception("Failed to encode scaled bitmap");
            }
        }
        Files.move(windCacheFile(context, true).toPath(), windCacheFile(context, false).toPath(), StandardCopyOption.ATOMIC_MOVE);

        Pixmap pixmap = new Pixmap(Gdx.files.absolute(windCacheFile(context, false).getAbsolutePath()));
        synchronized (currentPixmapLock) {
            if (currentPixmap != null) {
                currentPixmap.dispose();
            }
            currentPixmap = pixmap;
            currentSeq.addAndGet(1);
        }
        System.gc();
    }

    private static Bitmap blur(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] a = new int[w*h];
        int[] b = new int[w*h];
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
        final int RADIUS = 2;
        final float[] KERNEL = {0.06136f, 0.24477f, 0.38774f, 0.24477f, 0.06136f};
        float a = 0, r = 0, g = 0, b = 0;
        int center = vertical ? y : x;
        for (int ki = 0, i = center - RADIUS; i <= center + RADIUS; i++, ki++) {
            int px = vertical ? x : MathUtils.clamp(i, 0, w-1);
            int py = vertical ? MathUtils.clamp(i, 0, h-1) : y;
            int pc = in[py*w + px];
            float kv = KERNEL[ki];
            a += (((pc & 0xff000000) >>> 24) / 255f) * kv;
            r += (((pc & 0x00ff0000) >>> 16) / 255f) * kv;
            g += (((pc & 0x0000ff00) >>> 8) / 255f) * kv;
            b += (((pc & 0x000000ff)) / 255f) * kv;
        }
        out[y*w + x] = ((int)(a * 255) << 24) | ((int)(r * 255) << 16) | ((int)(g * 255) << 8) | (int)(b * 255);
    }
}
