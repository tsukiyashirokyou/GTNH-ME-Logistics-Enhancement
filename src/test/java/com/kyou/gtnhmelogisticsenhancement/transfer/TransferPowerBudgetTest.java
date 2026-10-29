package com.kyou.gtnhmelogisticsenhancement.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Exercises the actual shared allowance/ledger, with a deterministic native energy contract. */
public class TransferPowerBudgetTest {

    @Test
    public void oneGridSharesGrossTickQuotaAcrossDevicesAndTransactions() {
        final Fixture f = new Fixture();
        assertEquals(60, f.reserve(60));
        f.account.settle(60, true);
        final TransferEnergyAccount other = new TransferEnergyAccount(() -> {}, f.budget);
        assertEquals(
            40,
            other.bind(f.grid, f.power, 1, 1)
                .reserve(100));
        other.settle(40, true);
        assertEquals(0, f.reserve(100));
        assertEquals(100, f.power.debited, 0);
        assertEquals(900, f.power.power, 0);
        assertTrue(f.account.isDeferred());
    }

    @Test
    public void refundsDoNotRecycleTheSharedQuotaWithinTheSameTick() {
        final Fixture f = new Fixture();
        assertEquals(100, f.reserve(1000));
        f.account.settle(0, true);
        assertEquals(1000, f.power.power, 0);
        final int probes = f.power.simulationCalls;
        for (int attempt = 0; attempt < 100; attempt++) {
            assertEquals(0, f.reserve(1000));
        }
        assertEquals(probes, f.power.simulationCalls);
        assertEquals(1, f.power.debitCalls);
        f.budget.clear();
        assertEquals(100, f.reserve(1000));
        f.account.settle(100, true);
        assertEquals(900, f.power.power, 0);
    }

    @Test
    public void reserveFloorStopsAtTwentyPercentAndResumesAfterRecharge() {
        final Fixture f = new Fixture();
        f.power.power = 210;
        assertEquals(10, f.reserve(1_000_000));
        f.account.settle(10, true);
        assertEquals(200, f.power.power, 0);
        f.budget.clear();
        assertEquals(0, f.reserve(1_000_000));
        f.power.power = 199;
        assertEquals(0, f.reserve(1_000_000));
        f.power.power = 1000;
        // A Tick that started blocked does not refill its allowance halfway through the Tick.
        assertEquals(0, f.reserve(1_000_000));
        f.budget.clear();
        assertEquals(100, f.reserve(1_000_000));
        f.account.settle(100, true);
        assertFalse(f.account.isPending());
    }

    @Test
    public void fiveSecondsIdleReserveIsCappedAtHalfLocalCapacity() {
        final Fixture f = new Fixture();
        f.power.idle = 4;
        f.power.power = 405;
        assertEquals(5, f.reserve(1000));
        f.account.settle(5, true);
        assertEquals(400, f.power.power, 0);
        f.budget.clear();
        f.power.idle = 1000;
        f.power.power = 501;
        assertEquals(1, f.reserve(1000));
        f.account.settle(1, true);
        assertEquals(500, f.power.power, 0);
    }

    @Test
    public void smallGridCanPayOneUnitWithoutCrossingItsReserve() {
        final Fixture f = new Fixture();
        f.power.power = f.power.capacity = 5;
        f.power.idle = 2;
        assertEquals(1, f.reserve(1000));
        f.account.settle(1, true);
        assertEquals(4, f.power.power, 0);
        assertEquals(0, f.reserve(1000));
        f.budget.clear();
        f.power.power = 3;
        assertEquals(0, f.reserve(1000));
        assertEquals(1, f.power.debitCalls);
    }

    @Test
    public void actualSimulationFindsExternalPowerWithNoLocalStoredPower() {
        final Fixture f = new Fixture();
        f.power.storedOverride = 0;
        f.power.capacity = 0;
        f.power.idle = 2;
        assertEquals(100, f.reserve(1000));
        f.account.settle(100, true);
        assertEquals(900, f.power.power, 0);
    }

    @Test
    public void staleOptimisticStatisticsCannotReplaceRealEnergySimulation() {
        final Fixture f = new Fixture();
        f.power.storedOverride = 1_000_000;
        f.power.power = 210;
        assertEquals(10, f.reserve(1_000_000));
        f.account.settle(10, true);
        assertEquals(200, f.power.power, 0);
    }

    @Test
    public void independentActualGridsHaveIndependentAllowances() {
        final Fixture f = new Fixture();
        assertEquals(100, f.reserve(1000));
        f.account.settle(100, true);
        final EnergySettlementTest.Power secondPower = new EnergySettlementTest.Power();
        secondPower.power = secondPower.capacity = 1000;
        final TransferEnergyAccount second = new TransferEnergyAccount(() -> {}, f.budget);
        assertEquals(
            100,
            second.bind(new Object(), secondPower, 1, 1)
                .reserve(1000));
        second.settle(100, true);
        assertEquals(100, secondPower.debited, 0);
    }

