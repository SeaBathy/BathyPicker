package com.seabathy.bathypicker;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The GeoTIFF being written, in Download/BathyPicker. On Android 10 and newer it goes through
 * MediaStore and stays hidden from other apps until it's finished. On older phones it's written
 * as name.part and renamed at the end.
 */
final class Output {
    final String name;
    FileChannel write, read;
    private final Context ctx;
    private Uri uri;
    private ParcelFileDescriptor pfd;
    private RandomAccessFile raf;
    private File part, done;

    private Output(Context ctx, String name) {
        this.ctx = ctx;
        this.name = name;
    }

    static Output create(Context ctx, String name) throws IOException {
        Output o = new Output(ctx, name);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = ctx.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "image/tiff");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + Engine.FOLDER);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            o.uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (o.uri == null) throw new IOException("Couldn't create the file in Downloads.");
            o.pfd = cr.openFileDescriptor(o.uri, "rw");
            if (o.pfd == null) throw new IOException("Couldn't open the file in Downloads.");
            o.write = new FileOutputStream(o.pfd.getFileDescriptor()).getChannel();
            o.read = new FileInputStream(o.pfd.getFileDescriptor()).getChannel();
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), Engine.FOLDER);
            if (!dir.isDirectory() && !dir.mkdirs())
                throw new IOException("Couldn't create " + dir + ". Allow BathyPicker to use storage in the phone's settings.");
            o.done = new File(dir, name);
            o.part = new File(dir, name + ".part");
            o.raf = new RandomAccessFile(o.part, "rw");
            o.raf.setLength(0);
            o.write = o.raf.getChannel();
            o.read = o.write;
        }
        return o;
    }

    /** Make the finished file visible. Returns a content:// address other apps can be given. */
    Uri publish() throws IOException {
        close();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            ctx.getContentResolver().update(uri, v, null, null);
            return uri;
        }
        if (!part.renameTo(done)) throw new IOException("Couldn't rename the finished file.");
        final Uri[] found = new Uri[1];
        CountDownLatch latch = new CountDownLatch(1);
        MediaScannerConnection.scanFile(ctx, new String[]{done.getPath()}, new String[]{"image/tiff"}, (p, u) -> {
            found[0] = u;
            latch.countDown();
        });
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return found[0];
    }

    /** Remove an unfinished file. */
    void discard() {
        close();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                if (uri != null) ctx.getContentResolver().delete(uri, null, null);
            } else if (part != null) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            }
        } catch (RuntimeException ignored) {
            // nothing more to do
        }
    }

    private void close() {
        try {
            if (pfd != null) pfd.close();
        } catch (IOException ignored) {
            // already closed
        }
        try {
            if (raf != null) raf.close();
        } catch (IOException ignored) {
            // already closed
        }
        pfd = null;
        raf = null;
    }
}
