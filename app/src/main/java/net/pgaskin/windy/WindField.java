package net.pgaskin.windy;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.util.Log;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;

import java.io.File;
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

        Files.copy(src, windCacheFile(context, true).toPath(), StandardCopyOption.REPLACE_EXISTING);

        final Bitmap img = BitmapFactory.decodeFile(windCacheFile(context, true).toString());
        if (img == null) {
            throw new Exception("Failed to decode input bitmap");
        }
        synchronized (currentBitmapLock) {
            if (currentBitmap != null) {
                currentBitmap.recycle();
            }
            currentBitmap = img;
            currentSeq.addAndGet(1);
        }

        Files.move(windCacheFile(context, true).toPath(), windCacheFile(context, false).toPath(), StandardCopyOption.ATOMIC_MOVE);
    }
}
