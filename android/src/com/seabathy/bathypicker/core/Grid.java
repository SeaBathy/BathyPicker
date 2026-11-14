package com.seabathy.bathypicker.core;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.channels.FileChannel;

/**
 * A float raster kept in a temporary file, read and written a row (or part of a row) at a time:
 * the working copies a blended build needs, which can be far bigger than a phone's memory.
 */
final class Grid implements Closeable {
    final int w, h;
    private final File file;
    private final RandomAccessFile raf;
    private final FileChannel ch;

    Grid(File file, int w, int h) throws IOException {
        this.file = file;
        this.w = w;
        this.h = h;
        raf = new RandomAccessFile(file, "rw");
        raf.setLength((long) w * h * 4);
        ch = raf.getChannel();
    }

    /** Fill every pixel with v. */
    void fill(float v) throws IOException {
        float[] row = new float[w];
        java.util.Arrays.fill(row, v);
        for (int y = 0; y < h; y++) write(y, 0, row, 0, w);
    }

    void read(int y, int x0, float[] dst, int off, int n) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN);
        long pos = ((long) y * w + x0) * 4;
        while (b.hasRemaining()) {
            int got = ch.read(b, pos + b.position());
            if (got < 0) throw new IOException("Working file ended early");
        }
        b.flip();
        b.asFloatBuffer().get(dst, off, n);
    }

    void write(int y, int x0, float[] src, int off, int n) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(n * 4).order(ByteOrder.LITTLE_ENDIAN);
        FloatBuffer f = b.asFloatBuffer();
        f.put(src, off, n);
        long pos = ((long) y * w + x0) * 4;
        while (b.hasRemaining()) ch.write(b, pos + b.position());
    }

    @Override
    public void close() throws IOException {
        try {
            ch.close();
            raf.close();
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
