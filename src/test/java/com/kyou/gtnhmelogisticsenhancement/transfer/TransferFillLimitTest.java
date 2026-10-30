package com.kyou.gtnhmelogisticsenhancement.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.math.BigInteger;
import java.util.Random;

import org.junit.Test;

public class TransferFillLimitTest {

    @Test
    public void percentCeilingCountsExistingStockRatherThanOnlyFreeSpace() {
        assertEquals(100, TransferFillLimit.DEFAULT_PERCENT);
        assertEquals(800, TransferFillLimit.allowance(0, 1000, 80));
        assertEquals(450, TransferFillLimit.allowance(350, 650, 80));
        assertEquals(0, TransferFillLimit.allowance(800, 200, 80));
        assertEquals(0, TransferFillLimit.allowance(900, 100, 80));
        assertEquals(200, TransferFillLimit.allowance(800, 200, 100));
        assertEquals(0, TransferFillLimit.allowance(0, Long.MAX_VALUE, 0));
    }

    @Test
    public void roundsDownWithoutLosingRemainderFractionsOrOverflowingLong() {
        for (final long stored : new long[] { 0, 1, 99, 100, 101, Integer.MAX_VALUE, Long.MAX_VALUE - 1,
            Long.MAX_VALUE }) {
            for (final long remaining : new long[] { 0, 1, 99, 101, Integer.MAX_VALUE, Long.MAX_VALUE }) {
                for (int percent = 0; percent <= 100; percent++) {
                    assertEquals(
                        reference(stored, remaining, percent),
                        TransferFillLimit.allowance(stored, remaining, percent));
                }
            }
        }
    }

    @Test
    public void randomLongValuesMatchExactIntegerArithmetic() {
        final Random random = new Random(281944);
        for (int sample = 0; sample < 10000; sample++) {
            final long stored = random.nextLong() & Long.MAX_VALUE;
            final long remaining = random.nextLong() & Long.MAX_VALUE;
            final int percent = random.nextInt(101);
            final long permitted = TransferFillLimit.allowance(stored, remaining, percent);
            assertEquals(reference(stored, remaining, percent), permitted);
            assertTrue(permitted >= 0 && permitted <= remaining);
        }
    }

    @Test
    public void invalidQuantitiesAndConfigurationAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> TransferFillLimit.allowance(-1, 100, 80));
        assertThrows(IllegalArgumentException.class, () -> TransferFillLimit.allowance(0, -1, 80));
        assertThrows(IllegalArgumentException.class, () -> TransferFillLimit.validate(-1));
        assertThrows(IllegalArgumentException.class, () -> TransferFillLimit.validate(101));
    }

    private static long reference(final long stored, final long remaining, final int percent) {
        return BigInteger.valueOf(stored)
            .add(BigInteger.valueOf(remaining))
            .multiply(BigInteger.valueOf(percent))
            .divide(BigInteger.valueOf(100))
            .subtract(BigInteger.valueOf(stored))
            .max(BigInteger.ZERO)
            .longValueExact();
    }
}
