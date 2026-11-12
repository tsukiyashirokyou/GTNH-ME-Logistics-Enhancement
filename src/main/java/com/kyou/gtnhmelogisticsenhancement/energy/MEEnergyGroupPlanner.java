package com.kyou.gtnhmelogisticsenhancement.energy;

import java.math.BigInteger;
import java.util.Arrays;

/** Independent same-color net-EU quotas. The unpainted default color is an ordinary isolated pool. */
public final class MEEnergyGroupPlanner {

    public static final int COLOR_COUNT = 17;
    public static final int DEFAULT_COLOR = 0;

    private final long[] sources = new long[COLOR_COUNT];
    private final long[] remaining = new long[COLOR_COUNT];
    private final long[] reserved = new long[COLOR_COUNT];
    private BigInteger[] wideSources;
    private BigInteger[] wideRemaining;
    private BigInteger[] wideReserved;
    private BigInteger wideSourceTotal;
    private BigInteger wideDemandTotal;
    private BigInteger wideGranted;
    private long sourceTotal;
    private long demandTotal;
    private long granted;
    private long augmentations;
    private boolean wide;
    private boolean sealed;
    private boolean frozen;

    public static boolean matches(final int sourceColor, final int targetColor) {
        checkColor(sourceColor);
        checkColor(targetColor);
        return sourceColor == targetColor;
    }

    public void reset() {
        Arrays.fill(this.sources, 0);
        Arrays.fill(this.remaining, 0);
        Arrays.fill(this.reserved, 0);
        if (this.wideSources != null) {
            Arrays.fill(this.wideSources, BigInteger.ZERO);
            Arrays.fill(this.wideRemaining, BigInteger.ZERO);
            Arrays.fill(this.wideReserved, BigInteger.ZERO);
        }
        this.sourceTotal = this.demandTotal = this.granted = this.augmentations = 0;
        this.wideSourceTotal = this.wideDemandTotal = this.wideGranted = null;
        this.wide = this.sealed = this.frozen = false;
    }

    public void addSource(final int color, final long net) {
        checkBuilding();
        checkColor(color);
        nonNegative(net);
        if (!this.wide && this.sourceTotal > Long.MAX_VALUE - net) promote();
        if (this.wide) {
            final BigInteger amount = BigInteger.valueOf(net);
            this.wideSources[color] = this.wideSources[color].add(amount);
            this.wideSourceTotal = this.wideSourceTotal.add(amount);
        } else {
            this.sources[color] += net;
            this.sourceTotal += net;
        }
    }

    public void noteDemand(final long net) {
        checkBuilding();
        nonNegative(net);
        if (!this.wide && this.demandTotal > Long.MAX_VALUE - net) promote();
        if (this.wide) this.wideDemandTotal = this.wideDemandTotal.add(BigInteger.valueOf(net));
        else this.demandTotal += net;
    }

    public void noteDemand(final int color, final long net) {
        checkColor(color);
        noteDemand(net);
    }

    public void sealSources() {
        checkBuilding();
        if (this.wide) {
            System.arraycopy(this.wideSources, 0, this.wideRemaining, 0, COLOR_COUNT);
            this.wideGranted = BigInteger.ZERO;
        } else System.arraycopy(this.sources, 0, this.remaining, 0, COLOR_COUNT);
        this.sealed = true;
    }

    /** Reserves only this color's remaining supply, independently of all other colors. */
    public long request(final int color, final long requested) {
        checkSolving();
        checkColor(color);
        nonNegative(requested);
        final long added;
        if (this.wide) {
            final BigInteger amount = this.wideRemaining[color].min(BigInteger.valueOf(requested));
            added = amount.longValueExact();
            if (added > 0) {
                this.wideRemaining[color] = this.wideRemaining[color].subtract(amount);
                this.wideReserved[color] = this.wideReserved[color].add(amount);
                this.wideGranted = this.wideGranted.add(amount);
            }
        } else {
            added = Math.min(requested, this.remaining[color]);
            this.remaining[color] -= added;
            this.reserved[color] += added;
            this.granted += added;
        }
        if (added > 0) this.augmentations++;
        return added;
    }

    public boolean canRouteTo(final int color) {
        checkSolving();
        checkColor(color);
        return this.wide ? this.wideRemaining[color].signum() > 0 : this.remaining[color] > 0;
    }

    public boolean hasRemainingSupply() {
        checkSolving();
        return this.wide ? this.wideGranted.compareTo(this.wideSourceTotal) < 0 : this.granted < this.sourceTotal;
    }

    public void freeze() {
        checkSolving();
        this.frozen = true;
    }

    /** Wide aggregates are exposed only in bounded long fragments to the native-stock commit. */
    public long flowLimit(final int sourceColor, final int targetColor, final long limit) {
        checkFrozen();
        checkColor(sourceColor);
        checkColor(targetColor);
        nonNegative(limit);
        if (sourceColor != targetColor) return 0;
        return this.wide ? this.wideReserved[targetColor].min(BigInteger.valueOf(limit))
            .longValueExact() : Math.min(limit, this.reserved[targetColor]);
    }

    public void consumeFlow(final int sourceColor, final int targetColor, final long net) {
        if (flowLimit(sourceColor, targetColor, net) != net)
            throw new IllegalArgumentException("Fragment exceeds remaining color quota");
        if (this.wide)
            this.wideReserved[targetColor] = this.wideReserved[targetColor].subtract(BigInteger.valueOf(net));
        else this.reserved[targetColor] -= net;
    }

    /** Informational total initially granted, not a balance or remaining energy. */
    public BigInteger totalGranted() {
        if (!this.sealed) throw new IllegalStateException("Sources are not sealed");
        return this.wide ? this.wideGranted : BigInteger.valueOf(this.granted);
    }

    public boolean usesWideArithmetic() {
        return this.wide;
    }

    public long augmentations() {
        return this.augmentations;
    }

    /** Retained diagnostic: isolated pools never traverse graph edges. */
    public long edgeVisits() {
        return 0;
    }

    boolean usesDirectQuotas() {
        return this.sealed;
    }

    private void promote() {
        if (this.wideSources == null) {
            this.wideSources = new BigInteger[COLOR_COUNT];
            this.wideRemaining = new BigInteger[COLOR_COUNT];
            this.wideReserved = new BigInteger[COLOR_COUNT];
        }
        for (int color = 0; color < COLOR_COUNT; color++) {
            this.wideSources[color] = BigInteger.valueOf(this.sources[color]);
            this.wideRemaining[color] = this.wideReserved[color] = BigInteger.ZERO;
        }
        this.wideSourceTotal = BigInteger.valueOf(this.sourceTotal);
        this.wideDemandTotal = BigInteger.valueOf(this.demandTotal);
        this.wide = true;
    }

    private void checkBuilding() {
        if (this.sealed) throw new IllegalStateException("Cannot change the input snapshot after sealing");
    }

    private void checkSolving() {
        if (!this.sealed || this.frozen) throw new IllegalStateException("Quotas are not open for planning");
    }

    private void checkFrozen() {
        if (!this.frozen) throw new IllegalStateException("Final quotas are not frozen");
    }

    private static void checkColor(final int color) {
        if (color < 0 || color >= COLOR_COUNT) throw new IllegalArgumentException("Color must be in [0,16]");
    }

    private static void nonNegative(final long amount) {
        if (amount < 0) throw new IllegalArgumentException("Quota must be nonnegative");
    }
}
