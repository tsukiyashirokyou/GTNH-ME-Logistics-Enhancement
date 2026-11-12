package com.kyou.gtnhmelogisticsenhancement.energy;

import java.math.BigInteger;
import java.util.Arrays;

/** Immutable sampled stock/count data. The last two amounts encode a completed transfer window. */
public final class MEEnergyMonitorRow {

    public static final int SOURCE_STOCK = 0, SOURCE_CAPACITY = 1, TARGET_STOCK = 2, TARGET_CAPACITY = 3;
    public static final int SOURCE_LIMIT = 4, ONLINE_LIMIT = 5, TARGET_LIMIT = 6, GROSS = 7, NET = 8;
    public static final MEEnergyMonitorRow EMPTY;

    static {
        final BigInteger[] zero = new BigInteger[9];
        Arrays.fill(zero, BigInteger.ZERO);
        EMPTY = new MEEnergyMonitorRow(new int[6], zero);
    }

    private final int[] counts;
    private final BigInteger[] amounts;

    MEEnergyMonitorRow(final int[] counts, final BigInteger[] amounts) {
        this.counts = counts.clone();
        this.amounts = amounts.clone();
    }

    public int sources() {
        return this.counts[0];
    }

    public int onlineSources() {
        return this.counts[1];
    }

    public int targets() {
        return this.counts[2];
    }

    public int onlineTargets() {
        return this.counts[3];
    }

    public int sleepingTargets() {
        return this.counts[4];
    }

    public int invalidStocks() {
        return this.counts[5];
    }

    public BigInteger amount(final int index) {
        return this.amounts[index];
    }

    String encode(final MEEnergyMonitorWindow.Value flow) {
        final StringBuilder output = new StringBuilder(192);
        for (final int count : this.counts) output.append(count)
            .append(';');
        for (int index = 0; index < 7; index++) output.append(this.amounts[index])
            .append(';');
        return output.append(flow.gross)
            .append(';')
            .append(flow.net)
            .toString();
    }

    /** Bounded server-originated payload; malformed data never triggers unbounded integer parsing. */
    public static MEEnergyMonitorRow decode(final String encoded) {
        if (encoded == null || encoded.length() > 512) return EMPTY;
        final String[] fields = encoded.split(";", -1);
        if (fields.length != 15) return EMPTY;
        try {
            final int[] counts = new int[6];
            final BigInteger[] amounts = new BigInteger[9];
            for (int index = 0; index < fields.length; index++) {
                final String value = fields[index];
                if (value.isEmpty() || value.length() > 39) return EMPTY;
                for (int digit = 0; digit < value.length(); digit++)
                    if (value.charAt(digit) < '0' || value.charAt(digit) > '9') return EMPTY;
                if (index < 6) counts[index] = Integer.parseInt(value);
                else {
                    amounts[index - 6] = new BigInteger(value);
                    if (amounts[index - 6].bitLength() > 128) return EMPTY;
                }
            }
            if (counts[1] > counts[0] || counts[3] > counts[2]
                || counts[4] > counts[2]
                || counts[5] > (long) counts[0] + counts[2]
                || amounts[NET].compareTo(amounts[GROSS]) > 0) return EMPTY;
            return new MEEnergyMonitorRow(counts, amounts);
        } catch (final NumberFormatException invalid) {
            return EMPTY;
        }
    }
}
