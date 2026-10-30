package com.kyou.gtnhmelogisticsenhancement.distributed;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Main-thread physical-AE allowances. No power is extracted, stored or refunded by this ledger. */
final class DistributedEnergyBudget {

    interface Power {

        boolean valid();

        boolean infinite();

        double stored();

        double capacity();

        double idle();

        double available(double physicalRequest);
    }

    private final Map<Object, Double> remaining = new IdentityHashMap<>();
    private final Set<Object> probing = Collections.newSetFromMap(new IdentityHashMap<>());

    void clear() {
        this.remaining.clear();
        this.probing.clear();
    }

    long authorize(final Object grid, final Power power, final long requested, final int unit,
        final double multiplier) {
        if (grid == null || requested < 1
            || requested > Integer.MAX_VALUE
            || unit < 1
            || !Double.isFinite(multiplier)
            || multiplier <= 0) return 0;
        final Double existing = this.remaining.get(grid);
        if (existing != null && existing < multiplier || !this.probing.add(grid)) return 0;
        try {
            if (!power.valid()) return 0;
            final double stored = power.stored(), capacity = power.capacity(), idle = power.idle();
            final boolean infinite = power.infinite();
            if (!metric(idle) || Double.isNaN(stored)
                || stored < 0
                || Double.isNaN(capacity)
                || capacity < 0
                || (!infinite && (!metric(stored) || !metric(capacity)))) return 0;
            // Statistics are estimates. A bounded real simulation below also sees overflow/external providers.
            final double reserve = metric(capacity) && capacity > 0
                ? Math.max(capacity * 0.2, Math.min(saturatingProduct(idle, 100), capacity * 0.5))
                : saturatingProduct(idle, 100);
            final double fullCharge = charge(requested, unit, multiplier);
            final double desired = existing == null ? fullCharge : Math.min(existing, fullCharge);
            final double probe = sum(
                reserve,
                existing == null ? Math.max(desired, metric(stored) ? stored : 0) : desired);
            final double available = power.available(probe);
            if (!metric(available) || available > probe || !power.valid()) {
                this.remaining.put(grid, 0.0);
                return 0;
            }
            final double margin = available <= reserve ? 0 : difference(available, reserve);
            // At most 10% per Grid/Tick, except one CONFIG-priced unit for otherwise safe tiny networks.
            final double single = Math.min(margin, Math.max(multiplier, available * 0.1));
            final double allowance = existing == null ? single : Math.min(existing, margin);
            final long units = requested / unit + (requested % unit == 0 ? 0 : 1);
            final long paidUnits = new BigDecimal(allowance).divide(new BigDecimal(multiplier), 0, RoundingMode.DOWN)
                .min(BigDecimal.valueOf(units))
                .longValueExact();
            final long amount = Math.min(requested, paidUnits * unit);
            final double cost = amount == 0 ? 0 : charge(amount, unit, multiplier);
            // Gross claims stay spent for this Tick, even on rejection, preventing synchronous quota recycling.
            this.remaining.put(grid, difference(existing == null ? single : existing, cost));
            return amount;
        } finally {
            this.probing.remove(grid);
        }
    }

    private static boolean metric(final double value) {
        return Double.isFinite(value) && value >= 0;
    }

    private static double charge(final long count, final int unit, final double multiplier) {
        final long units = count / unit + (count % unit == 0 ? 0 : 1);
        final BigDecimal exact = new BigDecimal(multiplier).multiply(BigDecimal.valueOf(units));
        double rounded = exact.doubleValue();
        if (!Double.isFinite(rounded)) return Double.MAX_VALUE;
        if (new BigDecimal(rounded).compareTo(exact) < 0) rounded = Math.nextUp(rounded);
        return rounded;
    }

    private static double difference(final double larger, final double smaller) {
        final BigDecimal exact = new BigDecimal(larger).subtract(new BigDecimal(smaller));
        if (exact.signum() < 0) throw new IllegalStateException("Energy budget exceeded");
        double rounded = exact.doubleValue();
        if (new BigDecimal(rounded).compareTo(exact) > 0) rounded = Math.nextDown(rounded);
        return rounded;
    }

    private static double saturatingProduct(final double a, final double b) {
        final double result = a * b;
        return Double.isFinite(result) ? result : Double.MAX_VALUE;
    }

    private static double sum(final double a, final double b) {
        return a > Double.MAX_VALUE - b ? Double.MAX_VALUE : a + b;
    }
}
