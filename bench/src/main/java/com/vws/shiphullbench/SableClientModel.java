package com.vws.shiphullbench;

import java.util.ArrayList;
import java.util.List;

/**
 * Sable 2.0.3 client pose interpolation, copied from
 * {@code SubLevelSnapshotInterpolator} and {@code ClientSableInterpolationState}.
 *
 * The interpolation clock is per client, not per ship. A MOVE snapshot advances it.
 * A ship that is simply not mentioned that tick keeps its old buffer. Past the newest
 * snapshot, Sable extrapolates at most one tick from the previous segment, then holds.
 * The render sample is {@code interpolationTick - interpolationDelay} (default 1.5).
 */
public final class SableClientModel {
    public static final double INTERPOLATION_DELAY = 1.5;
    private static final float EXPECTED_MS = 50f;

    public static final class Ship {
        private final List<Snap> buffer = new ArrayList<>();
        private boolean stopped;
        /** True once a snapshot has been sampled into {@link #x}, {@link #y}, {@link #z}. */
        public boolean ready;
        public double x, y, z;

        public void clear() {
            buffer.clear();
            stopped = false;
            ready = false;
            x = y = z = 0;
        }

        void receiveMove(int gameTick, double px, double py, double pz) {
            if (buffer.isEmpty() || buffer.get(buffer.size() - 1).tick != gameTick) {
                buffer.add(new Snap(gameTick, px, py, pz));
            }
            stopped = false;
        }

        void receiveStop() {
            stopped = true;
        }

        void sample(double pointer) {
            int start = (int) (pointer - 6.0);
            while (!buffer.isEmpty() && buffer.get(0).tick < start) buffer.remove(0);
            if (buffer.isEmpty()) return;
            double[] dest = sampleAt(pointer);
            x = dest[0];
            y = dest[1];
            z = dest[2];
            ready = true;
        }

        private double[] sampleAt(double gameTick) {
            int beforeIndex = -1;
            Snap before = null;
            Snap after = null;
            for (int i = 0; i < buffer.size(); i++) {
                Snap snapshot = buffer.get(i);
                if (snapshot.tick == gameTick) return new double[] {snapshot.x, snapshot.y, snapshot.z};
                if (snapshot.tick < gameTick) {
                    beforeIndex = i;
                    before = snapshot;
                    continue;
                }
                after = snapshot;
                break;
            }
            double[] dest = new double[3];
            if (before == null || after == null) {
                if (before != null) {
                    dest[0] = before.x;
                    dest[1] = before.y;
                    dest[2] = before.z;
                    int earlier = beforeIndex - 1;
                    if (earlier >= 0 && !stopped) {
                        Snap prev = buffer.get(earlier);
                        double dead = clamp(gameTick - before.tick, 0.0, 1.0);
                        double span = before.tick - prev.tick;
                        double fraction = span == 0.0 ? 0.0 : dead / span;
                        double t = 1.0 + fraction;
                        dest[0] = prev.x + (before.x - prev.x) * t;
                        dest[1] = prev.y + (before.y - prev.y) * t;
                        dest[2] = prev.z + (before.z - prev.z) * t;
                    }
                } else if (after != null) {
                    dest[0] = after.x;
                    dest[1] = after.y;
                    dest[2] = after.z;
                }
            } else {
                double span = after.tick - before.tick;
                double factor = span == 0.0 ? 0.0 : (gameTick - before.tick) / span;
                dest[0] = before.x + (after.x - before.x) * factor;
                dest[1] = before.y + (after.y - before.y) * factor;
                dest[2] = before.z + (after.z - before.z) * factor;
            }
            return dest;
        }
    }

    private static final class Snap {
        final int tick;
        final double x, y, z;

        Snap(int tick, double x, double y, double z) {
            this.tick = tick;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private double mostRecentTick = -1;
    private boolean receivedFirst;
    private double interpolationTick;
    private double estimatedSpeed = 1;
    private float serverMs = EXPECTED_MS;
    private boolean stateStopped = true;
    private double pointer;

    public double pointer() {
        return pointer;
    }

    /** A tick that contained at least one MOVE. Advances the shared clock, matching the info packet. */
    public void receiveMoves(int gameTick, int msSinceLast) {
        receiveInfo(msSinceLast, gameTick, false);
    }

    /**
     * The tick after the last update, Sable sends an info packet with stopped=true and then
     * drops the player from the updating set. Later quiet ticks send nothing.
     */
    public void receiveGlobalStop(int gameTick, int msSinceLast) {
        receiveInfo(msSinceLast, gameTick, true);
    }

    public void tickClock() {
        if (!receivedFirst) return;
        if (!stateStopped) {
            estimatedSpeed = lerp(0.05, estimatedSpeed, EXPECTED_MS / Math.max(1f, serverMs));
        }
        interpolationTick += estimatedSpeed;
        double lo = mostRecentTick - INTERPOLATION_DELAY;
        double hi = mostRecentTick + 1.5;
        interpolationTick = clamp(interpolationTick, lo, hi);
        pointer = interpolationTick - INTERPOLATION_DELAY;
    }

    private void receiveInfo(int msSinceLast, int gameTick, boolean stopped) {
        if (gameTick < mostRecentTick) return;
        if (!receivedFirst || (stateStopped && !stopped)) {
            interpolationTick = gameTick;
            estimatedSpeed = 1.0;
            receivedFirst = true;
        }
        stateStopped = stopped;
        mostRecentTick = gameTick;
        serverMs = msSinceLast;
    }

    private static double lerp(double t, double a, double b) {
        return a + (b - a) * t;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
