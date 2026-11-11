package com.seabathy.bathypicker.core;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** "Check size": the same rules as assess() in bathy_picker.py. */
public final class Planner {
    public static final double GB = 1024.0 * 1024 * 1024;
    public static final double DEFAULT_MAX_GB = 3.0;
    static final double[] RES_LADDER = {10, 15, 20, 25, 30, 50, 100, 200, 250, 500, 1000, 2000, 5000};
    public static final String[] RESAMPLING = {"auto", "near", "average", "bilinear", "cubic"};
    public static final String FORMAT = "Tiled GeoTIFF with overviews (Deflate)";

    private Planner() {}

    public static final class Params {
        public double w, s, e, n;
        public List<String> zones;
        public String crs = "wgs84";
        public String resolution = "auto";
        public String resampling = "auto";

        public double[] bbox() {
            return new double[]{w, s, e, n};
        }
    }

    /** A checked request, ready to build. */
    public static final class Plan {
        public Params p;
        public double resM, finestM;
        public String crs, filename;
        public List<Catalog.Zone> order = new ArrayList<>();  // coarse first, finest last = finest wins
        public List<Catalog.Info> orderInfo = new ArrayList<>();
        public Map<String, Object> json;
        public long estBytes;
    }

    public static String crsLabel(String crs) {
        return "albers".equals(crs) ? "GDA2020 Australian Albers (metres)" : "WGS84 lat/long";
    }

    public static Params parse(double[] bbox, List<String> zones, String crs, String resolution, String resampling) {
        if (bbox == null || bbox.length != 4) throw new IllegalArgumentException("Pick an area first: west, south, east and north are all needed.");
        double w = bbox[0], s = bbox[1], e = bbox[2], n = bbox[3];
        for (double v : bbox) if (Double.isNaN(v) || Double.isInfinite(v)) throw new IllegalArgumentException("The box edges must be numbers.");
        if (!(-180 <= w && w < e && e <= 180 && -90 <= s && s < n && n <= 90))
            throw new IllegalArgumentException("West must be less than east and south less than north (latitude -90 to 90, longitude -180 to 180).");
        if (e - w < 0.0005 || n - s < 0.0005) throw new IllegalArgumentException("The box is too small. Drag the corners further apart.");
        if (e - w > 90 || n - s > 70) throw new IllegalArgumentException("The box is too large. Zoom in and pick a smaller area.");
        List<String> keys = new ArrayList<>();
        if (zones == null) {
            for (Catalog.Zone z : Catalog.ZONES) keys.add(z.key);
        } else {
            for (String k : zones) if (Catalog.zone(k) == null) throw new IllegalArgumentException("Unknown depth layer: " + k);
            for (Catalog.Zone z : Catalog.ZONES) if (zones.contains(z.key)) keys.add(z.key);
        }
        if (keys.isEmpty()) throw new IllegalArgumentException("Pick at least one depth layer.");
        if (crs == null) crs = "wgs84";
        if (!crs.equals("wgs84") && !crs.equals("albers")) throw new IllegalArgumentException("Unknown output coordinate system.");
        String res = resolution == null ? "auto" : resolution.trim().toLowerCase(Locale.ROOT);
        if (!res.equals("auto") && !res.equals("finest")) {
            double r;
            try {
                r = Double.parseDouble(res);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Resolution must be auto, finest, or a number of metres.");
            }
            if (!(r >= 1 && r <= 20000)) throw new IllegalArgumentException("Resolution must be between 1 and 20000 metres.");
            res = g(r);
        }
        if (resampling == null) resampling = "auto";
        if (!Arrays.asList(RESAMPLING).contains(resampling)) throw new IllegalArgumentException("Unknown resampling method.");
        Params p = new Params();
        p.w = w; p.s = s; p.e = e; p.n = n;
        p.zones = keys;
        p.crs = crs;
        p.resolution = res;
        p.resampling = resampling;
        return p;
    }

    static double[] boxMetres(double[] b) {
        double mid = Math.toRadians((b[1] + b[3]) / 2);
        return new double[]{(b[2] - b[0]) * Catalog.M_PER_DEG * Math.cos(mid), (b[3] - b[1]) * Catalog.M_PER_DEG};
    }

