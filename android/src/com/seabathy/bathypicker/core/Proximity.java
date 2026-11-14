package com.seabathy.bathypicker.core;

/**
 * Distance to the nearest target pixel, in pixels, the way GDAL's GDALComputeProximity works
 * (gdal raster proximity, which the desktop BathyPicker runs): a pass down the image and one back
 * up, each sweeping every line left-to-right and right-to-left and carrying the nearest target
 * found so far. Its results are not always the exact Euclidean distance, so this follows its
 * steps rather than computing the exact answer, to give the same values as the desktop.
 */
final class Proximity {
    private Proximity() {}

    /** The whole two-pass computation on a raster in memory (for tests; Builder runs the same steps on its working files). */
    static float[] compute(boolean[] target, int w, int h, double maxDist, float nodata) {
        float[] out = new float[w * h];
        int[] nx = new int[w], ny = new int[w];
        boolean[] t = new boolean[w];
        float[] prox = new float[w];
        java.util.Arrays.fill(nx, -1);
        java.util.Arrays.fill(ny, -1);
        for (int y = 0; y < h; y++) {
            System.arraycopy(target, y * w, t, 0, w);
            java.util.Arrays.fill(prox, -1.0f);
            line(t, nx, ny, true, y, w, maxDist, prox);
            line(t, nx, ny, false, y, w, maxDist, prox);
            System.arraycopy(prox, 0, out, y * w, w);
        }
        java.util.Arrays.fill(nx, -1);
        java.util.Arrays.fill(ny, -1);
        for (int y = h - 1; y >= 0; y--) {
            System.arraycopy(out, y * w, prox, 0, w);
            System.arraycopy(target, y * w, t, 0, w);
            line(t, nx, ny, false, y, w, maxDist, prox);
            line(t, nx, ny, true, y, w, maxDist, prox);
            finish(prox, w, nodata);
            System.arraycopy(prox, 0, out, y * w, w);
        }
        return out;
    }

    /** The last step of the upward pass: no target in reach is NoData. */
    static void finish(float[] prox, int w, float nodata) {
        for (int i = 0; i < w; i++) if (prox[i] < 0.0f) prox[i] = nodata;
    }

    /**
     * One line of a sweep (ProcessProximityLine in gdalproximity.cpp). target[x] marks target
     * pixels; nearX/nearY carry the nearest target per column (-1 for none); prox holds the
     * distance so far (-1 for none yet).
     */
    static void line(boolean[] target, int[] nearX, int[] nearY, boolean forward, int iLine, int nX, double maxDist, float[] prox) {
        final int iStart = forward ? 0 : nX - 1, iEnd = forward ? nX : -1, iStep = forward ? 1 : -1;
        final double big = Math.max(maxDist, nX);
        for (int i = iStart; i != iEnd; i += iStep) {
            if (target[i]) {
                prox[i] = 0.0f;
                nearX[i] = i;
                nearY[i] = iLine;
                continue;
            }
            // Nearer to the target that was nearest to the pixel above (below)?
            double near = big * big * 2.0;
            if (nearX[i] != -1) {
                double d = (double) (nearX[i] - i) * (nearX[i] - i) + (double) (nearY[i] - iLine) * (nearY[i] - iLine);
                if (d < near) {
                    near = d;
                } else {
                    nearX[i] = -1;
                    nearY[i] = -1;
                }
            }
            // ... to the left (right) pixel's?
            int last = i - iStep;
            if (i != iStart && nearX[last] != -1) {
                double d = (double) (nearX[last] - i) * (nearX[last] - i) + (double) (nearY[last] - iLine) * (nearY[last] - iLine);
                if (d < near) {
                    near = d;
                    nearX[i] = nearX[last];
                    nearY[i] = nearY[last];
                }
            }
            // ... to the top-right (bottom-left) pixel's?
            int tr = i + iStep;
            if (tr != iEnd && nearX[tr] != -1) {
                double d = (double) (nearX[tr] - i) * (nearX[tr] - i) + (double) (nearY[tr] - iLine) * (nearY[tr] - iLine);
                if (d < near) {
                    near = d;
                    nearX[i] = nearX[tr];
                    nearY[i] = nearY[tr];
                }
            }
            if (nearX[i] != -1 && near <= maxDist * maxDist && (prox[i] < 0 || near < (double) prox[i] * prox[i]))
                prox[i] = (float) Math.sqrt(near);
        }
    }
}
