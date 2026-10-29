package com.kyou.gtnhmelogisticsenhancement.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TransferEnergyTest {

    @Test
    public void largeItemAndEssentiaAmountsUseOneReservation() {
        for (final long amount : new long[] { 1_000_000, 10_000_000, 100_000_000 }) {
            final RecordingSource source = new RecordingSource();
            assertEquals(amount, TransferEnergy.reserve(amount, 1, 1, source));
            assertEquals(amount, source.simulatedRequest, 0);
            assertEquals(amount, source.actualRequest, 0);
            assertEquals(1, source.simulations);
            assertEquals(1, source.debits);
        }
    }

    @Test
    public void largeFluidAmountUsesNativeMilliBucketUnits() {
        final RecordingSource source = new RecordingSource();
        assertEquals(100_000_000, TransferEnergy.reserve(100_000_000, 1000, 1, source));
        assertEquals(100_000, source.simulatedRequest, 0);
        assertEquals(100_000, source.actualRequest, 0);
    }

    @Test
    public void longInventoryIsBoundedByTheIntBackedNativeAdapter() {
        final RecordingSource source = new RecordingSource();
        assertEquals(TransferEnergy.MAX_AMOUNT, TransferEnergy.reserve(Long.MAX_VALUE, 1, 1, source));
        assertEquals(Integer.MAX_VALUE, source.actualRequest, 0);
    }

    @Test
    public void fluidCeilingDoesNotOverflowAtLongMaximum() {
        assertEquals(9_223_372_036_854_776L, TransferEnergy.unitsFor(Long.MAX_VALUE, 1000));
        assertEquals(Long.MAX_VALUE, TransferEnergy.unitsFor(Long.MAX_VALUE, 1));
        assertEquals(0, TransferEnergy.unitsFor(0, 1000));
        assertEquals(1, TransferEnergy.unitsFor(1, 1000));
        assertEquals(1, TransferEnergy.unitsFor(1000, 1000));
        assertEquals(2, TransferEnergy.unitsFor(1001, 1000));
    }

    @Test
    public void intMaximumFluidAmountRetainsItsLastMilliBucket() {
        final RecordingSource source = new RecordingSource();
        assertEquals(Integer.MAX_VALUE, TransferEnergy.reserve(Integer.MAX_VALUE, 1000, 1, source));
        assertEquals(2_147_484, source.actualRequest, 0);
    }

    @Test
    public void zeroQuantityDoesNotAccessEnergy() {
        final RecordingSource source = new RecordingSource();
        assertEquals(0, TransferEnergy.reserve(0, 1, 1, source));
        assertEquals(0, source.simulations);
        assertEquals(0, source.debits);
    }

    @Test
    public void insufficientItemEnergyDoesNotDebitOrRoundUp() {
        final RecordingSource source = new RecordingSource();
        source.simulatedPower = Math.nextDown(1.0);
        assertEquals(0, TransferEnergy.reserve(100_000_000, 1, 1, source));
        assertEquals(0, source.debits);
    }

    @Test
    public void fractionalFluidEnergyShrinksTheRealDebit() {
        final RecordingSource source = new RecordingSource();
        source.simulatedPower = 0.5;
        assertEquals(500, TransferEnergy.reserve(100_000_000, 1000, 1, source));
        assertEquals(0.5, source.actualRequest, 0);
        assertEquals(1, source.debits);
    }

    @Test
    public void energyLossBetweenSimulationAndDebitFurtherReducesExtraction() {
        final RecordingSource source = new RecordingSource();
        source.simulatedPower = 1500;
        source.actualPower = 17;
        assertEquals(17, TransferEnergy.reserve(100_000_000, 1, 1, source));
        assertEquals(1500, source.actualRequest, 0);
    }

    @Test
    public void disappearedEnergyCannotAuthorizeExtraction() {
        final RecordingSource source = new RecordingSource();
        source.actualPower = 0;
        assertEquals(0, TransferEnergy.reserve(100_000_000, 1, 1, source));
        assertEquals(1, source.debits);
    }

    @Test
    public void floatingMultiplicationCannotCreateTheNextNativeUnit() {
        final RecordingSource source = new RecordingSource();
        source.simulatedPower = 0.009;
        // The IEEE value represented by 0.009 is below 9/1000, although double multiplication rounds to 9.
        assertEquals(9.0, source.simulatedPower * 1000, 0);
        assertEquals(8, TransferEnergy.reserve(1000, 1000, 1, source));
    }

    @Test
    public void actualFractionalDebitAlsoUsesExactDownwardAuthorization() {
        final RecordingSource source = new RecordingSource();
        source.actualPower = Math.nextDown(0.001);
        assertEquals(0, TransferEnergy.reserve(1000, 1000, 1, source));
    }

    @Test
    public void hugeConfigMultiplierShrinksTheRequestBeforeNativeMultiplication() {
        final RecordingSource source = new RecordingSource();
        source.multiplier = 1.0e308;
        assertEquals(1, TransferEnergy.reserve(Long.MAX_VALUE, 1, source.multiplier, source));
        assertEquals(1, source.simulatedRequest, 0);
        assertEquals(1, source.actualRequest, 0);
    }

    @Test
    public void largestFiniteMultiplierRemainsFiniteForFluidUnits() {
        final RecordingSource source = new RecordingSource();
        source.multiplier = Double.MAX_VALUE;
        assertEquals(1000, TransferEnergy.reserve(Long.MAX_VALUE, 1000, source.multiplier, source));
        assertEquals(1, source.actualRequest, 0);
    }

    @Test
    public void smallestPositiveMultiplierDoesNotOverflowTheNominalBudget() {
        final RecordingSource source = new RecordingSource();
        source.multiplier = Double.MIN_VALUE;
        assertEquals(Integer.MAX_VALUE, TransferEnergy.reserve(Long.MAX_VALUE, 1, source.multiplier, source));
    }

    @Test
    public void fractionalConfigUnderflowNeverIssuesAFreeRealDebit() {
        final RecordingSource source = new RecordingSource();
        source.multiplier = Double.MIN_VALUE;
        source.simulatedPower = 0.5;
        assertEquals(0, TransferEnergy.reserve(1000, 1000, source.multiplier, source));
        assertEquals(1, source.simulations);
        assertEquals(0, source.debits);
    }

    @Test
    public void malformedEnergySimulationStopsBeforeAnyDebit() {
        for (final double returned : new double[] { -1, Double.NaN, Double.POSITIVE_INFINITY, 101 }) {
            final RecordingSource source = new RecordingSource();
            source.simulatedPower = returned;
            source.returnUnchecked = true;
            assertThrows(IllegalStateException.class, () -> TransferEnergy.reserve(100, 1, 1, source));
            assertEquals(0, source.debits);
        }
    }

    @Test
    public void malformedActualDebitIsNotUsedOrCompensated() {
        for (final double returned : new double[] { -1, Double.NaN, Double.POSITIVE_INFINITY, 101 }) {
            final RecordingSource source = new RecordingSource();
            source.simulatedPower = 100;
            source.actualPower = returned;
            source.returnUnchecked = true;
            assertThrows(IllegalStateException.class, () -> TransferEnergy.reserve(100, 1, 1, source));
            assertEquals(1, source.simulations);
            assertEquals(1, source.debits);
        }
    }

    @Test
    public void evenOneUlpAboveTheDebitIsRejected() {
        final RecordingSource source = new RecordingSource();
        source.actualPower = Math.nextUp(100.0);
        source.simulatedPower = 100;
        source.returnUnchecked = true;
        assertThrows(IllegalStateException.class, () -> TransferEnergy.reserve(100, 1, 1, source));
        assertEquals(1, source.debits);
    }

    @Test
    public void invalidInputsNeverAccessTheEnergySource() {
        final RecordingSource source = new RecordingSource();
        for (final double multiplier : new double[] { 0, -1, Double.NaN, Double.POSITIVE_INFINITY }) {
            assertThrows(IllegalArgumentException.class, () -> TransferEnergy.reserve(1, 1, multiplier, source));
        }
        assertThrows(IllegalArgumentException.class, () -> TransferEnergy.reserve(-1, 1, 1, source));
        assertThrows(IllegalArgumentException.class, () -> TransferEnergy.reserve(1, 0, 1, source));
        assertThrows(IllegalArgumentException.class, () -> TransferEnergy.reserve(1, -1, 1, source));
        assertThrows(IllegalArgumentException.class, () -> TransferEnergy.reserve(1, 1, 1, null));
        assertEquals(0, source.simulations);
        assertEquals(0, source.debits);
    }

    @Test
    public void settlementAuthorizationNeverRoundsAnUnpaidNominalUnitUp() {
        for (final double multiplier : new double[] { Double.MIN_VALUE, 0.1, 1, 2.5, 1e308, Double.MAX_VALUE }) {
            for (final int unit : new int[] { 1, 1000 }) {
                final double paid = TransferEnergy.charge(1001, unit, multiplier);
                if (Double.isFinite(paid)) {
                    assertEquals(1001, TransferEnergy.authorized(paid, unit, multiplier, 1001));
                    final long fewer = TransferEnergy.authorized(Math.nextDown(paid), unit, multiplier, 1001);
                    assertTrue(fewer < 1001);
                    assertTrue(TransferEnergy.charge(fewer, unit, multiplier) <= Math.nextDown(paid));
                }
            }
        }
    }

    @Test
    public void settlementUsesCeilOnActualFluidQuantityAndDoesNotOverflow() {
        assertEquals(0, TransferEnergy.charge(0, 1000, 2.5), 0);
        assertEquals(2.5, TransferEnergy.charge(1, 1000, 2.5), 0);
        assertEquals(2.5, TransferEnergy.charge(1000, 1000, 2.5), 0);
        assertEquals(5, TransferEnergy.charge(1001, 1000, 2.5), 0);
        assertEquals(2_147_484, TransferEnergy.charge(TransferEnergy.MAX_AMOUNT, 1000, 1), 0);
        assertEquals(TransferEnergy.MAX_AMOUNT, TransferEnergy.authorized(Double.MAX_VALUE, 1, 1, Long.MAX_VALUE));
        assertEquals(1, TransferEnergy.authorized(Double.MAX_VALUE, 1, Double.MAX_VALUE, Long.MAX_VALUE));
        assertEquals(
            TransferEnergy.MAX_AMOUNT,
            TransferEnergy.authorized(1, Integer.MAX_VALUE, Double.MIN_VALUE, Long.MAX_VALUE));
    }

    @Test
    public void fractionalConfiguredPriceIsReservedUpAndRefundedDown() {
        final double charge = TransferEnergy.charge(3, 1, 0.1);
        final java.math.BigDecimal exact = new java.math.BigDecimal(0.1).multiply(java.math.BigDecimal.valueOf(3));
        assertTrue(new java.math.BigDecimal(charge).compareTo(exact) >= 0);
        final double prepaid = TransferEnergy.charge(99, 1, 0.1);
        final double refund = TransferEnergy.difference(prepaid, charge);
        assertTrue(
            new java.math.BigDecimal(refund).add(new java.math.BigDecimal(charge))
                .compareTo(new java.math.BigDecimal(prepaid)) <= 0);
        assertThrows(IllegalStateException.class, () -> TransferEnergy.difference(charge, prepaid));
    }

    @Test
    public void settlementRejectsMalformedQuantitiesAndParameters() {
        assertThrows(IllegalArgumentException.class, () -> TransferEnergy.charge(Long.MAX_VALUE, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> TransferEnergy.charge(-1, 1, 1));
        for (final double invalid : new double[] { 0, -1, Double.NaN, Double.POSITIVE_INFINITY }) {
            assertThrows(IllegalArgumentException.class, () -> TransferEnergy.charge(1, 1, invalid));
            assertThrows(IllegalArgumentException.class, () -> TransferEnergy.authorized(1, 1, invalid, 1));
        }
        assertThrows(IllegalArgumentException.class, () -> TransferEnergy.authorized(Double.NaN, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> TransferEnergy.authorized(1, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> TransferEnergy.authorized(1, 1, 1, -1));
    }

    private static final class RecordingSource implements TransferEnergy.Source {

        private double simulatedPower = Double.MAX_VALUE;
        private double actualPower = Double.MAX_VALUE;
        private double multiplier = 1;
        private double simulatedRequest;
        private double actualRequest;
        private boolean returnUnchecked;
        private int simulations;
        private int debits;

        @Override
        public double extract(final double amount, final boolean simulate) {
            assertTrue(Double.isFinite(amount));
            assertTrue(amount > 0);
            assertTrue(Double.isFinite(amount * this.multiplier));
            assertTrue(amount * this.multiplier > 0);
            if (simulate) {
                this.simulations++;
                this.simulatedRequest = amount;
            } else {
                this.debits++;
                this.actualRequest = amount;
            }
            final double configured = simulate ? this.simulatedPower : this.actualPower;
            return this.returnUnchecked ? configured : Math.min(amount, configured);
        }
    }
}
