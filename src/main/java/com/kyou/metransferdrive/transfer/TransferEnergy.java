package com.kyou.metransferdrive.transfer;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Native int-backed external storage and AE double energy boundaries for one transfer. */
public final class TransferEnergy {

    /** AE item adapters convert the offered stack to an int ItemStack before reporting its remainder. */
    public static final long MAX_AMOUNT = Integer.MAX_VALUE;

    @FunctionalInterface
    public interface Source {

        double extract(double amount, boolean simulate);
    }

    private TransferEnergy() {}

    /** Legacy prepayment calculation retained for regression comparisons; production uses TransferEnergyAccount. */
    public static long reserve(final long amount, final int amountPerUnit, final double multiplier,
        final Source energy) {
        if (amount < 0 || amountPerUnit <= 0 || !Double.isFinite(multiplier) || multiplier <= 0 || energy == null) {
            throw new IllegalArgumentException("Invalid transfer energy request");
        }
        if (amount == 0) {
            return 0;
        }

        long bounded = Math.min(amount, MAX_AMOUNT);
        long units = unitsFor(bounded, amountPerUnit);
        // AE's CONFIG path multiplies the request before accessing the real energy providers.
        units = (long) Math.min(units, Math.floor(Double.MAX_VALUE / multiplier));
        if (units > 0 && !Double.isFinite(units * multiplier)) {
            // The quotient above can round up at the largest finite double boundary.
            units--;
        }
        if (units <= 0) {
            return 0;
        }
        bounded = Math.min(bounded, units * amountPerUnit);
        final double request = unitsFor(bounded, amountPerUnit);
        final double available = checked(energy.extract(request, true), request);
        final long affordable = affordable(available, amountPerUnit, bounded);
        if (affordable == 0) {
            return 0;
        }

        final double debit = Math.min(available, unitsFor(affordable, amountPerUnit));
        if (debit * multiplier <= 0) {
            // A fractional request can underflow after CONFIG scaling even when the whole-AE simulation did not.
            return 0;
        }
        final double paid = checked(energy.extract(debit, false), debit);
        return affordable(paid, amountPerUnit, affordable);
    }

    /** Positive quantities use quotient/remainder instead of the overflowing amount + unit - 1 expression. */
    static long unitsFor(final long amount, final int amountPerUnit) {
        if (amount < 0 || amountPerUnit <= 0) {
            throw new IllegalArgumentException("Invalid transfer energy units");
        }
        return amount / amountPerUnit + (amount % amountPerUnit == 0 ? 0 : 1);
    }

    private static double checked(final double returned, final double requested) {
        if (!Double.isFinite(returned) || returned < 0 || returned > requested) {
            throw new IllegalStateException("AE energy returned an invalid amount");
        }
        return returned;
    }

    private static long affordable(final double power, final int amountPerUnit, final long maximum) {
        if (power == Math.rint(power)) {
            // Requests are at most MAX_AMOUNT; the product of two positive int-sized values fits a long.
            return Math.min(maximum, (long) power * amountPerUnit);
        }
        // Multiplying doubles can round a value just below the next native unit up to that integer.
        // Only fractional energy uses this exact conversion; ordinary whole-AE reservations allocate no decimals.
        final long supported = new BigDecimal(power).multiply(BigDecimal.valueOf(amountPerUnit))
            .longValue();
        return Math.min(maximum, supported);
    }

    /** Physical AE, rounded upwards so the reservation covers the configured nominal charge. */
    static double charge(final long count, final int unit, final double multiplier) {
        if (count < 0 || count > MAX_AMOUNT || unit <= 0 || !Double.isFinite(multiplier) || multiplier <= 0) {
            throw new IllegalArgumentException("Invalid settlement quantity or multiplier");
        }
        final long units = unitsFor(count, unit);
        if (multiplier == 1.0) {
            return units;
        }
        final BigDecimal exact = new BigDecimal(multiplier).multiply(BigDecimal.valueOf(units));
        double value = exact.doubleValue();
        if (Double.isFinite(value) && new BigDecimal(value).compareTo(exact) < 0) {
            value = Math.nextUp(value);
        }
        return value;
    }

    /** Only fully paid nominal AE units authorize extraction; a partial mB batch still costs ceil(mB/1000). */
    static long authorized(final double physical, final int unit, final double multiplier, final long maximum) {
        if (!Double.isFinite(physical) || physical < 0
            || unit <= 0
            || !Double.isFinite(multiplier)
            || multiplier <= 0) {
            throw new IllegalArgumentException("Invalid physical energy authorization");
        }
        final long bounded = Math.min(maximum, MAX_AMOUNT);
        final long units = unitsFor(bounded, unit);
        final long paidUnits;
        if (multiplier == 1.0) {
            paidUnits = (long) Math.min(units, Math.floor(physical));
        } else {
            paidUnits = new BigDecimal(physical).divide(new BigDecimal(multiplier), 0, RoundingMode.DOWN)
                .min(BigDecimal.valueOf(units))
                .longValueExact();
        }
        return Math.min(bounded, paidUnits * unit);
    }

    static double difference(final double larger, final double smaller) {
        final BigDecimal exact = new BigDecimal(larger).subtract(new BigDecimal(smaller));
        if (exact.signum() < 0) {
            throw new IllegalStateException("Energy charge exceeds prepayment");
        }
        double value = exact.doubleValue();
        if (new BigDecimal(value).compareTo(exact) > 0) {
            value = Math.nextDown(value);
        }
        return value;
    }
}
