package com.kyou.gtnhmelogisticsenhancement.transfer;

import java.util.IdentityHashMap;

/** Physical-AE allowances shared by actual source Grid identity, only for the current server work Tick. */
final class TransferPowerBudget {

    static final TransferPowerBudget SERVER = new TransferPowerBudget();
    private final IdentityHashMap<Object, Double> remaining = new IdentityHashMap<>();

    void clear() {
        this.remaining.clear();
    }

    double allowance(final Object grid, final TransferEnergyAccount.Port port, final double multiplier) {
        final Double existing = this.remaining.get(grid);
        if (existing != null && existing <= 0 || !port.valid()) {
            return 0;
        }
        final boolean infinite = port.infinite();
        final double stored = port.stored();
        final double capacity = port.capacity();
        final double idle = port.idle();
        if (!validMetric(idle) || Double.isNaN(stored)
            || stored < 0
            || Double.isNaN(capacity)
            || capacity < 0
            || (!infinite && (!validMetric(stored) || !validMetric(capacity)))) {
            return 0;
        }
        final double reserve = infinite ? 0
            : capacity > 0 ? Math.max(capacity * 0.2, Math.min(product(idle, 100), capacity * 0.5))
                : product(idle, 100);
        double maximum = TransferEnergy.charge(TransferEnergy.MAX_AMOUNT, 1, multiplier);
        if (!Double.isFinite(maximum)) {
            maximum = Double.MAX_VALUE;
        }
        // A finite, saturating probe also sees native overflow and external/wireless energy providers.
        final double probe = sum(reserve, Math.max(maximum, validMetric(stored) ? stored : 0));
        final double available = checked(port.extract(probe, true), probe);
        if (!port.valid()) {
            return 0;
        }
        final double margin = available <= reserve ? 0 : TransferEnergy.difference(available, reserve);
        // One nominal AE minimum avoids percentage rounding permanently starving very small, otherwise safe grids.
        final double single = infinite ? Math.min(maximum, available)
            : Math.min(margin, Math.max(multiplier, available * 0.1));
        final Double current = this.remaining.get(grid);
        if (current == null) {
            this.remaining.put(grid, single);
            return single;
        }
        return Math.min(current, single);
    }

    void reserve(final Object grid, final double physical) {
        final Double available = this.remaining.get(grid);
        if (available == null || !Double.isFinite(physical) || physical < 0 || physical > available) {
            throw new IllegalStateException("Invalid shared transfer energy reservation");
        }
        // Gross reservations consume the Tick quota even if later refunded. Rejection cannot recycle the quota.
        this.remaining.put(grid, TransferEnergy.difference(available, physical));
    }

    static double checked(final double value, final double maximum) {
        if (!Double.isFinite(value) || value < 0 || value > maximum) {
            throw new IllegalStateException("Invalid native energy result");
        }
        return value;
    }

    private static boolean validMetric(final double value) {
        return Double.isFinite(value) && value >= 0;
    }

    private static double product(final double left, final double right) {
        final double value = left * right;
        return Double.isFinite(value) ? value : Double.MAX_VALUE;
    }

    private static double sum(final double left, final double right) {
        return left > Double.MAX_VALUE - right ? Double.MAX_VALUE : left + right;
    }
}
