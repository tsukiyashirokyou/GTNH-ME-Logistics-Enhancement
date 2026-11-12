package com.kyou.gtnhmelogisticsenhancement.energy;

import java.math.BigInteger;

/** Main-thread-only unsigned 128-bit telemetry accumulator. Never an EU account. */
public final class MEEnergyWideCounter {

    private long hi;
    private long lo;

    public MEEnergyWideCounter() {}

    MEEnergyWideCounter(final long hi, final long lo) {
        this.hi = hi;
        this.lo = lo;
    }

    public void add(final long amount) {
        if (amount < 0) throw new IllegalArgumentException("Amount must be nonnegative");
        final long next = this.lo + amount;
        final boolean carry = Long.compareUnsigned(next, this.lo) < 0;
        if (carry && this.hi == -1L) throw new ArithmeticException("Telemetry counter overflow");
        this.lo = next;
        if (carry) this.hi++;
    }

    public void clear() {
        this.hi = this.lo = 0;
    }

    public boolean isZero() {
        return this.hi == 0 && this.lo == 0;
    }

    public BigInteger toBigInteger() {
        return unsigned(this.hi).shiftLeft(64)
            .add(unsigned(this.lo));
    }

    private static BigInteger unsigned(final long value) {
        return value >= 0 ? BigInteger.valueOf(value)
            : BigInteger.valueOf(value & Long.MAX_VALUE)
                .setBit(63);
    }
}
