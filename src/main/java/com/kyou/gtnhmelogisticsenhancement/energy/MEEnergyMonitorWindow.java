package com.kyou.gtnhmelogisticsenhancement.energy;

import java.math.BigInteger;
import java.util.Arrays;

/** Completed 20-game-tick telemetry windows; no wall time and no changes to real energy. */
public final class MEEnergyMonitorWindow {

    public static final int PERIOD = 20;
    public static final int CELLS = 17 * 9;
    private final Flow[] current = new Flow[CELLS];
    private final Value[] completed = new Value[CELLS];
    private long start;
    private long last;
    private long completedEnd = -1;

    public MEEnergyMonitorWindow(final long now) {
        if (now < 0) throw new IllegalArgumentException("Invalid tick");
        this.start = this.last = now;
        Arrays.fill(this.completed, Value.ZERO);
    }

    public void record(final int cell, final long gross, final long net, final long now) {
        checkCell(cell);
        if (gross < net || net < 0) throw new IllegalArgumentException("Invalid committed amounts");
        advance(now);
        if (gross == 0) return;
        if (this.current[cell] == null) this.current[cell] = new Flow();
        this.current[cell].gross.add(gross);
        this.current[cell].net.add(net);
    }

    public void advance(final long now) {
        if (now < this.last) throw new IllegalArgumentException("Tick moved backwards");
        this.last = now;
        final long windows = (now - this.start) / PERIOD;
        if (windows == 0) return;
        for (int cell = 0; cell < CELLS; cell++) {
            final Flow flow = this.current[cell];
            this.completed[cell] = windows == 1 && flow != null && !flow.gross.isZero()
                ? new Value(flow.gross.toBigInteger(), flow.net.toBigInteger())
                : Value.ZERO;
            if (flow != null) {
                flow.gross.clear();
                flow.net.clear();
            }
        }
        this.start += windows * PERIOD;
        this.completedEnd = this.start;
    }

    public Value value(final int cell) {
        checkCell(cell);
        return this.completed[cell];
    }

    public long completedEnd() {
        return this.completedEnd;
    }

    public static int cell(final int tier, final int color) {
        if (tier < 5 || tier > 13 || color < 0 || color > 16)
            throw new IllegalArgumentException("Invalid monitoring group");
        return color * 9 + tier - 5;
    }

    private static void checkCell(final int cell) {
        if (cell < 0 || cell >= CELLS) throw new IllegalArgumentException("Invalid monitoring cell");
    }

    private static final class Flow {

        final MEEnergyWideCounter gross = new MEEnergyWideCounter();
        final MEEnergyWideCounter net = new MEEnergyWideCounter();
    }

    public static final class Value {

        public static final Value ZERO = new Value(BigInteger.ZERO, BigInteger.ZERO);
        public final BigInteger gross;
        public final BigInteger net;

        private Value(final BigInteger gross, final BigInteger net) {
            this.gross = gross;
            this.net = net;
        }

        public BigInteger loss() {
            return this.gross.subtract(this.net);
        }
    }
}
