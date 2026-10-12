package com.vws.shiphullbench;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** MSPT and pose-series reductions used by the headless probe. */
public final class BenchStats {
    private BenchStats() {}

    public static final class Series {
        private final List<Double> values = new ArrayList<>();

        public void add(double v) {
            if (Double.isFinite(v)) values.add(v);
        }

        public int n() {
            return values.size();
        }

        public double mean() {
            if (values.isEmpty()) return Double.NaN;
            double s = 0;
            for (double v : values) s += v;
            return s / values.size();
        }

        /** Unbiased sample standard deviation. NaN when fewer than two samples. */
        public double stddev() {
            if (values.size() < 2) return Double.NaN;
            double m = mean();
            double s = 0;
            for (double v : values) {
                double d = v - m;
                s += d * d;
            }
            return Math.sqrt(s / (values.size() - 1));
        }

        public double p99() {
            if (values.isEmpty()) return Double.NaN;
            double[] a = new double[values.size()];
            for (int i = 0; i < a.length; i++) a[i] = values.get(i);
            Arrays.sort(a);
            int idx = (int) Math.ceil(0.99 * a.length) - 1;
            if (idx < 0) idx = 0;
            if (idx >= a.length) idx = a.length - 1;
            return a[idx];
        }

        public double min() {
            if (values.isEmpty()) return Double.NaN;
            double m = Double.POSITIVE_INFINITY;
            for (double v : values) m = Math.min(m, v);
            return m;
        }

        public double max() {
            if (values.isEmpty()) return Double.NaN;
            double m = Double.NEGATIVE_INFINITY;
            for (double v : values) m = Math.max(m, v);
            return m;
        }

        /** RMS of the values themselves. Used for second-difference magnitudes. */
        public double rms() {
            if (values.isEmpty()) return Double.NaN;
            double s = 0;
            for (double v : values) s += v * v;
            return Math.sqrt(s / values.size());
        }
    }

    /** Magnitude of the second finite difference, in blocks per tick squared. */
    public static double secondDifference(double[] p0, double[] p1, double[] p2) {
        double dx = p2[0] - 2.0 * p1[0] + p0[0];
        double dy = p2[1] - 2.0 * p1[1] + p0[1];
        double dz = p2[2] - 2.0 * p1[2] + p0[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
