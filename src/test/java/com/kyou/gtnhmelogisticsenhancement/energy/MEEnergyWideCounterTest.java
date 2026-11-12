package com.kyou.gtnhmelogisticsenhancement.energy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.math.BigInteger;
import java.util.Random;

import org.junit.Test;

public class MEEnergyWideCounterTest {

    private static final BigInteger LIMIT = BigInteger.ONE.shiftLeft(128);

    @Test
    public void clearAndUnsignedCarriesPreserveTheExactValue() {
        final MEEnergyWideCounter counter = new MEEnergyWideCounter();
        assertTrue(counter.isZero());
        counter.add(Long.MAX_VALUE);
        counter.add(Long.MAX_VALUE);
        counter.add(2);
        assertFalse(counter.isZero());
        assertEquals(BigInteger.ONE.shiftLeft(64), counter.toBigInteger());
        counter.clear();
        assertTrue(counter.isZero());
        assertEquals(BigInteger.ZERO, counter.toBigInteger());
        final MEEnergyWideCounter highSign = new MEEnergyWideCounter(Long.MAX_VALUE, -1L);
        highSign.add(1);
        assertEquals(BigInteger.ONE.shiftLeft(127), highSign.toBigInteger());
        assertEquals(LIMIT.subtract(BigInteger.ONE), new MEEnergyWideCounter(-1L, -1L).toBigInteger());
    }

    @Test
    public void overflowAndNegativeInputLeaveBothWordsUnchanged() {
        final MEEnergyWideCounter counter = new MEEnergyWideCounter(-1L, -2L);
        final BigInteger before = LIMIT.subtract(BigInteger.valueOf(2));
        assertThrows(ArithmeticException.class, () -> counter.add(2));
        assertThrows(IllegalArgumentException.class, () -> counter.add(-1));
        assertEquals(before, counter.toBigInteger());
        counter.add(1);
        counter.add(0);
        assertEquals(LIMIT.subtract(BigInteger.ONE), counter.toBigInteger());
        assertThrows(ArithmeticException.class, () -> counter.add(1));
        assertEquals(LIMIT.subtract(BigInteger.ONE), counter.toBigInteger());
    }

    @Test
    public void arbitraryUnsignedStatesMatchIndependentBigIntegerArithmetic() {
        final Random random = new Random(0x4d45454e45524759L);
        for (int sample = 0; sample < 256; sample++) {
            BigInteger expected = new BigInteger(128, random);
            final MEEnergyWideCounter counter = new MEEnergyWideCounter(
                expected.shiftRight(64)
                    .longValue(),
                expected.longValue());
            for (int step = 0; step < 16; step++) {
                final long amount = step % 5 == 0 ? Long.MAX_VALUE : random.nextLong() & Long.MAX_VALUE;
                final BigInteger next = expected.add(BigInteger.valueOf(amount));
                if (next.compareTo(LIMIT) >= 0) assertThrows(ArithmeticException.class, () -> counter.add(amount));
                else {
                    counter.add(amount);
                    expected = next;
                }
                assertEquals(expected, counter.toBigInteger());
            }
        }
    }
}
