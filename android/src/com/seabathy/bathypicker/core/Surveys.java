package com.seabathy.bathypicker.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The list of original surveys (catalogue/surveys.json), read the same way as the desktop
 * BathyPicker does (clean_survey, in_box and the helpers next to them in bathy_picker.py), so
 * both list and pick the same surveys.
 */
public final class Surveys {
    public static final String SCHEMA = "bathypicker-surveys/v1";
    public static final String URL = "https://raw.githubusercontent.com/SeaBathy/BathyPicker/main/catalogue/surveys.json";
    public static final String REPORT_URL = "https://github.com/SeaBathy/BathyPicker/issues/new";
    static final String HOST = "https://ausseabed-public-bathymetry.s3.ap-southeast-2.amazonaws.com/";
    static final double BLEND_MIN_M = 200.0, BLEND_MAX_M = 500.0;

    public static final class SFile {
        public String url, crs;
        public long bytes;
        public double[] bbox;
        public List<double[]> cells;
        public boolean tiled;
        public Proj proj;
    }

    public static final class Survey {
        public String id, name, statedDatum, verdict, updated, credit, source, licence, note;
        public Integer year;
        public double resM, shiftM, stepM;
        public int sign;
        public double[] bbox;
        public boolean defaultOff;
        public List<SFile> files;

        Survey withFiles(List<SFile> f) {
            Survey s = new Survey();
            s.id = id; s.name = name; s.statedDatum = statedDatum; s.verdict = verdict; s.updated = updated;
            s.credit = credit; s.source = source; s.licence = licence; s.note = note; s.year = year;
            s.resM = resM; s.shiftM = shiftM; s.stepM = stepM; s.sign = sign; s.bbox = bbox; s.defaultOff = defaultOff;
            s.files = f;
            return s;
        }
    }

    public final List<Survey> list;
    public final String built;
    public String origin;

    private Surveys(List<Survey> list, String built, String origin) {
        this.list = list;
        this.built = built;
        this.origin = origin;
    }

