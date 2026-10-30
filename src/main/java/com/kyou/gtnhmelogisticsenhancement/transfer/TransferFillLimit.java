package com.kyou.gtnhmelogisticsenhancement.transfer;

/** Pure per-identity arithmetic. No world, inventory or energy operations. */
public final class TransferFillLimit {

    public static final int DEFAULT_PERCENT = 100;

    private TransferFillLimit() {}

    public static int validate(final int percent) {
        if (percent < 0 || percent > 100) {
            throw new IllegalArgumentException("Fill percentage must be between 0 and 100");
        }
        return percent;
    }

    /** floor((stored+remaining)*percent/100)-stored, without overflowing the long sum or products. */
    public static long allowance(final long stored, final long remaining, final int percent) {
        validate(percent);
        if (stored < 0 || remaining < 0) {
            throw new IllegalArgumentException("Negative target storage quantity");
        }
        if (percent == 100) return remaining;
        if (percent == 0) return 0;
        final int reserve = 100 - percent;
        final long positive = remaining / 100 * percent + remaining % 100 * percent / 100;
        final long negative = stored / 100 * reserve + stored % 100 * reserve / 100;
        final int fractions = (int) (remaining % 100 * percent % 100 - stored % 100 * reserve % 100);
        return Math.max(0, positive - negative + Math.floorDiv(fractions, 100));
    }
}
