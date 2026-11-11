package com.seabathy.bathypicker.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Writes a tiled float32 GeoTIFF: 256 x 256 tiles, Deflate with the floating-point predictor
 * (PREDICTOR=3, the same as BathyPicker's GDAL settings), GDAL NoData, GeoKeys for EPSG:4326 or
 * EPSG:9473, and averaged overviews. Tiles can arrive in any order, so blocks are built in parallel.
 * The directories go at the end of the file and the header is written last. It is a classic TIFF
 * when everything fits under 4 GB, and a BigTIFF otherwise.
 */
public final class TiffWriter {
    public static final int TILE = 256;

    private final FileChannel out, in;
    private final float nodata;
    private final double[] gt;
    private final int epsg;
    private long end = 16;  // room for either header
    final List<int[]> sizes = new ArrayList<>();       // per level: width, height, tilesX, tilesY
    private final List<long[]> offsets = new ArrayList<>();
    private final List<long[]> counts = new ArrayList<>();

    public TiffWriter(FileChannel out, FileChannel in, int width, int height, double[] gt, int epsg, float nodata) {
        this.out = out;
        this.in = in;
        this.gt = gt.clone();
        this.epsg = epsg;
        this.nodata = nodata;
        int w = width, h = height;
        while (true) {
            int tx = (w + TILE - 1) / TILE, ty = (h + TILE - 1) / TILE;
            sizes.add(new int[]{w, h, tx, ty});
            offsets.add(new long[tx * ty]);
            counts.add(new long[tx * ty]);
            if (w <= TILE && h <= TILE) break;
            w = (w + 1) / 2;
            h = (h + 1) / 2;
        }
    }

    public int width() { return sizes.get(0)[0]; }
    public int height() { return sizes.get(0)[1]; }
    public int tilesX() { return sizes.get(0)[2]; }
    public int tilesY() { return sizes.get(0)[3]; }
    public int levels() { return sizes.size(); }

    /** Compress and store one TILE x TILE tile. Thread-safe. */
    public void writeTile(int level, int tx, int ty, float[] px) throws IOException {
        byte[] z = compress(px);
        long pos;
        synchronized (this) {
            pos = end;
            end += z.length;
        }
        ByteBuffer bb = ByteBuffer.wrap(z);
        long p = pos;
        while (bb.hasRemaining()) p += out.write(bb, p);
        int i = ty * sizes.get(level)[2] + tx;
        synchronized (this) {
            offsets.get(level)[i] = pos;
            counts.get(level)[i] = z.length;
        }
    }

    float[] readTile(int level, int tx, int ty) throws IOException {
        long pos, len;
        int i = ty * sizes.get(level)[2] + tx;
        synchronized (this) {
            pos = offsets.get(level)[i];
            len = counts.get(level)[i];
        }
        byte[] z = new byte[(int) len];
        ByteBuffer bb = ByteBuffer.wrap(z);
        long p = pos;
        while (bb.hasRemaining()) {
            int n = in.read(bb, p);
            if (n < 0) throw new IOException("Unexpected end of the output file");
            p += n;
        }
        return decompress(z);
    }

    /** Each overview tile is the average of the valid pixels in the 2 x 2 block below it. */
    public void buildOverviews(ExecutorService pool, Http.Cancel cancel, Runnable tick) throws IOException {
        for (int lv = 1; lv < sizes.size(); lv++) {
            final int level = lv;
            int[] s = sizes.get(lv), below = sizes.get(lv - 1);
            List<Future<?>> fs = new ArrayList<>();
            for (int ty = 0; ty < s[3]; ty++) {
                final int fty = ty;
                fs.add(pool.submit(() -> {
                    for (int tx = 0; tx < s[2]; tx++) {
                        cancel.check();
                        float[] o = new float[TILE * TILE];
                        java.util.Arrays.fill(o, nodata);
                        for (int q = 0; q < 4; q++) {
                            int cx = tx * 2 + (q & 1), cy = fty * 2 + (q >> 1);
                            if (cx >= below[2] || cy >= below[3]) continue;
                            float[] c = readTile(level - 1, cx, cy);
                            int ox = (q & 1) * (TILE / 2), oy = (q >> 1) * (TILE / 2);
                            for (int y = 0; y < TILE / 2; y++) {
                                for (int x = 0; x < TILE / 2; x++) {
                                    double sum = 0;
                                    int n = 0;
                                    int b = (2 * y) * TILE + 2 * x;
                                    float v;
                                    if ((v = c[b]) != nodata && v == v) { sum += v; n++; }
                                    if ((v = c[b + 1]) != nodata && v == v) { sum += v; n++; }
                                    if ((v = c[b + TILE]) != nodata && v == v) { sum += v; n++; }
                                    if ((v = c[b + TILE + 1]) != nodata && v == v) { sum += v; n++; }
                                    if (n > 0) o[(oy + y) * TILE + ox + x] = (float) (sum / n);
                                }
                            }
                        }
                        writeTile(level, tx, fty, o);
                    }
                    tick.run();
                    return null;
                }));
            }
            for (Future<?> f : fs) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.io.InterruptedIOException("Cancelled");
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable c = e.getCause();
                    if (c instanceof IOException) throw (IOException) c;
                    throw new IOException(String.valueOf(c), c);
                }
            }
        }
    }

    /** Number of overview tile rows, for progress. */
    public int overviewRows() {
        int n = 0;
        for (int lv = 1; lv < sizes.size(); lv++) n += sizes.get(lv)[3];
        return n;
    }

    /** Write the directories and the header. Returns the final file size. */
    public long finish() throws IOException {
        long dataEnd = end;
        boolean big = dataEnd + 2_000_000L + 32L * offsets.get(0).length >= 0xFFFFFFFFL;
        ByteArrayOutputStream ifds = new ByteArrayOutputStream();
        long base = (dataEnd + 7) & ~7L;
        long first = base;
        for (int lv = 0; lv < sizes.size(); lv++) {
            Ifd d = new Ifd(big);
            int[] s = sizes.get(lv);
            d.add(254, 4, new long[]{lv == 0 ? 0 : 1});
            d.add(256, 4, new long[]{s[0]});
            d.add(257, 4, new long[]{s[1]});
            d.add(258, 3, new long[]{32});
            d.add(259, 3, new long[]{8});
            d.add(262, 3, new long[]{1});
            d.add(277, 3, new long[]{1});
            d.add(284, 3, new long[]{1});
            d.add(317, 3, new long[]{3});
            d.add(322, 3, new long[]{TILE});
            d.add(323, 3, new long[]{TILE});
            d.add(324, big ? 16 : 4, offsets.get(lv));
            d.add(325, big ? 16 : 4, counts.get(lv));
            d.add(339, 3, new long[]{3});
            if (lv == 0) {
                d.addDoubles(33550, new double[]{gt[1], -gt[5], 0});
                d.addDoubles(33922, new double[]{0, 0, 0, gt[0], gt[3], 0});
                long[] keys = epsg == 4326
                        ? new long[]{1, 1, 0, 4, 1024, 0, 1, 2, 1025, 0, 1, 1, 2048, 0, 1, 4326, 2054, 0, 1, 9102}
                        : new long[]{1, 1, 0, 4, 1024, 0, 1, 1, 1025, 0, 1, 1, 3072, 0, 1, epsg, 3076, 0, 1, 9001};
                d.add(34735, 3, keys);
            }
            d.addAscii(42113, gdalNumber(nodata));
            long pos = base + ifds.size();
            boolean last = lv == sizes.size() - 1;
            byte[] bytes = d.serialize(pos, last);
            ifds.write(bytes, 0, bytes.length);
        }
        byte[] tail = ifds.toByteArray();
        ByteBuffer bb = ByteBuffer.wrap(tail);
        long p = base;
        while (bb.hasRemaining()) p += out.write(bb, p);

        ByteBuffer h = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        h.put((byte) 'I').put((byte) 'I');
        if (big) {
            h.putShort((short) 43).putShort((short) 8).putShort((short) 0).putLong(first);
        } else {
            h.putShort((short) 42).putInt((int) first).putLong(0);
        }
        h.flip();
        p = 0;
        while (h.hasRemaining()) p += out.write(h, p);
        out.force(false);
        return base + tail.length;
    }

    static String gdalNumber(float v) {
        if (v == 3.4e38f) return "3.4e+38";
        String s = Double.toString(v);
        return s.replace("E", "e+").replace("e+-", "e-");
    }

    // ------------------------------------------------------------------ codec

    byte[] compress(float[] px) {
        byte[] raw = new byte[TILE * TILE * 4];
        int w = TILE;
        for (int y = 0; y < TILE; y++) {
            int base = y * w * 4;
            for (int x = 0; x < w; x++) {
                int bits = Float.floatToRawIntBits(px[y * w + x]);
                raw[base + x] = (byte) (bits >>> 24);
                raw[base + w + x] = (byte) (bits >>> 16);
                raw[base + 2 * w + x] = (byte) (bits >>> 8);
                raw[base + 3 * w + x] = (byte) bits;
            }
            for (int i = w * 4 - 1; i > 0; i--) raw[base + i] -= raw[base + i - 1];
        }
        Deflater d = new Deflater(6);
        try {
            d.setInput(raw);
            d.finish();
            ByteArrayOutputStream bo = new ByteArrayOutputStream(raw.length / 3);
            byte[] buf = new byte[65536];
            while (!d.finished()) {
                int n = d.deflate(buf);
                bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        } finally {
            d.end();
        }
    }

    float[] decompress(byte[] z) throws IOException {
        byte[] raw = new byte[TILE * TILE * 4];
        Inflater inf = new Inflater();
        try {
            inf.setInput(z);
            int pos = 0;
            while (pos < raw.length) {
                int n = inf.inflate(raw, pos, raw.length - pos);
                if (n == 0 && (inf.finished() || inf.needsInput())) break;
                pos += n;
            }
        } catch (DataFormatException e) {
            throw new IOException("Output tile is damaged", e);
        } finally {
            inf.end();
        }
        float[] px = new float[TILE * TILE];
        int w = TILE;
        for (int y = 0; y < TILE; y++) {
            int base = y * w * 4;
            for (int i = 1; i < w * 4; i++) raw[base + i] += raw[base + i - 1];
            for (int x = 0; x < w; x++) {
                int bits = ((raw[base + x] & 0xff) << 24) | ((raw[base + w + x] & 0xff) << 16)
                        | ((raw[base + 2 * w + x] & 0xff) << 8) | (raw[base + 3 * w + x] & 0xff);
                px[y * w + x] = Float.intBitsToFloat(bits);
            }
        }
        return px;
    }

    // ------------------------------------------------------------------ directory builder

    private static final class Ifd {
        final boolean big;
        final List<Object[]> entries = new ArrayList<>();  // tag, type, count, payload bytes

        Ifd(boolean big) {
            this.big = big;
        }

        void add(int tag, int type, long[] vals) {
            int sz = RemoteTiff.typeSize(type);
            ByteBuffer b = ByteBuffer.allocate(vals.length * sz).order(ByteOrder.LITTLE_ENDIAN);
            for (long v : vals) {
                if (sz == 2) b.putShort((short) v);
                else if (sz == 4) b.putInt((int) v);
                else b.putLong(v);
            }
            entries.add(new Object[]{tag, type, (long) vals.length, b.array()});
        }

        void addDoubles(int tag, double[] vals) {
            ByteBuffer b = ByteBuffer.allocate(vals.length * 8).order(ByteOrder.LITTLE_ENDIAN);
            for (double v : vals) b.putDouble(v);
            entries.add(new Object[]{tag, 12, (long) vals.length, b.array()});
        }

        void addAscii(int tag, String s) {
            byte[] t = (s + "\0").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            entries.add(new Object[]{tag, 2, (long) t.length, t});
        }

        /** The directory at file position pos, followed by its out-of-line values. */
        byte[] serialize(long pos, boolean last) {
            int n = entries.size();
            int entrySize = big ? 20 : 12, inline = big ? 8 : 4;
            int head = (big ? 8 : 2) + n * entrySize + (big ? 8 : 4);
            long extra = 0;
            for (Object[] e : entries) {
                int len = ((byte[]) e[3]).length;
                if (len > inline) extra += (len + 1) & ~1;
            }
            int total = (int) (head + extra);
            total = (total + 7) & ~7;
            ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
            if (big) b.putLong(n); else b.putShort((short) n);
            long dataPos = pos + head;
            List<byte[]> outOfLine = new ArrayList<>();
            for (Object[] e : entries) {
                byte[] payload = (byte[]) e[3];
                b.putShort((short) (int) (Integer) e[0]);
                b.putShort((short) (int) (Integer) e[1]);
                if (big) b.putLong((Long) e[2]); else b.putInt((int) (long) (Long) e[2]);
                if (payload.length <= inline) {
                    byte[] cell = new byte[inline];
                    System.arraycopy(payload, 0, cell, 0, payload.length);
                    b.put(cell);
                } else {
                    if (big) b.putLong(dataPos); else b.putInt((int) dataPos);
                    outOfLine.add(payload);
                    dataPos += (payload.length + 1) & ~1;
                }
            }
            long next = last ? 0 : pos + total;
            if (big) b.putLong(next); else b.putInt((int) next);
            for (byte[] p : outOfLine) {
                b.put(p);
                if ((p.length & 1) == 1) b.put((byte) 0);
            }
            return b.array();
        }
    }
}