    @Test
    public void gridIdentityUsesReferenceIdentityRatherThanEqualsOrDimension() {
        final Fixture f = new Fixture();
        final String first = new String("same hash and equality");
        final String second = new String("same hash and equality");
        assertEquals(
            100,
            f.account.bind(first, f.power, 1, 1)
                .reserve(1000));
        f.account.settle(100, true);
        assertEquals(
            90,
            f.account.bind(second, f.power, 1, 1)
                .reserve(1000));
        f.account.settle(90, true);
        assertEquals(190, f.power.debited, 0);
    }

    @Test
    public void invalidatedSourceCannotReserveOrRedirectARefund() {
        final Fixture f = new Fixture();
        f.power.valid = false;
        assertEquals(0, f.reserve(1000));
        assertEquals(0, f.power.debitCalls);
        assertEquals(0, f.power.simulationCalls);
        f.power.valid = true;
        assertEquals(100, f.reserve(1000));
        f.power.valid = false;
        assertThrows(IllegalStateException.class, () -> f.account.settle(30, true));
        assertTrue(f.account.isPending());
        assertEquals(0, f.power.refundCalls);
        assertThrows(IllegalStateException.class, () -> f.account.bind(new Object(), f.power, 1, 1));
    }

    @Test
    public void infiniteFlagStillUsesFiniteBoundedNativeAuthorization() {
        final Fixture f = new Fixture();
        f.power.infinite = true;
        f.power.power = f.power.capacity = Double.POSITIVE_INFINITY;
        assertEquals(TransferEnergy.MAX_AMOUNT, f.reserve(Long.MAX_VALUE));
        f.account.settle(TransferEnergy.MAX_AMOUNT, true);
        assertEquals(TransferEnergy.MAX_AMOUNT, f.power.debited, 0);
        assertTrue(Double.isInfinite(f.power.power));
        assertEquals(0, f.reserve(Long.MAX_VALUE));
    }

    @Test
    public void invalidMetricsDoNotAuthorizeAnEnergyDebit() {
        for (double metric : new double[] { Double.NaN, Double.POSITIVE_INFINITY }) {
            final Fixture f = new Fixture();
            f.power.power = metric;
            assertEquals(0, f.reserve(1000));
            f.power.power = 1000;
            f.power.capacity = metric;
            assertEquals(0, f.reserve(1000));
            f.power.capacity = 1000;
            f.power.idle = metric;
            assertEquals(0, f.reserve(1000));
            assertEquals(0, f.power.debitCalls);
        }
    }

    @Test
    public void physicalMultiplierAppliesToIdleReserveAndWholeNominalMinimum() {
        final Fixture f = new Fixture();
        f.power.power = 201;
        assertEquals(
            0,
            f.account.bind(f.grid, f.power, 1, 2.5)
                .reserve(1000));
        f.budget.clear();
        f.power.power = 205;
        assertEquals(
            2,
            f.account.bind(f.grid, f.power, 1, 2.5)
                .reserve(1000));
        f.account.settle(2, true);
        assertEquals(5, f.power.debited, 0);
        assertEquals(200, f.power.power, 0);
    }

    @Test
    public void synchronousEnergyCallbackCannotSpendAnAlreadyReservedQuota() {
        final Fixture f = new Fixture();
        final TransferEnergyAccount other = new TransferEnergyAccount(() -> {}, f.budget);
        f.power.beforeDebit = () -> assertEquals(
            0,
            other.bind(f.grid, f.power, 1, 1)
                .reserve(1000));
        assertEquals(100, f.reserve(1000));
        f.account.settle(100, true);
        assertEquals(1, f.power.debitCalls);
        assertEquals(900, f.power.power, 0);
    }

    @Test
    public void realSchedulerScopesOneQuotaAcrossItsDeviceSlicesAndRenewsNextTick() {
        final EnergySettlementTest.Power power = new EnergySettlementTest.Power();
        power.power = power.capacity = 1000;
        final Object grid = new Object();
        final TransferScheduler scheduler = new TransferScheduler(() -> 0);
        for (int device = 0; device < 2; device++) {
            final TransferEnergyAccount account = new TransferEnergyAccount(() -> {}, TransferPowerBudget.SERVER);
            scheduler.submit(slice -> {
                while (slice.transaction()) {
                    final long authorized = account.bind(grid, power, 1, 1)
                        .reserve(1000);
                    account.settle(authorized, true);
                    if (authorized == 0) {
                        break;
                    }
                }
                return true;
            });
        }
        scheduler.runTick();
        assertEquals(100, power.debited, 0);
        assertEquals(900, power.power, 0);
        scheduler.runTick();
        assertEquals(190, power.debited, 0);
        assertEquals(810, power.power, 0);
        assertEquals(2, power.debitCalls);
    }

    private static final class Fixture {

        final Object grid = new Object();
        final TransferPowerBudget budget = new TransferPowerBudget();
        final EnergySettlementTest.Power power = new EnergySettlementTest.Power();
        final TransferEnergyAccount account = new TransferEnergyAccount(() -> {}, this.budget);

        Fixture() {
            this.power.power = this.power.capacity = 1000;
        }

        long reserve(final long count) {
            return this.account.bind(this.grid, this.power, 1, 1)
                .reserve(count);
        }
    }
}
