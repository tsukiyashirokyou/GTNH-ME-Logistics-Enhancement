package com.kyou.gtnhmelogisticsenhancement.transfer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TransferClockTest {

    @Test
    public void defaultPeriodIsExactly1200Ticks() {
        final TransferClock clock = new TransferClock();
        for (int tick = 1; tick < 1200; tick++) {
            assertFalse(clock.tick());
        }
        assertTrue(clock.tick());
        assertFalse(clock.tick());
    }

    @Test
    public void periodChangeResetsCountdownInSeconds() {
        final TransferClock clock = new TransferClock();
        clock.setSeconds(1);
        for (int tick = 1; tick < 20; tick++) {
            assertFalse(clock.tick());
        }
        assertTrue(clock.tick());
    }

    @Test(expected = IllegalArgumentException.class)
    public void zeroIsNotAValidPeriod() {
        new TransferClock().setSeconds(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void excessivePeriodIsRejectedBeforeTickMultiplication() {
        new TransferClock().setSeconds(Integer.MAX_VALUE);
    }
}
