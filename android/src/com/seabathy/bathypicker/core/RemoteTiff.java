package com.seabathy.bathypicker.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reads a tiled float32 GeoTIFF (classic or BigTIFF) over HTTP byte ranges, the way GDAL's
 * /vsicurl/ does: the header once, then only the tile-offset rows and tiles a job touches.
 */
public final class RemoteTiff {
    public final String url;
    public final List<Level> levels = new ArrayList<>();
    /** Geotransform of the full-resolution level: x0, dx, 0, y0, 0, dy (dy negative). */
    public final double[] gt = new double[6];
    public final float nodata;
    public final boolean hasNodata;
    public final int epsg;

    private final boolean little;
    private final boolean big;
    private byte[] head = new byte[0];
    private final OnceMap<Long, long[][]> rowIndex = new OnceMap<>();

    public static final class Level {
        public int index, width, height, tileW, tileH, tilesX, tilesY;
        int compression, predictor, bits, sampleFormat;
        int offType, cntType;
        long offPtr, cntPtr;
        long[] offInline, cntInline;
        /** Pixel size in source units (metres) at this level. */
        public double resX, resY;
    }

    private RemoteTiff(String url, boolean little, boolean big, float nodata, boolean hasNodata, int epsg) {
        this.url = url;
        this.little = little;
        this.big = big;
        this.nodata = nodata;
        this.hasNodata = hasNodata;
        this.epsg = epsg;
    }

