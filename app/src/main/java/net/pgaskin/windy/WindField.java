package net.pgaskin.windy;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.GdxRuntimeException;

import java.io.ByteArrayOutputStream;
import java.io.File;
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
     *         <li>Gaussian blur horizontal/vertical with kernel size 5 and sigma 1.
     *         <li>Downscale to 1/4 of the size (bilinear).
     *         <li>This all has the effect of smoothing out sharper turns and removing very fine details from the wallpaper (TODO: it might be nice to have an option to keep this detail, since it looks interesting in itself)
     *     </ul>
     * </ul>
     */
    public static void updateCache(Context context, InputStream srcStream) throws Exception {
        Log.i(TAG, "updating cached field pixmap");
        Bitmap srcBitmap = BitmapFactory.decodeStream(srcStream);
        if (srcBitmap == null) {
            throw new Exception("Failed to decode input bitmap");
        }
        Bitmap sclBitmap = Bitmap.createScaledBitmap(srcBitmap, srcBitmap.getWidth() / 4, srcBitmap.getHeight() / 4, true);
        ByteArrayOutputStream sclStream = new ByteArrayOutputStream();
        if (!sclBitmap.compress(Bitmap.CompressFormat.PNG, 100, sclStream)) {
            throw new Exception("Failed to encode scaled bitmap");
        }
        byte[] sclBytes = sclStream.toByteArray();
        Pixmap dstPixmap = BlurUtils.blurPixmap(new Pixmap(sclBytes, 0, sclBytes.length)); // TODO: replace this with something better
        PixmapIO.writePNG(Gdx.files.absolute(windCacheFile(context, true).getAbsolutePath()), dstPixmap);
        Files.move(windCacheFile(context, true).toPath(), windCacheFile(context, false).toPath(), StandardCopyOption.ATOMIC_MOVE);
        synchronized (currentPixmapLock) {
            if (currentPixmap != null) {
                currentPixmap.dispose();
            }
            currentPixmap = dstPixmap;
            currentSeq.addAndGet(1);
        }
        System.gc();
    }

    private static File windCacheFile(Context context, boolean temp) {
        return new File(context.createDeviceProtectedStorageContext().getFilesDir(), "wind_cache.png" + (temp ? ".tmp" : ""));
    }
}
