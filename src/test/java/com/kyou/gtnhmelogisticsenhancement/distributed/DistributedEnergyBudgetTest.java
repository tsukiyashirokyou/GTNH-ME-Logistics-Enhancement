package com.kyou.gtnhmelogisticsenhancement.distributed;

import static org.junit.Assert.*;

import org.junit.Test;

/** Actual allowance ledger with a deterministic physical-AE source; no world/performance measurement. */
public class DistributedEnergyBudgetTest {

    @Test
    public void oneGridSharesTenPercentAcrossMachinesAndStorageTypes() {
        final Fixture f = new Fixture();
        assertEquals(60, f.authorize(60, 1, 1));
        f.power.stored -= 60;
        assertEquals(40000, f.authorize(1000000, 1000, 1));
        f.power.stored -= 40;
        final int probes = f.power.probes;
        for (int i = 0; i < 32; i++) assertEquals(0, f.authorize(Integer.MAX_VALUE, 1, 1));
        assertEquals(probes, f.power.probes);
        f.budget.clear();
        assertEquals(90, f.authorize(Integer.MAX_VALUE, 1, 1));
    }

    @Test
    public void reserveFloorStopsAndResumesNextTickAfterRecharge() {
        final Fixture f = new Fixture();
        f.power.stored = 210;
        assertEquals(10, f.authorize(100000000, 1, 1));
        f.power.stored = 200;
        f.budget.clear();
        assertEquals(0, f.authorize(100000000, 1, 1));
        f.power.stored = 1000;
        assertEquals(0, f.authorize(100000000, 1, 1));
        f.budget.clear();
        assertEquals(100, f.authorize(100000000, 1, 1));
    }

    @Test
    public void fiveSecondIdleReserveAndTinyGridMinimumAreBounded() {
        final Fixture f = new Fixture();
        f.power.idle = 4;
        f.power.stored = 405;
        assertEquals(5, f.authorize(1000, 1, 1));
        f.budget.clear();
        f.power.idle = 1000;
        f.power.stored = 501;
        assertEquals(1, f.authorize(1000, 1, 1));
        f.budget.clear();
        f.power.capacity = f.power.stored = 5;
        f.power.idle = 2;
        assertEquals(1, f.authorize(1000, 1, 1));
    }

    @Test
    public void rejectedOutputCannotRecycleGrossAllowance() {
        final Fixture f = new Fixture();
        assertEquals(100, f.authorize(1000, 1, 1));
        assertEquals(1000, f.power.stored, 0);
        assertEquals(0, f.authorize(1000, 1, 1));
        f.budget.clear();
        assertEquals(100, f.authorize(1000, 1, 1));
    }

    @Test
    public void realSimulationOverridesStaleStatisticsAndSeesExternalPower() {
        final Fixture f = new Fixture();
        f.power.stored = 1000000;
        f.power.reachable = 210;
        assertEquals(10, f.authorize(Integer.MAX_VALUE, 1, 1));
        f.budget.clear();
        f.power.stored = f.power.capacity = 0;
        f.power.reachable = 1000;
        f.power.idle = 2;
        assertEquals(100, f.authorize(Integer.MAX_VALUE, 1, 1));
    }

    @Test
    public void wholeNativeUnitsAndConfigMultiplierRespectAllowance() {
        final Fixture f = new Fixture();
        assertEquals(40, f.authorize(100000000, 1, 2.5));
        f.budget.clear();
        assertEquals(40000, f.authorize(100000000, 1000, 2.5));
        f.budget.clear();
        // Binary double 0.01 is slightly greater than exact 1/100. Never round authorization upwards.
        final long decimalAuthorized = f.authorize(100000000, 1, 0.01);
        assertEquals(9999, decimalAuthorized);
        assertTrue(
            new java.math.BigDecimal(0.01).multiply(java.math.BigDecimal.valueOf(decimalAuthorized))
                .compareTo(java.math.BigDecimal.valueOf(100)) <= 0);
        f.budget.clear();
        f.power.stored = 200.5;
        assertEquals(0, f.authorize(1, 1000, 1));
        f.budget.clear();
        assertEquals(0, f.authorize(1, 1, Double.NaN));
        assertEquals(0, f.authorize(1, 1, Double.POSITIVE_INFINITY));
    }

    @Test
    public void gridIdentityUsesReferenceEqualityAndRenewsEachTick() {
        final Fixture f = new Fixture();
        final String a = new String("same"), b = new String("same");
        assertEquals(100, f.budget.authorize(a, f.power, 1000, 1, 1));
        assertEquals(100, f.budget.authorize(b, f.power, 1000, 1, 1));
        assertEquals(0, f.budget.authorize(a, f.power, 1000, 1, 1));
        f.budget.clear();
        assertEquals(100, f.budget.authorize(a, f.power, 1000, 1, 1));
    }

    @Test
    public void invalidationAndSimulationReentryCannotClaimAnotherAllowance() {
        final Fixture f = new Fixture();
        f.power.duringProbe = () -> assertEquals(0, f.authorize(1000, 1, 1));
        assertEquals(100, f.authorize(1000, 1, 1));
        f.budget.clear();
        f.power.duringProbe = () -> f.power.valid = false;
        assertEquals(0, f.authorize(1000, 1, 1));
    }

    @Test
    public void infinityAndNumericBoundariesAlwaysUseFiniteProbes() {
        final Fixture f = new Fixture();
        f.power.infinite = true;
        f.power.stored = f.power.capacity = f.power.reachable = Double.POSITIVE_INFINITY;
        final long authorized = f.authorize(Integer.MAX_VALUE, 1, 1);
        assertTrue(authorized > 0 && authorized <= Integer.MAX_VALUE);
        f.budget.clear();
        assertEquals(0, f.authorize((long) Integer.MAX_VALUE + 1, 1, 1));
        f.power.infinite = false;
        assertEquals(0, f.authorize(Integer.MAX_VALUE, 1, 1));
    }

    private static final class Fixture {

        final DistributedEnergyBudget budget = new DistributedEnergyBudget();
        final Object grid = new Object();
        final Power power = new Power();

        long authorize(final long amount, final int unit, final double multiplier) {
            return this.budget.authorize(this.grid, this.power, amount, unit, multiplier);
        }
    }

    private static final class Power implements DistributedEnergyBudget.Power {

        double stored = 1000, capacity = 1000, idle;
        double reachable = Double.NaN;
        boolean valid = true, infinite;
        int probes;
        Runnable duringProbe;

        @Override
        public boolean valid() {
            return this.valid;
        }

        @Override
        public boolean infinite() {
            return this.infinite;
        }

        @Override
        public double stored() {
            return this.stored;
        }

        @Override
        public double capacity() {
            return this.capacity;
        }

        @Override
        public double idle() {
            return this.idle;
        }

        @Override
        public double available(final double request) {
            assertTrue(Double.isFinite(request) && request > 0);
            this.probes++;
            if (this.duringProbe != null) this.duringProbe.run();
            return Math.min(Double.isNaN(this.reachable) ? this.stored : this.reachable, request);
        }
    }
}