    public static RemoteTiff open(String url, Http.Cancel cancel) throws IOException {
        Header h = new Header(url, cancel);
        h.ensure(0, 16);
        byte[] b = h.buf;
        boolean little;
        if (b[0] == 'I' && b[1] == 'I') little = true;
        else if (b[0] == 'M' && b[1] == 'M') little = false;
        else throw new IOException(url + " isn't a TIFF file.");
        h.little = little;
        int magic = h.u16(2);
        boolean big;
        long ifd;
        if (magic == 42) {
            big = false;
            ifd = h.u32(4);
        } else if (magic == 43) {
            big = true;
            ifd = h.u64(8);
        } else {
            throw new IOException(url + " isn't a TIFF file.");
        }
        h.big = big;

        List<Level> lv = new ArrayList<>();
        double[] scale = null, tie = null;
        int[] keys = null;
        String nodataText = null;
        int guard = 0;
        while (ifd != 0 && guard++ < 64) {
            int entrySize = big ? 20 : 12;
            h.ensure(ifd, big ? 8 : 2);
            long n = big ? h.u64(ifd) : h.u16(ifd);
            long first = ifd + (big ? 8 : 2);
            h.ensure(first, (int) (n * entrySize + (big ? 8 : 4)));
            Level L = new Level();
            long subfile = 0;
            L.predictor = 1;
            L.compression = 1;
            L.sampleFormat = 1;
            for (int i = 0; i < n; i++) {
                long e = first + (long) i * entrySize;
                int tag = h.u16(e), type = h.u16(e + 2);
                long count = big ? h.u64(e + 4) : h.u32(e + 4);
                long valPos = e + (big ? 12 : 8);
                int tsize = typeSize(type);
                long bytes = count * tsize;
                long dataPos = bytes <= (big ? 8 : 4) ? valPos : (big ? h.u64(valPos) : h.u32(valPos));
                switch (tag) {
                    case 254: subfile = h.uint(type, dataPos); break;
                    case 256: L.width = (int) h.uint(type, dataPos); break;
                    case 257: L.height = (int) h.uint(type, dataPos); break;
                    case 258: L.bits = (int) h.uint(type, dataPos); break;
                    case 259: L.compression = (int) h.uint(type, dataPos); break;
                    case 317: L.predictor = (int) h.uint(type, dataPos); break;
                    case 322: L.tileW = (int) h.uint(type, dataPos); break;
                    case 323: L.tileH = (int) h.uint(type, dataPos); break;
                    case 339: L.sampleFormat = (int) h.uint(type, dataPos); break;
                    case 324:
                        L.offType = type;
                        if (dataPos == valPos) L.offInline = h.uints(type, dataPos, (int) count);
                        else L.offPtr = dataPos;
                        break;
                    case 325:
                        L.cntType = type;
                        if (dataPos == valPos) L.cntInline = h.uints(type, dataPos, (int) count);
                        else L.cntPtr = dataPos;
                        break;
                    case 33550:
                        if (lv.isEmpty()) { h.ensure(dataPos, (int) bytes); scale = h.doubles(dataPos, (int) count); }
                        break;
                    case 33922:
                        if (lv.isEmpty()) { h.ensure(dataPos, (int) bytes); tie = h.doubles(dataPos, (int) count); }
                        break;
                    case 34735:
                        if (lv.isEmpty()) {
                            h.ensure(dataPos, (int) bytes);
                            keys = new int[(int) count];
                            for (int k = 0; k < count; k++) keys[k] = h.u16(dataPos + 2L * k);
                        }
                        break;
                    case 42113:
                        if (lv.isEmpty()) {
                            h.ensure(dataPos, (int) bytes);
                            StringBuilder sb = new StringBuilder();
                            for (int k = 0; k < count; k++) {
                                int c = h.buf[(int) (dataPos + k)] & 0xff;
                                if (c == 0) break;
                                sb.append((char) c);
                            }
                            nodataText = sb.toString().trim();
                        }
                        break;
                    default:
                        break;
                }
            }
            long next = big ? h.u64(first + n * entrySize) : h.u32(first + n * entrySize);
            if ((subfile & 4) == 0) {  // skip transparency masks
                if (L.tileW <= 0 || L.tileH <= 0) throw new IOException(url + " isn't tiled.");
                if (L.bits != 32 || L.sampleFormat != 3) throw new IOException(url + " isn't 32-bit float data.");
                if (L.compression != 1 && L.compression != 5 && L.compression != 8 && L.compression != 32946)
                    throw new IOException(url + " uses TIFF compression " + L.compression + ", which this app can't read.");
                L.tilesX = (L.width + L.tileW - 1) / L.tileW;
                L.tilesY = (L.height + L.tileH - 1) / L.tileH;
                L.index = lv.size();
                lv.add(L);
            }
            ifd = next;
        }
        if (lv.isEmpty()) throw new IOException(url + " has no image.");
        if (scale == null || tie == null || tie.length < 6) throw new IOException(url + " has no georeferencing.");

        int epsg = 0;
        boolean pixelIsPoint = false;
        if (keys != null && keys.length >= 4) {
            for (int k = 0; k < keys[3]; k++) {
                int id = keys[4 + 4 * k], loc = keys[5 + 4 * k], val = keys[7 + 4 * k];
                if (loc != 0) continue;
                if (id == 3072 || (id == 2048 && epsg == 0)) epsg = val;
                if (id == 1025 && val == 2) pixelIsPoint = true;
            }
        }
        float nd = Float.NaN;
        boolean hasNd = false;
        if (nodataText != null && !nodataText.isEmpty()) {
            try {
                nd = (float) Double.parseDouble(nodataText);
                hasNd = true;
            } catch (NumberFormatException ignored) {
                // no usable nodata
            }
        }
        RemoteTiff t = new RemoteTiff(url, little, big, nd, hasNd, epsg);
        t.head = h.buf;
        t.gt[1] = scale[0];
        t.gt[5] = -scale[1];
        t.gt[0] = tie[3] - tie[0] * scale[0];
        t.gt[3] = tie[4] + tie[1] * scale[1];
        if (pixelIsPoint) {
            t.gt[0] -= scale[0] / 2;
            t.gt[3] += scale[1] / 2;
        }
        Level base = lv.get(0);
        for (Level L : lv) {
            L.resX = scale[0] * base.width / (double) L.width;
            L.resY = scale[1] * base.height / (double) L.height;
            t.levels.add(L);
        }
        return t;
    }

    /** Bounding box of the raster in its own coordinates: minX, minY, maxX, maxY. */
    public double[] bounds() {
        Level b = levels.get(0);
        return new double[]{gt[0], gt[3] + gt[5] * b.height, gt[0] + gt[1] * b.width, gt[3]};
    }

    public boolean isNodata(float v) {
        return Float.isNaN(v) || (hasNodata && v == nodata);
    }

