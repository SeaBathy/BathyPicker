// The resampling and scale steps follow GDAL's gdalwarpkernel.cpp and gdalwarpoperation.cpp:
// Copyright (c) 2003, Frank Warmerdam; Copyright (c) 2007-2013, Even Rouault. MIT licence; see
// licenses/GDAL-LICENSE.txt.
package com.seabathy.bathypicker.core;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What gdalwarp does for BathyPicker, without GDAL: lays every chosen IMAS zone onto one output
 * grid (coarse zones first, the finest last so it wins where they overlap), using each file's
 * overview closest to the output detail, then the ticked original surveys on top (bottom of the
 * list first), and writes a tiled GeoTIFF.
 *
 * With "Blend survey edges" (the desktop's blend_cmds), each survey is laid on separately and
 * fades in from the layer underneath over a band at its edges, using working copies kept in
 * files (see Grid), since the distance to a survey's edge needs the whole picture.
 */
public final class Builder {
    static final int BLOCK_TILES = 8;  // a work block is 8 tiles wide and 2 tall (2048 x 512 pixels),
    static final int BLOCK_ROWS = 2;   // which matches the 512-pixel source tiles, so each is fetched about once
    static final float NODATA = 3.4e38f;
    static final float FAR = 1e9f;     // "further than we looked" in the distance files
    /** NoData in the finished file (inside, empty pixels are always NODATA). */
    private float outNodata = NODATA;
    /** For tests: -1 picks overviews like gdalwarp; 0 always reads full resolution (gdalwarp -ovr NONE). */
    public static int forceLevel = -1;

    private final Planner.Plan plan;
    private final Job job;
    private final TileSource src;
    private final ExecutorService workers;
    /** Where blending keeps its working files (the app's cache folder). */
    public File workDir = new File(System.getProperty("java.io.tmpdir"));

    // the output grid
    final int width, height;
    final double[] gt = new double[6];
    final boolean geographic;
    final String resampling;

    /** One source: an IMAS zone file, or one file of an original survey. */
    static final class Layer {
        RemoteTiff t;
        int level;
        Proj proj;          // null: an IMAS zone in Australian Albers (the fast path)
        float sign = 1, shift = 0;
        String label;
        /** Output pixels per source pixel, across x and y (gdalwarp's dfXScale / dfYScale); set by scales(). */
        double xScale = 1, yScale = 1;
    }

    /** Source pixel position (at the layer's level) of an output pixel position (x right, y down). */
    private void sourceXY(Layer Ly, RemoteTiff.Level L, double dx, double dy, double[] out) {
        double X = gt[0] + dx * gt[1], Y = gt[3] + dy * gt[5];
        double lon, lat;
        if (geographic) {
            lon = X;
            lat = Y;
        } else {
            double[] ll = Albers.inverse(X, Y);
            lon = ll[0];
            lat = ll[1];
        }
        if (Ly.proj != null) {
            Ly.proj.forward(lon, lat, out);
        } else {
            double[] xy = Albers.forward(lon, lat);
            out[0] = xy[0];
            out[1] = xy[1];
        }
        out[0] = (out[0] - Ly.t.gt[0]) / L.resX;
        out[1] = (Ly.t.gt[3] - out[1]) / L.resY;
    }

    /**
     * gdalwarp's dfXScale / dfYScale (GDALWarpKernel::PerformWarp in GDAL 3.13): up to 10 x 10
     * output pixels spread over the output are mapped into the source; each one's width and height
     * there (or the square root of its area, if it comes out turned or skewed) gives a scale, and
     * the scales are averaged, leaving out stray ones. A scale within 5 % of 1/2, 1/3 ... is
     * rounded to it. Worked out once for the whole output, as gdalwarp does when it warps the
     * output in one go, not per work block.
     */
    private void scales(Layer Ly) {
        RemoteTiff.Level L = Ly.t.levels.get(Ly.level);
        int nPx = Math.min(10, width), nPy = Math.min(10, height);
        List<double[]> xy = new ArrayList<>();
        double[] p0 = new double[2], p1 = new double[2], p2 = new double[2], p3 = new double[2];
        for (int iy = 0; iy < nPy; iy++) {
            double fy = (iy > 0 && iy == nPy - 1 ? -1.0 : 0.0) + (nPy == 1 ? 0.0 : (double) iy * height / (nPy - 1));
            for (int ix = 0; ix < nPx; ix++) {
                double fx = (ix > 0 && ix == nPx - 1 ? -1.0 : 0.0) + (nPx == 1 ? 0.0 : (double) ix * width / (nPx - 1));
                sourceXY(Ly, L, fx, fy, p0);
                sourceXY(Ly, L, fx + 1, fy, p1);
                sourceXY(Ly, L, fx, fy + 1, p2);
                sourceXY(Ly, L, fx + 1, fy + 1, p3);
                double vx01 = p1[0] - p0[0], vy01 = p1[1] - p0[1], len01 = vx01 * vx01 + vy01 * vy01;
                double vx23 = p3[0] - p2[0], vy23 = p3[1] - p2[1], len23 = vx23 * vx23 + vy23 * vy23;
                double vx02 = p2[0] - p0[0], vy02 = p2[1] - p0[1], len02 = vx02 * vx02 + vy02 * vy02;
                double vx13 = p3[0] - p1[0], vy13 = p3[1] - p1[1], len13 = vx13 * vx13 + vy13 * vy13;
                if (Math.abs(vy01) < 0.35 * vx01 && Math.abs(vy23) < 0.35 * vx23
                        && Math.abs(len01 - len23) < 0.1 * Math.abs(len01) && Math.abs(len02 - len13) < 0.1 * Math.abs(len02)) {
                    double xl = Math.sqrt(Math.sqrt(len01 * len23)), yl = Math.sqrt(Math.sqrt(len02 * len13));
                    if (xl > 1e-10 && yl > 1e-10) xy.add(new double[]{1.0 / xl, 1.0 / yl});
                } else {
                    // area of the polygon p0, p1, p3, p2
                    double[][] poly = {p0, p1, p3, p2};
                    double area = 0;
                    for (int i = 0; i < 4; i++) {
                        double[] a = poly[i], c = poly[(i + 1) % 4];
                        area += a[0] * c[1] - c[0] * a[1];
                    }
                    double f = Math.sqrt(Math.abs(area) / 2);
                    if (f > 1e-10) xy.add(new double[]{1.0 / f, 1.0 / f});
                }
            }
        }
        double xs = 0, ys = 0;
        if (!xy.isEmpty()) {
            xy.sort((a, b) -> Double.compare(a[0] * a[1], b[0] * b[1]));
            double xMax = 0, yMax = 0;
            for (double[] s : xy) { xMax = Math.max(xMax, s[0]); yMax = Math.max(yMax, s[1]); }
            int n = 0;
            for (double[] s : xy) {
                if (s[0] > 0.1 * xMax && s[1] > 0.1 * yMax) {
                    n++;
                    xs += (s[0] - xs) / n;
                    ys += (s[1] - ys) / n;
                }
            }
        }
        if (xs <= 0) xs = 1.0;
        if (ys <= 0) ys = 1.0;
        Ly.xScale = roundReciprocal(xs);
        Ly.yScale = roundReciprocal(ys);
    }

    private static double roundReciprocal(double s) {
        if (s < 1.0) {
            double r = 1.0 / s;
            int n = (int) (r + 0.5);
            if (Math.abs(r - n) < 0.05) s = 1.0 / n;
        }
        return s;
    }

    private final List<Layer> imas = new ArrayList<>();
    /** The surveys bottom of the list first, each with its files in order (a later file wins). */
    private final List<List<Layer>> surveys = new ArrayList<>();
    private final List<Surveys.Survey> surveyOrder = new ArrayList<>();

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
        if (r.equals("auto")) {
            r = plan.resM <= plan.finestM * 1.01 ? "near" : "average";
            // Surveys finer than IMAS: the 10 m composite underneath would show as blocks with "near".
            if (!plan.chosen.isEmpty() && plan.imasResM != null && plan.resM < plan.imasResM * 0.99) r = "bilinear";
        }
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

        // Overview choice per IMAS zone, like gdalwarp's default -ovr AUTO.
        double midLat = Math.toRadians((b[1] + b[3]) / 2);
        double shrink = geographic ? Math.sqrt(2.0 / (1.0 + 1.0 / (Math.cos(midLat) * Math.cos(midLat)))) : 1.0;
        for (int i = 0; i < plan.order.size(); i++) {
            Layer L = new Layer();
            L.t = plan.orderInfo.get(i).tiff;
            L.level = pickLevel(L.t, plan.resM / plan.orderInfo.get(i).pxM * shrink);
            L.label = plan.order.get(i).file;
            imas.add(L);
        }
        // Surveys: the same rule, with the source pixels per output pixel measured at the box's middle.
        List<Surveys.Survey> bottomUp = new ArrayList<>(plan.chosen);
        Collections.reverse(bottomUp);
        double cx = (b[0] + b[2]) / 2, cy = (b[1] + b[3]) / 2;
        for (Surveys.Survey s : bottomUp) {
            List<Layer> files = new ArrayList<>();
            for (Surveys.SFile f : s.files) {
                Layer L = new Layer();
                L.t = plan.surveyTiffs.get(f.url);
                L.proj = f.proj;
                L.sign = s.sign;
                L.shift = (float) s.shiftM;
                L.label = s.name + " (" + f.url.substring(f.url.lastIndexOf('/') + 1) + ")";
                L.level = pickLevel(L.t, sourcePerOutput(L.t, f.proj, cx, cy) * shrink);
                files.add(L);
            }
            surveys.add(files);
            surveyOrder.add(s);
        }
        for (Layer L : imas) scales(L);
        for (List<Layer> s : surveys) for (Layer L : s) scales(L);
    }

    /** gdalwarp's -ovr AUTO: the overview just finer than the output (target = source pixels per output pixel). */
    private static int pickLevel(RemoteTiff t, double target) {
        int lv = -1;
        int base = t.levels.get(0).width;
        for (; target > 1.0 && lv < t.levels.size() - 1; lv++) {
            double ratio = lv < 0 ? 1.0 : base / (double) t.levels.get(lv).width;
            double next = base / (double) t.levels.get(lv + 1).width;
            if (ratio < target && next > target) break;
            if (Math.abs(ratio - target) < 1e-1) break;
        }
        return forceLevel >= 0 ? Math.min(forceLevel, t.levels.size() - 1) : Math.max(0, lv);
    }

    /** Full-resolution source pixels across one output pixel, at lon/lat (for picking overviews). */
    private double sourcePerOutput(RemoteTiff t, Proj proj, double lon, double lat) {
        double[] a = new double[2], c = new double[2], d = new double[2];
        double dLon, dLat;
        if (geographic) {
            dLon = gt[1];
            dLat = gt[1];
        } else {
            dLat = plan.resM / Catalog.M_PER_DEG;
            dLon = dLat / Math.cos(Math.toRadians(lat));
        }
        proj.forward(lon, lat, a);
        proj.forward(lon + dLon, lat, c);
        proj.forward(lon, lat + dLat, d);
        double rx = t.levels.get(0).resX, ry = t.levels.get(0).resY;
        double sx = Math.hypot((c[0] - a[0]) / rx, (c[1] - a[1]) / ry), sy = Math.hypot((d[0] - a[0]) / rx, (d[1] - a[1]) / ry);
        return Math.sqrt(sx * sy);
    }

    public int epsg() {
        return geographic ? 4326 : 9473;
    }

    /** Build into the given file. Returns the result map the page shows. */
    public Map<String, Object> run(FileChannel out, FileChannel in) throws IOException {
        // The file's NoData value: IMAS's (3.4e38); for surveys only, the first input's, as gdalwarp takes it.
        if (imas.isEmpty() && !surveys.isEmpty() && surveys.get(0).get(0).t.hasNodata) outNodata = surveys.get(0).get(0).t.nodata;
        TiffWriter tw = new TiffWriter(out, in, width, height, gt, epsg(), outNodata);
        for (Layer L : imas) {
            RemoteTiff.Level lv = L.t.levels.get(L.level);
            job.add(String.format(Locale.ROOT, "%s: %s m%s", L.label, Planner.g(Math.round(lv.resX * 100) / 100.0),
                    L.level == 0 ? "" : " (overview " + L.level + ")"));
        }
        for (List<Layer> s : surveys) {
            for (Layer L : s) job.add("Survey " + L.label + (L.level == 0 ? "" : " (overview " + L.level + ")"));
        }
        job.add(String.format(Locale.ROOT, "Output %d x %d pixels at %s m, %s resampling, %s", width, height,
                Planner.g(plan.resM), resampling, geographic ? "EPSG:4326" : "EPSG:9473"));

        int blocksX = (tw.tilesX() + BLOCK_TILES - 1) / BLOCK_TILES, blocksY = (tw.tilesY() + BLOCK_ROWS - 1) / BLOCK_ROWS;
        int total = blocksX * blocksY;
        int ovRows = tw.overviewRows();
        double mainShare = 92.0 * total / (total + ovRows * 0.25 + 1e-9);
        if (plan.blend) {
            blended(tw, mainShare);
        } else {
            AtomicInteger doneBlocks = new AtomicInteger();
            String stage = surveys.isEmpty() ? "Reading from IMAS and merging the depth layers" : "Reading IMAS and the surveys and merging them";
            job.progress(0, stage);
            List<Future<?>> fs = new ArrayList<>();
            for (int by = 0; by < blocksY; by++) {
                for (int bx = 0; bx < blocksX; bx++) {
                    final int fbx = bx, fby = by;
                    fs.add(workers.submit(() -> {
                        job.check();
                        float[] buf = new float[blockW(tw, fbx) * blockH(tw, fby)];
                        Arrays.fill(buf, NODATA);
                        for (Layer L : imas) layer(L, buf, fbx, fby, tw);
                        for (List<Layer> s : surveys) for (Layer L : s) layer(L, buf, fbx, fby, tw);
                        writeBlock(tw, buf, fbx, fby);
                        int d = doneBlocks.incrementAndGet();
                        job.progress(mainShare * d / total, stage + " (" + Planner.fmtBytes(src.bytesDownloaded.get()) + " downloaded)");
                        return null;
                    }));
                }
            }
            waitAll(fs);
        }

        job.add("Downloaded " + Planner.fmtBytes(src.bytesDownloaded.get()));
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
        List<Object> used = new ArrayList<>();
        for (Surveys.Survey s : plan.chosen) used.add(s.name);
        r.put("surveys_used", used);
        synchronized (this) {
            r.put("min", valid > 0 ? min : null);
            r.put("max", valid > 0 ? max : null);
            r.put("valid_percent", 100.0 * valid / ((double) width * height));
        }
        return r;
    }

    private int blockW(TiffWriter tw, int bx) {
        return Math.min(BLOCK_TILES * TiffWriter.TILE, tw.tilesX() * TiffWriter.TILE - bx * BLOCK_TILES * TiffWriter.TILE);
    }

    private int blockH(TiffWriter tw, int by) {
        return Math.min(BLOCK_ROWS * TiffWriter.TILE, tw.tilesY() * TiffWriter.TILE - by * BLOCK_ROWS * TiffWriter.TILE);
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

    // ------------------------------------------------------------------ blocks

    /** Statistics and the tiles of one finished block (buf is the block, tile padding included). */
    private void writeBlock(TiffWriter tw, float[] buf, int bx, int by) throws IOException {
        final int T = TiffWriter.TILE;
        int bw = blockW(tw, bx), bh = blockH(tw, by);
        int c0 = bx * BLOCK_TILES * T, r0 = by * BLOCK_ROWS * T;
        int cw = Math.min(bw, width - c0), ch = Math.min(bh, height - r0);
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
        if (Float.floatToIntBits(outNodata) != Float.floatToIntBits(NODATA)) {
            for (int i = 0; i < buf.length; i++) if (buf[i] == NODATA) buf[i] = outNodata;
        }
        float[] tile = new float[T * T];
        for (int r = 0; r < bh / T; r++) {
            for (int t = 0; t < bw / T; t++) {
                for (int y = 0; y < T; y++) System.arraycopy(buf, (r * T + y) * bw + t * T, tile, y * T, T);
                tw.writeTile(0, bx * BLOCK_TILES + t, by * BLOCK_ROWS + r, tile);
            }
        }
    }

    /** Paints one source layer into a block buffer (only pixels inside the image; tile padding stays as it is). */
    private void layer(Layer Ly, float[] buf, int bx, int by, TiffWriter tw) throws IOException {
        job.check();
        final int T = TiffWriter.TILE;
        int bw = blockW(tw, bx);
        int c0 = bx * BLOCK_TILES * T, r0 = by * BLOCK_ROWS * T;
        int cw = Math.min(bw, width - c0), ch = Math.min(blockH(tw, by), height - r0);
        if (cw <= 0 || ch <= 0) return;
        paint(Ly, buf, bw, c0, r0, cw, ch);
    }

    /**
     * Source pixel coordinates (at the layer's level) for output points on a half-pixel lattice:
     * k = 2 * column + 1 is a pixel's centre, k = 2 * column its left edge (rows the same with j).
     */
    private final class Mapper {
        final RemoteTiff t;
        final Proj proj;
        final double sx0, sy0, rx, ry;  // source origin and pixel size at this level
        final int c0, r0, nc;
        // IMAS fast path. Geographic output: rho per row, sin/cos theta per column. Projected: px per column, py per row.
        double[] rho, sinT, cosT, colX, rowY;
        // other sources: two rows of the lattice, worked out when first asked for
        final int[] rowJ = {-1, -1};
        final double[][] rowPx = new double[2][], rowPy = new double[2][];
        int nextSlot;
        final double[] tmp = new double[2];

        Mapper(Layer Ly, RemoteTiff.Level L, int c0, int r0, int cols, int rows) {
            this.t = Ly.t;
            this.proj = Ly.proj;
            this.c0 = c0;
            this.r0 = r0;
            sx0 = t.gt[0];
            sy0 = t.gt[3];
            rx = L.resX;
            ry = L.resY;
            nc = 2 * cols + 1;
            int nr = 2 * rows + 1;
            if (proj != null) {
                rowPx[0] = new double[nc]; rowPx[1] = new double[nc];
                rowPy[0] = new double[nc]; rowPy[1] = new double[nc];
            } else if (geographic) {
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

        private int row(int j) {
            if (rowJ[0] == j) return 0;
            if (rowJ[1] == j) return 1;
            int s = nextSlot;
            nextSlot ^= 1;
            rowJ[s] = j;
            double Y = gt[3] + (r0 + j * 0.5) * gt[5];
            double[] px = rowPx[s], py = rowPy[s];
            for (int k = 0; k < nc; k++) {
                double X = gt[0] + (c0 + k * 0.5) * gt[1];
                if (geographic) {
                    proj.forward(X, Y, tmp);
                } else {
                    double[] ll = Albers.inverse(X, Y);
                    proj.forward(ll[0], ll[1], tmp);
                }
                px[k] = (tmp[0] - sx0) / rx;
                py[k] = (sy0 - tmp[1]) / ry;
            }
            return s;
        }

        /** Source x for half-step column k and half-step row j. */
        double px(int k, int j) {
            if (proj != null) return rowPx[row(j)][k];
            if (!geographic) return colX[k];
            return (rho[j] * sinT[k] - sx0) / rx;
        }

        double py(int k, int j) {
            if (proj != null) return rowPy[row(j)][k];
            if (!geographic) return rowY[j];
            return (sy0 - Albers.RHO0 + rho[j] * cosT[k]) / ry;
        }
    }

    private void paint(Layer Ly, float[] buf, int bw, int c0, int r0, int cw, int ch) throws IOException {
        RemoteTiff t = Ly.t;
        RemoteTiff.Level L = t.levels.get(Ly.level);
        Mapper m = new Mapper(Ly, L, c0, r0, cw, ch);

        // Source window: the block outline, densified, plus room for the resampling kernel.
        double minx = Double.MAX_VALUE, maxx = -Double.MAX_VALUE, miny = Double.MAX_VALUE, maxy = -Double.MAX_VALUE;
        int nc = 2 * cw, nr = 2 * ch;
        for (int j : new int[]{0, nr}) {
            for (int k : steps(nc, 32)) {
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
        double xScale = Ly.xScale, yScale = Ly.yScale;
        int pad = resampling.equals("cubic") ? 3 : 2;
        if (resampling.equals("bilinear") || resampling.equals("cubic")) {
            int r = resampling.equals("cubic") ? 2 : 1;
            pad = Math.max(pad, (int) Math.ceil(r / Math.min(1.0, Math.min(xScale, yScale))) + 1);
        }
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
        boolean scaled = Ly.sign != 1 || Ly.shift != 0;
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
                        if (t.isNodata(v)) {
                            win[dO + x] = Float.NaN;
                        } else {
                            // A shifted or flipped survey (the desktop reads it through vrt:// with scale=):
                            // value * sign + shift in double, kept as float32, before resampling.
                            win[dO + x] = scaled ? (float) (v * (double) Ly.sign + Ly.shift) : v;
                        }
                    }
                }
            }
        }

        switch (resampling) {
            case "near": near(m, win, ww, wh, wx0, wy0, buf, bw, cw, ch); break;
            case "average": average(m, win, ww, wh, wx0, wy0, buf, bw, cw, ch); break;
            default: kernel(m, win, ww, wh, wx0, wy0, buf, bw, cw, ch, resampling.equals("cubic"), xScale, yScale); break;
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
                int ix = (int) Math.floor(m.px(k, j) + 1e-10) - wx0, iy = (int) Math.floor(m.py(k, j) + 1e-10) - wy0;   // like gdalwarp: (int) (x + 1e-10)
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

    /**
     * Bilinear or cubic (Keys a = -0.5) the way gdalwarp does them (gdalwarpkernel.cpp, GDAL 3.13),
     * using only valid source pixels:
     * - an output pixel stays empty where the source pixel under its centre is empty;
     * - while the output is no more than twice as coarse as the source (both scales above 0.5),
     *   the plain 2 x 2 (4 x 4) kernel (GWKBilinearResample4Sample);
     * - coarser, a kernel stretched by 1 / scale (GWKResample), summed a row at a time.
     */
    private static void kernel(Mapper m, float[] win, int ww, int wh, int wx0, int wy0, float[] buf, int bw, int cw, int ch,
                               boolean cubic, double xScale, double yScale) {
        boolean plain = xScale > 0.5 && yScale > 0.5;
        int filt = cubic ? 2 : 1;
        int rX = !plain && xScale < 1.0 ? (int) Math.ceil(filt / xScale) : filt;
        int rY = !plain && yScale < 1.0 ? (int) Math.ceil(filt / yScale) : filt;
        // gdalwarp's taps run from ((filter + 1) % 2) - radius to radius
        int initX = ((filt + 1) % 2) - rX, initY = ((filt + 1) % 2) - rY;
        double[] kx = new double[rX - initX + 1];
        for (int y = 0; y < ch; y++) {
            int j = 2 * y + 1;
            for (int x = 0; x < cw; x++) {
                int k = 2 * x + 1;
                double sxp = m.px(k, j), syp = m.py(k, j);
                // the source pixel under the centre, as gdalwarp finds it: (int) (coordinate + 1e-10)
                int ccx = (int) Math.floor(sxp + 1e-10) - wx0, ccy = (int) Math.floor(syp + 1e-10) - wy0;
                if (ccx < 0 || ccy < 0 || ccx >= ww || ccy >= wh) continue;
                float centre = win[ccy * ww + ccx];
                if (centre != centre) continue;
                double fx = sxp - wx0, fy = syp - wy0;   // window pixel coordinates
                int ix = (int) Math.floor(fx - 0.5), iy = (int) Math.floor(fy - 0.5);
                double dx = fx - 0.5 - ix, dy = fy - 0.5 - iy;
                double v;
                if (plain && !cubic) {
                    double rx = 1.5 - (fx - ix), ry = 1.5 - (fy - iy);
                    if (ix == -1 && wx0 == 0) { ix = 0; rx = 1; }
                    if (iy == -1 && wy0 == 0) { iy = 0; ry = 1; }
                    double acc = 0, div = 0;
                    for (int b = 0; b < 2; b++) {
                        int sy = iy + b;
                        if (sy < 0 || sy >= wh) continue;
                        double wyv = b == 0 ? ry : 1.0 - ry;
                        for (int a = 0; a < 2; a++) {
                            int sx = ix + a;
                            if (sx < 0 || sx >= ww) continue;
                            float s = win[sy * ww + sx];
                            if (s != s) continue;
                            double w = (a == 0 ? rx : 1.0 - rx) * wyv;
                            div += w;
                            acc += s * w;
                        }
                    }
                    if (div == 1.0) v = acc;
                    else if (div < 0.00001) continue;
                    else v = acc / div;
                } else {
                    boolean xb = !plain && xScale < 1.0, yb = !plain && yScale < 1.0;
                    for (int i = initX; i <= rX; i++) kx[i - initX] = weight(xb ? (i - dx) * xScale : i - dx, cubic);
                    double acc = 0, wsum = 0;
                    for (int jj = initY; jj <= rY; jj++) {
                        int sy = iy + jj;
                        if (sy < 0 || sy >= wh) continue;
                        double w1 = weight(yb ? (jj - dy) * yScale : jj - dy, cubic);
                        double rowAcc = 0, rowW = 0;
                        int row = sy * ww;
                        for (int i = initX; i <= rX; i++) {
                            int sx = ix + i;
                            if (sx < 0 || sx >= ww) continue;
                            float s = win[row + sx];
                            if (s != s) continue;
                            double w2 = kx[i - initX];
                            rowAcc += s * w2;
                            rowW += w2;
                        }
                        acc += rowAcc * w1;
                        wsum += rowW * w1;
                    }
                    if (wsum < 0.000001) continue;
                    v = (wsum < 0.99999 || wsum > 1.00001) ? acc / wsum : acc;
                }
                buf[y * bw + x] = (float) v;
            }
        }
    }

    private static double weight(double t, boolean cubic) {
        t = Math.abs(t);
        if (!cubic) return t < 1 ? 1 - t : 0;
        if (t <= 1) return (1.5 * t - 2.5) * t * t + 1;
        if (t < 2) return ((-0.5 * t + 2.5) * t - 4) * t + 2;
        return 0;
    }

    // ------------------------------------------------------------------ blended build

    /**
     * blend_cmds in bathy_picker.py: IMAS first, then each survey from the bottom of the list up,
     * fading in from what's underneath over a band at its edges and gaps.
     */
    private void blended(TiffWriter tw, double share) throws IOException {
        String tag0 = Long.toHexString(System.nanoTime());
        int nLayers = surveys.size();
        double steps = 1 + nLayers * 4 + 1;
        int[] done = {0};
        File cf = new File(workDir, "bp-" + tag0 + "-c.tmp"), sf = new File(workDir, "bp-" + tag0 + "-s.tmp"),
                df = new File(workDir, "bp-" + tag0 + "-d.tmp");
        try (Grid C = new Grid(cf, width, height); Grid S = new Grid(sf, width, height); Grid D = new Grid(df, width, height)) {
            job.progress(0, "Reading the IMAS composite");
            toGrid(tw, C, imas, share * ++done[0] / steps, "Reading the IMAS composite");
            for (int i = 0; i < nLayers; i++) {
                Surveys.Survey s = surveyOrder.get(i);
                String tag = nLayers > 1 ? "survey " + (i + 1) + " of " + nLayers : "the survey";
                String st = "Reading " + tag + ": " + s.name;
                job.progress(share * done[0] / steps, st);
                toGrid(tw, S, surveys.get(i), share * (done[0] + 1) / steps, st);
                done[0]++;
                double bandPx = Math.max(1.0, plan.bandM.get(s.id) / plan.resM);
                double look = num(2 * bandPx);
                job.progress(share * done[0] / steps, "Measuring distances in " + tag);
                double inner = distances(C, S, D, look);
                done[0] += 2;
                double band = num(Math.max(1.0, Math.min(bandPx, 0.5 * inner)));
                plan.bandUsedM.put(s.id, band * plan.resM);
                job.add(String.format(Locale.ROOT, "Blending %s over %.0f m", tag, band * plan.resM));
                job.progress(share * done[0] / steps, "Blending the edges of " + tag);
                blend(C, S, D, band);
                done[0]++;
            }
            job.progress(share * done[0] / steps, "Writing the GeoTIFF");
            fromGrid(tw, C);
        }
    }

    /** A number as the desktop writes it into its GDAL expressions ({:g}) and GDAL reads it back. */
    static double num(double v) {
        return Double.parseDouble(Planner.g(v));
    }

    /** Lays layers onto an empty grid, block by block (the desktop's gdalwarp into a working file). */
    private void toGrid(TiffWriter tw, Grid g, List<Layer> layers, double pctAfter, String stage) throws IOException {
        int blocksX = (tw.tilesX() + BLOCK_TILES - 1) / BLOCK_TILES, blocksY = (tw.tilesY() + BLOCK_ROWS - 1) / BLOCK_ROWS;
        List<Future<?>> fs = new ArrayList<>();
        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                final int fbx = bx, fby = by;
                fs.add(workers.submit(() -> {
                    job.check();
                    int bw = blockW(tw, fbx);
                    float[] buf = new float[bw * blockH(tw, fby)];
                    Arrays.fill(buf, NODATA);
                    for (Layer L : layers) layer(L, buf, fbx, fby, tw);
                    int c0 = fbx * BLOCK_TILES * TiffWriter.TILE, r0 = fby * BLOCK_ROWS * TiffWriter.TILE;
                    int cw = Math.min(bw, width - c0), ch = Math.min(blockH(tw, fby), height - r0);
                    for (int y = 0; y < ch; y++) g.write(r0 + y, c0, buf, y * bw, cw);
                    return null;
                }));
            }
        }
        waitAll(fs);
        job.progress(pctAfter, stage + " (" + Planner.fmtBytes(src.bytesDownloaded.get()) + " downloaded)");
    }

    /**
     * Distance (pixels, up to `look`) from each pixel to the nearest place where the layer below has
     * data and the survey doesn't: its outer edge and its gaps (gdal raster proximity, as on the
     * desktop). Returns how deep into the survey its middle is, capped at look (the largest of
     * min(distance, look) over the survey's pixels).
     */
    private double distances(Grid C, Grid S, Grid D, double look) throws IOException {
        int w = width;
        int[] nx = new int[w], ny = new int[w];
        boolean[] t = new boolean[w];
        float[] prox = new float[w], c = new float[w], s = new float[w];
        float lookF = (float) look;
        Arrays.fill(nx, -1);
        Arrays.fill(ny, -1);
        for (int y = 0; y < height; y++) {
            job.check();
            targets(C, S, y, c, s, t);
            Arrays.fill(prox, -1.0f);
            Proximity.line(t, nx, ny, true, y, w, look, prox);
            Proximity.line(t, nx, ny, false, y, w, look, prox);
            D.write(y, 0, prox, 0, w);
        }
        Arrays.fill(nx, -1);
        Arrays.fill(ny, -1);
        float inner = 0;
        for (int y = height - 1; y >= 0; y--) {
            job.check();
            D.read(y, 0, prox, 0, w);
            targets(C, S, y, c, s, t);
            Proximity.line(t, nx, ny, false, y, w, look, prox);
            Proximity.line(t, nx, ny, true, y, w, look, prox);
            Proximity.finish(prox, w, FAR);
            D.write(y, 0, prox, 0, w);
            for (int x = 0; x < w; x++) {
                // k = S is empty ? 0 : min(D, look), kept as float32 like the desktop's working file
                float k = s[x] == NODATA ? 0f : (float) Math.min((double) prox[x], look);
                if (k > inner) inner = k;
            }
        }
        return inner;
    }

    /** One row: the targets (the layer below has data, the survey doesn't), with both rows read into c and s. */
    private static void targets(Grid C, Grid S, int y, float[] c, float[] s, boolean[] t) throws IOException {
        C.read(y, 0, c, 0, c.length);
        S.read(y, 0, s, 0, s.length);
        for (int x = 0; x < t.length; x++) t[x] = s[x] == NODATA && c[x] != NODATA;
    }

    /** blend_calc: S where far enough in, a smooth ramp from C to S across the band, C where S is empty. */
    private void blend(Grid C, Grid S, Grid D, double band) throws IOException {
        int w = width;
        float[] c = new float[w], s = new float[w], d = new float[w];
        for (int y = 0; y < height; y++) {
            job.check();
            C.read(y, 0, c, 0, w);
            S.read(y, 0, s, 0, w);
            D.read(y, 0, d, 0, w);
            for (int x = 0; x < w; x++) {
                if (s[x] == NODATA) continue;               // C stays
                if (c[x] == NODATA || d[x] >= band) {       // S as it is
                    c[x] = s[x];
                    continue;
                }
                double tt = d[x] / band, cc = c[x], ss = s[x];
                c[x] = (float) (cc + (ss - cc) * (tt * tt * (3 - 2 * tt)));
            }
            C.write(y, 0, c, 0, w);
        }
    }

    /** The finished grid into the GeoTIFF's tiles, a block at a time. */
    private void fromGrid(TiffWriter tw, Grid g) throws IOException {
        int blocksX = (tw.tilesX() + BLOCK_TILES - 1) / BLOCK_TILES, blocksY = (tw.tilesY() + BLOCK_ROWS - 1) / BLOCK_ROWS;
        List<Future<?>> fs = new ArrayList<>();
        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                final int fbx = bx, fby = by;
                fs.add(workers.submit(() -> {
                    job.check();
                    int bw = blockW(tw, fbx);
                    float[] buf = new float[bw * blockH(tw, fby)];
                    Arrays.fill(buf, NODATA);
                    int c0 = fbx * BLOCK_TILES * TiffWriter.TILE, r0 = fby * BLOCK_ROWS * TiffWriter.TILE;
                    int cw = Math.min(bw, width - c0), ch = Math.min(blockH(tw, fby), height - r0);
                    for (int y = 0; y < ch; y++) g.read(r0 + y, c0, buf, y * bw, cw);
                    writeBlock(tw, buf, fbx, fby);
                    return null;
                }));
            }
        }
        waitAll(fs);
    }
}
