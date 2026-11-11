package com.seabathy.bathypicker.core;

/**
 * GDA2020 / Australian Albers (EPSG:9473) on the GRS 1980 ellipsoid.
 * lat_0 = 0, lon_0 = 132, standard parallels -18 and -36, no false easting or northing.
 * Formulas from Snyder, "Map Projections: A Working Manual" (USGS 1395), pages 101-102.
 * GDA2020 and WGS84 are treated as the same datum, which is what PROJ does by default
 * (EPSG transformation 8450, a null shift with about 3 m accuracy).
 */
public final class Albers {
    private static final double A = 6378137.0;
    private static final double F = 1.0 / 298.257222101;
    private static final double E2 = F * (2 - F);
    private static final double E = Math.sqrt(E2);
    private static final double LON0 = Math.toRadians(132.0);

    static final double N;
    private static final double C;
    static final double RHO0;

    static {
        double p1 = Math.toRadians(-18.0), p2 = Math.toRadians(-36.0), p0 = 0.0;
        double m1 = m(p1), m2 = m(p2);
        double q1 = q(p1), q2 = q(p2), q0 = q(p0);
        N = (m1 * m1 - m2 * m2) / (q2 - q1);
        C = m1 * m1 + N * q1;
        RHO0 = A * Math.sqrt(C - N * q0) / N;
    }

    private Albers() {}

    private static double m(double phi) {
        double s = Math.sin(phi);
        return Math.cos(phi) / Math.sqrt(1 - E2 * s * s);
    }

    private static double q(double phi) {
        double s = Math.sin(phi);
        return (1 - E2) * (s / (1 - E2 * s * s) - (1 / (2 * E)) * Math.log((1 - E * s) / (1 + E * s)));
    }

    /** rho for a latitude in degrees. Depends on latitude only, so rows can share it. */
    public static double rho(double latDeg) {
        return A * Math.sqrt(C - N * q(Math.toRadians(latDeg))) / N;
    }

    /** theta for a longitude in degrees. Depends on longitude only, so columns can share it. */
    public static double theta(double lonDeg) {
        return N * (Math.toRadians(lonDeg) - LON0);
    }

    public static double x(double rho, double theta) {
        return rho * Math.sin(theta);
    }

    public static double y(double rho, double theta) {
        return RHO0 - rho * Math.cos(theta);
    }

    /** Longitude, latitude in degrees to Albers metres. */
    public static double[] forward(double lonDeg, double latDeg) {
        double r = rho(latDeg), t = theta(lonDeg);
        return new double[]{r * Math.sin(t), RHO0 - r * Math.cos(t)};
    }

    /** Albers metres to longitude, latitude in degrees. */
    public static double[] inverse(double x, double y) {
        double dy = RHO0 - y;
        double rho = Math.sqrt(x * x + dy * dy);
        double theta;
        if (N < 0) {
            rho = -rho;
            theta = Math.atan2(-x, -dy);
        } else {
            theta = Math.atan2(x, dy);
        }
        double qq = (C - rho * rho * N * N / (A * A)) / N;
        double phi = Math.asin(Math.max(-1, Math.min(1, qq / 2)));
        for (int i = 0; i < 15; i++) {
            double s = Math.sin(phi), c = Math.cos(phi);
            double es = 1 - E2 * s * s;
            double d = es * es / (2 * c) * (qq / (1 - E2) - s / es + (1 / (2 * E)) * Math.log((1 - E * s) / (1 + E * s)));
            phi += d;
            if (Math.abs(d) < 1e-12) break;
        }
        return new double[]{Math.toDegrees(LON0 + theta / N), Math.toDegrees(phi)};
    }
}
