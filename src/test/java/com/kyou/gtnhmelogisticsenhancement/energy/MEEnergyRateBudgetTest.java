package com.kyou.gtnhmelogisticsenhancement.energy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Random;

import org.junit.Test;

public class MEEnergyRateBudgetTest {

    @Test
    public void pauseFirstSyncsEarnedTicksAndNeverEarnsOfflinePermission() {
        final MEEnergyRateBudget budget = new MEEnergyRateBudget(1, 24);
        budget.setActive(true, 0);
        budget.setActive(false, 19);
        assertEquals(19, budget.tokens(1000));
        assertEquals(0, budget.availableGross(24, 0, 1000));
        assertFalse(budget.spend(1, 1000));
        budget.setActive(true, 1000);
        assertEquals(20, budget.tokens(1001));
        assertTrue(budget.spend(20, 1001));
        assertFalse(budget.spend(1, 1001));
        assertEquals(0, budget.tokens(1001));
    }

    @Test
    public void hugeElapsedTimeSaturatesBeforeMultiplicationAndResetDropsOnlyPermission() {
        final long rate = 9007199254740992L;
        final MEEnergyRateBudget budget = new MEEnergyRateBudget(rate, rate * 24);
        budget.setActive(true, 0);
        assertEquals(rate * 24, budget.tokens(Long.MAX_VALUE));
        assertTrue(budget.spend(rate * 24, Long.MAX_VALUE));
        assertEquals(0, budget.tokens(Long.MAX_VALUE));
        budget.reset(0); // A new lifetime may use a fresh logical clock origin.
        assertFalse(budget.isActive());
        budget.setActive(true, 10);
        assertEquals(0, budget.tokens(10));
        assertEquals(rate, budget.tokens(11));
        final MEEnergyRateBudget small = new MEEnergyRateBudget(100, 3);
        small.setActive(true, 0);
        assertEquals(3, small.tokens(1));
    }

    @Test
    public void actualGrossCostAndNativeReserveLimitTheSource() {
        final MEEnergyRateBudget budget = new MEEnergyRateBudget(8192, 24 * 8192);
        budget.setActive(true, 0);
        assertEquals(24 * 8192 - 512, budget.availableGross(Long.MAX_VALUE, 512, 24));
        assertEquals(0, budget.availableGross(511, 512, 24));
        assertEquals(0, budget.availableGross(100, Long.MAX_VALUE, 24));
        final MEEnergyLossAccount loss = new MEEnergyLossAccount();
        final long available = budget.availableGross(10000, 512, 24);
        final long net = loss.maximumNet(available);
        final long cost = loss.grossCost(net);
        assertTrue(cost > net);
        final long before = budget.tokens(24);
        assertTrue(budget.spend(cost, 24));
        assertEquals(before - cost, budget.tokens(24));
        assertFalse(budget.spend(before, 24));
    }

    @Test
    public void randomSmallIntervalsMatchAnIndependentPerTickBucket() {
        final Random random = new Random(24020);
        final MEEnergyRateBudget budget = new MEEnergyRateBudget(7, 168);
        long now = 0;
        long reference = 0;
        boolean active = false;
        for (int step = 0; step < 5000; step++) {
            final int elapsed = random.nextInt(31);
            for (int tick = 0; tick < elapsed; tick++) {
                if (active) reference = Math.min(168, reference + 7);
            }
            now += elapsed;
            if (random.nextBoolean()) {
                active = random.nextBoolean();
                budget.setActive(active, now);
            } else {
                final long requested = random.nextInt(200);
                final boolean expected = active && requested <= reference;
                assertEquals(expected, budget.spend(requested, now));
                if (expected) reference -= requested;
            }
            assertEquals(reference, budget.tokens(now));
        }
    }

    @Test
    public void invalidCallsDoNotChangeTheClockOrTokens() {
        assertThrows(IllegalArgumentException.class, () -> new MEEnergyRateBudget(0, 24));
        assertThrows(IllegalArgumentException.class, () -> new MEEnergyRateBudget(1, -1));
        final MEEnergyRateBudget budget = new MEEnergyRateBudget(1, 24);
        budget.setActive(true, 10);
        assertEquals(2, budget.tokens(12));
        assertThrows(IllegalArgumentException.class, () -> budget.setActive(false, 11));
        assertThrows(IllegalArgumentException.class, () -> budget.spend(-1, 12));
        assertThrows(IllegalArgumentException.class, () -> budget.availableGross(-1, 0, 12));
        assertThrows(IllegalArgumentException.class, () -> budget.availableGross(1, -1, 12));
        assertThrows(IllegalArgumentException.class, () -> budget.reset(-1));
        assertEquals(2, budget.tokens(12));
        assertTrue(budget.isActive());
    }
}