    /** {offsets, byteCounts} for one row of tiles at a level. */
    long[][] tileRow(Level L, int ty, Http.Cancel cancel) throws IOException {
        long key = ((long) L.index << 32) | ty;
        try {
            return rowIndex.get(key, () -> {
                int start = ty * L.tilesX, n = L.tilesX;
                long[] off = readIndex(L.offInline, L.offType, L.offPtr, start, n, cancel);
                long[] cnt = readIndex(L.cntInline, L.cntType, L.cntPtr, start, n, cancel);
                return new long[][]{off, cnt};
            });
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private long[] readIndex(long[] inline, int type, long ptr, int start, int n, Http.Cancel cancel) throws IOException {
        long[] out = new long[n];
        if (inline != null) {
            System.arraycopy(inline, start, out, 0, n);
            return out;
        }
        int sz = typeSize(type);
        long pos = ptr + (long) start * sz;
        byte[] b;
        if (pos + (long) n * sz <= head.length) {
            b = new byte[n * sz];
            System.arraycopy(head, (int) pos, b, 0, b.length);
        } else {
            b = Http.range(url, pos, n * sz, cancel);
        }
        for (int i = 0; i < n; i++) {
            int p = i * sz;
            switch (sz) {
                case 2: out[i] = u16(b, p); break;
                case 4: out[i] = u32(b, p); break;
                default: out[i] = u64(b, p); break;
            }
        }
        return out;
    }

    /** Decode one compressed tile into floats (tileW * tileH, row-major). */
    float[] decode(Level L, byte[] data, int off, int len) throws IOException {
        int n = L.tileW * L.tileH;
        byte[] raw = new byte[n * 4];
        if (L.compression == 1) {
            System.arraycopy(data, off, raw, 0, Math.min(len, raw.length));
        } else if (L.compression == 5) {
            lzw(data, off, len, raw);
        } else {
            Inflater inf = new Inflater();
            try {
                inf.setInput(data, off, len);
                int pos = 0;
                while (pos < raw.length) {
                    int got = inf.inflate(raw, pos, raw.length - pos);
                    if (got == 0) {
                        if (inf.finished() || inf.needsInput()) break;
                        if (inf.needsDictionary()) throw new IOException("Deflate dictionary needed");
                    }
                    pos += got;
                }
            } catch (DataFormatException e) {
                throw new IOException("A tile in " + url + " is damaged (" + e.getMessage() + ").");
            } finally {
                inf.end();
            }
        }
        float[] out = new float[n];
        int w = L.tileW;
        if (L.predictor == 3) {
            byte[] row = new byte[w * 4];
            for (int y = 0; y < L.tileH; y++) {
                int base = y * w * 4;
                for (int i = 1; i < row.length; i++) raw[base + i] += raw[base + i - 1];
                for (int x = 0; x < w; x++) {
                    int bits = ((raw[base + x] & 0xff) << 24) | ((raw[base + w + x] & 0xff) << 16)
                            | ((raw[base + 2 * w + x] & 0xff) << 8) | (raw[base + 3 * w + x] & 0xff);
                    out[y * w + x] = Float.intBitsToFloat(bits);
                }
            }
            return out;
        }
        for (int i = 0; i < n; i++) {
            int p = i * 4;
            int bits = little
                    ? (raw[p] & 0xff) | ((raw[p + 1] & 0xff) << 8) | ((raw[p + 2] & 0xff) << 16) | ((raw[p + 3] & 0xff) << 24)
                    : ((raw[p] & 0xff) << 24) | ((raw[p + 1] & 0xff) << 16) | ((raw[p + 2] & 0xff) << 8) | (raw[p + 3] & 0xff);
            out[i] = Float.intBitsToFloat(bits);
        }
        if (L.predictor == 2) {  // horizontal differencing on the integer bit pattern (rare for floats)
            for (int y = 0; y < L.tileH; y++)
                for (int x = 1; x < w; x++) {
                    int a = Float.floatToRawIntBits(out[y * w + x - 1]), b2 = Float.floatToRawIntBits(out[y * w + x]);
                    out[y * w + x] = Float.intBitsToFloat(a + b2);
                }
        }
        return out;
    }

    /** TIFF LZW (codes MSB first, 9 to 12 bits, early change), as in libtiff's LZWDecode. */
    static int lzw(byte[] in, int off, int len, byte[] out) {
        int[] prefix = new int[4096];
        byte[] suffix = new byte[4096], first = new byte[4096];
        int[] length = new int[4096];
        for (int i = 0; i < 256; i++) {
            prefix[i] = -1;
            suffix[i] = (byte) i;
            first[i] = (byte) i;
            length[i] = 1;
        }
        int next = 258, width = 9, old = -1, op = 0;
        long buf = 0;
        int bits = 0, pos = off, end = off + len;
        while (op < out.length) {
            while (bits < width && pos < end) {
                buf = (buf << 8) | (in[pos++] & 0xff);
                bits += 8;
            }
            if (bits < width) break;
            int code = (int) ((buf >>> (bits - width)) & ((1 << width) - 1));
            bits -= width;
            if (code == 256) {
                next = 258;
                width = 9;
                old = -1;
                continue;
            }
            if (code == 257) break;
            if (old == -1) {
                if (code > 255) break;  // damaged stream
                out[op++] = (byte) code;
                old = code;
                continue;
            }
            int emit;
            byte head;
            if (code < next) {
                emit = code;
                head = first[code];
            } else if (code == next) {
                emit = -1;
                head = first[old];
            } else {
                break;  // damaged stream
            }
            if (next < 4096) {
                prefix[next] = old;
                suffix[next] = head;
                first[next] = first[old];
                length[next] = length[old] + 1;
                next++;
            }
            int c = emit >= 0 ? emit : next - 1;
            int n = length[c];
            int stop = Math.min(out.length, op + n);
            for (int k = op + n - 1; k >= op; k--) {
                if (k < stop) out[k] = suffix[c];
                c = prefix[c];
            }
            op = stop;
            if (next >= (1 << width) - 1 && width < 12) width++;
            old = code;
        }
        return op;
    }

    // ------------------------------------------------------------------ byte helpers

    static int typeSize(int type) {
        switch (type) {
            case 1: case 2: case 6: case 7: return 1;
            case 3: case 8: return 2;
            case 4: case 9: case 11: case 13: return 4;
            case 5: case 10: case 12: case 16: case 17: case 18: return 8;
            default: return 1;
        }
    }

    private int u16(byte[] b, int p) {
        return little ? (b[p] & 0xff) | ((b[p + 1] & 0xff) << 8) : ((b[p] & 0xff) << 8) | (b[p + 1] & 0xff);
    }

    private long u32(byte[] b, int p) {
        return little
                ? (b[p] & 0xffL) | ((b[p + 1] & 0xffL) << 8) | ((b[p + 2] & 0xffL) << 16) | ((b[p + 3] & 0xffL) << 24)
                : ((b[p] & 0xffL) << 24) | ((b[p + 1] & 0xffL) << 16) | ((b[p + 2] & 0xffL) << 8) | (b[p + 3] & 0xffL);
    }

    private long u64(byte[] b, int p) {
        long lo = u32(b, little ? p : p + 4), hi = u32(b, little ? p + 4 : p);
        return (hi << 32) | lo;
    }

    /** The start of the file, grown on demand. */
    private static final class Header {
        final String url;
        final Http.Cancel cancel;
        byte[] buf = new byte[0];
        boolean little = true, big = false;

        Header(String url, Http.Cancel cancel) {
            this.url = url;
            this.cancel = cancel;
        }

        void ensure(long pos, int len) throws IOException {
            long need = pos + len;
            if (need <= buf.length) return;
            if (need > 64L * 1024 * 1024) throw new IOException("Header of " + url + " is unexpectedly large.");
            int want = (int) Math.max(need, Math.max(65536, buf.length * 2L));
            byte[] more = Http.range(url, buf.length, want - buf.length, cancel);
            byte[] nb = new byte[buf.length + more.length];
            System.arraycopy(buf, 0, nb, 0, buf.length);
            System.arraycopy(more, 0, nb, buf.length, more.length);
            buf = nb;
        }

        int u16(long p) {
            int i = (int) p;
            return little ? (buf[i] & 0xff) | ((buf[i + 1] & 0xff) << 8) : ((buf[i] & 0xff) << 8) | (buf[i + 1] & 0xff);
        }

        long u32(long p) {
            int i = (int) p;
            return little
                    ? (buf[i] & 0xffL) | ((buf[i + 1] & 0xffL) << 8) | ((buf[i + 2] & 0xffL) << 16) | ((buf[i + 3] & 0xffL) << 24)
                    : ((buf[i] & 0xffL) << 24) | ((buf[i + 1] & 0xffL) << 16) | ((buf[i + 2] & 0xffL) << 8) | (buf[i + 3] & 0xffL);
        }

        long u64(long p) {
            long lo = u32(little ? p : p + 4), hi = u32(little ? p + 4 : p);
            return (hi << 32) | lo;
        }

        long uint(int type, long p) throws IOException {
            ensure(p, typeSize(type));
            switch (typeSize(type)) {
                case 1: return buf[(int) p] & 0xff;
                case 2: return u16(p);
                case 4: return u32(p);
                default: return u64(p);
            }
        }

        long[] uints(int type, long p, int n) throws IOException {
            long[] out = new long[n];
            int s = typeSize(type);
            for (int i = 0; i < n; i++) out[i] = uint(type, p + (long) i * s);
            return out;
        }

        double[] doubles(long p, int n) {
            double[] out = new double[n];
            for (int i = 0; i < n; i++) out[i] = Double.longBitsToDouble(u64(p + 8L * i));
            return out;
        }
    }
}
