package com.seabathy.bathypicker.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The coordinate systems the original surveys come in, longitude/latitude (degrees) to the
 * survey's own x, y. Every datum here (WGS84, GDA94, GDA2020, ITRF2014) is treated as the same,
 * which is what PROJ picks for these pairs by default (null transformations of about 2-3 m
 * accuracy), so a survey lands on the same spot as on the desktop.
 */
public abstract class Proj {
    public abstract void forward(double lonDeg, double latDeg, double[] out);

    static final double WGS84_A = 6378137.0, WGS84_RF = 298.257223563, GRS80_RF = 298.257222101;

    /** From a catalogue "crs" value: an EPSG code or the WKT of a transverse Mercator. Null if unknown. */
    public static Proj parse(String crs) {
        if (crs == null) return null;
        String c = crs.trim();
        Matcher m = Pattern.compile("^EPSG:(\\d+)$").matcher(c);
        if (m.find()) {
            int code = Integer.parseInt(m.group(1));
            if (code == 4326 || code == 4283 || code == 7844) return new Geographic();
            if (code >= 32701 && code <= 32760) return Tm.utm(code - 32700, true, WGS84_A, WGS84_RF);
            if (code >= 32601 && code <= 32660) return Tm.utm(code - 32600, false, WGS84_A, WGS84_RF);
            if (code >= 28348 && code <= 28358) return Tm.utm(code - 28300, true, WGS84_A, GRS80_RF);  // GDA94 / MGA
            if (code >= 7846 && code <= 7859) return Tm.utm(code - 7800, true, WGS84_A, GRS80_RF);    // GDA2020 / MGA
            if (code == 3395) return new Mercator(WGS84_A, WGS84_RF);
            if (code == 9473) return new AlbersProj();
            return null;
        }
        String w = c.toUpperCase(Locale.ROOT);
        if (w.startsWith("PROJCS[") && w.contains("TRANSVERSE_MERCATOR")) {
            double lat0 = param(c, "latitude_of_origin"), lon0 = param(c, "central_meridian");
            double k0 = param(c, "scale_factor"), fe = param(c, "false_easting"), fn = param(c, "false_northing");
            Matcher sp = Pattern.compile("SPHEROID\\[\"[^\"]*\",\\s*([0-9.eE+-]+),\\s*([0-9.eE+-]+)").matcher(c);
            if (!sp.find() || Double.isNaN(lon0) || Double.isNaN(k0) || Double.isNaN(fe) || Double.isNaN(fn)) return null;
            if (!Double.isNaN(lat0) && lat0 != 0) return null;
            return new Tm(lon0, k0, fe, fn, Double.parseDouble(sp.group(1)), Double.parseDouble(sp.group(2)));
        }
        return null;
    }

    private static double param(String wkt, String name) {
        Matcher m = Pattern.compile("PARAMETER\\[\"" + name + "\",\\s*([0-9.eE+-]+)\\]", Pattern.CASE_INSENSITIVE).matcher(wkt);
        return m.find() ? Double.parseDouble(m.group(1)) : Double.NaN;
    }

    static final class Geographic extends Proj {
        @Override public void forward(double lon, double lat, double[] out) {
            out[0] = lon;
            out[1] = lat;
        }
    }

    static final class AlbersProj extends Proj {
        @Override public void forward(double lon, double lat, double[] out) {
            double[] xy = Albers.forward(lon, lat);
            out[0] = xy[0];
            out[1] = xy[1];
        }
    }

    /** Ellipsoidal Mercator (EPSG:3395, World Mercator). */
    static final class Mercator extends Proj {
        final double a, e;

        Mercator(double a, double rf) {
            this.a = a;
            double f = 1 / rf;
            e = Math.sqrt(f * (2 - f));
        }

        @Override public void forward(double lon, double lat, double[] out) {
            double phi = Math.toRadians(lat), s = Math.sin(phi);
            out[0] = a * Math.toRadians(lon);
            out[1] = a * Math.log(Math.tan(Math.PI / 4 + phi / 2) * Math.pow((1 - e * s) / (1 + e * s), e / 2));
        }
    }

    /**
     * Transverse Mercator by Krueger's series to sixth order in n (Karney 2011), accurate to well
     * under a millimetre across a UTM zone: the same as PROJ's default (Poder/Engsager) to far
     * below a pixel.
     */
    static final class Tm extends Proj {
        final double lon0, k0, fe, fn, aHat, e;
        final double[] alpha = new double[7];

        static Tm utm(int zone, boolean south, double a, double rf) {
            return new Tm(-183 + 6 * zone, 0.9996, 500000, south ? 10000000 : 0, a, rf);
        }

        Tm(double lon0, double k0, double fe, double fn, double a, double rf) {
            this.lon0 = lon0;
            this.k0 = k0;
            this.fe = fe;
            this.fn = fn;
            double f = 1 / rf, n = f / (2 - f);
            e = Math.sqrt(f * (2 - f));
            double n2 = n * n, n3 = n2 * n, n4 = n3 * n, n5 = n4 * n, n6 = n5 * n;
            aHat = a / (1 + n) * (1 + n2 / 4 + n4 / 64 + n6 / 256);
            alpha[1] = n / 2 - 2 * n2 / 3 + 5 * n3 / 16 + 41 * n4 / 180 - 127 * n5 / 288 + 7891 * n6 / 37800;
            alpha[2] = 13 * n2 / 48 - 3 * n3 / 5 + 557 * n4 / 1440 + 281 * n5 / 630 - 1983433 * n6 / 1935360;
            alpha[3] = 61 * n3 / 240 - 103 * n4 / 140 + 15061 * n5 / 26880 + 167603 * n6 / 181440;
            alpha[4] = 49561 * n4 / 161280 - 179 * n5 / 168 + 6601661 * n6 / 7257600;
            alpha[5] = 34729 * n5 / 80640 - 3418889 * n6 / 1995840;
            alpha[6] = 212378941 * n6 / 319334400;
        }

        @Override public void forward(double lon, double lat, double[] out) {
            double phi = Math.toRadians(lat), lam = Math.toRadians(lon - lon0);
            double s = Math.sin(phi);
            double t = Math.sinh(atanh(s) - e * atanh(e * s));
            double xiP = Math.atan2(t, Math.cos(lam));
            double etaP = atanh(Math.sin(lam) / Math.sqrt(1 + t * t));
            double xi = xiP, eta = etaP;
            for (int j = 1; j <= 6; j++) {
                xi += alpha[j] * Math.sin(2 * j * xiP) * Math.cosh(2 * j * etaP);
                eta += alpha[j] * Math.cos(2 * j * xiP) * Math.sinh(2 * j * etaP);
            }
            out[0] = fe + k0 * aHat * eta;
            out[1] = fn + k0 * aHat * xi;
        }

        private static double atanh(double x) {
            return 0.5 * Math.log((1 + x) / (1 - x));
        }
    }
}
