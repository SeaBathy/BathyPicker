package com.seabathy.bathypicker.core;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What gdalwarp does for BathyPicker, without GDAL: lays every chosen zone onto one output grid
 * (coarse zones first, the finest last so it wins where they overlap), using each zone's overview
 * closest to the output detail, and writes a tiled GeoTIFF block by block.
 */
public final class Builder {
    static final int BLOCK_TILES = 8;  // a work block is 8 tiles wide and 2 tall (2048 x 512 pixels),
    static final int BLOCK_ROWS = 2;   // which matches the 512-pixel source tiles, so each is fetched about once
    static final float NODATA = 3.4e38f;
    /** For tests: -1 picks overviews like gdalwarp; 0 always reads full resolution (gdalwarp -ovr NONE). */
    public static int forceLevel = -1;

    private final Planner.Plan plan;
    private final Job job;
    private final TileSource src;
    private final ExecutorService workers;

    // the output grid
    final int width, height;
    final double[] gt = new double[6];
    final boolean geographic;
    final String resampling;
    private final int[] zoneLevel;

    // statistics while writing
    private double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
    private long valid;

    public Builder(Planner.Plan plan, Job job, TileSource src, ExecutorService workers) {
        this.plan = plan;
        this.job = job;
        this.src = src;
        this.workers = workers;
        this.geographic = plan.crs.equals("wgs84");
        String r = plan.p.resampling;
        if (r.equals("auto")) r = plan.resM <= plan.finestM * 1.01 ? "near" : "average";
        this.resampling = r;

        double[] b = plan.p.bbox();
        double minX, minY, maxX, maxY, res;
        if (geographic) {
            res = plan.resM / Catalog.M_PER_DEG;
            minX = b[0]; minY = b[1]; maxX = b[2]; maxY = b[3];
        } else {
            // gdalwarp -te_srs EPSG:4326: the box's edges, densified and projected, then their bounding box.
            res = plan.resM;
            minX = minY = Double.MAX_VALUE;
            maxX = maxY = -Double.MAX_VALUE;
            int steps = 21;
            for (int i = 0; i <= steps; i++) {
                double f = i / (double) steps;
                double lon = b[0] + (b[2] - b[0]) * f, lat = b[1] + (b[3] - b[1]) * f;
                for (double[] ll : new double[][]{{lon, b[1]}, {lon, b[3]}, {b[0], lat}, {b[2], lat}}) {
                    double[] xy = Albers.forward(ll[0], ll[1]);
                    minX = Math.min(minX, xy[0]); maxX = Math.max(maxX, xy[0]);
                    minY = Math.min(minY, xy[1]); maxY = Math.max(maxY, xy[1]);
                }
            }
        }
        width = Math.max(1, (int) ((maxX - minX + res / 2) / res));
        height = Math.max(1, (int) ((maxY - minY + res / 2) / res));
        gt[0] = minX; gt[1] = res; gt[3] = maxY; gt[5] = -res;

        // Overview choice per zone, like gdalwarp's default -ovr AUTO.
        double midLat = Math.toRadians((b[1] + b[3]) / 2);
        double shrink = geographic ? Math.sqrt(2.0 / (1.0 + 1.0 / (Math.cos(midLat) * Math.cos(midLat)))) : 1.0;
        zoneLevel = new int[plan.order.size()];
        for (int i = 0; i < plan.order.size(); i++) {
            RemoteTiff t = plan.orderInfo.get(i).tiff;
            double target = plan.resM / plan.orderInfo.get(i).pxM * shrink;
            int lv = -1;
            int base = t.levels.get(0).width;
            for (; target > 1.0 && lv < t.levels.size() - 1; lv++) {
                double ratio = lv < 0 ? 1.0 : base / (double) t.levels.get(lv).width;
                double next = base / (double) t.levels.get(lv + 1).width;
                if (ratio < target && next > target) break;
                if (Math.abs(ratio - target) < 1e-1) break;
            }
            zoneLevel[i] = forceLevel >= 0 ? Math.min(forceLevel, t.levels.size() - 1) : Math.max(0, lv);
        }
    }

