package com.seabathy.bathypicker.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/** The 8 IMAS depth-zone files and their headers, read once and kept. */
public final class Catalog {
    public static final String BASE_URL =
            "https://data.imas.utas.edu.au/attachments/69e9ac91-babe-47ed-8c37-0ef08f29338a/bathymetry/";
    public static final String DOI_URL = "https://doi.org/10.25959/Y9VF-V723";
    public static final String CITATION = "Flukes, E. (2026). Multi-resolution bathymetry composite surface for Australian waters (EEZ) "
            + "[Data set]. Institute for Marine and Antarctic Studies. " + DOI_URL;
    public static final double M_PER_DEG = 111_320.0;

    public static final class Zone {
        public final String key, file, label;
        public final int idx;

        Zone(int idx, String key, String file, String label) {
            this.idx = idx;
            this.key = key;
            this.file = file;
            this.label = label;
        }
    }

    // Only the first three depth ranges are stated in the dataset record; the rest are named, not guessed.
    public static final List<Zone> ZONES = new ArrayList<>();

    static {
        String[][] z = {
                {"shallow", "01_shallow_bathy.tif", "Shallow (0-30 m)"},
                {"mesophotic", "02_mesophotic_bathy.tif", "Mesophotic (30-70 m)"},
                {"rariphotic", "03_rariphotic_bathy.tif", "Rariphotic (70-200 m)"},
                {"upperslope", "04_upperslope_bathy.tif", "Upper slope"},
                {"midslope", "05_midslope_bathy.tif", "Mid slope"},
                {"lowerslope", "06_lowerslope_bathy.tif", "Lower slope"},
                {"abyss", "07_abyss_bathy.tif", "Abyss"},
                {"hadal", "08_hadal_bathy.tif", "Hadal"},
        };
        for (int i = 0; i < z.length; i++) ZONES.add(new Zone(i, z[i][0], z[i][1], z[i][2]));
    }

    public static Zone zone(String key) {
        for (Zone z : ZONES) if (z.key.equals(key)) return z;
        return null;
    }

    /** What the planner needs to know about a zone file. */
    public static final class Info {
        public RemoteTiff tiff;
        public double pxM;
        /** WGS84 extent from the four corners, like gdalinfo's wgs84Extent: w, s, e, n. */
        public double[] extent;
    }

    private final OnceMap<String, Info> infos = new OnceMap<>();
    private final ExecutorService pool;

    public Catalog(ExecutorService pool) {
        this.pool = pool;
    }

    public Info info(Zone z, Http.Cancel cancel) throws IOException {
        try {
            return infos.get(z.key, () -> {
                RemoteTiff t;
                try {
                    t = RemoteTiff.open(BASE_URL + z.file, cancel);
                } catch (IOException e) {
                    throw new IOException("Could not read " + z.file + " from IMAS (" + e.getMessage()
                            + "). Check your internet connection.", e);
                }
                if (t.epsg != 9473 && t.epsg != 3577)
                    throw new IOException(z.file + " is in EPSG:" + t.epsg + ", not GDA2020 Australian Albers, so this app can't use it.");
                Info info = new Info();
                info.tiff = t;
                info.pxM = Math.min(Math.abs(t.gt[1]), Math.abs(t.gt[5]));
                double[] b = t.bounds();
                double w = 1e9, s = 1e9, e = -1e9, n = -1e9;
                for (double[] c : new double[][]{{b[0], b[3]}, {b[0], b[1]}, {b[2], b[1]}, {b[2], b[3]}}) {
                    double[] ll = Albers.inverse(c[0], c[1]);
                    w = Math.min(w, ll[0]);
                    e = Math.max(e, ll[0]);
                    s = Math.min(s, ll[1]);
                    n = Math.max(n, ll[1]);
                }
                info.extent = new double[]{w, s, e, n};
                return info;
            });
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(String.valueOf(e.getMessage()), e);
        }
    }

    /** Headers of several zones, read in parallel. */
    public Map<String, Info> infos(List<Zone> zones, Http.Cancel cancel) throws IOException {
        List<Future<Info>> fs = new ArrayList<>();
        for (Zone z : zones) fs.add(pool.submit(() -> info(z, cancel)));
        Map<String, Info> out = new LinkedHashMap<>();
        for (int i = 0; i < zones.size(); i++) {
            try {
                out.put(zones.get(i).key, fs.get(i).get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException("Cancelled");
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable c = e.getCause();
                if (c instanceof IOException) throw (IOException) c;
                throw new IOException(String.valueOf(c), c);
            }
        }
        return out;
    }
}