    /** Null if the text isn't a catalogue this app understands. */
    @SuppressWarnings("unchecked")
    public static Surveys parse(String text, String origin) {
        Object o;
        try {
            o = Json.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
        if (!(o instanceof Map) || !SCHEMA.equals(((Map<String, Object>) o).get("schema"))) return null;
        Map<String, Object> m = (Map<String, Object>) o;
        List<Survey> out = new ArrayList<>();
        Object raw = m.get("surveys");
        if (raw instanceof List) {
            for (Object s : (List<Object>) raw) {
                if (!(s instanceof Map)) continue;
                Survey c = clean((Map<String, Object>) s);
                if (c != null) out.add(c);
            }
        }
        Object built = m.get("built");
        return new Surveys(out, built == null ? "" : String.valueOf(built), origin);
    }

    private static String str(Object o, int max) {
        String s = o == null ? "" : String.valueOf(o);
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static double num(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) return Double.parseDouble((String) o);
        throw new IllegalArgumentException("not a number");
    }

    private static double[] box(Object o) {
        List<?> l = (List<?>) o;
        if (l.size() != 4) throw new IllegalArgumentException("box");
        return new double[]{num(l.get(0)), num(l.get(1)), num(l.get(2)), num(l.get(3))};
    }

    /** clean_survey: keep an entry only if it is well-formed and points at the known public host. */
    @SuppressWarnings("unchecked")
    static Survey clean(Map<String, Object> s) {
        try {
            List<SFile> files = new ArrayList<>();
            for (Object fo : (List<Object>) s.get("files")) {
                Map<String, Object> f = (Map<String, Object>) fo;
                String url = String.valueOf(f.get("url"));
                if (!url.startsWith(HOST)) return null;
                for (char ch : " \"<>\\?#&".toCharArray()) if (url.indexOf(ch) >= 0) return null;
                SFile sf = new SFile();
                sf.url = url;
                sf.bbox = box(f.get("bbox"));
                sf.cells = new ArrayList<>();
                Object cells = f.get("cells");
                if (cells instanceof List) {
                    List<Object> cl = (List<Object>) cells;
                    for (int i = 0; i < Math.min(600, cl.size()); i++) {
                        List<?> c = (List<?>) cl.get(i);
                        if (c.size() == 4) sf.cells.add(box(c));
                    }
                }
                if (sf.cells.isEmpty()) sf.cells.add(sf.bbox);
                Object b = f.get("bytes");
                sf.bytes = Math.max(0, b == null ? 0 : (long) num(b));
                Object t = f.get("tiled");
                sf.tiled = t == null || Boolean.TRUE.equals(t);
                sf.crs = f.get("crs") == null ? null : String.valueOf(f.get("crs"));
                sf.proj = Proj.parse(sf.crs);
                files.add(sf);
            }
            Survey c = new Survey();
            c.sign = s.get("sign") == null ? 1 : (int) num(s.get("sign"));
            c.shiftM = s.get("shift_m") == null ? 0 : num(s.get("shift_m"));
            c.resM = num(s.get("res_m"));
            c.bbox = box(s.get("bbox"));
            if ((c.sign != 1 && c.sign != -1) || Math.abs(c.shiftM) > 10 || !(c.resM >= 0.05 && c.resM <= 10000) || files.isEmpty())
                return null;
            Object year = s.get("year");
            c.year = year instanceof Number && ((Number) year).doubleValue() == Math.rint(((Number) year).doubleValue())
                    ? (int) ((Number) year).doubleValue() : null;
            c.id = str(s.get("id"), 120);
            if (s.get("id") == null) return null;
            Object name = s.get("name");
            c.name = str(name == null || String.valueOf(name).isEmpty() ? s.get("id") : name, 120);
            c.files = files;
            c.statedDatum = s.get("stated_datum") == null ? null : String.valueOf(s.get("stated_datum"));
            Object check = s.get("check");
            Map<String, Object> ck = check instanceof Map ? (Map<String, Object>) check : Collections.emptyMap();
            c.verdict = str(ck.get("verdict"), 200);
            c.updated = str(s.get("updated"), 10);
            c.credit = str(s.get("credit"), 300);
            c.source = str(s.get("source"), 120);
            c.licence = str(s.get("licence"), 40);
            c.defaultOff = Boolean.TRUE.equals(s.get("default_off"));
            c.note = str(s.get("note"), 300);
            c.stepM = stepEstimate(ck, c.shiftM);
            return c;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** step_estimate: rough size of the depth step between a survey (after its shift) and IMAS, 0-3 m. */
    static double stepEstimate(Map<String, Object> check, double shift) {
        double diff, spread;
        try {
            diff = check.get("median_diff_m") == null ? 0 : num(check.get("median_diff_m"));
            spread = Math.abs(check.get("mad_m") == null ? 0 : num(check.get("mad_m")));
        } catch (RuntimeException e) {
            return 0.0;
        }
        double left = shift != 0 ? Math.min(Math.abs(diff - shift), Math.abs(diff + shift)) : Math.abs(diff);
        return pyRound(Math.min(3.0, left + spread), 2);
    }

    /** Python's round(x, n): to nearest, ties to even, on the decimal digits. */
    static double pyRound(double x, int n) {
        return new java.math.BigDecimal(x).setScale(n, java.math.RoundingMode.HALF_EVEN).doubleValue();
    }

    /** blend_band_m: how far into a survey its edge fades: 200 m, up to 500 m where the step is bigger. */
    public static double blendBandM(Survey s, double resM) {
        return Math.max(4 * resM, Math.min(BLEND_MAX_M, BLEND_MIN_M + 100.0 * s.stepM));
    }

    static boolean intersects(double[] a, double[] b) {
        return a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3];
    }

    /** in_box: the surveys with a data cell in the box, each with only its files that do. */
    public List<Survey> inBox(double[] bbox) {
        List<Survey> out = new ArrayList<>();
        for (Survey s : list) {
            if (!intersects(bbox, s.bbox)) continue;
            List<SFile> files = new ArrayList<>();
            for (SFile f : s.files) {
                for (double[] c : f.cells) {
                    if (intersects(bbox, c)) { files.add(f); break; }
                }
            }
            if (!files.isEmpty()) out.add(s.withFiles(files));
        }
        return out;
    }

    /** overlap_fraction: share of box a covered by box b. */
    static double overlapFraction(double[] a, double[] b) {
        double w = Math.max(a[0], b[0]), s = Math.max(a[1], b[1]), e = Math.min(a[2], b[2]), n = Math.min(a[3], b[3]);
        if (e <= w || n <= s) return 0.0;
        return (e - w) * (n - s) / Math.max(1e-12, (a[2] - a[0]) * (a[3] - a[1]));
    }

    static double cellsCover(double[] bbox, SFile f) {
        double t = 0;
        for (double[] c : f.cells) t += overlapFraction(bbox, c);
        return t;
    }

    /** survey_read_bytes: rough bytes read from one file for this box. */
    static double readBytes(double[] bbox, SFile f) {
        double[] fb = f.bbox;
        if (!f.tiled) {
            double n = Math.min(bbox[3], fb[3]), s = Math.max(bbox[1], fb[1]);
            return f.bytes * Math.max(0.0, n - s) / Math.max(1e-12, fb[3] - fb[1]);
        }
        double area = 0, inside = 0;
        for (double[] c : f.cells) {
            double a = (c[2] - c[0]) * (c[3] - c[1]);
            area += a;
            inside += overlapFraction(c, bbox) * a;
        }
        if (area == 0) area = 1e-12;
        return f.bytes * inside / area;
    }

    /** default_survey_order: finest detail, then newest survey, then newest upload. */
    static void defaultOrder(List<Survey> l) {
        l.sort((a, b) -> {
            int c = Double.compare(a.resM, b.resM);
            if (c != 0) return c;
            c = Integer.compare(b.year == null ? 0 : b.year, a.year == null ? 0 : a.year);
            if (c != 0) return c;
            return descending(a.updated, b.updated);
        });
    }

    /** Python's tuple(-ord(c) for c in s) order: later characters first, and a prefix before the longer text. */
    static int descending(String a, String b) {
        for (int i = 0; i < Math.min(a.length(), b.length()); i++) {
            if (a.charAt(i) != b.charAt(i)) return Character.compare(b.charAt(i), a.charAt(i));
        }
        return Integer.compare(a.length(), b.length());
    }

    /** blended_note: the catalogue's step warnings, reworded for blended edges. */
    static String blendedNote(String note) {
        note = note.replace("; expect small steps at its edges.", "; its edges are blended into IMAS.");
        if (note.contains("IMAS may not be on sea level at this spot."))
            note += " The edge is blended into IMAS, so there's no step, and the middle of the survey stays as surveyed.";
        return note;
    }
}