    static long[] outputPixels(double[] b, double resM, String crs) {
        if (crs.equals("wgs84")) {
            double d = resM / Catalog.M_PER_DEG;
            return new long[]{Math.max(1, (long) Math.ceil((b[2] - b[0]) / d)), Math.max(1, (long) Math.ceil((b[3] - b[1]) / d))};
        }
        double[] m = boxMetres(b);
        return new long[]{Math.max(1, (long) Math.ceil(m[0] / resM)), Math.max(1, (long) Math.ceil(m[1] / resM))};
    }

    static boolean intersects(double[] a, double[] b) {
        return !(a[2] <= b[0] || a[0] >= b[2] || a[3] <= b[1] || a[1] >= b[3]);
    }

    /** {resolution in metres, note}. */
    static Object[] chooseResolution(double[] b, double finestM, String crs, String mode, double cap) {
        double finest = Math.max(Math.round(finestM * 1000) / 1000.0, 0.5);
        if (mode.equals("finest")) return new Object[]{finest, "Finest detail in the layers you picked."};
        if (!mode.equals("auto")) return new Object[]{Double.parseDouble(mode), "As you asked."};
        List<Double> ladder = new ArrayList<>();
        ladder.add(finest);
        for (double r : RES_LADDER) if (r > finest * 1.01) ladder.add(r);
        for (double r : ladder) {
            long[] px = outputPixels(b, r, crs);
            if (px[0] * px[1] * 4.0 <= cap)
                return new Object[]{r, r == finest ? "Full detail." : "Finest detail that fits under the size limit."};
        }
        return new Object[]{ladder.get(ladder.size() - 1), "Even the coarsest option is over the size limit."};
    }

    static String makeFilename(double[] b, double resM, String crs) {
        String res = g(resM).replace(".", "p");
        String suffix = crs.equals("wgs84") ? "" : "_albers";
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
        return "IMAS-bathymetry_" + lat(b[1]) + "-" + lat(b[3]) + "_" + lon(b[0]) + "-" + lon(b[2]) + "_" + res + "m" + suffix + "_" + stamp + ".tif";
    }

    private static String lat(double v) {
        return String.format(Locale.ROOT, "%.2f%s", Math.abs(v), v >= 0 ? "N" : "S");
    }

    private static String lon(double v) {
        return String.format(Locale.ROOT, "%.2f%s", Math.abs(v), v >= 0 ? "E" : "W");
    }