    public int epsg() {
        return geographic ? 4326 : 9473;
    }

    /** Build into the given file. Returns the result map the page shows. */
    public Map<String, Object> run(FileChannel out, FileChannel in) throws IOException {
        TiffWriter tw = new TiffWriter(out, in, width, height, gt, epsg(), NODATA);
        for (int i = 0; i < plan.order.size(); i++) {
            RemoteTiff.Level L = plan.orderInfo.get(i).tiff.levels.get(zoneLevel[i]);
            job.add(String.format(Locale.ROOT, "%s: %s m%s", plan.order.get(i).file, Planner.g(Math.round(L.resX * 100) / 100.0),
                    zoneLevel[i] == 0 ? "" : " (overview " + zoneLevel[i] + ")"));
        }
        job.add(String.format(Locale.ROOT, "Output %d x %d pixels at %s m, %s resampling, %s", width, height,
                Planner.g(plan.resM), resampling, geographic ? "EPSG:4326" : "EPSG:9473"));

        int blocksX = (tw.tilesX() + BLOCK_TILES - 1) / BLOCK_TILES, blocksY = (tw.tilesY() + BLOCK_ROWS - 1) / BLOCK_ROWS;
        int total = blocksX * blocksY;
        int ovRows = tw.overviewRows();
        double mainShare = 92.0 * total / (total + ovRows * 0.25 + 1e-9);
        AtomicInteger doneBlocks = new AtomicInteger();
        String stage = "Reading from IMAS and merging the depth layers";
        job.progress(0, stage);

        List<Future<?>> fs = new ArrayList<>();
        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                final int fbx = bx, fby = by;
                fs.add(workers.submit(() -> {
                    job.check();
                    block(tw, fbx, fby);
                    int d = doneBlocks.incrementAndGet();
                    job.progress(mainShare * d / total, stage + " (" + Planner.fmtBytes(src.bytesDownloaded.get()) + " downloaded)");
                    return null;
                }));
            }
        }
        waitAll(fs);

        job.add("Downloaded " + Planner.fmtBytes(src.bytesDownloaded.get()) + " from IMAS");
        if (ovRows > 0) {
            String ostage = "Building overviews for fast zooming";
            job.progress(mainShare, ostage);
            AtomicInteger doneRows = new AtomicInteger();
            tw.buildOverviews(workers, job, () -> job.progress(mainShare + (99 - mainShare) * doneRows.incrementAndGet() / ovRows, ostage));
        }
        job.progress(99, "Finishing the file");
        long size = tw.finish();

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", plan.filename);
        r.put("bytes", size);
        r.put("bbox", Arrays.asList(plan.p.w, plan.p.s, plan.p.e, plan.p.n));
        r.put("res_m", plan.resM == Math.rint(plan.resM) ? (Object) (long) plan.resM : (Object) plan.resM);
        r.put("crs_label", Planner.crsLabel(plan.crs));
        r.put("format", Planner.FORMAT);
        r.put("size_px", Arrays.asList(width, height));
        synchronized (this) {
            r.put("min", valid > 0 ? min : null);
            r.put("max", valid > 0 ? max : null);
            r.put("valid_percent", 100.0 * valid / ((double) width * height));
        }
        return r;
    }

    private static void waitAll(List<Future<?>> fs) throws IOException {
        try {
            for (Future<?> f : fs) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    for (Future<?> g : fs) g.cancel(false);
                    Throwable c = e.getCause();
                    if (c instanceof IOException) throw (IOException) c;
                    if (c instanceof OutOfMemoryError) throw new IOException("The phone ran out of memory. Try a smaller box or coarser detail.");
                    throw new IOException(String.valueOf(c), c);
                }
            }
        } catch (InterruptedException e) {
            for (Future<?> g : fs) g.cancel(false);
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Cancelled");
        }
    }

    // ------------------------------------------------------------------ one block

    private void block(TiffWriter tw, int bx, int by) throws IOException {
        final int T = TiffWriter.TILE;
        int c0 = bx * BLOCK_TILES * T, r0 = by * BLOCK_ROWS * T;
        int bw = Math.min(BLOCK_TILES * T, tw.tilesX() * T - c0), bh = Math.min(BLOCK_ROWS * T, tw.tilesY() * T - r0);
        float[] buf = new float[bw * bh];
        Arrays.fill(buf, NODATA);
        // Only pixels inside the image are computed; the tile padding stays NoData.
        int cw = Math.min(bw, width - c0), ch = Math.min(bh, height - r0);

        for (int i = 0; i < plan.order.size(); i++) {
            job.check();
            zone(i, buf, bw, c0, r0, cw, ch);
        }

        long v = 0;
        double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
        for (int y = 0; y < ch; y++) {
            for (int x = 0; x < cw; x++) {
                float f = buf[y * bw + x];
                if (f != NODATA) {
                    v++;
                    if (f < mn) mn = f;
                    if (f > mx) mx = f;
                }
            }
        }
        synchronized (this) {
            valid += v;
            if (v > 0) {
                min = Math.min(min, mn);
                max = Math.max(max, mx);
            }
        }
        float[] tile = new float[T * T];
        for (int r = 0; r < bh / T; r++) {
            for (int t = 0; t < bw / T; t++) {
                for (int y = 0; y < T; y++) System.arraycopy(buf, (r * T + y) * bw + t * T, tile, y * T, T);
                tw.writeTile(0, bx * BLOCK_TILES + t, by * BLOCK_ROWS + r, tile);
            }
        }
    }

    /**
     * Source pixel coordinates (at the zone's level) for output corner points: cx/cy are output
     * pixel-corner coordinates relative to the block. Fills sx, sy.
     */
    private final class Mapper {
        final RemoteTiff t;
        final double sx0, sy0, rx, ry;  // source origin and pixel size at this level
        final int c0, r0;
        // geographic output: rho per output row edge/centre, sin/cos theta per column edge/centre (half-pixel steps)
        double[] rho, sinT, cosT;
        // projected output: source px per column, py per row (half-pixel steps)
        double[] colX, rowY;

        Mapper(RemoteTiff t, RemoteTiff.Level L, int c0, int r0, int cols, int rows) {
            this.t = t;
            this.c0 = c0;
            this.r0 = r0;
            sx0 = t.gt[0];
            sy0 = t.gt[3];
            rx = L.resX;
            ry = L.resY;
            int nc = 2 * cols + 1, nr = 2 * rows + 1;
            if (geographic) {
                rho = new double[nr];
                sinT = new double[nc];
                cosT = new double[nc];
                for (int j = 0; j < nr; j++) rho[j] = Albers.rho(gt[3] + (r0 + j * 0.5) * gt[5]);
                for (int k = 0; k < nc; k++) {
                    double th = Albers.theta(gt[0] + (c0 + k * 0.5) * gt[1]);
                    sinT[k] = Math.sin(th);
                    cosT[k] = Math.cos(th);
                }
            } else {
                colX = new double[nc];
                rowY = new double[nr];
                for (int k = 0; k < nc; k++) colX[k] = (gt[0] + (c0 + k * 0.5) * gt[1] - sx0) / rx;
                for (int j = 0; j < nr; j++) rowY[j] = (sy0 - (gt[3] + (r0 + j * 0.5) * gt[5])) / ry;
            }
        }

        /** Source x for half-step column k and half-step row j. */
        double px(int k, int j) {
            if (!geographic) return colX[k];
            return (rho[j] * sinT[k] - sx0) / rx;
        }

        double py(int k, int j) {
            if (!geographic) return rowY[j];
            return (sy0 - Albers.RHO0 + rho[j] * cosT[k]) / ry;
        }
    }

    private void zone(int zi, float[] buf, int bw, int c0, int r0, int cw, int ch) throws IOException {
        RemoteTiff t = plan.orderInfo.get(zi).tiff;
        RemoteTiff.Level L = t.levels.get(zoneLevel[zi]);
        Mapper m = new Mapper(t, L, c0, r0, cw, ch);

        // Source window: the block outline, densified, plus room for the resampling kernel.
        double minx = Double.MAX_VALUE, maxx = -Double.MAX_VALUE, miny = Double.MAX_VALUE, maxy = -Double.MAX_VALUE;
        int nc = 2 * cw, nr = 2 * ch;
        for (int k : steps(nc, 32)) {
            for (int j : new int[]{0, nr}) {
                double x = m.px(k, j), y = m.py(k, j);
                minx = Math.min(minx, x); maxx = Math.max(maxx, x); miny = Math.min(miny, y); maxy = Math.max(maxy, y);
            }
        }
        for (int j : steps(nr, 8)) {
            for (int k : new int[]{0, nc}) {
                double x = m.px(k, j), y = m.py(k, j);
                minx = Math.min(minx, x); maxx = Math.max(maxx, x); miny = Math.min(miny, y); maxy = Math.max(maxy, y);
            }
        }
        int pad = resampling.equals("cubic") ? 3 : 2;
        int wx0 = (int) Math.floor(minx) - pad, wy0 = (int) Math.floor(miny) - pad;
        int wx1 = (int) Math.ceil(maxx) + pad, wy1 = (int) Math.ceil(maxy) + pad;  // exclusive
        wx0 = Math.max(0, wx0); wy0 = Math.max(0, wy0);
        wx1 = Math.min(L.width, wx1); wy1 = Math.min(L.height, wy1);
        if (wx1 <= wx0 || wy1 <= wy0) return;

        int tx0 = wx0 / L.tileW, tx1 = (wx1 - 1) / L.tileW, ty0 = wy0 / L.tileH, ty1 = (wy1 - 1) / L.tileH;
        byte[][][] tiles = src.tiles(t, L, tx0, ty0, tx1, ty1, job);
        boolean any = false;
        for (byte[][] row : tiles) for (byte[] z : row) if (z.length > 0) any = true;
        if (!any) return;

        int ww = wx1 - wx0, wh = wy1 - wy0;
        float[] win = new float[ww * wh];
        Arrays.fill(win, Float.NaN);
        for (int ty = ty0; ty <= ty1; ty++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                byte[] z = tiles[ty - ty0][tx - tx0];
                if (z.length == 0) continue;
                float[] tl = t.decode(L, z, 0, z.length);
                int ox = tx * L.tileW, oy = ty * L.tileH;
                int xa = Math.max(wx0, ox), xb = Math.min(wx1, ox + L.tileW);
                int ya = Math.max(wy0, oy), yb = Math.min(wy1, oy + L.tileH);
                for (int y = ya; y < yb; y++) {
                    int so = (y - oy) * L.tileW + (xa - ox), dO = (y - wy0) * ww + (xa - wx0);
                    for (int x = 0; x < xb - xa; x++) {
                        float v = tl[so + x];
                        win[dO + x] = t.isNodata(v) ? Float.NaN : v;
                    }
                }
            }
        }

        switch (resampling) {
            case "near": near(m, win, ww, wh, wx0, wy0, buf, bw, cw, ch); break;
            case "average": average(m, win, ww, wh, wx0, wy0, buf, bw, cw, ch); break;
            default: kernel(m, win, ww, wh, wx0, wy0, buf, bw, cw, ch, resampling.equals("cubic")); break;
        }
    }

    /** 0 .. n in about `parts` steps, always including n. */
    private static int[] steps(int n, int parts) {
        int step = Math.max(1, n / parts);
        int count = n / step + 2;
        int[] out = new int[count];
        int c = 0;
        for (int k = 0; k < n; k += step) out[c++] = k;
        out[c++] = n;
        return Arrays.copyOf(out, c);
    }

    private static void near(Mapper m, float[] win, int ww, int wh, int wx0, int wy0, float[] buf, int bw, int cw, int ch) {
        for (int y = 0; y < ch; y++) {
            int j = 2 * y + 1;
            for (int x = 0; x < cw; x++) {
                int k = 2 * x + 1;
                int ix = (int) Math.floor(m.px(k, j)) - wx0, iy = (int) Math.floor(m.py(k, j)) - wy0;
                if (ix < 0 || iy < 0 || ix >= ww || iy >= wh) continue;
                float v = win[iy * ww + ix];
                if (v == v) buf[y * bw + x] = v;
            }
        }
    }

    /** Mean of the valid source pixels under each output pixel, weighted by how much of each is covered. */
    private static void average(Mapper m, float[] win, int ww, int wh, int wx0, int wy0, float[] buf, int bw, int cw, int ch) {
        for (int y = 0; y < ch; y++) {
            int j0 = 2 * y, j1 = 2 * y + 2;
            for (int x = 0; x < cw; x++) {
                int k0 = 2 * x, k1 = 2 * x + 2;
                // Like GDAL's average: the footprint is spanned by the pixel's top-left and bottom-right corners.
                double xa = m.px(k0, j0) - wx0, xb = m.px(k1, j1) - wx0;
                double ya = m.py(k0, j0) - wy0, yb = m.py(k1, j1) - wy0;
                if (xa > xb) { double tt = xa; xa = xb; xb = tt; }
                if (ya > yb) { double tt = ya; ya = yb; yb = tt; }
                int ix0 = Math.max(0, (int) Math.floor(xa + 1e-10)), ix1 = Math.min(ww, (int) Math.ceil(xb - 1e-10));
                int iy0 = Math.max(0, (int) Math.floor(ya + 1e-10)), iy1 = Math.min(wh, (int) Math.ceil(yb - 1e-10));
                double sum = 0, wsum = 0;
                for (int iy = iy0; iy < iy1; iy++) {
                    double wy = Math.min(yb, iy + 1) - Math.max(ya, iy);
                    if (wy <= 0) continue;
                    int row = iy * ww;
                    for (int ix = ix0; ix < ix1; ix++) {
                        float v = win[row + ix];
                        if (v != v) continue;
                        double wx = Math.min(xb, ix + 1) - Math.max(xa, ix);
                        if (wx <= 0) continue;
                        sum += v * wx * wy;
                        wsum += wx * wy;
                    }
                }
                if (wsum > 1e-10) buf[y * bw + x] = (float) (sum / wsum);
            }
        }
    }

    /** Bilinear (2 x 2) or cubic (4 x 4, Keys a = -0.5), using only valid neighbours. */
    private static void kernel(Mapper m, float[] win, int ww, int wh, int wx0, int wy0, float[] buf, int bw, int cw, int ch,
                               boolean cubic) {
        double[] kx = new double[4], ky = new double[4];
        for (int y = 0; y < ch; y++) {
            int j = 2 * y + 1;
            for (int x = 0; x < cw; x++) {
                int k = 2 * x + 1;
                double fx = m.px(k, j) - wx0 - 0.5, fy = m.py(k, j) - wy0 - 0.5;
                int ix = (int) Math.floor(fx), iy = (int) Math.floor(fy);
                double dx = fx - ix, dy = fy - iy;
                int lo, n;
                if (cubic) {
                    lo = -1; n = 4;
                    for (int q = 0; q < 4; q++) { kx[q] = keys(dx - (q - 1)); ky[q] = keys(dy - (q - 1)); }
                } else {
                    lo = 0; n = 2;
                    kx[0] = 1 - dx; kx[1] = dx; ky[0] = 1 - dy; ky[1] = dy;
                }
                double sum = 0, wsum = 0;
                for (int b = 0; b < n; b++) {
                    int sy = iy + lo + b;
                    if (sy < 0 || sy >= wh) continue;
                    for (int a = 0; a < n; a++) {
                        int sx = ix + lo + a;
                        if (sx < 0 || sx >= ww) continue;
                        float v = win[sy * ww + sx];
                        if (v != v) continue;
                        double w = kx[a] * ky[b];
                        sum += v * w;
                        wsum += w;
                    }
                }
                if (wsum > 1e-5) buf[y * bw + x] = (float) (sum / wsum);
            }
        }
    }

    private static double keys(double t) {
        t = Math.abs(t);
        if (t <= 1) return (1.5 * t - 2.5) * t * t + 1;
        if (t < 2) return ((-0.5 * t + 2.5) * t - 4) * t + 2;
        return 0;
    }
}
