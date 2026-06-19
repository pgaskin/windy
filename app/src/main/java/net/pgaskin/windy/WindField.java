// SPDX-FileCopyrightText: 2023-2026 Patrick Gaskin
// SPDX-License-Identifier: AGPL-3.0-or-later
package net.pgaskin.windy;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicInteger;

public class WindField {
    private static final String TAG = "WindField";
    private static final AtomicInteger currentSeq = new AtomicInteger();
    private static final Object currentBitmapLock = new Object();
    private static Bitmap currentBitmap;

    public static final class Snapshot {
        public final byte[] rgba; // row-major rgba8888
        public final int width;
        public final int height;
        public final int seq;

        Snapshot(byte[] rgba, int width, int height, int seq) {
            this.rgba = rgba;
            this.width = width;
            this.height = height;
            this.seq = seq;
        }
    }

    public static int currentSeq() {
        return currentSeq.get();
    }

    public static Snapshot snapshot(Context context) {
        synchronized (currentBitmapLock) {
            if (currentBitmap == null) {
                Log.i(TAG, "loading initial wind field bitmap");
                try (final InputStream is = Files.newInputStream(windCacheFile(context, false).toPath())) {
                    currentBitmap = BitmapFactory.decodeStream(is);
                } catch (Exception ex) {
                    // ignored; fall back to the embedded asset
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
            return toSnapshot(currentBitmap, currentSeq.get());
        }
    }

    private static Snapshot toSnapshot(Bitmap bitmap, int seq) {
        final Bitmap rgbaBitmap = bitmap.getConfig() == Bitmap.Config.ARGB_8888
                ? bitmap
                : bitmap.copy(Bitmap.Config.ARGB_8888, false);
        final int width = rgbaBitmap.getWidth();
        final int height = rgbaBitmap.getHeight();
        final byte[] rgba = new byte[width * height * 4];
        rgbaBitmap.copyPixelsToBuffer(ByteBuffer.wrap(rgba));
        return new Snapshot(rgba, width, height, seq);
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