    /** Python's {:g} for the values used here. */
    public static String g(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        String s = String.format(Locale.ROOT, "%.6g", v);
        if (s.contains("e")) return s;
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    public static String fmtBytes(double n) {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        for (String u : units) {
            if (n < 1024 || u.equals("TB"))
                return (u.equals("B") || u.equals("KB")) ? String.format(Locale.ROOT, "%.0f %s", n, u) : String.format(Locale.ROOT, "%.1f %s", n, u);
            n /= 1024;
        }
        return String.format(Locale.ROOT, "%.1f TB", n);
    }

    /**
     * Check a request. freeBytes is the free space where the file will go, outDirLabel is shown on the page.
     */
    public static Plan assess(Params p, Catalog cat, double maxGb, long freeBytes, String outDirLabel, Http.Cancel cancel)
            throws IOException {
        List<Catalog.Zone> zones = new ArrayList<>();
        for (Catalog.Zone z : Catalog.ZONES) if (p.zones.contains(z.key)) zones.add(z);
        Map<String, Catalog.Info> infos = cat.infos(zones, cancel);
        double[] bbox = p.bbox();
        double[] bm = boxMetres(bbox);
        double cap = maxGb * GB;
        List<String> errors = new ArrayList<>(), warnings = new ArrayList<>();
        List<Object> rows = new ArrayList<>();
        List<Catalog.Zone> covered = new ArrayList<>();
        for (Catalog.Zone z : zones) {
            Catalog.Info info = infos.get(z.key);
            boolean hit = info.extent == null || intersects(bbox, info.extent);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("key", z.key);
            r.put("label", z.label);
            r.put("file", z.file);
            r.put("native_res_m", Math.round(info.pxM * 1000) / 1000.0);
            r.put("window_px", Arrays.asList((long) Math.ceil(bm[0] / info.pxM), (long) Math.ceil(bm[1] / info.pxM)));
            r.put("covered", hit);
            rows.add(r);
            if (hit) covered.add(z);
        }
        Plan plan = new Plan();
        plan.p = p;
        plan.crs = p.crs;
        Map<String, Object> out = new LinkedHashMap<>();
        plan.json = out;
        if (covered.isEmpty()) {
            errors.add("This box is outside the area the dataset covers (Australia's exclusive economic zone).");
            out.put("ok", false);
            out.put("errors", errors);
            out.put("warnings", warnings);
            out.put("zones", rows);
            return plan;
        }
        double finest = Double.MAX_VALUE;
        for (Catalog.Zone z : covered) finest = Math.min(finest, infos.get(z.key).pxM);
        Object[] ch = chooseResolution(bbox, finest, p.crs, p.resolution, cap);
        double resM = (Double) ch[0];
        String note = (String) ch[1];
        long[] px = outputPixels(bbox, resM, p.crs);
        long est = px[0] * px[1] * 4;
        if (!p.resolution.equals("auto") && !p.resolution.equals("finest") && resM < finest * 0.99)
            warnings.add(g(resM) + " m is finer than the data itself (" + g(Math.round(finest * 10) / 10.0)
                    + " m). The file gets bigger with no extra detail.");
        if (est > cap)
            errors.add("About " + fmtBytes(est) + " is over the " + g(maxGb) + " GB limit. Pick a smaller box or a coarser resolution.");
        List<String> skipped = new ArrayList<>();
        for (Object o : rows) {
            @SuppressWarnings("unchecked") Map<String, Object> r = (Map<String, Object>) o;
            if (!(Boolean) r.get("covered")) skipped.add((String) r.get("label"));
        }
        if (!skipped.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String sk : skipped) sb.append(sb.length() > 0 ? ", " : "").append(sk);
            warnings.add("No coverage in this box for: " + sb + ".");
        }
        warnings.add("Only seafloor inside Australian waters has data. Land and other countries' waters stay empty (NoData).");
        // Room for the file, its overviews and a margin.
        double need = est * 0.7;
        if (freeBytes >= 0) {
            if (freeBytes < need)
                errors.add("Not enough free space on this phone (" + fmtBytes(freeBytes) + " free, roughly " + fmtBytes(need) + " needed).");
            else if (freeBytes < est * 1.2)
                warnings.add("Storage space is tight on this phone (" + fmtBytes(freeBytes) + " free).");
        }

        List<Catalog.Zone> ordered = new ArrayList<>(covered);
        ordered.sort((a, b) -> {
            int c = Double.compare(infos.get(b.key).pxM, infos.get(a.key).pxM);
            return c != 0 ? c : Integer.compare(b.idx, a.idx);
        });
        List<String> files = new ArrayList<>();
        for (Catalog.Zone z : ordered) {
            files.add(z.file);
            plan.order.add(z);
            plan.orderInfo.add(infos.get(z.key));
        }
        plan.resM = resM;
        plan.finestM = Math.round(finest * 1000) / 1000.0;
        plan.filename = makeFilename(bbox, resM, p.crs);
        plan.estBytes = est;
        out.put("ok", errors.isEmpty());
        out.put("errors", errors);
        out.put("warnings", warnings);
        out.put("zones", rows);
        out.put("bbox", Arrays.asList(bbox[0], bbox[1], bbox[2], bbox[3]));
        out.put("box_km", Arrays.asList(Math.round(bm[0] / 100) / 10.0, Math.round(bm[1] / 100) / 10.0));
        out.put("crs", p.crs);
        out.put("crs_label", crsLabel(p.crs));
        out.put("finest_res_m", plan.finestM);
        out.put("res_m", resM == Math.rint(resM) ? (Object) (long) resM : (Object) resM);
        out.put("res_note", note);
        out.put("output_px", Arrays.asList(px[0], px[1]));
        out.put("est_bytes", est);
        out.put("cap_bytes", cap);
        out.put("free_bytes", freeBytes);
        out.put("format", FORMAT);
        out.put("files", files);
        out.put("filename", plan.filename);
        out.put("out_dir", outDirLabel);
        return plan;
    }
}
